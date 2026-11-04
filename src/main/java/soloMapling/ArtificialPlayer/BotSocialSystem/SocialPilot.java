package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Ticket 181 stage 7: the Henesys pilot. A small, stable set of roster bots ({@code -Dslums.socialpilot.size}, 20)
 * that live the social layer for real in one town ({@code -Dslums.socialpilot.map}, Henesys 100000000). They are
 * ordinary roster bots on the existing infrastructure ({@code ModeSwitch}, {@code SocialDecider}, intents, bonds,
 * memory); the pilot only adds four things, each a bias or a hold, never a script:
 *
 * <ol>
 *   <li><b>Who.</b> {@link #select}: the active roster members with the lowest {@link #rank} (a mixed hash of the roster
 *       slot), sticky: a member stays while it is active, a replacement is the next lowest hash. Deterministic for a
 *       given roster, and it does not reshuffle when new bots are enrolled.</li>
 *   <li><b>Director hold.</b> {@link #holds}: inside its own play window a pilot bot is not planned out by the
 *       population director (the session-length logout, the above-the-curve trim) and is logged in ahead of other
 *       bots. Everything else that ends a session (a server stop, a GM, a rage quit, a crash, the window ending) works
 *       as before; the director's hold is the one hook ({@code BotPopulationDirector.candidates}).</li>
 *   <li><b>Placement.</b> {@link #destination}: where a pilot bot's town stay goes (a Henesys map from the
 *       {@link SocialRoutine} preference, ahead of the generic town pull); {@link #LIFE_BIAS} adds a small
 *       hang-out pull at the hours its routine gathers; {@code LifeTownSlots.reserve} lets a pilot bot in a Henesys map
 *       that is full for everybody else ({@link #headroom()} extra, so 20 bots never bounce off a 50-bot cap).</li>
 *   <li><b>Counters.</b> {@link #stats()}, memory only: members online, the mode census among them, landed acts by
 *       kind, friendships formed, conflicts, memories written, intents kept and broken, gifts, avoids, restarts
 *       survived, groups.</li>
 * </ol>
 *
 * <p>{@code -Dslums.socialpilot=false}: no members, no hold, no bias, no counters ({@code ModeSwitch} and
 * {@code SocialDecider} behave exactly as before). On unless {@code false}.
 */
public final class SocialPilot {

    private static final Logger log = LoggerFactory.getLogger(SocialPilot.class);

    public static final String SWITCH = "slums.socialpilot";
    public static final String SIZE = "slums.socialpilot.size";
    public static final String MAP = "slums.socialpilot.map";
    public static final int DEFAULT_SIZE = 20;
    public static final int DEFAULT_MAP = 100000000;
    /** How much likelier the director is to log a pilot bot in (weighted sampling), against 1 for a plain roster bot. */
    static final double LOGIN_BOOST = 3.0;
    /** The roster is looked at again at most this often. */
    static final long REFRESH_MS = 10 * 60_000L;
    /** The bond graph is scanned for formed friendships at most this often. */
    static final long SCAN_MS = 2 * 60_000L;
    /** The largest hang-out pull the pilot adds (the plug-in clamp is {@link LifePolicy#MAX_PLUGIN_BIAS}). */
    static final double HANG_OUT_PULL = 0.25;

    private SocialPilot() {
    }

    /** On unless {@code -Dslums.socialpilot=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH));
    }

    /** How many bots the pilot has ({@code -Dslums.socialpilot.size}, default 20, 0 = none). */
    public static int size() {
        return intProperty(SIZE, DEFAULT_SIZE, 0, 200);
    }

    /** The pilot's home map ({@code -Dslums.socialpilot.map}, default Henesys). */
    public static int homeMap() {
        return intProperty(MAP, DEFAULT_MAP, 1, Integer.MAX_VALUE);
    }

    private static int intProperty(String key, int def, int lo, int hi) {
        try {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? def : Math.max(lo, Math.min(hi, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ── who ──

    /** A well-mixed hash of a roster slot: the lowest ranks are the pilot. Fixed forever (changing it reshuffles). */
    public static long rank(int slot) {
        long z = (slot + 0x5F1A07L) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return (z ^ (z >>> 31)) >>> 1; // non-negative, so sorting is plain
    }

    /**
     * The pilot slots: members of {@code current} that are still in {@code activeSlots} stay (lowest rank first when
     * there are more than {@code size}); the rest of the places go to the active slots with the lowest {@link #rank}.
     * Pure; the result is sorted by rank.
     */
    public static List<Integer> select(Collection<Integer> activeSlots, int size, Collection<Integer> current) {
        Set<Integer> active = new HashSet<>(activeSlots);
        List<Integer> kept = new ArrayList<>();
        for (int slot : current) {
            if (active.contains(slot)) {
                kept.add(slot);
            }
        }
        Comparator<Integer> byRank = Comparator.comparingLong(SocialPilot::rank).thenComparingInt(i -> i);
        kept.sort(byRank);
        List<Integer> out = new ArrayList<>(kept.subList(0, Math.min(size, kept.size())));
        if (out.size() < size) {
            Set<Integer> have = new HashSet<>(out);
            List<Integer> rest = new ArrayList<>();
            for (int slot : active) {
                if (!have.contains(slot)) {
                    rest.add(slot);
                }
            }
            rest.sort(byRank);
            for (int i = 0; out.size() < size && i < rest.size(); i++) {
                out.add(rest.get(i));
            }
        }
        out.sort(byRank);
        return out;
    }

    private static volatile Set<Integer> members = Set.of();
    private static volatile long refreshedAt;
    private static volatile long scannedAt;
    private static volatile long bootAt = SocialSim.now();

    /** The pilot's character ids (empty when the pilot is off or the roster has not been read). */
    public static Set<Integer> members() {
        return enabled() ? members : Set.of();
    }

    /** Whether this character is a pilot bot now. */
    public static boolean isPilot(int charId) {
        return enabled() && members.contains(charId);
    }

    /**
     * From the director's tick: keeps the member set (at most every {@link #REFRESH_MS}), the life-policy bias and the
     * friendship scan up to date. {@code activeSlots} supplies the roster slots that may log in (asked only when the roster is due to be read). Never throws.
     */
    public static void maybeStep(long now, java.util.function.Supplier<Collection<Integer>> activeSlots) {
        try {
            if (!enabled()) {
                if (!members.isEmpty()) {
                    members = Set.of();
                }
                refreshedAt = 0; // switched on again later: the roster is read at once
                scannedAt = 0;
                return;
            }
            register();
            if (refreshedAt == 0 || now - refreshedAt >= REFRESH_MS) {
                refreshedAt = now;
                refresh(activeSlots.get(), now);
            }
            if (now - scannedAt >= SCAN_MS) {
                scannedAt = now;
                scan(now);
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("Social pilot step failed", e);
        }
    }

    /** Reselects the members from the roster's active slots (the sticky rule of {@link #select}). */
    static void refresh(Collection<Integer> activeSlots, long now) {
        List<Integer> current = new ArrayList<>();
        for (int id : members) {
            current.add(id - BotStore.BOT_ID_BASE);
        }
        List<Integer> chosen = select(activeSlots, size(), current);
        Set<Integer> next = new LinkedHashSet<>();
        for (int slot : chosen) {
            next.add(BotStore.BOT_ID_BASE + slot);
        }
        if (!next.equals(members)) {
            log.info("Social pilot: {} bots ({} changed)", next.size(), symmetricDifference(members, next));
        }
        members = Set.copyOf(next);
    }

    private static int symmetricDifference(Set<Integer> a, Set<Integer> b) {
        int n = 0;
        for (int x : a) {
            if (!b.contains(x)) n++;
        }
        for (int x : b) {
            if (!a.contains(x)) n++;
        }
        return n;
    }

    /** Test seam: the members as given (character ids), and the clocks of the periodic work reset. */
    static void setMembersForTest(Collection<Integer> charIds) {
        members = Set.copyOf(charIds);
        refreshedAt = 0;
        scannedAt = 0;
    }

    // ── the director's hold ──

    /**
     * Whether the population director keeps this bot online now: it is a pilot bot and the minute of the day is inside
     * its own play window ({@code windowLen} minutes from {@code windowStart}; a bot with no window is never held).
     * Pure but for the member set.
     */
    public static boolean holds(int charId, int windowStart, int windowLen, int minuteOfDay) {
        if (!enabled() || windowLen <= 0 || !members.contains(charId)) {
            return false;
        }
        return ((minuteOfDay - windowStart) % 1440 + 1440) % 1440 < windowLen;
    }

    /** Login weight multiplier of a roster bot (slot): the pilot is logged in ahead of other bots. */
    public static double loginBoost(int slot) {
        return isPilot(BotStore.BOT_ID_BASE + slot) ? LOGIN_BOOST : 1.0;
    }

    // ── placement ──

    /** The pilot's gathering maps: the home map's town family (Henesys: town, market, park, pet park). */
    public static int[] maps() {
        int home = homeMap();
        LifeTowns.Town town = LifeTowns.townOf(home);
        return town == null ? new int[]{home} : town.family().clone();
    }

    /** Whether the map is one of the pilot's gathering maps. */
    public static boolean isPilotMap(int mapId) {
        for (int m : maps()) {
            if (m == mapId) return true;
        }
        return false;
    }

    /** Extra bots a pilot bot may add to a full pilot map: the size of the pilot, so the pilot never bounces off a cap. */
    public static int headroom() {
        return size();
    }

    /**
     * Where this bot's town stay goes: the {@link SocialRoutine} spot of its mind and its group at this hour, or 0 when
     * the bot is not in the pilot, the routine is off or its mind is not in memory. Memory only; called from
     * {@code LifeRuntime.townDestination}.
     */
    public static int destination(int charId, long now) {
        if (!isPilot(charId)) {
            return 0;
        }
        int map = SocialRoutine.preferredMap(charId, now);
        if (map > 0) {
            placements.increment();
        }
        return map;
    }

    /**
     * The hang-out pull of the pilot: at the hours this bot's routine gathers, HANG_OUT (and SHOW_OFF) get up to
     * {@link #HANG_OUT_PULL}, in proportion to the gather and evening weight, and a bot whose rest or companionship need
     * is high ignores the schedule (the weight is damped, see {@link SocialRoutine#townPull}). Never negative.
     */
    public static final LifePolicy.Bias LIFE_BIAS = (activity, context, situation) -> {
        if (situation == null || context == null || context.commitment() || activity != Activity.HANG_OUT
                && activity != Activity.SHOW_OFF) {
            return 0;
        }
        int id = situation.botId();
        if (!isPilot(id)) {
            return 0;
        }
        return HANG_OUT_PULL * SocialRoutine.townPull(id, SocialSim.now());
    };

    private static volatile boolean registered;

    private static synchronized void register() {
        if (!registered) {
            LifePolicy.addBias(LIFE_BIAS);
            registered = true;
        }
    }

    // ── counters ──

    private static final Map<SocialActKind, LongAdder> acts = new EnumMap<>(SocialActKind.class);
    private static final LongAdder placements = new LongAdder(), memoriesWritten = new LongAdder(),
            intentsKept = new LongAdder(), intentsBroken = new LongAdder(), routineBiases = new LongAdder(),
            conflictActs = new LongAdder(), ranActs = new LongAdder();
    private static final Set<Long> everFriend = ConcurrentHashMap.newKeySet();
    private static volatile int friendEdges, mutualFriendPairs, rivalEdges, held;

    static {
        for (SocialActKind kind : SocialActKind.values()) {
            acts.put(kind, new LongAdder());
        }
    }

    /** One carried-out act (called by {@link SocialActions#perform}): a pilot actor's landed acts are counted by kind. */
    static void onAct(int actor, SocialActKind kind, boolean landed) {
        if (!members.contains(actor) || !enabled()) {
            return;
        }
        ranActs.increment();
        if (landed) {
            acts.get(kind).increment();
            if (kind == SocialActKind.ARGUE) {
                conflictActs.increment();
            }
        }
    }

    /** A pilot bot wrote a social memory note (called by the acts' writer). */
    static void onMemory(int actor) {
        if (members.contains(actor) && enabled()) {
            memoriesWritten.increment();
        }
    }

    /** A pilot bot's intent ended kept or broken (called by {@link SocialIntents}). */
    static void onIntent(int actor, boolean kept) {
        if (members.contains(actor) && enabled()) {
            (kept ? intentsKept : intentsBroken).increment();
        }
    }

    /** The mode bias of a pilot bot was non-neutral at a mode evaluation. */
    static void onRoutineBias() {
        routineBiases.increment();
    }

    /** The bond graph among the pilot: directed edges at FRIEND or beyond now, edges that ever were, rivals; groups. */
    static void scan(long now) {
        Set<Integer> ids = members;
        int friends = 0, rivals = 0, mutual = 0;
        for (int a : ids) {
            for (int b : ids) {
                if (a == b) continue;
                Stage s = BotBonds.stage(a, b);
                if (isFriendStage(s)) {
                    friends++;
                    everFriend.add(((long) a << 32) | (b & 0xffffffffL));
                    if (a < b && isFriendStage(BotBonds.stage(b, a))) {
                        mutual++;
                    }
                } else if (s == Stage.RIVAL || s == Stage.ESTRANGED) {
                    rivals++;
                }
            }
        }
        friendEdges = friends;
        mutualFriendPairs = mutual;
        rivalEdges = rivals;
    }

    /** FRIEND or CLOSE_FRIEND (what the groups and the friendship count mean by a friend). */
    public static boolean isFriendStage(Stage s) {
        return s == Stage.FRIEND || s == Stage.CLOSE_FRIEND;
    }

    /** The online roster slots and modes the stats read: the game in production, a fake in tests. */
    public interface Probe {
        boolean online(int charId);

        /** {@code training}, {@code social}, {@code town}, or null. */
        String mode(int charId);
    }

    private static final Probe GAME = new Probe() {
        @Override
        public boolean online(int charId) {
            return CharacterStorage.getBotById(charId) != null;
        }

        @Override
        public String mode(int charId) {
            var bot = CharacterStorage.getBotById(charId);
            ModeSwitch.Mode m = bot == null ? null : ModeSwitch.Mode.of(bot);
            return m == null ? null : m.name().toLowerCase(java.util.Locale.ROOT);
        }
    };
    private static volatile Probe probe = GAME;

    /** Test seam (null restores the game); pair with {@link #resetForTest}. */
    static void useProbeForTest(Probe test) {
        probe = test != null ? test : GAME;
    }

    /**
     * What the pilot has done this run, for the dashboard and auto-QA (a sorted copy; memory only). Keys: {@code members},
     * {@code online}, {@code held}, {@code mode.<mode>}, {@code acts.<KIND>} and {@code acts.landed} / {@code acts.carriedOut},
     * {@code friendships.edges} (directed edges at FRIEND or beyond now), {@code friendships.formed} (edges that reached
     * it this run), {@code friendships.mutualPairs}, {@code conflicts.arguments}, {@code conflicts.rivalEdges},
     * {@code memories}, {@code intents.kept}, {@code intents.broken}, {@code gifts}, {@code avoids},
     * {@code restartsSurvived}, {@code placements}, {@code routineBiases}, {@code groups}, {@code groupedBots}.
     * All zero when the pilot is off.
     */
    public static Map<String, Long> stats() {
        Map<String, Long> out = new TreeMap<>();
        Set<Integer> ids = enabled() ? members : Set.of();
        long online = 0, restarts = 0;
        Map<String, Long> modes = new TreeMap<>();
        for (String m : new String[]{"training", "social", "town"}) {
            modes.put(m, 0L);
        }
        Probe p = probe;
        for (int id : ids) {
            if (p.online(id)) {
                online++;
                String mode = p.mode(id);
                if (mode != null) {
                    modes.merge(mode, 1L, Long::sum);
                }
            }
            LifeMemory mem = LifeMemoryStore.peek(id);
            if (mem != null && mem.restored() && mem.savedAt() > 0 && mem.savedAt() < bootAt) {
                restarts++;
            }
        }
        long landed = 0;
        for (Map.Entry<SocialActKind, LongAdder> e : acts.entrySet()) {
            long n = e.getValue().sum();
            out.put("acts." + e.getKey().name(), n);
            landed += n;
        }
        out.put("acts.landed", landed);
        out.put("acts.carriedOut", ranActs.sum());
        modes.forEach((k, v) -> out.put("mode." + k, v));
        out.put("members", (long) ids.size());
        out.put("online", online);
        out.put("held", (long) held);
        out.put("friendships.edges", (long) friendEdges);
        out.put("friendships.formed", (long) everFriend.size());
        out.put("friendships.mutualPairs", (long) mutualFriendPairs);
        out.put("conflicts.arguments", conflictActs.sum());
        out.put("conflicts.rivalEdges", (long) rivalEdges);
        out.put("memories", memoriesWritten.sum());
        out.put("intents.kept", intentsKept.sum());
        out.put("intents.broken", intentsBroken.sum());
        out.put("gifts", acts.get(SocialActKind.GIVE_GIFT).sum());
        out.put("avoids", acts.get(SocialActKind.AVOID).sum());
        out.put("restartsSurvived", restarts);
        out.put("placements", placements.sum());
        out.put("routineBiases", routineBiases.sum());
        SocialGroups.Snapshot g = SocialGroups.peek();
        out.put("groups", (long) g.groups().size());
        out.put("groupedBots", (long) g.byBot().size());
        return out;
    }

    /** How many members the director holds online right now (set by the director when it builds candidates). */
    public static void setHeld(int n) {
        held = n;
    }

    /** Test seam: forgets members, counters and the periodic clocks. */
    static void resetForTest() {
        members = Set.of();
        refreshedAt = 0;
        scannedAt = 0;
        bootAt = SocialSim.now();
        probe = GAME;
        acts.values().forEach(LongAdder::reset);
        placements.reset();
        memoriesWritten.reset();
        intentsKept.reset();
        intentsBroken.reset();
        routineBiases.reset();
        conflictActs.reset();
        ranActs.reset();
        everFriend.clear();
        friendEdges = 0;
        mutualFriendPairs = 0;
        rivalEdges = 0;
        held = 0;
    }

    /** Test seam: marks "now" as the moment this run started (a bot whose memory was saved before it survived a restart). */
    static void bootNowForTest(long now) {
        bootAt = now;
    }
}
