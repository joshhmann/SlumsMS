package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;

/**
 * Ticket 58, 1.5.0: each bot's feelings about each real player. A tie is small (a score, two counters, a time), is
 * persisted ({@code botaffinity}, migration 011) and fades with time; {@link AffinityRules} has the arithmetic.
 *
 * <p><b>Cheap by construction.</b> Ties only matter while their player is online, so a player's ties are read once,
 * off the game thread, the first time they enter a map, and kept in memory while they play; every read here is a map
 * lookup and never touches the database. Writes go out on a worker when something changed. Off-screen bots do no
 * affinity work at all: every event needs a real player who did something to the bot.
 *
 * <p>Players never see a number or a state: it shows only in how bots behave ({@link AffinityReactions}). A player
 * who said {@code @botdrama off} is never a rival or nemesis to any bot, and hostile events about them do not count.
 *
 * <p>System properties: {@code solomapling.affinity} (true) switches it all off; {@code solomapling.affinity.hostility}
 * (true) keeps scores but stops every hostile behaviour; {@code solomapling.affinity.maxTiesPerPlayer} (40); plus the
 * event weights and thresholds in {@link AffinityEvent} and {@link AffinityRules}.
 */
public final class BotAffinity {

    private static final Logger log = LoggerFactory.getLogger(BotAffinity.class);

    /** Memory notes a bot writes when its feelings change, so its chat and gossip carry them (ticket 14/15). */
    public static final String NOTE_FRIEND = "is a good friend, always nice to me";
    public static final String NOTE_KS = "keeps ksing my mobs";
    public static final String NOTE_STEAL = "took my drops";
    public static final String NOTE_DEFAME = "defamed me for no reason";
    public static final String NOTE_RUDE = "was rude to me";
    public static final String NOTE_BEEF = "we have beef";
    public static final String NOTE_NEMESIS = "my nemesis, cant stand them";
    /** Notes that, heard second hand, make a listener a little cooler or warmer toward that player. */
    static final List<String> HOSTILE_NOTES = List.of(NOTE_KS, NOTE_STEAL, NOTE_DEFAME, NOTE_BEEF, NOTE_NEMESIS);
    static final List<String> FRIENDLY_NOTES = List.of(NOTE_FRIEND);
    /** The grudge notes an {@code @botdrama off} purges (gossip passes them on). */
    static final List<String> DRAMA_NOTES = List.of(NOTE_KS, NOTE_STEAL, NOTE_DEFAME, NOTE_RUDE, NOTE_BEEF, NOTE_NEMESIS);

    /** One online player's ties. Guarded by itself. */
    static final class PlayerTies {
        final int charId;
        final Map<Integer, Tie> ties = new HashMap<>();
        volatile boolean optedOut;
        volatile long touchedAt;

        PlayerTies(int charId) {
            this.charId = charId;
        }
    }

    private static final Map<Integer, PlayerTies> online = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> loading = new ConcurrentHashMap<>();
    private static volatile AffinityStore store = new AffinityStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    /** Whether a player is still online (the sweep drops the others). */
    private static volatile IntPredicate isOnline = BotAffinity::playerOnline;
    private static volatile boolean sweeperStarted;
    /**
     * Every player with {@code @botdrama off}: read from {@code botdrama} at boot ({@link #loadOptOuts}) and kept in
     * step with every toggle, so the opt-out holds whether or not the player's ties are loaded.
     */
    private static final java.util.Set<Integer> optedOutIds = ConcurrentHashMap.newKeySet();
    private static volatile boolean optOutsLoaded;
    private static volatile long optOutsTriedAt;
    /** Writes for one player land in order (a save never overtakes the delete of the same tie, or the reverse). */
    private static final soloMapling.ArtificialPlayer.SerialWrites writes =
            new soloMapling.ArtificialPlayer.SerialWrites(r -> writer.execute(r));
    /** What a toggle to off purges: the gossip about the player that bots hold (a seam for tests). */
    private static final java.util.function.IntConsumer MEMORY_PURGE = BotAffinity::purgeGossip;
    private static volatile java.util.function.IntConsumer purge = MEMORY_PURGE;
    /** Defame claims when the store cannot answer (no migration, database down): player id -> when. */
    private static final Map<Integer, Long> defamedInMemory = new ConcurrentHashMap<>();

    private BotAffinity() {
    }

    // ── switches ──

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.affinity", "true"));
    }

    /** Whether hostile behaviour (trash talk, KS, defame, refusals) may happen at all. */
    public static boolean hostilityEnabled() {
        return enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.affinity.hostility", "true"));
    }

    static int maxTiesPerPlayer() {
        return Integer.getInteger("solomapling.affinity.maxTiesPerPlayer", 40);
    }

    public static long now() {
        return clock.getAsLong();
    }

    // ── a player's ties: loaded when they show up, dropped when they are gone ──

    /** A real player entered a map: their ties are read the first time (off the calling thread). */
    public static void ensureLoaded(Character player) {
        if (player == null || !enabled() || isBotId(player.getId())) {
            return;
        }
        int charId = player.getId();
        PlayerTies known = online.get(charId);
        if (known != null) {
            known.touchedAt = now();
            return;
        }
        if (loading.putIfAbsent(charId, Boolean.TRUE) != null) {
            return;
        }
        startSweeper();
        if (!optOutsLoaded) {
            writer.execute(BotAffinity::loadOptOuts); // the boot read failed: try again (at most once a minute)
        }
        writer.execute(() -> {
            try {
                load(charId);
            } catch (RuntimeException e) {
                log.warn("Could not load bot affinity for character {}", charId, e);
            } finally {
                loading.remove(charId);
            }
        });
    }

    /** Reads a player's ties now (the caller's thread): drops faded ones and keeps the strongest. */
    static PlayerTies load(int charId) {
        long now = now();
        PlayerTies pt = new PlayerTies(charId);
        pt.optedOut = store.optedOut(charId) || optedOutIds.contains(charId);
        List<Tie> faded = new ArrayList<>();
        for (Tie tie : store.load(charId)) {
            if (AffinityRules.faded(tie, now)) {
                faded.add(tie);
            } else {
                pt.ties.put(tie.botId, tie);
            }
        }
        for (Tie tie : faded) {
            store.delete(charId, tie.botId);
        }
        for (Tie dropped : bound(pt, now, -1)) {
            store.delete(charId, dropped.botId);
        }
        pt.touchedAt = now;
        online.put(charId, pt);
        return pt;
    }

    /**
     * Keeps at most {@link #maxTiesPerPlayer()} ties, dropping the weakest; the dropped ones. The tie of bot slot
     * {@code keep} (the one an event just touched, -1 for none) is never dropped: the weakest of the others goes.
     */
    static List<Tie> bound(PlayerTies pt, long now, int keep) {
        int max = maxTiesPerPlayer();
        List<Tie> dropped = new ArrayList<>();
        if (pt.ties.size() <= max) {
            return dropped;
        }
        List<Tie> all = new ArrayList<>(pt.ties.values());
        all.removeIf(t -> t.botId == keep);
        all.sort(Comparator.comparingDouble(t -> Math.abs(AffinityRules.scoreAt(t, now))));
        int excess = pt.ties.size() - max;
        for (Tie t : all.subList(0, Math.min(excess, all.size()))) {
            pt.ties.remove(t.botId);
            dropped.add(t);
        }
        return dropped;
    }

    /** The player logged off (or the sweep found them gone): forget the in-memory copy. Everything is stored. */
    public static void unload(int charId) {
        online.remove(charId);
    }

    // ── reading: memory only ──

    /** How bot {@code botCharId} feels about player {@code playerId}; neutral when unknown or not loaded. */
    public static AffinityState state(int botCharId, int playerId) {
        if (!enabled() || !isBotId(botCharId)) {
            return AffinityState.NEUTRAL;
        }
        PlayerTies pt = online.get(playerId);
        if (pt == null) {
            return AffinityState.NEUTRAL;
        }
        synchronized (pt) {
            Tie tie = pt.ties.get(slot(botCharId));
            AffinityState s = AffinityRules.state(tie, now(), out(pt));
            return s.hostile() && !hostilityEnabled() ? AffinityState.NEUTRAL : s;
        }
    }

    /** Whether this bot is a rival or nemesis of the player (and hostility is allowed). */
    public static boolean hostile(int botCharId, int playerId) {
        return state(botCharId, playerId).hostile();
    }

    public static boolean friend(int botCharId, int playerId) {
        return state(botCharId, playerId) == AffinityState.FRIEND;
    }

    /** Whether this player's ties are in memory (events about them count). */
    public static boolean loaded(int charId) {
        return online.containsKey(charId);
    }

    /**
     * Whether the player turned bot drama off. Memory only, and authoritative: the set of opted-out players is read
     * from {@code botdrama} at boot and kept in step with every toggle, so it holds whether or not the player's ties
     * are loaded (before the boot read lands, a player whose ties are loaded still answers from their own row).
     */
    public static boolean optedOut(int playerId) {
        if (optedOutIds.contains(playerId)) {
            return true;
        }
        PlayerTies pt = online.get(playerId);
        return pt != null && pt.optedOut;
    }

    private static boolean out(PlayerTies pt) {
        return pt.optedOut || optedOutIds.contains(pt.charId);
    }

    /**
     * Boot: reads every {@code @botdrama off} into memory. A failed read leaves the set unloaded and is retried (at
     * most once a minute) the next time a player's ties load. True when the set is loaded.
     */
    public static boolean loadOptOuts() {
        if (optOutsLoaded) {
            return true;
        }
        long now = now();
        synchronized (optedOutIds) {
            if (optOutsLoaded || (optOutsTriedAt > 0 && now - optOutsTriedAt < TimeUnit.MINUTES.toMillis(1))) {
                return optOutsLoaded;
            }
            optOutsTriedAt = now;
            java.util.Set<Integer> ids;
            try {
                ids = store.loadOptedOut();
            } catch (RuntimeException e) {
                ids = null;
            }
            if (ids == null) {
                log.warn("Could not read the @botdrama opt-outs; trying again later");
                return false;
            }
            optedOutIds.addAll(ids); // toggles made meanwhile are already in the set (and in the table)
            optOutsLoaded = true;
            return true;
        }
    }

    /** Whether any bot holds a non-neutral tie with this player (a cheap pre-check for the on-sight reactions). */
    public static boolean hasTies(int playerId) {
        PlayerTies pt = online.get(playerId);
        if (pt == null) {
            return false;
        }
        synchronized (pt) {
            return !pt.ties.isEmpty();
        }
    }

    // ── events ──

    /** Records an event between a bot and a real player (either order of characters is fine). */
    public static boolean record(Character a, Character b, AffinityEvent event) {
        if (a == null || b == null) {
            return false;
        }
        boolean aBot = isBotId(a.getId());
        boolean bBot = isBotId(b.getId());
        if (aBot == bBot) {
            return false; // bot-bot and player-player ties are not part of 1.5.0
        }
        Character bot = aBot ? a : b;
        Character player = aBot ? b : a;
        return record(bot.getId(), player.getId(), event, event.delta(), player.getName(), bot.getName(),
                player.getMapId());
    }

    /** As {@link #record(Character, Character, AffinityEvent)} with ids and an explicit delta. */
    public static boolean record(int botCharId, int playerId, AffinityEvent event, int delta, String playerName,
                                 String botName, int mapId) {
        if (!enabled() || !isBotId(botCharId) || isBotId(playerId)) {
            return false;
        }
        PlayerTies pt = online.get(playerId);
        if (pt == null) {
            return false; // not loaded yet (they just logged in): this one is not counted
        }
        long now = now();
        int slot = slot(botCharId);
        AffinityState before;
        AffinityState after;
        Tie snapshot;
        synchronized (pt) {
            boolean off = out(pt);
            Tie tie = pt.ties.get(slot);
            if (tie == null) {
                if (delta < 0 && off) {
                    return false;
                }
                tie = new Tie(slot, playerId, 0, 0, 0, now);
            }
            before = AffinityRules.state(pt.ties.get(slot), now, off);
            if (!AffinityRules.apply(tie, event, delta, now, off)) {
                return false;
            }
            pt.ties.put(slot, tie);
            after = AffinityRules.state(tie, now, off);
            snapshot = tie.snapshot();
            tie.dirty = false;
            // The cap drops the weakest other tie, never the one just touched; queued in order with the save below,
            // so a delete and a save of the same tie can never land the wrong way round.
            for (Tie dropped : bound(pt, now, slot)) {
                writes.submit(playerId, () -> store.delete(playerId, dropped.botId));
            }
            writes.submit(playerId, () -> store.save(snapshot));
        }
        pt.touchedAt = now;
        if (QaLog.on(QaLog.Topic.AFFINITY)) { // !qa af-1..af-3
            QaLog.map(QaLog.Topic.AFFINITY, mapId, "EVENT " + event.key() + " " + signed(delta) + " bot " + name(botName,
                    botCharId) + " -> " + name(playerName, playerId) + ": score " + Math.round(snapshot.score)
                    + " (kind " + snapshot.kind + ", hostile " + snapshot.hostile + "), " + before.label()
                    + (before != after ? " -> " + after.label().toUpperCase(Locale.ROOT) : ""));
        }
        if (before != after) {
            onStateChange(slot, playerId, after, event);
        }
        if (delta > 0 && !event.hearsay()) {
            // 1.5.0 bot guilds: a guildmate who does things with the bot is not ignoring it
            soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.noteAttention(botCharId, playerId);
        }
        return true;
    }

    /** A bot's feelings crossed into a new state: it writes a short note (chat memory and gossip carry it). */
    private static void onStateChange(int slot, int playerId, AffinityState after, AffinityEvent cause) {
        String note = switch (after) {
            case FRIEND -> NOTE_FRIEND;
            case NEMESIS -> NOTE_NEMESIS;
            case RIVAL -> switch (cause) {
                case KS -> NOTE_KS;
                case DROP_STEAL -> NOTE_STEAL;
                case DEFAME -> NOTE_DEFAME;
                case RUDE_CHAT -> NOTE_RUDE;
                default -> NOTE_BEEF;
            };
            case NEUTRAL -> null;
        };
        if (note == null || cause.hearsay()) {
            return; // gossip alone never writes a first-hand note
        }
        notes.note(slot, playerId, note);
    }

    /** Where a bot's new-feelings note goes: its {@link BotMemory} (a seam for tests). */
    public interface Notes {
        void note(int slot, int playerId, String text);
    }

    private static final Notes MEMORY = (slot, playerId, note) -> writer.execute(() -> {
        try {
            boolean known = BotMemory.about(slot, playerId).stream().anyMatch(n -> n.text().equals(note));
            if (!known) {
                BotMemory.remember(slot, playerId, List.of(note), BotMemory.Source.DIRECT, 0, 0, now());
            }
        } catch (RuntimeException e) {
            log.debug("Could not note bot {}'s new feelings about {}", slot, playerId, e);
        }
    });
    private static volatile Notes notes = MEMORY;

    /** Tests: where notes go (null = bot memory). */
    public static void useNotesForTest(Notes testNotes) {
        notes = testNotes != null ? testNotes : MEMORY;
    }

    /**
     * Gossip (ticket 15) reached a bot: a note about a player it heard second hand. A hostile or friendly affinity
     * note nudges the listener a little ({@link AffinityEvent#HEARSAY_BAD} / {@link AffinityEvent#HEARSAY_GOOD}),
     * never past {@link AffinityRules#HEARSAY_CAP}. Only for players online now (their ties are in memory).
     */
    public static void onHearsay(int listenerSlot, int playerId, String noteText) {
        AffinityEvent event = hearsayOf(noteText);
        if (event != null) {
            record(BotStore.BOT_ID_BASE + listenerSlot, playerId, event, event.delta(), null, null, 0);
        }
    }

    static AffinityEvent hearsayOf(String noteText) {
        if (noteText == null) {
            return null;
        }
        String text = noteText.toLowerCase(Locale.ROOT);
        for (String n : HOSTILE_NOTES) {
            if (text.contains(n)) {
                return AffinityEvent.HEARSAY_BAD;
            }
        }
        for (String n : FRIENDLY_NOTES) {
            if (text.contains(n)) {
                return AffinityEvent.HEARSAY_GOOD;
            }
        }
        return null;
    }

    // ── @botdrama ──

    /**
     * {@code @botdrama off|on}: stored per character, effective at once (also for the gossip about them and every
     * check made while their ties are not loaded). Off also purges what bots gossip about them.
     */
    public static void setOptedOut(Character player, boolean out) {
        int charId = player.getId();
        if (out) {
            optedOutIds.add(charId);
        } else {
            optedOutIds.remove(charId);
        }
        PlayerTies pt = online.get(charId);
        if (pt != null) {
            pt.optedOut = out;
        }
        long now = now();
        writes.submit(charId, () -> {
            store.setOptedOut(charId, out, now);
            if (online.get(charId) == null) {
                PlayerTies loaded = load(charId); // they typed it before their ties were loaded
                loaded.optedOut = out;
            }
        });
        if (out) {
            writes.submit(charId, () -> purge.accept(charId));
        }
    }

    /** Bots forget the gossip about this player: every hearsay note, and every affinity note they could pass on. */
    private static void purgeGossip(int charId) {
        try {
            int purged = BotMemory.forgetGossipAbout(charId, DRAMA_NOTES);
            if (purged > 0 && QaLog.on(QaLog.Topic.AFFINITY)) {
                QaLog.any(QaLog.Topic.AFFINITY, "PURGED " + purged + " gossip notes about " + charId + " (@botdrama off)");
            }
        } catch (RuntimeException e) {
            log.warn("Could not purge the gossip about character {}", charId, e);
        }
    }

    /**
     * Claims today's one bot defame of this player (the server's local day, which starts at {@code dayStart}):
     * true when this call got it. Durable and atomic through the store; when the store cannot answer, a claim in
     * memory stands in so the limit still holds until a restart.
     */
    public static boolean claimDefame(int playerId, long dayStart, long now) {
        synchronized (defamedInMemory) {
            Long last = defamedInMemory.get(playerId);
            if (last != null && last >= dayStart) {
                return false; // this process already gave one today
            }
            Boolean stored;
            try {
                stored = store.claimDefame(playerId, dayStart, now);
            } catch (RuntimeException e) {
                stored = null;
            }
            if (stored != null && !stored) {
                defamedInMemory.put(playerId, now);
                return false;
            }
            defamedInMemory.put(playerId, now);
            if (defamedInMemory.size() > 10_000) {
                defamedInMemory.values().removeIf(t -> t < dayStart);
            }
            return true;
        }
    }

    // ── GM tools (!affinity) ──

    /** Puts bot -> player in a state now (the player must be online). False when the player is not loaded yet. */
    public static boolean stage(int botCharId, int playerId, AffinityState state) {
        PlayerTies pt = tiesLoading(playerId);
        if (pt == null || !isBotId(botCharId)) {
            return false;
        }
        long now = now();
        Tie snapshot;
        synchronized (pt) {
            Tie tie = pt.ties.computeIfAbsent(slot(botCharId), s -> new Tie(s, playerId, 0, 0, 0, now));
            AffinityRules.stage(tie, state, now);
            snapshot = tie.snapshot();
            if (state == AffinityState.NEUTRAL) {
                pt.ties.remove(tie.botId);
            }
        }
        if (state == AffinityState.NEUTRAL) {
            writes.submit(playerId, () -> store.delete(playerId, snapshot.botId));
        } else {
            writes.submit(playerId, () -> store.save(snapshot));
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.any(QaLog.Topic.AFFINITY, "STAGED bot " + botCharId + " -> " + playerId + " " + state.label());
        }
        return true;
    }

    /** A fresh grudge: at least a rival, as of now. False when the player is not loaded or opted out. */
    public static boolean forceGrudge(int botCharId, int playerId) {
        PlayerTies pt = tiesLoading(playerId);
        if (pt == null || !isBotId(botCharId) || out(pt)) {
            return false;
        }
        long now = now();
        Tie snapshot;
        synchronized (pt) {
            Tie tie = pt.ties.computeIfAbsent(slot(botCharId), s -> new Tie(s, playerId, 0, 0, 0, now));
            AffinityRules.forceGrudge(tie, now);
            snapshot = tie.snapshot();
        }
        writes.submit(playerId, () -> store.save(snapshot));
        onStateChange(snapshot.botId, playerId, AffinityState.RIVAL, AffinityEvent.KS);
        return true;
    }

    /** One line about a pair for GMs: state, score now, counters, age, cooldowns. */
    public static String describe(int botCharId, int playerId) {
        PlayerTies pt = tiesLoading(playerId);
        if (pt == null) {
            return "player " + playerId + " is not online";
        }
        long now = now();
        synchronized (pt) {
            Tie tie = pt.ties.get(slot(botCharId));
            if (tie == null) {
                return "neutral (no history)" + (out(pt) ? ", player has @botdrama off" : "");
            }
            return AffinityRules.state(tie, now, out(pt)).label() + ": score " + Math.round(AffinityRules.scoreAt(tie,
                    now)) + " (stored " + Math.round(tie.score) + " " + age(now - tie.updatedAt) + " ago), kind "
                    + tie.kind + ", hostile " + tie.hostile + (out(pt) ? ", player has @botdrama off" : "")
                    + (hostilityEnabled() ? "" : ", hostility switched off server-wide");
        }
    }

    /** Every tie of a player, strongest first, as GM lines. */
    public static List<String> describeAll(int playerId, java.util.function.IntFunction<String> botNames) {
        PlayerTies pt = tiesLoading(playerId);
        List<String> out = new ArrayList<>();
        if (pt == null) {
            out.add("player " + playerId + " is not online");
            return out;
        }
        long now = now();
        List<Tie> ties;
        synchronized (pt) {
            ties = new ArrayList<>();
            for (Tie t : pt.ties.values()) {
                ties.add(t.snapshot());
            }
        }
        ties.sort(Comparator.comparingDouble((Tie t) -> -Math.abs(AffinityRules.scoreAt(t, now))));
        for (Tie t : ties) {
            out.add(botNames.apply(BotStore.BOT_ID_BASE + t.botId) + ": " + AffinityRules.state(t, now, out(pt))
                    .label() + " " + Math.round(AffinityRules.scoreAt(t, now)) + " (kind " + t.kind + ", hostile "
                    + t.hostile + ")");
        }
        if (out.isEmpty()) {
            out.add("no bot has feelings about them yet");
        }
        if (out(pt)) {
            out.add("@botdrama is off for them");
        }
        return out;
    }

    /** Drops every tie a bot has with online players and in the store (a staged !qa bot that left). */
    public static void forgetBot(int botCharId) {
        int slot = slot(botCharId);
        for (PlayerTies pt : online.values()) {
            boolean had;
            synchronized (pt) {
                had = pt.ties.remove(slot) != null;
            }
            if (had) {
                int charId = pt.charId;
                writes.submit(charId, () -> store.delete(charId, slot));
            }
        }
    }

    /** The player's ties, loading them on this thread if needed (GM commands run on a worker). */
    private static PlayerTies tiesLoading(int playerId) {
        PlayerTies pt = online.get(playerId);
        if (pt == null && enabled() && !isBotId(playerId) && isOnline.test(playerId)) {
            pt = load(playerId);
        }
        return pt;
    }

    // ── housekeeping ──

    private static void startSweeper() {
        if (sweeperStarted) {
            return;
        }
        synchronized (BotAffinity.class) {
            if (sweeperStarted) {
                return;
            }
            sweeperStarted = true;
            try {
                ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(BotAffinity::sweep, 5, 5,
                        TimeUnit.MINUTES);
            } catch (RuntimeException e) {
                log.debug("No sweeper for bot affinity", e);
            }
        }
    }

    /** Drops players who have been gone a while (their ties are all stored). */
    static int sweep() {
        long now = now();
        int dropped = 0;
        for (PlayerTies pt : new ArrayList<>(online.values())) {
            if (now - pt.touchedAt > TimeUnit.MINUTES.toMillis(10) && !isOnline.test(pt.charId)) {
                online.remove(pt.charId);
                dropped++;
            }
        }
        return dropped;
    }

    private static boolean playerOnline(int charId) {
        try {
            net.server.world.World world = soloMapling.server.SoloMaplingUtilities.world();
            Character chr = world == null ? null : world.getPlayerStorage().getCharacterById(charId);
            return chr != null && chr.isLoggedinWorld();
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isBotId(int charId) {
        return charId >= BotStore.BOT_ID_BASE;
    }

    static int slot(int botCharId) {
        return botCharId - BotStore.BOT_ID_BASE;
    }

    private static String signed(int d) {
        return d > 0 ? "+" + d : Integer.toString(d);
    }

    private static String name(String name, int id) {
        return name == null ? "#" + id : name;
    }

    private static String age(long ms) {
        long min = TimeUnit.MILLISECONDS.toMinutes(Math.max(0, ms));
        if (min < 120) {
            return min + "m";
        }
        long h = min / 60;
        return h < 48 ? h + "h" : (h / 24) + "d";
    }

    // ── test seams ──

    /** Tests: a store, a writer, a clock and an online check (nulls restore the real ones); forgets everything. */
    public static void useForTest(AffinityStore testStore, Executor testWriter, LongSupplier testClock,
                                  IntPredicate testOnline) {
        store = testStore != null ? testStore : new AffinityStore.Jdbc();
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runAsync;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        isOnline = testOnline != null ? testOnline : BotAffinity::playerOnline;
        purge = testStore != null ? id -> { } : MEMORY_PURGE;
        online.clear();
        loading.clear();
        optedOutIds.clear();
        optOutsLoaded = false;
        optOutsTriedAt = 0;
        defamedInMemory.clear();
    }

    /** Tests: what an opt-out purges (null = bot memory). */
    public static void usePurgeForTest(java.util.function.IntConsumer testPurge) {
        purge = testPurge != null ? testPurge : MEMORY_PURGE;
    }
}
