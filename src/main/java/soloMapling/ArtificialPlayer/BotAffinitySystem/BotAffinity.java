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
        pt.optedOut = store.optedOut(charId);
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
        for (Tie dropped : bound(pt, now)) {
            store.delete(charId, dropped.botId);
        }
        pt.touchedAt = now;
        online.put(charId, pt);
        return pt;
    }

    /** Keeps at most {@link #maxTiesPerPlayer()} ties, dropping the weakest; the dropped ones. */
    private static List<Tie> bound(PlayerTies pt, long now) {
        int max = maxTiesPerPlayer();
        List<Tie> dropped = new ArrayList<>();
        if (pt.ties.size() <= max) {
            return dropped;
        }
        List<Tie> all = new ArrayList<>(pt.ties.values());
        all.sort(Comparator.comparingDouble(t -> Math.abs(AffinityRules.scoreAt(t, now))));
        for (Tie t : all.subList(0, all.size() - max)) {
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
            AffinityState s = AffinityRules.state(tie, now(), pt.optedOut);
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

    /** Whether the player turned bot drama off. Memory only (false until their ties are loaded). */
    public static boolean optedOut(int playerId) {
        PlayerTies pt = online.get(playerId);
        return pt != null && pt.optedOut;
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
            Tie tie = pt.ties.get(slot);
            if (tie == null) {
                if (delta < 0 && pt.optedOut) {
                    return false;
                }
                tie = new Tie(slot, playerId, 0, 0, 0, now);
            }
            before = AffinityRules.state(pt.ties.get(slot), now, pt.optedOut);
            if (!AffinityRules.apply(tie, event, delta, now, pt.optedOut)) {
                return false;
            }
            pt.ties.put(slot, tie);
            after = AffinityRules.state(tie, now, pt.optedOut);
            snapshot = tie.snapshot();
            tie.dirty = false;
            for (Tie dropped : bound(pt, now)) {
                writer.execute(() -> store.delete(playerId, dropped.botId));
            }
        }
        pt.touchedAt = now;
        writer.execute(() -> store.save(snapshot));
        if (QaLog.on(QaLog.Topic.AFFINITY)) { // !qa af-1..af-3
            QaLog.map(QaLog.Topic.AFFINITY, mapId, "EVENT " + event.key() + " " + signed(delta) + " bot " + name(botName,
                    botCharId) + " -> " + name(playerName, playerId) + ": score " + Math.round(snapshot.score)
                    + " (kind " + snapshot.kind + ", hostile " + snapshot.hostile + "), " + before.label()
                    + (before != after ? " -> " + after.label().toUpperCase(Locale.ROOT) : ""));
        }
        if (before != after) {
            onStateChange(slot, playerId, after, event);
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

    /** {@code @botdrama off|on}: stored per character, effective at once. */
    public static void setOptedOut(Character player, boolean out) {
        int charId = player.getId();
        PlayerTies pt = online.get(charId);
        if (pt != null) {
            pt.optedOut = out;
        }
        long now = now();
        writer.execute(() -> {
            store.setOptedOut(charId, out, now);
            if (online.get(charId) == null) {
                PlayerTies loaded = load(charId); // they typed it before their ties were loaded
                loaded.optedOut = out;
            }
        });
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
            writer.execute(() -> store.delete(playerId, snapshot.botId));
        } else {
            writer.execute(() -> store.save(snapshot));
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.any(QaLog.Topic.AFFINITY, "STAGED bot " + botCharId + " -> " + playerId + " " + state.label());
        }
        return true;
    }

    /** A fresh grudge: at least a rival, as of now. False when the player is not loaded or opted out. */
    public static boolean forceGrudge(int botCharId, int playerId) {
        PlayerTies pt = tiesLoading(playerId);
        if (pt == null || !isBotId(botCharId) || pt.optedOut) {
            return false;
        }
        long now = now();
        Tie snapshot;
        synchronized (pt) {
            Tie tie = pt.ties.computeIfAbsent(slot(botCharId), s -> new Tie(s, playerId, 0, 0, 0, now));
            AffinityRules.forceGrudge(tie, now);
            snapshot = tie.snapshot();
        }
        writer.execute(() -> store.save(snapshot));
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
                return "neutral (no history)" + (pt.optedOut ? ", player has @botdrama off" : "");
            }
            return AffinityRules.state(tie, now, pt.optedOut).label() + ": score " + Math.round(AffinityRules.scoreAt(tie,
                    now)) + " (stored " + Math.round(tie.score) + " " + age(now - tie.updatedAt) + " ago), kind "
                    + tie.kind + ", hostile " + tie.hostile + (pt.optedOut ? ", player has @botdrama off" : "")
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
            out.add(botNames.apply(BotStore.BOT_ID_BASE + t.botId) + ": " + AffinityRules.state(t, now, pt.optedOut)
                    .label() + " " + Math.round(AffinityRules.scoreAt(t, now)) + " (kind " + t.kind + ", hostile "
                    + t.hostile + ")");
        }
        if (out.isEmpty()) {
            out.add("no bot has feelings about them yet");
        }
        if (pt.optedOut) {
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
                writer.execute(() -> store.delete(charId, slot));
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
        online.clear();
        loading.clear();
    }
}
