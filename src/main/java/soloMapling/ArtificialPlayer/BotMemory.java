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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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
 *
 * <p><b>Stage 3 (ticket 271, migration 063).</b> A note's subject is a real player or another bot
 * ({@link SubjectType}; a bot subject is keyed by its character id, so the same cap, LRU and age sweep apply), and a
 * note carries a place, a confidence, a valence, a {@link Provenance} and a second participant. {@link #remember(int,
 * int, Kind, String, double, int, Provenance, double, int, long)} is the writer for code (never the LLM);
 * {@link #recall(int, int, Query, long, int)} picks by relevance ({@link #score}); a repeat of a similar significant
 * note merges into one with a count instead of filling the pair ({@link #consolidateOrInsert}). Without migration 063
 * the new fields are kept in memory only and old rows read with the defaults of their source.
 */
public final class BotMemory {

    private static final Logger log = LoggerFactory.getLogger(BotMemory.class);

    public enum Source { DIRECT, HEARSAY }

    /** Who a note is about (migration 063 column {@code SubjectType}). A bot's id is at least {@link BotStore#BOT_ID_BASE}. */
    public enum SubjectType {
        PLAYER, BOT;

        public static SubjectType of(int subjectId) {
            return subjectId >= BotStore.BOT_ID_BASE ? BOT : PLAYER;
        }

        static SubjectType parse(String raw, int subjectId) {
            if (raw != null) {
                try {
                    return valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    // fall through to the id
                }
            }
            return of(subjectId);
        }
    }

    /**
     * How the bot came to know it (migration 063 column {@code Provenance}): OBJECTIVE is a fact the game itself
     * recorded, WITNESSED something the bot was part of or saw, REPORTED something it was told (always
     * {@link Source#HEARSAY}), BELIEF the bot's own conclusion.
     */
    public enum Provenance {
        OBJECTIVE, WITNESSED, REPORTED, BELIEF;

        static Provenance parse(String raw, Source source) {
            if (raw != null) {
                try {
                    return valueOf(raw.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    // fall through to the source
                }
            }
            return source == Source.DIRECT ? WITNESSED : REPORTED;
        }
    }

    /** What a note is (migration 030 column {@code Kind}). */
    public enum Kind {
        /** A chat note from the brain (and every note from before 1.6.0). */
        NOTE,
        /** The note a bot writes when its feelings about the player change ("is a good friend..."). */
        FEELING,
        // Episodes: things that happened between the two, written by code.
        PQ, BOSS, PARTY, HELP, FAME, TRADE, GIFT, FAVOR, SCAM,
        // Stage 3 (ticket 271), bot to bot: a conversation, an ask and its answer, gossip passed on. Written by code.
        CHAT, ASKED, TOLD,
        /** A player's goal or wanted item, with a follow-up tag. */
        GOAL,
        /** Slot: what the pair last talked about. */
        TOPIC,
        /** Slot: an inside joke (a line one of them laughed at). */
        JOKE,
        /** Slot: how the player looked when these two last talked (their level), written when the chat ends. */
        PROGRESS,
        /**
         * Stage 5 (ticket 271), bot to bot: the subject promised to do something with or for the bot and did not (the
         * promise's deadline passed). Written by code (SocialIntents), negative valence. Named short: the Kind column is
         * VARCHAR(8) (migration 030), so no migration is needed.
         */
        BROKEN;

        public boolean episode() {
            return ordinal() >= PQ.ordinal() && ordinal() <= TOLD.ordinal() || this == BROKEN;
        }

        /** The most a merged note of this kind can weigh: small talk never becomes a heavy memory. */
        public int maxWeight() {
            return this == CHAT || this == ASKED || this == TOLD ? 5 : 10;
        }

        /** What a note of this kind weighs when its writer gives no weight (the writer API). */
        public int defaultWeight() {
            return switch (this) {
                case PQ -> 6;
                case BOSS -> 8;
                case PARTY, TRADE, GOAL -> 3;
                case HELP -> 3;
                case FAME -> 4;
                case GIFT -> 6;
                case FAVOR, FEELING -> 5;
                case BROKEN -> 4;
                case SCAM -> 9;
                case CHAT, ASKED, TOLD, NOTE -> 2;
                default -> 3;
            };
        }

        /** Whether gossip may pass it on: what makes a reputation (S4 reads the same rule). */
        public boolean passable() {
            return this == NOTE || this == FEELING || this == GIFT || this == FAVOR || this == SCAM;
        }

        /** One per pair, replaced rather than added; never shown as a fact or passed on as gossip. */
        public boolean slot() {
            return this == TOPIC || this == JOKE || this == PROGRESS;
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
     *
     * <p>Stage 3 (migration 063): {@code subjectType} (the subject is a real player or another bot),
     * {@code locationMapId} (where it happened, 0 = unknown), {@code confidence} 0..1 (how sure the bot is),
     * {@code valence} -1..1 (how it felt: bad to good, its size is how much it stuck), {@code provenance} and
     * {@code otherId} (a second character involved, 0 = none). A row from before 063 reads with the defaults of its
     * source: confidence 0.9 for DIRECT and 0.5 / hops for HEARSAY, WITNESSED or REPORTED, valence 0, no place.
     */
    public record Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                       long createdAt, long lastUsedAt, Kind kind, int weight, String tag, long followedUpAt,
                       SubjectType subjectType, int locationMapId, double confidence, double valence,
                       Provenance provenance, int otherId) {

        public Note {
            kind = kind == null ? Kind.NOTE : kind;
            weight = Math.max(1, Math.min(10, weight));
            tag = tag == null ? "" : tag;
            subjectType = subjectType == null ? SubjectType.of(aboutCharId) : subjectType;
            locationMapId = Math.max(0, locationMapId);
            confidence = Double.isNaN(confidence) || confidence < 0 ? defaultConfidence(source, hops)
                    : Math.min(1, confidence);
            valence = Double.isNaN(valence) ? 0 : Math.max(-1, Math.min(1, valence));
            provenance = provenance == null ? (source == Source.DIRECT ? Provenance.WITNESSED : Provenance.REPORTED)
                    : provenance;
            otherId = Math.max(0, otherId);
        }

        /** A note as written before migration 063: the defaults of its source for every new field. */
        public Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                    long createdAt, long lastUsedAt, Kind kind, int weight, String tag, long followedUpAt) {
            this(id, botId, aboutCharId, text, source, fromBotId, hops, createdAt, lastUsedAt, kind, weight, tag,
                    followedUpAt, null, 0, Double.NaN, 0, null, 0);
        }

        /** A note as before 1.6.0: a plain chat note of weight 1. */
        public Note(long id, int botId, int aboutCharId, String text, Source source, int fromBotId, int hops,
                    long createdAt, long lastUsedAt) {
            this(id, botId, aboutCharId, text, source, fromBotId, hops, createdAt, lastUsedAt, Kind.NOTE, 1, "", 0);
        }

        /** 0.9 for what the bot lived, 0.5 for what it heard once, halving with each further mouth. */
        public static double defaultConfidence(Source source, int hops) {
            return source == Source.DIRECT ? 0.9 : 0.5 / Math.max(1, hops);
        }

        public boolean tagged() {
            return !tag.isEmpty();
        }

        Note used(long at) {
            return with(text, createdAt, at, weight, followedUpAt, locationMapId, confidence, valence, otherId);
        }

        Note followedUp(long at) {
            return with(text, createdAt, lastUsedAt, weight, at, locationMapId, confidence, valence, otherId);
        }

        Note with(String newText, long newCreatedAt, long newLastUsedAt, int newWeight, long newFollowedUpAt,
                  int newLocation, double newConfidence, double newValence, int newOther) {
            return new Note(id, botId, aboutCharId, newText, source, fromBotId, hops, newCreatedAt, newLastUsedAt, kind,
                    newWeight, tag, newFollowedUpAt, subjectType, newLocation, newConfidence, newValence, provenance,
                    newOther);
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
    /** Migration 063: the same insert with the subject type, place, confidence, valence, provenance and other party. */
    static final String INSERT_SOCIAL = "INSERT INTO botmemory (BotId, AboutCharacterId, Note, Source, FromBotId, Hops, "
            + "CreatedAt, LastUsedAt, Kind, Weight, Tag, FollowedUpAt, SubjectType, Location, Confidence, Valence, "
            + "Provenance, OtherParticipant) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    static final String PROBE_SOCIAL = "SELECT SubjectType, Location, Confidence, Valence, Provenance, OtherParticipant "
            + "FROM botmemory WHERE 1 = 0";
    /** A repeat of a significant note folds into the old one (text with its count, weight, recency): one UPDATE per schema. */
    static final String MERGE_PLAIN = "UPDATE botmemory SET Note = ?, CreatedAt = ? WHERE Id = ?";
    static final String MERGE_KINDS = "UPDATE botmemory SET Note = ?, CreatedAt = ?, Weight = ? WHERE Id = ?";
    static final String MERGE_SOCIAL = "UPDATE botmemory SET Note = ?, CreatedAt = ?, Weight = ?, Location = ?, "
            + "Confidence = ?, Valence = ?, OtherParticipant = ? WHERE Id = ?";
    static final String DELETE = "DELETE FROM botmemory WHERE Id = ?";
    static final String DELETE_PAIR = "DELETE FROM botmemory WHERE BotId = ? AND AboutCharacterId = ?";
    static final String TOUCH = "UPDATE botmemory SET LastUsedAt = ? WHERE Id = ?";
    static final String FOLLOWED_UP = "UPDATE botmemory SET FollowedUpAt = ? WHERE Id = ?";
    static final String RECENT_OF_BOT = "SELECT * FROM botmemory WHERE BotId = ? AND Hops < ? ORDER BY CreatedAt DESC LIMIT ?";
    /** {@link #RECENT_OF_BOT} for a whole round of tellers: one round trip instead of one per teller. */
    static final String RECENT_OF_BOTS = "SELECT * FROM botmemory WHERE BotId IN (%s) AND Hops < ? ORDER BY BotId, CreatedAt DESC";
    /** S4's reputation input: how many first-hand notes of each kind bots hold about a player. */
    static final String KIND_COUNTS = "SELECT Kind, COUNT(*) AS N FROM botmemory WHERE AboutCharacterId = ? "
            + "AND Source = 'DIRECT' GROUP BY Kind";
    /** {@link #KIND_COUNTS} for the first-hand notes of a given set of bots only (a holder's own circle). */
    static final String KIND_COUNTS_OF = "SELECT Kind, COUNT(*) AS N FROM botmemory WHERE AboutCharacterId = ? "
            + "AND Source = 'DIRECT' AND BotId IN (%s) GROUP BY Kind";
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

    /** Bots per {@code IN (...)} list in {@link #recentPassableFor}. */
    static final int READ_CHUNK = 32;

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
    /** Whether migration 063's columns exist: null = not known yet. */
    private static volatile Boolean social;
    private static volatile long socialCheckedAt;
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
        if (unread.contains(key)) {
            return; // 1.6.5 b327: the pair was never read, so these notes are not all it holds; the next look reads it
        }
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
        List<Note> read = load(botId, charId); // read outside the cache's lock
        if (read == null) {
            // 1.6.5 b327: the read did not happen (a busy share, a broken table). Nothing is cached, so the next look
            // reads again; before this the empty list was cached and the bot "knew nothing" until eviction or restart.
            // A writer holding the pair's lock sees the pair as unread and does not cache its partial view (put)
            unread.add(k);
            return List.of();
        }
        unread.remove(k);
        List<Note> loaded = List.copyOf(read);
        synchronized (cache) {
            List<Note> raced = cache.get(k);
            if (raced != null) {
                return raced;
            }
            cache.put(k, loaded);
            return loaded;
        }
    }

    /**
     * Ticket 157 (bot inspect): how many notes this bot holds about anyone (players and, since Stage 3, other bots) in
     * the in-memory cache, and about how many subjects. A scan of the cache under its lock (at most {@code solomapling.memory.cachePairs} pairs, no database):
     * call it on demand only, never per tick. Pairs never loaded or already evicted are not counted.
     */
    public static int[] cachedNotesBy(int botId) {
        int notes = 0;
        int pairs = 0;
        synchronized (cache) {
            for (Map.Entry<Long, List<Note>> e : cache.entrySet()) {
                if ((int) (e.getKey() >>> 32) == botId) {
                    pairs++;
                    notes += e.getValue().size();
                }
            }
        }
        return new int[]{notes, pairs};
    }

    /** One query for a recall: what the bot is after right now. All parts are optional ({@link #NO_QUERY}). */
    public record Query(Set<Kind> kinds, double moodValence, int locationMapId, Set<String> topicWords, int otherId) {

        public Query {
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
            moodValence = Double.isNaN(moodValence) ? 0 : Math.max(-1, Math.min(1, moodValence));
            locationMapId = Math.max(0, locationMapId);
            topicWords = topicWords == null ? Set.of() : Set.copyOf(topicWords);
            otherId = Math.max(0, otherId);
        }

        /** Nothing in particular: salience, freshness and confidence alone. */
        public static Query none() {
            return NO_QUERY;
        }

        /** The query for a thing being talked about here: a place, the talk's words and the mood the bot is in. */
        public static Query of(Collection<Kind> kinds, double moodValence, int locationMapId, String topicText,
                               int otherId) {
            return new Query(kinds == null ? Set.of() : Set.copyOf(kinds), moodValence, locationMapId,
                    words(topicText), otherId);
        }

        /** Lower-case words of three or more letters (no filler), the unit a topic match counts in. */
        public static Set<String> words(String text) {
            Set<String> out = new HashSet<>();
            if (text == null) {
                return out;
            }
            for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
                if (w.length() >= 3 && !FILLER.contains(w)) {
                    out.add(w);
                }
            }
            return out;
        }
    }

    private static final Query NO_QUERY = new Query(Set.of(), 0, 0, Set.of(), 0);
    private static final Set<String> FILLER = Set.of("the", "and", "you", "that", "this", "with", "for", "was", "are",
            "but", "not", "have", "has", "had", "just", "lol", "got", "its", "your", "they", "them", "from", "what");

    /** Score bonuses for a match with the query (each multiplies the base score). */
    static final double KIND_BONUS = 1.6, LOCATION_BONUS = 1.3, OTHER_BONUS = 1.5, WORD_BONUS = 0.4, MOOD_BONUS = 0.3;

    /**
     * How worth bringing up a note is for this query: salience (its weight, grown by how strongly it felt either
     * way) x freshness (halving every 10 days, 30 for a heavy note, as {@link #worth}) x confidence, times a bonus for
     * each match: a kind of interest, the place, the other party, words of the topic, and a feeling that agrees with
     * the bot's mood. Slots score 0 (never recalled as notes). Pure.
     */
    static double score(Note n, Query q, long now) {
        if (n.kind().slot()) {
            return 0;
        }
        double salience = n.weight() / 10.0 * (1 + Math.abs(n.valence()));
        long halfLife = TimeUnit.DAYS.toMillis(n.weight() >= HEAVY ? 30 : 10);
        double fresh = Math.pow(0.5, Math.max(0, now - n.createdAt()) / (double) halfLife);
        double s = salience * fresh * n.confidence();
        if (!q.kinds().isEmpty() && q.kinds().contains(n.kind())) {
            s *= KIND_BONUS;
        }
        if (q.locationMapId() > 0 && n.locationMapId() == q.locationMapId()) {
            s *= LOCATION_BONUS;
        }
        if (q.otherId() > 0 && n.otherId() == q.otherId()) {
            s *= OTHER_BONUS;
        }
        if (!q.topicWords().isEmpty()) {
            int hit = 0;
            Set<String> mine = Query.words(n.text() + " " + n.tag().replace(':', ' '));
            for (String w : q.topicWords()) {
                if (mine.contains(w)) {
                    hit++;
                }
            }
            s *= 1 + WORD_BONUS * Math.min(3, hit);
        }
        if (q.moodValence() != 0 && n.valence() != 0 && Math.signum(q.moodValence()) == Math.signum(n.valence())) {
            s *= 1 + MOOD_BONUS * Math.abs(q.moodValence());
        }
        return s;
    }

    /** The best-scoring first, own (DIRECT) before hearsay at an equal score, then the newest. */
    private static Comparator<Note> byScore(Query q, long now) {
        return (x, y) -> {
            double sx = score(x, q, now);
            double sy = score(y, q, now);
            if (Math.abs(sx - sy) > 1e-9) {
                return Double.compare(sy, sx);
            }
            if ((x.source() == Source.DIRECT) != (y.source() == Source.DIRECT)) {
                return x.source() == Source.DIRECT ? -1 : 1;
            }
            int byAge = Long.compare(y.createdAt(), x.createdAt());
            return byAge != 0 ? byAge : Long.compare(y.id(), x.id());
        };
    }

    /**
     * Stage 3's scored recall: the notes this bot holds about {@code subjectId} (a player or another bot) that are
     * most worth bringing up for {@code q}, best first, at most {@code limit}. The slots are not notes and are left
     * out. Recalled notes are stamped as used (so they fade slower and the age sweep keeps them).
     */
    public static List<Note> recall(int botId, int subjectId, Query q, long now, int limit) {
        List<Note> out = pick(botId, subjectId, false, q == null ? NO_QUERY : q, now, limit);
        touch(botId, subjectId, out, now);
        return out;
    }

    /**
     * The notes to put in the prompt: the {@link #RECALL} best by {@link #score} with no particular query - what the
     * bot saw itself before hearsay at an equal score, then the newest (the two slots are not notes and are left
     * out). Recalled notes are stamped as used.
     */
    public static List<String> recall(int botId, int charId, long now) {
        return recall(botId, charId, NO_QUERY, now);
    }

    /** {@link #recall(int, int, long)} for a query: the notes as the prompt's lines. */
    public static List<String> recall(int botId, int charId, Query q, long now) {
        List<String> recalled = new ArrayList<>();
        for (Note note : recall(botId, charId, q, now, RECALL)) {
            recalled.add(note.source() == Source.HEARSAY ? "heard from " + teller(note.fromBotId()) + ": " + note.text()
                    : note.text());
        }
        return recalled;
    }

    /** Pairs being loaded in the background by {@link #recallIfCached} (bounded: one load per pair at a time). */
    private static final Set<Long> warming = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * {@link #recall(int, int, Query, long, int)} for a hot path that must never wait on the database (a bot thread's
     * or a bot talk's prompt, built on the director's tick): it reads only what is already in memory. A pair that is not
     * cached yet comes back empty and is loaded in the background, so the NEXT talk of that pair has its memories.
     * (Writing a note loads the pair too, so a pair that has talked this session is always warm.)
     */
    public static List<Note> recallIfCached(int botId, int subjectId, Query q, long now, int limit) {
        long k = key(botId, subjectId);
        List<Note> known = cached(k);
        if (known == null) {
            if (warming.size() < 256 && warming.add(k)) {
                try {
                    background.execute(() -> {
                        try {
                            about(botId, subjectId);
                        } catch (RuntimeException e) {
                            log.debug("Could not warm what bot {} remembers about {}", botId, subjectId, e);
                        } finally {
                            warming.remove(k);
                        }
                    });
                } catch (RuntimeException e) {
                    warming.remove(k); // the executor refused it: ask again next time
                }
            }
            return List.of();
        }
        List<Note> notes = new ArrayList<>(known);
        notes.removeIf(n -> n.kind().slot());
        notes.sort(byScore(q == null ? NO_QUERY : q, now));
        List<Note> out = new ArrayList<>(notes.subList(0, Math.min(Math.max(0, limit), notes.size())));
        touch(botId, subjectId, out, now);
        return out;
    }

    /**
     * Ticket 59's memory card: the same notes {@link #recall} picks, as notes with their dates, at most
     * {@code limit}. Stamped as used.
     */
    public static List<Note> recallNotes(int botId, int charId, long now, int limit) {
        return recall(botId, charId, NO_QUERY, now, limit);
    }

    /** As {@link #recallNotes}, without the episodes (the card shows those on their own). */
    public static List<Note> recallFacts(int botId, int charId, long now, int limit) {
        List<Note> out = pick(botId, charId, true, NO_QUERY, now, limit);
        touch(botId, charId, out, now);
        return out;
    }

    private static List<Note> pick(int botId, int charId, boolean withoutEpisodes, Query q, long now, int limit) {
        List<Note> notes = new ArrayList<>(about(botId, charId));
        notes.removeIf(n -> n.kind().slot() || (withoutEpisodes && n.kind().episode()));
        notes.sort(byScore(q, now));
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
                        queueStamp(pendingFollow, n.id(), now); // 1.6.5 b324: batched like the last-used stamp
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
     * The newest notes a bot holds about anyone, that can still be passed on (ticket 15). Only what makes a
     * reputation travels ({@link Kind#passable}): chat notes, feelings, gifts, favors and scams; slots, goals and the
     * small episodes ("partied together") are the bot's own business.
     */
    public static List<Note> recentPassable(int botId, int limit) {
        return recentPassableFor(List.of(botId), limit).getOrDefault(botId, List.of());
    }

    /**
     * {@link #recentPassable(int, int)} for many bots at once: one round trip for a whole gossip round instead of
     * one per teller (1.6.3: at 3,000 bots the minute's twenty tellers each asked the pool for their own read, on the
     * thread that also drives the population tick). Bots are read in chunks so the statement stays small.
     *
     * @return notes per bot id, each bot's newest {@code limit} rows that can still be passed on (empty list absent)
     */
    public static Map<Integer, List<Note>> recentPassableFor(Collection<Integer> botIds, int limit) {
        Map<Integer, List<Note>> out = new HashMap<>();
        List<Integer> ids = new ArrayList<>(botIds);
        if (ids.isEmpty() || limit <= 0) {
            return out;
        }
        boolean extended = hasKinds();
        boolean social = hasSocial();
        for (int from = 0; from < ids.size(); from += READ_CHUNK) {
            List<Integer> chunk = ids.subList(from, Math.min(from + READ_CHUNK, ids.size()));
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            try (Connection con = connections().get();
                 PreparedStatement ps = con.prepareStatement(RECENT_OF_BOTS.formatted(marks))) {
                int p = 1;
                for (int id : chunk) {
                    ps.setInt(p++, id);
                }
                ps.setInt(p, MAX_HOPS);
                Map<Integer, Integer> seen = new HashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int owner = rs.getInt("BotId");
                        if (rs.getInt("AboutCharacterId") >= BotStore.BOT_ID_BASE) {
                            continue; // a note about another bot is that bot's own business, not a story about a player
                        }
                        int taken = seen.merge(owner, 1, Integer::sum);
                        if (taken > limit) {
                            continue; // same rows, same order as the single-bot read: the newest `limit`
                        }
                        Note n = read(rs, extended, social);
                        if (n.kind().passable()) {
                            out.computeIfAbsent(owner, k -> new ArrayList<>()).add(n);
                        }
                    }
                }
            } catch (SQLException e) {
                log.debug("Could not read the notes of {} bots", chunk.size(), e);
            }
        }
        return out;
    }

    /**
     * <b>Deliberately aggregate</b> (an operator statistic, never a bot's knowledge): how many first-hand notes of each
     * kind ALL bots together hold about this subject (a player or a bot). No bot may read this to decide what it knows
     * about someone: a bot's view is {@link #kindCounts(int, Collection)} over its own circle. Empty without migration
     * 030 or when the database cannot answer. Reads the database: call it off the game thread.
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
     * What a holder's own circle (the bot slots given: itself, its crew, its bond neighbours) holds first hand about
     * this subject, per kind. The only count a bot may act on: bots outside the circle are never read. Empty without
     * migration 030, with an empty circle, or when the database cannot answer. Reads the database.
     *
     * @param circleSlots bot slots (character id minus {@link BotStore#BOT_ID_BASE}) of the circle
     */
    public static Map<Kind, Integer> kindCounts(int aboutCharId, Collection<Integer> circleSlots) {
        Map<Kind, Integer> out = new EnumMap<>(Kind.class);
        List<Integer> slots = new ArrayList<>(new HashSet<>(circleSlots));
        if (slots.isEmpty() || !hasKinds()) {
            return out;
        }
        for (int from = 0; from < slots.size(); from += READ_CHUNK) {
            List<Integer> chunk = slots.subList(from, Math.min(from + READ_CHUNK, slots.size()));
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < chunk.size(); i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            try (Connection con = connections().get();
                 PreparedStatement ps = con.prepareStatement(KIND_COUNTS_OF.formatted(marks))) {
                ps.setInt(1, aboutCharId);
                int p = 2;
                for (int slot : chunk) {
                    ps.setInt(p++, slot);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.merge(Kind.parse(rs.getString("Kind")), rs.getInt("N"), Integer::sum);
                    }
                }
            } catch (SQLException e) {
                log.debug("Could not count the circle's notes about {}", aboutCharId, e);
            }
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
     * {@link #EPISODE_DEDUPE_MS}, or a tag the pair already holds and nobody followed up, counts once. A repeat of a
     * similar episode after that folds into the old note instead of filling the pair ({@link #consolidateOrInsert}).
     *
     * @return the stored (or merged) note, or null when it was a repeat (or the text was empty)
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
                if (sameTag || (n.kind() == kind && baseOf(n.text()).equalsIgnoreCase(clean)
                        && now - n.createdAt() < EPISODE_DEDUPE_MS)) {
                    return null;
                }
            }
            stored = consolidateOrInsert(pair, new Note(0, botId, charId, clean, Source.DIRECT, 0, 0, now, 0, kind,
                    weight, tag, 0));
            if (stored == null) {
                return null;
            }
            put(k, pair);
            overflow(botId, charId, now);
        }
        if (QaLog.on(QaLog.Topic.MEMORY)) { // !qa me-1, me-2
            QaLog.any(QaLog.Topic.MEMORY, "EPISODE " + kind.name() + " bot " + botId + " -> " + charId + ": \""
                    + stored.text() + "\" (weight " + stored.weight() + (stored.tagged() ? ", tag " + stored.tag() : "")
                    + ")");
        }
        maybePrune(now);
        return stored;
    }

    /**
     * Stage 3's writer: bot {@code botSlot} remembers something about {@code subjectId} - a real player or another bot
     * (character id) - written by server code, never by the LLM. {@code valence} -1..1 is how it felt, {@code location}
     * a map id (0: unknown), {@code confidence} 0..1 (NaN: the default of its provenance), {@code other} a second
     * character involved (0: none). REPORTED is stored as {@link Source#HEARSAY}, everything else as DIRECT. The weight
     * comes from the kind ({@link Kind#defaultWeight}, one more for a strong feeling, at most 2 for hearsay). The same
     * kind and text within {@link #EPISODE_DEDUPE_MS} counts once; later repeats of a similar episode merge into one
     * note with a count; the pair keeps at most {@link #MAX_PER_PAIR} notes.
     *
     * @return the stored (or merged) note, or null when it was a repeat, empty, a slot kind or about the bot itself
     */
    public static Note remember(int botSlot, int subjectId, Kind kind, String text, double valence, int location,
                                Provenance provenance, double confidence, int other, long now) {
        return remember(botSlot, subjectId, kind, text, valence, location, provenance, confidence, other, 0, 0, now);
    }

    /** As above, for something told: {@code fromBotId} (a slot) is who told it and {@code hops} how many mouths deep. */
    public static Note remember(int botSlot, int subjectId, Kind kind, String text, double valence, int location,
                                Provenance provenance, double confidence, int other, int fromBotId, int hops, long now) {
        String clean = clip(text);
        if (clean.isEmpty() || kind == null || kind.slot() || subjectId <= 0
                || subjectId == BotStore.BOT_ID_BASE + botSlot) {
            return null;
        }
        Provenance how = provenance == null ? Provenance.WITNESSED : provenance;
        Source source = how == Provenance.REPORTED ? Source.HEARSAY : Source.DIRECT;
        double v = Double.isNaN(valence) ? 0 : Math.max(-1, Math.min(1, valence));
        int weight = Math.min(kind.maxWeight(), kind.defaultWeight() + (Math.abs(v) >= 0.7 ? 1 : 0));
        if (source == Source.HEARSAY) {
            weight = Math.min(weight, 2);
        }
        Note fresh = new Note(0, botSlot, subjectId, clean, source, source == Source.HEARSAY ? fromBotId : 0,
                source == Source.HEARSAY ? Math.max(1, hops) : 0, now, 0, kind, weight, "", 0, null, location,
                confidence, v, how, other);
        long k = key(botSlot, subjectId);
        Note stored;
        synchronized (lock(k)) {
            List<Note> pair = new ArrayList<>(about(botSlot, subjectId));
            for (Note n : pair) {
                if (n.kind() == kind && baseOf(n.text()).equalsIgnoreCase(clean)
                        && (source == Source.HEARSAY || now - n.createdAt() < EPISODE_DEDUPE_MS)) {
                    return null; // a repeat (or already heard)
                }
            }
            stored = consolidateOrInsert(pair, fresh);
            if (stored == null) {
                return null;
            }
            put(k, pair);
            overflow(botSlot, subjectId, now);
        }
        if (QaLog.on(QaLog.Topic.MEMORY)) {
            QaLog.any(QaLog.Topic.MEMORY, "REMEMBER " + kind.name() + " bot " + botSlot + " -> " + subjectId + " ("
                    + stored.subjectType() + "): \"" + stored.text() + "\" (weight " + stored.weight() + ", "
                    + stored.provenance() + " " + String.format(Locale.ROOT, "%.2f", stored.confidence()) + ")");
        }
        maybePrune(now);
        return stored;
    }

    // ── consolidation: a repeat folds into the note it repeats ──

    /** The count suffix a merged note carries: "partied together (x3)". */
    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("^(.*) \\(x(\\d+)\\)$");
    /** Two episode texts this alike (shared words over all words) are the same kind of thing happening again. */
    static final double SIMILAR = 0.6;

    /** The text without a merge's count suffix. */
    static String baseOf(String text) {
        java.util.regex.Matcher m = COUNT.matcher(text);
        return m.matches() ? m.group(1) : text;
    }

    /** How many times a merged note stands for (1 for a note never merged). */
    static int countOf(String text) {
        java.util.regex.Matcher m = COUNT.matcher(text);
        if (!m.matches()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(m.group(2)));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Shared words over all words of two texts, 0..1 (1 for the same words, case aside). */
    static double similarity(String a, String b) {
        Set<String> x = new HashSet<>(List.of(a.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")));
        Set<String> y = new HashSet<>(List.of(b.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")));
        x.remove("");
        y.remove("");
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String w : x) {
            if (y.contains(w)) {
                shared++;
            }
        }
        return shared / (double) (x.size() + y.size() - shared);
    }

    /** Whether repeats of this kind of note merge: episodes and feelings, never chat notes (the brain condenses those) or goals. */
    private static boolean mergeable(Note n) {
        return (n.kind().episode() || n.kind() == Kind.FEELING) && !n.tagged();
    }

    /**
     * Called under the pair's lock: a significant note ({@link #mergeable}) that repeats one the pair holds (same kind
     * and source, {@link #SIMILAR} words) is folded into it - the newest wording with a count ("partied together
     * (x3)"), one more weight (at most the kind's maximum), the newest date, the mean valence and the best confidence -
     * so a recurring thing grows in weight instead of filling the {@link #MAX_PER_PAIR} cap. Anything else is inserted.
     * Deterministic, no model. {@code pair} is updated in place.
     *
     * @return the merged or inserted note, or null when the insert failed
     */
    private static Note consolidateOrInsert(List<Note> pair, Note fresh) {
        Note into = null;
        double best = 0;
        if (mergeable(fresh)) {
            for (Note n : pair) {
                if (n.kind() != fresh.kind() || n.source() != fresh.source() || n.followedUpAt() != 0 || !mergeable(n)) {
                    continue;
                }
                double s = similarity(baseOf(n.text()), baseOf(fresh.text()));
                if (s >= SIMILAR && (into == null || s > best || (s == best && n.createdAt() > into.createdAt()))) {
                    into = n;
                    best = s;
                }
            }
        }
        if (into == null) {
            Note stored = insert(fresh);
            if (stored != null) {
                pair.add(stored);
            }
            return stored;
        }
        int count = countOf(into.text()) + 1;
        String suffix = " (x" + count + ")";
        String base = baseOf(fresh.text());
        if (base.length() + suffix.length() > 160) {
            base = base.substring(0, 160 - suffix.length());
        }
        Note merged = into.with(base + suffix, Math.max(into.createdAt(), fresh.createdAt()), into.lastUsedAt(),
                Math.min(fresh.kind().maxWeight(), Math.max(into.weight(), fresh.weight()) + 1), into.followedUpAt(),
                fresh.locationMapId() > 0 ? fresh.locationMapId() : into.locationMapId(),
                Math.max(into.confidence(), fresh.confidence()),
                (into.valence() * (count - 1) + fresh.valence()) / count,
                fresh.otherId() != 0 ? fresh.otherId() : into.otherId());
        persistMerge(merged);
        pair.set(pair.indexOf(into), merged);
        return merged;
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
     * Records how the player looked when a chat ended, so a later greeting can tell whether they leveled up since
     * (owner's answer 8: greetings use what is true now). A slot: one row per pair, replaced, never a remembered fact
     * and never gossip, so it cannot push a real memory out. No schema change (the Kind column already holds it).
     */
    public static Note progress(client.Character bot, client.Character player, long now) {
        if (bot == null || player == null) {
            return null;
        }
        return setSlot(bot.getId() - BotStore.BOT_ID_BASE, player.getId(), Kind.PROGRESS,
                "level " + player.getLevel(), now);
    }

    /** The player's level the last time these two talked, or 0 when they never have. */
    public static int progressLevel(int botId, int charId) {
        Note newest = null;
        for (Note n : about(botId, charId)) {
            if (n.kind() == Kind.PROGRESS && (newest == null || n.createdAt() >= newest.createdAt())) {
                newest = n;
            }
        }
        return newest == null ? 0 : levelOf(newest.text());
    }

    private static int levelOf(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(text == null ? "" : text);
        if (!m.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
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
        double w = n.weight() * (1 + 0.5 * Math.abs(n.valence()))
                * Math.pow(0.5, Math.max(0, now - n.createdAt()) / (double) halfLife);
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
        // 1.6.5 b324: the sweep reads LastUsedAt from the table, so stamps still queued are written first; if the share
        // will not take them now, this sweep is skipped (the next one is 6 h away) rather than forget a note just used
        flushPending();
        synchronized (PENDING) {
            if (!pendingTouch.isEmpty()) {
                log.debug("Bot memory sweep skipped: {} last-used stamps are still waiting to be written", pendingTouch.size());
                return -1;
            }
        }
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
     * and each of the given notes (the bots' grudges, which are what gossip passes on) and every scam it remembers
     * (1.6.0) - so none of it is told again.
     * Notes from the bot's own conversations stay. How many notes went.
     */
    public static int forgetGossipAbout(int charId, List<String> passableNotes) {
        StringBuilder sql = new StringBuilder("DELETE FROM botmemory WHERE AboutCharacterId = ? AND (Source = 'HEARSAY'");
        if (!passableNotes.isEmpty()) {
            sql.append(" OR Note IN (").append("?, ".repeat(passableNotes.size() - 1)).append("?)");
        }
        if (hasKinds()) {
            sql.append(" OR Kind = 'SCAM'"); // 1.6.0 S3: a scam is a grudge too
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

    /** GM and {@code !qa} staging: the bot forgets everything about this player (a fresh pair). How many notes went. */
    public static int forgetPair(int botId, int charId) {
        long k = key(botId, charId);
        int removed = 0;
        synchronized (lock(k)) {
            try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(DELETE_PAIR)) {
                ps.setInt(1, botId);
                ps.setInt(2, charId);
                removed = ps.executeUpdate();
            } catch (SQLException | RuntimeException e) {
                log.warn("Could not clear what bot {} remembers about {}", botId, charId, e);
            }
            synchronized (cache) {
                cache.remove(k);
            }
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
        } catch (RuntimeException e) {
            return false; // no pool yet: ask again next time
        }
    }

    /**
     * Whether migration 063's columns exist (and 030's, which they sit on). Asked once (again 10 minutes after a "no"):
     * without them notes keep their subject type, place, confidence, valence, provenance and other party in memory only
     * and read back with the defaults of their source.
     */
    static boolean hasSocial() {
        if (!hasKinds()) {
            return false;
        }
        Boolean known = social;
        if (known != null && (known || clock.getAsLong() - socialCheckedAt < TimeUnit.MINUTES.toMillis(10))) {
            return known;
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(PROBE_SOCIAL);
             ResultSet ignored = ps.executeQuery()) {
            social = true;
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == 1054 || "42S22".equals(e.getSQLState())) { // unknown column: 063 not applied
                social = false;
                socialCheckedAt = clock.getAsLong();
                log.info("botmemory has no SubjectType column (migration 063 not applied): the new note fields are kept in memory only");
            }
            return false;
        } catch (RuntimeException e) {
            return false; // no pool yet: ask again next time
        }
    }

    /** The pair's notes, or null when they could not be read (1.6.5 b327: not the same thing as "none"). */
    private static List<Note> load(int botId, int charId) {
        List<Note> notes = new ArrayList<>();
        boolean extended = hasKinds();
        boolean social = hasSocial();
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(SELECT_PAIR)) {
            ps.setInt(1, botId);
            ps.setInt(2, charId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    notes.add(read(rs, extended, social));
                }
            }
        } catch (SQLException e) {
            if (BotDbGate.refused(e)) {
                LOADS_REFUSED.incrementAndGet();
                log.debug("The bots' share refused reading what bot {} remembers about {}", botId, charId);
            } else {
                LOADS_FAILED.incrementAndGet();
                log.warn("Could not read what bot {} remembers about {}", botId, charId, e);
            }
            return null;
        }
        LOADS_OK.incrementAndGet();
        // 1.6.5 b324: a stamp still queued is newer than the row: the read pair carries it, so an evicted pair read back
        // before the flush does not bring back a follow-up that was already made
        synchronized (PENDING) {
            if (!pendingTouch.isEmpty() || !pendingFollow.isEmpty()) {
                for (int i = 0; i < notes.size(); i++) {
                    Note n = notes.get(i);
                    Long used = pendingTouch.get(n.id());
                    Long followed = pendingFollow.get(n.id());
                    if (used != null && used > n.lastUsedAt()) {
                        n = n.used(used);
                    }
                    if (followed != null && followed > n.followedUpAt()) {
                        n = n.followedUp(followed);
                    }
                    notes.set(i, n);
                }
            }
        }
        return notes;
    }

    private static Note read(ResultSet rs, boolean extended, boolean social) throws SQLException {
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
        Source source = Source.valueOf(rs.getString("Source"));
        int about = rs.getInt("AboutCharacterId");
        SubjectType subject = null; // null: from the id (a pre-063 row)
        int location = 0;
        double confidence = Double.NaN; // NaN: the default of the source
        double valence = 0;
        Provenance provenance = null; // null: the default of the source
        int other = 0;
        if (social) {
            subject = SubjectType.parse(rs.getString("SubjectType"), about);
            location = rs.getInt("Location");
            if (rs.wasNull()) {
                location = 0;
            }
            confidence = rs.getDouble("Confidence");
            if (rs.wasNull()) {
                confidence = Double.NaN;
            }
            valence = rs.getDouble("Valence");
            String raw = rs.getString("Provenance");
            provenance = raw == null ? null : Provenance.parse(raw, source);
            other = rs.getInt("OtherParticipant");
            if (rs.wasNull()) {
                other = 0;
            }
        }
        return new Note(rs.getLong("Id"), rs.getInt("BotId"), about, rs.getString("Note"), source,
                rs.getInt("FromBotId"), rs.getInt("Hops"), rs.getLong("CreatedAt"), rs.getLong("LastUsedAt"), kind,
                weight, tag, followedUpAt, subject, location, confidence, valence, provenance, other);
    }

    /** Writes a merged note's new text, date and (by schema) weight and feeling; the row stays. */
    private static void persistMerge(Note merged) {
        if (merged.id() <= 0) {
            return; // a memory-only note
        }
        boolean social = hasSocial();
        boolean extended = social || hasKinds();
        try (Connection con = connections().get();
             PreparedStatement ps = con.prepareStatement(social ? MERGE_SOCIAL : extended ? MERGE_KINDS : MERGE_PLAIN)) {
            ps.setString(1, merged.text());
            ps.setLong(2, merged.createdAt());
            if (social) {
                ps.setInt(3, merged.weight());
                setNullable(ps, 4, merged.locationMapId());
                ps.setDouble(5, merged.confidence());
                ps.setDouble(6, merged.valence());
                setNullable(ps, 7, merged.otherId());
                ps.setLong(8, merged.id());
            } else if (extended) {
                ps.setInt(3, merged.weight());
                ps.setLong(4, merged.id());
            } else {
                ps.setLong(3, merged.id());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not merge memory {} of bot {}", merged.id(), merged.botId(), e);
        }
    }

    /** A nullable id column: 0 is stored as NULL. */
    private static void setNullable(PreparedStatement ps, int index, int value) throws SQLException {
        if (value > 0) {
            ps.setInt(index, value);
        } else {
            ps.setNull(index, java.sql.Types.INTEGER);
        }
    }

    /**
     * Stores a new note. 1.6.5 b324: a refusal of the bots' share no longer loses it: the note is queued (capped) and
     * written by the next flush ({@link #flushPending}), and the caller keeps it in memory with id 0 meanwhile. A
     * queue at its cap drops the note and counts it; any other failure counts it as failed. Either way the caller gets
     * null, never a note that looks stored.
     */
    private static Note insert(Note note) {
        try {
            Note stored = insertRow(note);
            INSERTS_WRITTEN.incrementAndGet();
            return stored;
        } catch (SQLException e) {
            if (BotDbGate.refused(e)) {
                return queueInsert(note);
            }
            INSERTS_FAILED.incrementAndGet();
            log.warn("Could not store a memory of bot {}", note.botId(), e);
            return null;
        }
    }

    private static Note insertRow(Note note) throws SQLException {
        boolean social = hasSocial();
        boolean extended = social || hasKinds();
        try (Connection con = connections().get();
             PreparedStatement ps = con.prepareStatement(social ? INSERT_SOCIAL : extended ? INSERT_KINDS : INSERT,
                     Statement.RETURN_GENERATED_KEYS)) {
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
            if (social) {
                ps.setString(13, note.subjectType().name());
                setNullable(ps, 14, note.locationMapId());
                ps.setDouble(15, note.confidence());
                ps.setDouble(16, note.valence());
                ps.setString(17, note.provenance().name());
                setNullable(ps, 18, note.otherId());
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
                    note.followedUpAt(), note.subjectType(), note.locationMapId(), note.confidence(), note.valence(),
                    note.provenance(), note.otherId());
        }
    }

    private static void delete(Note note) {
        if (note.id() <= 0) {
            synchronized (PENDING) {
                pendingInserts.remove(note); // a queued note forgotten before it was written is never written
            }
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
        // 1.6.5 b324: one queued stamp per note (the newest wins), written in one batch per flush on a platform thread,
        // instead of one virtual-thread UPDATE per note that a busy share refused and the code then dropped
        for (Long id : ids) {
            queueStamp(pendingTouch, id, now);
        }
    }

    private static BotStore.ConnectionSource connections() {
        BotStore.ConnectionSource source = connectionSource;
        return source != null ? source : BotDbGate::connection;
    }

    // ── 1.6.5 b324/b327: refused work is kept, retried a bounded number of times, capped and counted ──

    /** Pending stamps (last-used and followed-up together) the queue holds at most; past it a new one is dropped. */
    static final int MAX_PENDING_STAMPS = 20_000;
    /** Pending inserts the queue holds at most; past it a new note is dropped (and counted). */
    static final int MAX_PENDING_INSERTS = 2_000;
    /** Stamps per JDBC batch, and inserts per flush: one flush holds one permit for one bounded piece of work. */
    static final int STAMP_BATCH = 500;
    static final int INSERT_BATCH = 100;
    /** How often the flusher writes what is pending. */
    static final long FLUSH_EVERY_MS = 5_000;

    private static volatile int stampCap = MAX_PENDING_STAMPS;
    private static volatile int insertCap = MAX_PENDING_INSERTS;
    /** Guards the three queues below. */
    private static final Object PENDING = new Object();
    /** Note id -> the newest stamp waiting to be written, oldest first. */
    private static final LinkedHashMap<Long, Long> pendingTouch = new LinkedHashMap<>();
    private static final LinkedHashMap<Long, Long> pendingFollow = new LinkedHashMap<>();
    /** Notes whose insert the share refused, oldest first; each is in its pair's cache with id 0 meanwhile. */
    private static final java.util.ArrayDeque<Note> pendingInserts = new java.util.ArrayDeque<>();
    /** Pairs whose last read did not happen (b327): never cached from a writer's partial view. */
    private static final Set<Long> unread = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean flushing = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.atomic.AtomicBoolean flusherStarted = new java.util.concurrent.atomic.AtomicBoolean();

    private static final java.util.concurrent.atomic.AtomicLong LOADS_OK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LOADS_REFUSED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong LOADS_FAILED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAMPS_QUEUED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAMPS_WRITTEN = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAMPS_DROPPED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAMPS_FAILED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAMP_FLUSHES_REFUSED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INSERTS_WRITTEN = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INSERTS_QUEUED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INSERTS_RETRIED_OK = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INSERTS_FAILED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong INSERTS_DROPPED = new java.util.concurrent.atomic.AtomicLong();

    /**
     * What bot memory's database work came to since boot, by operation: the {@code BotDbGate} minute line carries it
     * ({@link #statsLine}) and the tests read it. "Written" moves only for a row that was written; every refused, failed
     * or dropped piece of work moves its own counter, so nothing lost is ever counted as a success.
     */
    public record Stats(long loadsOk, long loadsRefused, long loadsFailed, long touchesQueued, long touchesWritten,
                        long touchesDropped, long touchesFailed, long touchFlushesRefused, long insertsWritten,
                        long insertsQueued, long insertsRetriedOk, long insertsFailed, long insertsDropped,
                        int pendingTouches, int pendingInserts) {
    }

    public static Stats stats() {
        int stamps;
        int inserts;
        synchronized (PENDING) {
            stamps = pendingTouch.size() + pendingFollow.size();
            inserts = pendingInserts.size();
        }
        return new Stats(LOADS_OK.get(), LOADS_REFUSED.get(), LOADS_FAILED.get(), STAMPS_QUEUED.get(),
                STAMPS_WRITTEN.get(), STAMPS_DROPPED.get(), STAMPS_FAILED.get(), STAMP_FLUSHES_REFUSED.get(),
                INSERTS_WRITTEN.get(), INSERTS_QUEUED.get(), INSERTS_RETRIED_OK.get(), INSERTS_FAILED.get(),
                INSERTS_DROPPED.get(), stamps, inserts);
    }

    /** The memory half of the {@code BotDbGate} minute line: every operation's outcome since boot, and what waits. */
    public static String statsLine() {
        Stats s = stats();
        return "memory loads ok=" + s.loadsOk() + " refused=" + s.loadsRefused() + " failed=" + s.loadsFailed()
                + "; stamps queued=" + s.touchesQueued() + " written=" + s.touchesWritten() + " dropped="
                + s.touchesDropped() + " failed=" + s.touchesFailed() + " flushesRefused=" + s.touchFlushesRefused()
                + " pending=" + s.pendingTouches() + "; inserts written=" + s.insertsWritten() + " queued="
                + s.insertsQueued() + " retriedOk=" + s.insertsRetriedOk() + " failed=" + s.insertsFailed()
                + " dropped=" + s.insertsDropped() + " pending=" + s.pendingInserts();
    }

    /** Queues one stamp; a note already queued keeps one entry with the newest value (no cap needed for it). */
    private static void queueStamp(LinkedHashMap<Long, Long> queue, long id, long value) {
        if (id <= 0) {
            return;
        }
        synchronized (PENDING) {
            Long had = queue.get(id);
            if (had != null) {
                queue.put(id, Math.max(had, value));
                return;
            }
            if (pendingTouch.size() + pendingFollow.size() >= stampCap) {
                STAMPS_DROPPED.incrementAndGet();
                return;
            }
            queue.put(id, value);
            STAMPS_QUEUED.incrementAndGet();
        }
        startFlusher();
    }

    /** A refused insert: queued (with id 0, so the caller keeps it in memory), or dropped and counted at the cap. */
    private static Note queueInsert(Note note) {
        synchronized (PENDING) {
            if (pendingInserts.size() >= insertCap) {
                INSERTS_DROPPED.incrementAndGet();
                log.debug("The bots' share refused a memory of bot {} and the retry queue is full ({}): dropped",
                        note.botId(), insertCap);
                return null;
            }
            pendingInserts.addLast(note);
            INSERTS_QUEUED.incrementAndGet();
        }
        startFlusher();
        return note;
    }

    /**
     * Starts the 5-second flusher once, against the real pool only: a test that installs its own connection source
     * calls {@link #flushPending} itself. The flush runs on a platform thread ({@code runBotDb}), the one kind of thread
     * that may wait for the bots' share (BotDbGate's class notes), and at most one flush runs at a time.
     */
    private static void startFlusher() {
        if (connectionSource != null || !flusherStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(
                    () -> soloMapling.server.ExecutorServiceManager.runBotDb(BotMemory::flushPending),
                    FLUSH_EVERY_MS, FLUSH_EVERY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            flusherStarted.set(false); // no scheduler yet (early boot): the next queued write tries again
        }
    }

    /**
     * Writes what is pending: each stamp column as one JDBC batch of at most {@link #STAMP_BATCH}, then at most
     * {@link #INSERT_BATCH} queued inserts, each through {@link BotDbGate#write} (a refusal is asked again a bounded
     * number of times). Whatever the share still refuses stays queued for the next flush; a statement that fails for
     * any other reason is dropped and counted as failed, so a bad row is never retried for ever.
     */
    static void flushPending() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flushStamps(TOUCH, pendingTouch);
            flushStamps(FOLLOWED_UP, pendingFollow);
            flushInserts();
        } catch (RuntimeException e) {
            log.warn("Bot memory flush failed; what is pending stays queued", e);
        } finally {
            flushing.set(false);
        }
    }

    private static void flushStamps(String sql, LinkedHashMap<Long, Long> queue) {
        List<Map.Entry<Long, Long>> batch = new ArrayList<>();
        synchronized (PENDING) {
            for (Map.Entry<Long, Long> e : queue.entrySet()) {
                batch.add(Map.entry(e.getKey(), e.getValue()));
                if (batch.size() >= STAMP_BATCH) {
                    break;
                }
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            BotDbGate.write(() -> {
                try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(sql)) {
                    for (Map.Entry<Long, Long> e : batch) {
                        ps.setLong(1, e.getValue());
                        ps.setLong(2, e.getKey());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
            settle(queue, batch);
            STAMPS_WRITTEN.addAndGet(batch.size());
        } catch (SQLException e) {
            if (BotDbGate.refused(e)) {
                STAMP_FLUSHES_REFUSED.incrementAndGet(); // kept: the next flush asks again
                log.debug("The bots' share refused {} memory stamps; they stay queued", batch.size());
            } else {
                settle(queue, batch);
                STAMPS_FAILED.addAndGet(batch.size());
                log.warn("Could not write {} memory stamps; dropped", batch.size(), e);
            }
        }
    }

    /** Takes written (or failed) stamps off the queue, unless a newer stamp arrived for that note meanwhile. */
    private static void settle(LinkedHashMap<Long, Long> queue, List<Map.Entry<Long, Long>> batch) {
        synchronized (PENDING) {
            for (Map.Entry<Long, Long> e : batch) {
                queue.remove(e.getKey(), e.getValue());
            }
        }
    }

    private static void flushInserts() {
        for (int i = 0; i < INSERT_BATCH; i++) {
            Note next;
            synchronized (PENDING) {
                next = pendingInserts.peekFirst();
            }
            if (next == null) {
                return;
            }
            Note stored;
            try {
                stored = BotDbGate.write(() -> insertRow(next));
            } catch (SQLException e) {
                if (BotDbGate.refused(e)) {
                    log.debug("The bots' share refused a queued memory of bot {}; it stays queued", next.botId());
                    return; // the share is busy: the rest waits for the next flush too
                }
                synchronized (PENDING) {
                    pendingInserts.remove(next);
                }
                INSERTS_FAILED.incrementAndGet();
                log.warn("Could not store a queued memory of bot {}; dropped", next.botId(), e);
                continue;
            }
            synchronized (PENDING) {
                pendingInserts.remove(next);
            }
            INSERTS_WRITTEN.incrementAndGet();
            INSERTS_RETRIED_OK.incrementAndGet();
            long k = key(next.botId(), next.aboutCharId());
            synchronized (lock(k)) {
                List<Note> pair = cached(k);
                if (pair != null && pair.contains(next)) {
                    List<Note> swapped = new ArrayList<>(pair);
                    swapped.set(swapped.indexOf(next), stored); // the cached note gets its real row id
                    put(k, swapped);
                }
            }
        }
    }

    /**
     * The shutdown sequence's last write of bot memory ({@code Server}, after the bot save, under
     * {@link BotDbGate#unmetered}): flushes until nothing is pending, or a flush makes no progress, so the last five
     * seconds of stamps and any queued notes survive a restart.
     */
    public static void flushAtShutdown() {
        for (int round = 0; round < 200; round++) {
            Stats before = stats();
            if (before.pendingTouches() == 0 && before.pendingInserts() == 0) {
                return;
            }
            flushPending();
            Stats after = stats();
            if (after.pendingTouches() >= before.pendingTouches() && after.pendingInserts() >= before.pendingInserts()) {
                log.warn("Shutdown: {} bot memory stamps and {} notes could not be written", after.pendingTouches(),
                        after.pendingInserts());
                return;
            }
        }
    }

    /** Test seam: the queues' caps ({@code <= 0} restores the default). */
    static void useCapsForTest(int stamps, int inserts) {
        stampCap = stamps > 0 ? stamps : MAX_PENDING_STAMPS;
        insertCap = inserts > 0 ? inserts : MAX_PENDING_INSERTS;
    }

    private static void resetPendingForTest() {
        synchronized (PENDING) {
            pendingTouch.clear();
            pendingFollow.clear();
            pendingInserts.clear();
        }
        unread.clear();
        for (java.util.concurrent.atomic.AtomicLong c : List.of(LOADS_OK, LOADS_REFUSED, LOADS_FAILED, STAMPS_QUEUED,
                STAMPS_WRITTEN, STAMPS_DROPPED, STAMPS_FAILED, STAMP_FLUSHES_REFUSED, INSERTS_WRITTEN, INSERTS_QUEUED,
                INSERTS_RETRIED_OK, INSERTS_FAILED, INSERTS_DROPPED)) {
            c.set(0);
        }
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
        resetPendingForTest();
        kinds = null;
        social = null;
        lastPruneAt = 0;
        warming.clear();
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
