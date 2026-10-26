package soloMapling.ArtificialPlayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.qa.QaLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What a bot remembers about a player (bot-persistence ticket 14; 1.6.0 slice S3, the owner's four memory kinds):
 * short notes kept in {@code botmemory} so they survive restarts and logouts, and put back into the prompt and the
 * relationship card the next time the two meet.
 *
 * <ul>
 *   <li><b>Who you are:</b> a last-topic slot and an inside-joke slot per pair ({@link Kind#TOPIC}, {@link Kind#JOKE};
 *   one each, replaced), and the player's goals and wanted items ({@link Kind#GOAL}, with a follow-up {@link Note#tag}
 *   such as {@code item:zhelm} for slice S6). All scripted from what was said ({@link MemoryFacts}), no LLM.</li>
 *   <li><b>What we did together:</b> episodes written by code, never by the LLM ({@link BotEpisodes}): a PQ clear, a
 *   party, a heal, a trade, each with a {@link Kind} and a {@link Note#weight}.</li>
 *   <li><b>Favors and grudges:</b> the GIFT / FAVOR / SCAM episodes and the notes a bot writes when its feelings
 *   change ({@link Kind#FEELING}); the scores themselves live in {@code BotAffinity}.</li>
 *   <li>Chat notes the brain writes when a conversation ends ({@link Kind#NOTE}), as before.</li>
 * </ul>
 *
 * <p><b>Hygiene.</b> A pair holds at most {@link #MAX_PER_PAIR} notes: past that the four oldest chat notes are
 * condensed into one by the brain (or dropped when it is down), or, when there are fewer chat notes than that, the
 * notes least worth keeping ({@link #worth}) go. Notes expire by age once nobody has used them for a while
 * ({@link #expired}, swept every few hours). The in-memory cache holds at most {@code solomapling.memory.cachePairs}
 * (16,384) pairs, least recently used out first.
 *
 * <p>Without migration 030 the columns are missing: notes are stored as plain notes and the slots live in memory only.
 */
public final class BotMemory {

    private static final Logger log = LoggerFactory.getLogger(BotMemory.class);

    public enum Source { DIRECT, HEARSAY }

    /** What a note is (migration 030 column {@code Kind}). */
    public enum Kind {
        /** A chat note from the brain (and every note from before 1.6.0). */
        NOTE,
        /** The note a bot writes when its feelings about the player change ("is a good friend..."). */
        FEELING,
        // Episodes: things that happened between the two, written by code.
        PQ, BOSS, PARTY, HELP, FAME, TRADE, GIFT, FAVOR, SCAM,
        /** A player's goal or wanted item, with a follow-up tag. */
        GOAL,
        /** Slot: what the pair last talked about. */
        TOPIC,
        /** Slot: an inside joke (a line one of them laughed at). */
        JOKE;

        public boolean episode() {
            return ordinal() >= PQ.ordinal() && ordinal() <= SCAM.ordinal();
        }

        /** One per pair, replaced rather than added; never shown as a fact or passed on as gossip. */
        public boolean slot() {
            return this == TOPIC || this == JOKE;
        }

        public static Kind parse(String raw) {
            if (raw == null) {
                return NOTE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NOTE;
            }
        }
    }

    /**
     * One remembered thing. {@code weight} 1..10 is how much it matters; {@code tag} ("" for none) marks a goal or item
     * to follow up ({@code item:zhelm}, {@code goal:4th job}); {@code followedUpAt} is when a bot did (0 = not yet).
     */
    public record Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                       long createdAt, long lastUsedAt, Kind kind, int weight, String tag, long followedUpAt) {

        public Note {
            kind = kind == null ? Kind.NOTE : kind;
            weight = Math.max(1, Math.min(10, weight));
            tag = tag == null ? "" : tag;
        }

        /** A note as before 1.6.0: a plain chat note of weight 1. */
        public Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                    long createdAt, long lastUsedAt) {
            this(id, botId, aboutCharId, text, source, fromBotId, hops, createdAt, lastUsedAt, Kind.NOTE, 1, "", 0);
        }

        public boolean tagged() {
            return !tag.isEmpty();
        }

        Note used(long at) {
            return new Note(id, botId, aboutCharId, text, source, fromBotId, hops, createdAt, at, kind, weight, tag,
                    followedUpAt);
        }

        Note followedUp(long at) {
            return new Note(id, botId, aboutCharId, text, source, fromBotId, hops, createdAt, lastUsedAt, kind, weight,
                    tag, at);
        }
    }

    static final int MAX_PER_PAIR = 10;
    static final int RECALL = 5;
    static final int CONDENSE = 4;
    /** The same episode (kind and text) is not written twice within this time ("partied together" each re-join). */
    static final long EPISODE_DEDUPE_MS = TimeUnit.HOURS.toMillis(20);
    /** A tagged note is due for a follow-up this long after it was written ("a day later", slice S6). */
    public static final long FOLLOW_UP_AFTER_MS = TimeUnit.HOURS.toMillis(20);
    /** A note of at least this weight is "heavy": it fades slower and is kept longer. */
    static final int HEAVY = 7;

    static final String SELECT_PAIR = "SELECT * FROM botmemory WHERE BotId = ? AND AboutCharacterId = ? ORDER BY CreatedAt, Id";
    static final String INSERT = "INSERT INTO botmemory (BotId, AboutCharacterId, Note, Source, FromBotId, Hops, CreatedAt, "
            + "LastUsedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    /** Migration 030: the same insert with the kind, weight, tag and follow-up columns. */
    static final String INSERT_KINDS = "INSERT INTO botmemory (BotId, AboutCharacterId, Note, Source, FromBotId, Hops, "
            + "CreatedAt, LastUsedAt, Kind, Weight, Tag, FollowedUpAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String PROBE_KINDS = "SELECT Kind, Weight, Tag, FollowedUpAt FROM botmemory WHERE 1 = 0";
    static final String DELETE = "DELETE FROM botmemory WHERE Id = ?";
    static final String TOUCH = "UPDATE botmemory SET LastUsedAt = ? WHERE Id = ?";
    static final String FOLLOWED_UP = "UPDATE botmemory SET FollowedUpAt = ? WHERE Id = ?";
    static final String RECENT_OF_BOT = "SELECT * FROM botmemory WHERE BotId = ? AND Hops < ? ORDER BY CreatedAt DESC LIMIT ?";
    /** S4's reputation input: how many first-hand notes of each kind bots hold about a player. */
    static final String KIND_COUNTS = "SELECT Kind, COUNT(*) AS N FROM botmemory WHERE AboutCharacterId = ? "
            + "AND Source = 'DIRECT' GROUP BY Kind";
    // The age sweep (expired() in SQL): created before the first time AND not used since the second.
    static final String PRUNE_HEARSAY = "DELETE FROM botmemory WHERE Source = 'HEARSAY' AND CreatedAt < ? AND LastUsedAt < ?";
    static final String PRUNE_LIGHT = "DELETE FROM botmemory WHERE Source <> 'HEARSAY' AND Weight < 7"
            + " AND CreatedAt < ? AND LastUsedAt < ?";
    static final String PRUNE_HEAVY = "DELETE FROM botmemory WHERE Source <> 'HEARSAY' AND Weight >= 7"
            + " AND CreatedAt < ? AND LastUsedAt < ?";
    /** Without migration 030 there is no weight: every first-hand note ages as a light one. */
    static final String PRUNE_LEGACY = "DELETE FROM botmemory WHERE Source <> 'HEARSAY' AND CreatedAt < ? AND LastUsedAt < ?";

    // Ages: a note goes once it is older than the first AND unused for the second.
    static final long HEARSAY_MAX_AGE = TimeUnit.DAYS.toMillis(21), HEARSAY_IDLE = TimeUnit.DAYS.toMillis(7);
    static final long LIGHT_MAX_AGE = TimeUnit.DAYS.toMillis(45), LIGHT_IDLE = TimeUnit.DAYS.toMillis(14);
    static final long HEAVY_MAX_AGE = TimeUnit.DAYS.toMillis(120), HEAVY_IDLE = TimeUnit.DAYS.toMillis(30);
    static final long PRUNE_EVERY_MS = TimeUnit.HOURS.toMillis(6);

    /** A note passes through at most this many mouths: what a bot saw, and what it heard from that bot. */
    public static final int MAX_HOPS = 2;

    /** The notes {@code BotAffinity} writes when feelings change (kept here so the store can tell them apart). */
    static final Set<String> FEELINGS = Set.of("is a good friend, always nice to me", "keeps ksing my mobs",
            "took my drops", "defamed me for no reason", "was rude to me", "we have beef", "my nemesis, cant stand them");

    /** Pairs kept in memory: least recently used out first (audit 5.2: it never evicted before 1.6.0). */
    private static volatile int cachePairs = defaultCachePairs();
    /** Pair key -> its notes, oldest first (immutable lists). Access-ordered; guarded by itself. */
    private static final LinkedHashMap<Long, List<Note>> cache = new LinkedHashMap<>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<Note>> eldest) {
            return size() > Math.max(1, cachePairs);
        }
    };
    /** Writes to one pair happen one at a time (striped). */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new Object();
        }
    }

    private static volatile BotStore.ConnectionSource connectionSource;
    /** Where stamps, scripted chat notes and the age sweep run (a seam for tests). */
    private static volatile Executor background = soloMapling.server.ExecutorServiceManager::runAsync;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    /** Whether migration 030's columns exist: null = not known yet. */
    private static volatile Boolean kinds;
    private static volatile long kindsCheckedAt;
    private static volatile long lastPruneAt;

    private BotMemory() {
    }

    private static int defaultCachePairs() {
        return Integer.getInteger("solomapling.memory.cachePairs", 16_384);
    }

    private static long key(int botId, int charId) {
        return ((long) botId << 32) | (charId & 0xffffffffL);
    }

    private static Object lock(long key) {
        return LOCKS[(int) ((key ^ (key >>> 29)) & (LOCKS.length - 1))];
    }

    private static List<Note> cached(long key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private static void put(long key, List<Note> notes) {
        synchronized (cache) {
            cache.put(key, List.copyOf(notes));
        }
    }

    /** Every note this bot holds about this player, oldest first. */
    public static List<Note> about(int botId, int charId) {
        long k = key(botId, charId);
        List<Note> known = cached(k);
        if (known != null) {
            return known;
        }
        List<Note> loaded = List.copyOf(load(botId, charId)); // read outside the cache's lock
        synchronized (cache) {
            List<Note> raced = cache.get(k);
            if (raced != null) {
                return raced;
            }
            cache.put(k, loaded);
            return loaded;
        }
    }

    /** Own notes before hearsay, newest first. */
    private static final Comparator<Note> RECALL_ORDER = Comparator.comparing((Note n) -> n.source() != Source.DIRECT)
            .thenComparing(Comparator.comparingLong(Note::createdAt).reversed());

    /**
     * The notes to put in the prompt: what the bot saw itself before hearsay, newest first, at most {@link #RECALL}
     * (the two slots are not notes and are left out). Recalled notes are stamped as used.
     */
    public static List<String> recall(int botId, int charId, long now) {
        List<Note> used = pick(botId, charId, false, RECALL);
        List<String> recalled = new ArrayList<>();
        for (Note note : used) {
            recalled.add(note.source() == Source.HEARSAY ? "heard from " + teller(note.fromBotId()) + ": " + note.text()
                    : note.text());
        }
        touch(botId, charId, used, now);
        return recalled;
    }

    /**
     * Ticket 59's memory card: the same notes {@link #recall} picks (own before hearsay, newest first), as notes with
     * their dates, at most {@code limit}. Stamped as used.
     */
    public static List<Note> recallNotes(int botId, int charId, long now, int limit) {
        List<Note> out = pick(botId, charId, false, limit);
        touch(botId, charId, out, now);
        return out;
    }

    /** As {@link #recallNotes}, without the episodes (the card shows those on their own). */
    public static List<Note> recallFacts(int botId, int charId, long now, int limit) {
        List<Note> out = pick(botId, charId, true, limit);
        touch(botId, charId, out, now);
        return out;
    }

    private static List<Note> pick(int botId, int charId, boolean withoutEpisodes, int limit) {
        List<Note> notes = new ArrayList<>(about(botId, charId));
        notes.removeIf(n -> n.kind().slot() || (withoutEpisodes && n.kind().episode()));
        notes.sort(RECALL_ORDER);
        return new ArrayList<>(notes.subList(0, Math.min(Math.max(0, limit), notes.size())));
    }

    /**
     * The best things the two did together (first-hand episodes only), most worth remembering first: weight, faded by
     * age ({@link #worth}). At most {@code limit}; stamped as used.
     */
    public static List<Note> bestEpisodes(int botId, int charId, long now, int limit) {
        List<Note> notes = new ArrayList<>(about(botId, charId));
        notes.removeIf(n -> !n.kind().episode() || n.source() != Source.DIRECT);
        notes.sort(Comparator.comparingDouble((Note n) -> -worth(n, now)).thenComparing(
                Comparator.comparingLong(Note::createdAt).reversed()));
        List<Note> out = new ArrayList<>(notes.subList(0, Math.min(Math.max(0, limit), notes.size())));
        touch(botId, charId, out, now);
        return out;
    }

    /** The pair's last-topic slot, if any. */
    public static Optional<Note> lastTopic(int botId, int charId) {
        return slot(botId, charId, Kind.TOPIC);
    }

    /** The pair's inside-joke slot, if any. */
    public static Optional<Note> insideJoke(int botId, int charId) {
        return slot(botId, charId, Kind.JOKE);
    }

    private static Optional<Note> slot(int botId, int charId, Kind kind) {
        Note newest = null;
        for (Note n : about(botId, charId)) {
            if (n.kind() == kind && (newest == null || n.createdAt() >= newest.createdAt())) {
                newest = n;
            }
        }
        return Optional.ofNullable(newest);
    }

    /**
     * Slice S6: the tagged notes about this player that no bot followed up yet and that are at least
     * {@link #FOLLOW_UP_AFTER_MS} old ("u get ur zhelm?" a day later). Newest first.
     */
    public static List<Note> dueFollowUps(int botId, int charId, long now) {
        List<Note> due = new ArrayList<>();
        for (Note n : about(botId, charId)) {
            if (n.tagged() && n.followedUpAt() == 0 && now - n.createdAt() >= FOLLOW_UP_AFTER_MS) {
                due.add(n);
            }
        }
        due.sort(Comparator.comparingLong(Note::createdAt).reversed());
        return due;
    }

    /** Slice S6: the bot followed this tag up; every note of the pair with the same tag is done. How many. */
    public static int markFollowedUp(int botId, int charId, String tag, long now) {
        if (tag == null || tag.isEmpty()) {
            return 0;
        }
        long k = key(botId, charId);
        List<Note> done = new ArrayList<>();
        synchronized (lock(k)) {
            List<Note> pair = new ArrayList<>(about(botId, charId));
            for (int i = 0; i < pair.size(); i++) {
                Note n = pair.get(i);
                if (tag.equals(n.tag()) && n.followedUpAt() == 0) {
                    pair.set(i, n.followedUp(now));
                    done.add(n);
                }
            }
            if (!done.isEmpty()) {
                put(k, pair);
            }
        }
        if (!done.isEmpty() && hasKinds()) {
            background.execute(() -> {
                for (Note n : done) {
                    if (n.id() > 0) {
                        update(FOLLOWED_UP, now, n.id());
                    }
                }
            });
        }
        return done.size();
    }

    /** The name a listener knows a teller by ("someone" when unknown). */
    public static String tellerName(int fromBotId) {
        return teller(fromBotId);
    }

    /** Who passed a note on, as the listener knows them: the teller's name, or "someone". */
    private static String teller(int fromBotId) {
        try {
            BotStore.Persisted row = BotStore.persistedFor(BotStore.BOT_ID_BASE + fromBotId);
            return row == null ? "someone" : row.name;
        } catch (RuntimeException e) {
            return "someone"; // the store is unreadable: the note still counts, its teller is just unnamed
        }
    }

    /**
     * The newest notes a bot holds about anyone, that can still be passed on (ticket 15). Slots and goals are the
     * bot's own business and never passed on.
     */
    public static List<Note> recentPassable(int botId, int limit) {
        List<Note> notes = new ArrayList<>();
        boolean extended = hasKinds();
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(RECENT_OF_BOT)) {
            ps.setInt(1, botId);
            ps.setInt(2, MAX_HOPS);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Note n = read(rs, extended);
                    if (!n.kind().slot() && n.kind() != Kind.GOAL) {
                        notes.add(n);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Could not read the notes of bot {}", botId, e);
        }
        return notes;
    }

    /**
     * Slice S4's reputation input: how many first-hand notes of each kind bots hold about this player (SCAM, GIFT,
     * FAVOR, PQ...). Empty without migration 030 or when the database cannot answer. Reads the database: call it off
     * the game thread.
     */
    public static Map<Kind, Integer> kindCounts(int aboutCharId) {
        Map<Kind, Integer> out = new EnumMap<>(Kind.class);
        if (!hasKinds()) {
            return out;
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(KIND_COUNTS)) {
            ps.setInt(1, aboutCharId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.merge(Kind.parse(rs.getString("Kind")), rs.getInt("N"), Integer::sum);
                }
            }
        } catch (SQLException e) {
            log.debug("Could not count the notes about {}", aboutCharId, e);
        }
        return out;
    }

    /**
     * Bot {@code listener} hears a note from {@code teller} (ticket 15): it keeps it as hearsay, one hop further,
     * unless it already holds that note, the note is about to exceed {@link #MAX_HOPS}, or it is the listener's own.
     *
     * @return whether the listener kept it
     */
    public static boolean hear(int listener, Note heard, long now) {
        int hops = heard.hops() + 1;
        if (listener == heard.botId() || hops > MAX_HOPS || listener == heard.fromBotId()) {
            return false;
        }
        for (Note known : about(listener, heard.aboutCharId())) {
            if (known.text().equalsIgnoreCase(heard.text())) {
                return false;
            }
        }
        remember(listener, heard.aboutCharId(), List.of(heard.text()), Source.HEARSAY, heard.botId(), hops, now);
        return true;
    }

    /**
     * Stores new notes about a player, condensing the oldest when the pair is full. A first-hand note that matches one
     * of the affinity feeling notes is a {@link Kind#FEELING}; one about a goal or a wanted item gets its follow-up tag.
     */
    public static void remember(int botId, int charId, List<String> texts, Source source, int fromBotId, int hops,
                                long now) {
        long k = key(botId, charId);
        synchronized (lock(k)) {
            List<Note> pair = new ArrayList<>(about(botId, charId));
            for (String text : texts) {
                String clean = clip(text);
                if (clean.isEmpty()) {
                    continue;
                }
                Kind kind = source == Source.DIRECT && FEELINGS.contains(clean) ? Kind.FEELING : Kind.NOTE;
                int weight = source == Source.HEARSAY ? 1 : kind == Kind.FEELING ? 5 : 2;
                String tag = source == Source.DIRECT ? MemoryFacts.tagOf(clean) : "";
                Note stored = insert(new Note(0, botId, charId, clean, source, fromBotId, hops, now, 0, kind, weight,
                        tag, 0));
                if (stored != null) {
                    pair.add(stored);
                }
            }
            put(k, pair);
            overflow(botId, charId, now);
        }
        maybePrune(now);
    }

    /**
     * Writes something that happened between the bot and the player, without the LLM (slice S3): a PQ clear, a party,
     * a heal, a trade, a gift, a scam, or a goal the player named. The same kind and text within
     * {@link #EPISODE_DEDUPE_MS}, or a tag the pair already holds and nobody followed up, counts once.
     *
     * @return the stored note, or null when it was a repeat (or the text was empty)
     */
    public static Note episode(int botId, int charId, Kind kind, String text, int weight, String tag, long now) {
        String clean = clip(text);
        if (clean.isEmpty() || kind == null || kind.slot()) {
            return null;
        }
        long k = key(botId, charId);
        Note stored;
        synchronized (lock(k)) {
            List<Note> pair = new ArrayList<>(about(botId, charId));
            for (Note n : pair) {
                boolean sameTag = tag != null && !tag.isEmpty() && tag.equals(n.tag()) && n.followedUpAt() == 0;
                if (sameTag || (n.kind() == kind && n.text().equalsIgnoreCase(clean)
                        && now - n.createdAt() < EPISODE_DEDUPE_MS)) {
                    return null;
                }
            }
            stored = insert(new Note(0, botId, charId, clean, Source.DIRECT, 0, 0, now, 0, kind, weight, tag, 0));
            if (stored == null) {
                return null;
            }
            pair.add(stored);
            put(k, pair);
            overflow(botId, charId, now);
        }
        if (QaLog.on(QaLog.Topic.MEMORY)) { // !qa me-1, me-2
            QaLog.any(QaLog.Topic.MEMORY, "EPISODE " + kind.name() + " bot " + botId + " -> " + charId + ": \""
                    + clean + "\" (weight " + stored.weight() + (stored.tagged() ? ", tag " + stored.tag() : "") + ")");
        }
        maybePrune(now);
        return stored;
    }

    /** Fills one of the pair's two slots ({@link Kind#TOPIC}, {@link Kind#JOKE}), replacing what was there. */
    public static Note setSlot(int botId, int charId, Kind kind, String text, long now) {
        String clean = clip(text);
        if (clean.isEmpty() || kind == null || !kind.slot()) {
            return null;
        }
        long k = key(botId, charId);
        Note stored;
        synchronized (lock(k)) {
            List<Note> pair = new ArrayList<>(about(botId, charId));
            for (Note old : List.copyOf(pair)) {
                if (old.kind() == kind) {
                    pair.remove(old);
                    delete(old);
                }
            }
            Note fresh = new Note(0, botId, charId, clean, Source.DIRECT, 0, 0, now, 0, kind, 3, "", 0);
            // Without migration 030 a stored slot would read back as a plain note: it lives in memory only.
            stored = hasKinds() ? insert(fresh) : null;
            if (stored == null) {
                stored = fresh;
            }
            pair.add(stored);
            put(k, pair);
        }
        if (QaLog.on(QaLog.Topic.MEMORY)) {
            QaLog.any(QaLog.Topic.MEMORY, kind.name() + " bot " + botId + " -> " + charId + ": \"" + clean + "\"");
        }
        return stored;
    }

    private static String clip(String text) {
        String clean = text == null ? "" : text.trim();
        return clean.length() > 160 ? clean.substring(0, 160) : clean;
    }

    /**
     * A conversation ended. Scripted, with or without the LLM: the last topic, an inside joke and the player's goals
     * ({@link MemoryFacts}, only from what was said). With a live brain it is also asked for notes about the player.
     * Runs on background threads; nothing waits on it.
     */
    public static void writeConversationNotes(client.Character bot, client.Character player, List<BotBrain.Turn> transcript) {
        boolean botSpoke = transcript.stream().anyMatch(t -> t.role() == BotBrain.Role.ASSISTANT);
        if (!botSpoke) {
            return;
        }
        int botId = bot.getId() - BotStore.BOT_ID_BASE;
        int charId = player.getId();
        long now = clock.getAsLong();
        List<BotBrain.Turn> chat = List.copyOf(transcript);
        background.execute(() -> {
            try {
                MemoryFacts.remember(botId, charId, chat, now);
            } catch (RuntimeException e) {
                log.debug("Could not write the scripted notes of bot {} about {}", botId, charId, e);
            }
        });
        if (!BotBrains.get().isLive()) {
            return;
        }
        BotBrains.get().notes(notesPrompt(bot.getName(), player.getName(), transcript)).thenAccept(reply -> reply
                .ifPresent(text -> remember(botId, charId, List.of(text.split("\n")), Source.DIRECT, 0, 0,
                        System.currentTimeMillis())));
    }

    public static List<BotBrain.Turn> notesPrompt(String botName, String playerName, List<BotBrain.Turn> transcript) {
        StringBuilder chat = new StringBuilder();
        for (BotBrain.Turn turn : transcript) {
            if (turn.role() == BotBrain.Role.SYSTEM) {
                continue;
            }
            chat.append(turn.role() == BotBrain.Role.USER ? playerName : "You").append(": ").append(turn.content())
                    .append('\n');
        }
        String system = "You are " + botName + ", a MapleStory player. Below is a chat you just had with " + playerName
                + ". Write at most 2 short notes, each under 140 characters, about things worth remembering about "
                + playerName + " next time: their plans and goals, items, level or job news, what they like, promises "
                + "either of you made, how they treated you. Write each note on its own line from your point of view, "
                + "for example: said they're saving up for a pink adventurer cape. Use only what was said in the chat. "
                + "If nothing is worth remembering, write NONE.";
        return List.of(BotBrain.Turn.system(system), BotBrain.Turn.user(chat.toString().trim()));
    }

    // ── keeping a pair small ────────────────────────────────────────────────────────────────────

    /**
     * How much a note is worth keeping now: its weight, halving every 10 days (30 for a heavy note), half for hearsay,
     * plus one when it was used in the last three days. Slots are always kept.
     */
    static double worth(Note n, long now) {
        if (n.kind().slot()) {
            return Double.MAX_VALUE;
        }
        long halfLife = TimeUnit.DAYS.toMillis(n.weight() >= HEAVY ? 30 : 10);
        double w = n.weight() * Math.pow(0.5, Math.max(0, now - n.createdAt()) / (double) halfLife);
        if (n.source() == Source.HEARSAY) {
            w *= 0.5;
        }
        if (n.lastUsedAt() > 0 && now - n.lastUsedAt() < TimeUnit.DAYS.toMillis(3)) {
            w += 1;
        }
        return w;
    }

    /**
     * Whether a note has aged out: older than its class's age AND unused for its idle time (hearsay 21 / 7 days, a
     * light note 45 / 14, a heavy one 120 / 30). A note that is still brought up stays.
     */
    static boolean expired(Note n, long now) {
        long maxAge;
        long idle;
        if (n.source() == Source.HEARSAY) {
            maxAge = HEARSAY_MAX_AGE;
            idle = HEARSAY_IDLE;
        } else if (n.weight() >= HEAVY) {
            maxAge = HEAVY_MAX_AGE;
            idle = HEAVY_IDLE;
        } else {
            maxAge = LIGHT_MAX_AGE;
            idle = LIGHT_IDLE;
        }
        return n.createdAt() < now - maxAge && n.lastUsedAt() < now - idle;
    }

    /**
     * A pair over {@link #MAX_PER_PAIR}: the four oldest chat notes are condensed (or dropped when the brain is down)
     * as before 1.6.0; when there are fewer chat notes than that, the notes least worth keeping go until it fits.
     * Called under the pair's lock.
     */
    private static void overflow(int botId, int charId, long now) {
        List<Note> pair = about(botId, charId);
        if (pair.size() <= MAX_PER_PAIR) {
            return;
        }
        List<Note> chat = pair.stream().filter(n -> n.kind() == Kind.NOTE).toList();
        if (chat.size() >= CONDENSE) {
            condense(botId, charId, List.copyOf(chat.subList(0, CONDENSE)));
            return;
        }
        List<Note> rest = new ArrayList<>(pair);
        List<Note> byWorth = new ArrayList<>(pair);
        byWorth.removeIf(n -> n.kind().slot());
        byWorth.sort(Comparator.comparingDouble((Note n) -> worth(n, now)).thenComparingLong(Note::createdAt));
        for (Note weakest : byWorth) {
            if (rest.size() <= MAX_PER_PAIR) {
                break;
            }
            rest.remove(weakest);
            delete(weakest);
        }
        put(key(botId, charId), rest);
    }

    /** Merges the given (oldest chat) notes of a full pair into one, or drops them when the brain cannot help. */
    private static void condense(int botId, int charId, List<Note> oldest) {
        StringBuilder joined = new StringBuilder();
        for (Note note : oldest) {
            joined.append("- ").append(note.text()).append('\n');
        }
        List<BotBrain.Turn> prompt = List.of(BotBrain.Turn.system("Merge these notes about a player into ONE note "
                        + "under 140 characters that keeps what matters most. Write only the note."),
                BotBrain.Turn.user(joined.toString().trim()));
        long k = key(botId, charId);
        BotBrains.get().notes(prompt).thenAccept(reply -> {
            synchronized (lock(k)) {
                for (Note note : oldest) {
                    delete(note);
                }
                List<Note> rest = new ArrayList<>(about(botId, charId));
                rest.removeAll(oldest);
                put(k, rest);
            }
            reply.ifPresent(merged -> remember(botId, charId, List.of(merged.split("\n")[0]), Source.DIRECT, 0, 0,
                    oldest.get(oldest.size() - 1).createdAt()));
        });
    }

    /** Runs the age sweep in the background at most every {@link #PRUNE_EVERY_MS}. */
    private static void maybePrune(long now) {
        if (now - lastPruneAt < PRUNE_EVERY_MS) {
            return;
        }
        synchronized (BotMemory.class) {
            if (now - lastPruneAt < PRUNE_EVERY_MS) {
                return;
            }
            lastPruneAt = now;
        }
        background.execute(() -> prune(now));
    }

    /**
     * The age sweep: deletes every expired note ({@link #expired}) from the table and the cache. How many rows went
     * (-1 when the database could not answer).
     */
    static int prune(long now) {
        int removed = 0;
        boolean extended = hasKinds();
        try (Connection con = connections().get()) {
            removed += sweep(con, PRUNE_HEARSAY, now - HEARSAY_MAX_AGE, now - HEARSAY_IDLE);
            if (extended) {
                removed += sweep(con, PRUNE_LIGHT, now - LIGHT_MAX_AGE, now - LIGHT_IDLE);
                removed += sweep(con, PRUNE_HEAVY, now - HEAVY_MAX_AGE, now - HEAVY_IDLE);
            } else {
                removed += sweep(con, PRUNE_LEGACY, now - LIGHT_MAX_AGE, now - LIGHT_IDLE);
            }
        } catch (SQLException e) {
            log.debug("Could not sweep old bot memories", e);
            removed = -1;
        }
        synchronized (cache) {
            cache.replaceAll((k, notes) -> notes.stream().noneMatch(n -> expired(n, now)) ? notes
                    : notes.stream().filter(n -> !expired(n, now)).toList());
        }
        if (removed > 0) {
            log.info("Bot memory: forgot {} old notes nobody used", removed);
        }
        return removed;
    }

    private static int sweep(Connection con, String sql, long createdBefore, long usedBefore) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, createdBefore);
            ps.setLong(2, usedBefore);
            return ps.executeUpdate();
        }
    }

    /**
     * 1.5.0 {@code @botdrama off}: every bot forgets the gossip about this player - each note it heard second hand,
     * and each of the given notes (the bots' grudges, which are what gossip passes on) - so none of it is told again.
     * Notes from the bot's own conversations stay. How many notes went.
     */
    public static int forgetGossipAbout(int charId, List<String> passableNotes) {
        StringBuilder sql = new StringBuilder("DELETE FROM botmemory WHERE AboutCharacterId = ? AND (Source = 'HEARSAY'");
        if (!passableNotes.isEmpty()) {
            sql.append(" OR Note IN (").append("?, ".repeat(passableNotes.size() - 1)).append("?)");
        }
        sql.append(')');
        int removed = 0;
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(sql.toString())) {
            ps.setInt(1, charId);
            for (int i = 0; i < passableNotes.size(); i++) {
                ps.setString(i + 2, passableNotes.get(i));
            }
            removed = ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not purge the gossip about {}", charId, e);
        }
        synchronized (cache) {
            cache.keySet().removeIf(k -> (int) (k & 0xffffffffL) == charId); // re-read without the purged notes
        }
        return removed;
    }

    // ── storage ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Whether migration 030's columns exist. Asked once (again 10 minutes after a "no"); a database that cannot answer
     * at all counts as "no" for this call only.
     */
    static boolean hasKinds() {
        Boolean known = kinds;
        if (known != null && (known || clock.getAsLong() - kindsCheckedAt < TimeUnit.MINUTES.toMillis(10))) {
            return known;
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(PROBE_KINDS);
             ResultSet ignored = ps.executeQuery()) {
            kinds = true;
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1054 || "42S22".equals(e.getSQLState())) { // unknown column: 030 not applied
                kinds = false;
                kindsCheckedAt = clock.getAsLong();
                log.info("botmemory has no Kind column (migration 030 not applied): notes are stored as plain notes");
            }
            return false;
        }
    }

    private static List<Note> load(int botId, int charId) {
        List<Note> notes = new ArrayList<>();
        boolean extended = hasKinds();
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(SELECT_PAIR)) {
            ps.setInt(1, botId);
            ps.setInt(2, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(read(rs, extended));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not read what bot {} remembers about {}", botId, charId, e);
        }
        return notes;
    }

    private static Note read(ResultSet rs, boolean extended) throws SQLException {
        Kind kind = Kind.NOTE;
        int weight = 1;
        String tag = "";
        long followedUpAt = 0;
        if (extended) {
            kind = Kind.parse(rs.getString("Kind"));
            weight = rs.getInt("Weight");
            tag = rs.getString("Tag");
            followedUpAt = rs.getLong("FollowedUpAt");
        }
        return new Note(rs.getLong("Id"), rs.getInt("BotId"), rs.getInt("AboutCharacterId"), rs.getString("Note"),
                Source.valueOf(rs.getString("Source")), rs.getInt("FromBotId"), rs.getInt("Hops"),
                rs.getLong("CreatedAt"), rs.getLong("LastUsedAt"), kind, weight, tag, followedUpAt);
    }

    private static Note insert(Note note) {
        boolean extended = hasKinds();
        try (Connection con = connections().get();
             PreparedStatement ps = con.prepareStatement(extended ? INSERT_KINDS : INSERT, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, note.botId());
            ps.setInt(2, note.aboutCharId());
            ps.setString(3, note.text());
            ps.setString(4, note.source().name());
            ps.setInt(5, note.fromBotId());
            ps.setInt(6, note.hops());
            ps.setLong(7, note.createdAt());
            ps.setLong(8, note.lastUsedAt());
            if (extended) {
                ps.setString(9, note.kind().name());
                ps.setInt(10, note.weight());
                ps.setString(11, note.tag());
                ps.setLong(12, note.followedUpAt());
            }
            ps.executeUpdate();
            long id = 0;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys != null && keys.next()) {
                    id = keys.getLong(1);
                }
            }
            return new Note(id, note.botId(), note.aboutCharId(), note.text(), note.source(), note.fromBotId(),
                    note.hops(), note.createdAt(), note.lastUsedAt(), note.kind(), note.weight(), note.tag(),
                    note.followedUpAt());
        } catch (SQLException e) {
            log.warn("Could not store a memory of bot {}", note.botId(), e);
            return null;
        }
    }

    private static void delete(Note note) {
        if (note.id() <= 0) {
            return; // a memory-only slot
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(DELETE)) {
            ps.setLong(1, note.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not forget memory {} of bot {}", note.id(), note.botId(), e);
        }
    }

    /** Stamps notes as used: in the cache now (the sweep and {@link #worth} read it), in the table in the background. */
    private static void touch(int botId, int charId, List<Note> notes, long now) {
        if (notes.isEmpty()) {
            return;
        }
        long k = key(botId, charId);
        Set<Long> ids = new HashSet<>();
        for (Note n : notes) {
            if (n.id() > 0) {
                ids.add(n.id());
            }
        }
        synchronized (lock(k)) {
            List<Note> pair = cached(k);
            if (pair != null) {
                List<Note> stamped = new ArrayList<>(pair.size());
                for (Note n : pair) {
                    stamped.add(ids.contains(n.id()) ? n.used(now) : n);
                }
                put(k, stamped);
            }
        }
        background.execute(() -> {
            for (Long id : ids) {
                update(TOUCH, now, id);
            }
        });
    }

    private static void update(String sql, long value, long id) {
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("Could not update memory {}", id, e);
        }
    }

    private static BotStore.ConnectionSource connections() {
        BotStore.ConnectionSource source = connectionSource;
        return source != null ? source : BotDbGate::connection;
    }

    /** How many pairs the cache holds now. */
    static int cachedPairs() {
        synchronized (cache) {
            return cache.size();
        }
    }

    /** Test seam: a mock pool (null restores the real one); forgets every cached note and the schema check. */
    static void useConnectionSourceForTest(BotStore.ConnectionSource source) {
        connectionSource = source;
        kinds = null;
        lastPruneAt = 0;
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Test seam: where background work runs, the clock and the cache's size (nulls / 0 restore the real ones). */
    public static void useForTest(Executor testBackground, LongSupplier testClock, int testCachePairs) {
        background = testBackground != null ? testBackground : soloMapling.server.ExecutorServiceManager::runAsync;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        cachePairs = testCachePairs > 0 ? testCachePairs : defaultCachePairs();
    }
}
