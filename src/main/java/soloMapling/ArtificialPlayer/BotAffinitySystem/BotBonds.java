package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.SerialWrites;
import soloMapling.ArtificialPlayer.SocialCrew;
import soloMapling.ArtificialPlayer.SocialGate;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * 1.6.0 slice S2: bots' feelings about other bots ("bonds"), and the small crews that close friendships make.
 *
 * <p><b>Bounded by construction.</b> 3,000 bots make 9 million pairs, so nothing here stores or scans all of them:
 * <ul>
 *   <li>each bot keeps at most {@link #maxBonds()} (14) bonds; past that the weakest goes (the one just touched only
 *   when it is itself the weakest), as {@link BotAffinity#bound} does for players;</li>
 *   <li>bonds only move on shared events the game already has: a party join, minutes in one party (counted from the
 *   director's minute step, which walks the online bots once), a bot thread, guild mates meeting, a PQ together
 *   ({@link #onPqTogether}, for S3), a KS between bots on an observed map, a buff or a trade between bots, and a
 *   <i>sampled</i> co-presence tick: at most {@link #pairsPerMap()} random pairs per observed map per minute;</li>
 *   <li>reads are memory only. Crews are computed per bot on demand (two hops over mutual FRIEND bonds, at most
 *   {@link #CREW_MAX} bots) and cached for a minute.</li>
 * </ul>
 *
 * <p><b>Persistence:</b> {@code botbond} (migration 029), read whole once off the game thread at the first minute
 * step, written in one batch of what changed every {@link #FLUSH_MS} (deletes, then upserts, in order on one
 * {@link SerialWrites} key). Up to one flush interval of changes is lost on a crash.
 *
 * <p><b>Bartle (S1):</b> a bot's Socializer share speeds up its kind events, its Killer share its hostile ones
 * ({@link BondRules#scale}). A bond crossing into FRIEND pushes the bot's chart point once ({@code PARTY} for party-like
 * events, else {@code CHAT}), through {@link LifeMemory#push}, plasticity-weighted there.
 *
 * <p>System properties: {@code solomapling.bonds} (true) switches it all off; {@code solomapling.bonds.k} (14);
 * {@code solomapling.bonds.pairsPerMap} (3); half-lives in {@link BondRules}; weights in {@link BondEvent}.
 */
public final class BotBonds {

    private static final Logger log = LoggerFactory.getLogger(BotBonds.class);

    public static final int CREW_MAX = 6;
    static final long STEP_MS = TimeUnit.MINUTES.toMillis(1);
    static final long FLUSH_MS = TimeUnit.MINUTES.toMillis(3);
    static final long CREW_TTL_MS = TimeUnit.MINUTES.toMillis(1);
    static final long LOAD_RETRY_MS = TimeUnit.MINUTES.toMillis(10);
    /** A bot hit this mob this recently: another bot's first hit on it is a KS. */
    static final long KS_WINDOW_MS = TimeUnit.SECONDS.toMillis(5);

    /** One bot's bonds. Guarded by itself. */
    static final class BondSet {
        final int botId;
        final Map<Integer, Bond> bonds = new HashMap<>();

        BondSet(int botId) {
            this.botId = botId;
        }
    }

    /** A bond as readers see it (memory only). */
    public record View(int otherId, AffinityState state, double score, int kind, int hostile) {
    }

    /** Where a crew mate is right now. */
    public record CrewMate(int id, String name, int channel, int mapId, boolean town) {
    }

    private static final Map<Integer, BondSet> sets = new ConcurrentHashMap<>();
    private static final Set<Long> deletes = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> dirtyBots = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, long[]> crewCache = new ConcurrentHashMap<>();
    private static volatile BondStore store = new BondStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile Disposition disposition = BotBonds::seededShares;
    private static volatile Pushes pushes = BotBonds::pushChart;
    private static volatile Lookup lookup = BotBonds::worldLookup;
    private static final SerialWrites writes = new SerialWrites(r -> writer.execute(r));
    private static volatile boolean loaded;
    private static volatile boolean loading;
    private static volatile long loadTriedAt;
    private static volatile long lastStepAt;
    private static volatile long lastFlushAt;
    /** Diagnostics: pairs touched by the last minute step (party, sampled). */
    private static volatile int lastPartyPairs, lastSampledPairs;

    /** The crew source S5's gate asks (close FRIEND bonds); cheap and memory-only. */
    public static final SocialCrew CREW = BotBonds::crew;

    /** A bot's Bartle shares {socializer, killer}, 0..1. */
    @FunctionalInterface
    public interface Disposition {
        double[] shares(int botId);
    }

    /** Where a crossing-into-FRIEND push goes (a seam for tests). */
    @FunctionalInterface
    public interface Pushes {
        void push(int botId, SocialProfile.Push push);
    }

    /** An online character by id, memory only (a seam for tests). */
    @FunctionalInterface
    public interface Lookup {
        Character online(int characterId);
    }

    private BotBonds() {
    }

    // ── switches ──

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.bonds", "true"));
    }

    public static int maxBonds() {
        return Math.max(4, Math.min(32, Integer.getInteger("solomapling.bonds.k", 14)));
    }

    public static int pairsPerMap() {
        return Math.max(0, Math.min(10, Integer.getInteger("solomapling.bonds.pairsPerMap", 3)));
    }

    public static long now() {
        return clock.getAsLong();
    }

    static boolean isBot(int id) {
        return id >= BotStore.BOT_ID_BASE;
    }

    static long key(int botId, int otherId) {
        return ((long) botId << 32) | (otherId & 0xffffffffL);
    }

    static int keyBot(long key) {
        return (int) (key >>> 32);
    }

    static int keyOther(long key) {
        return (int) key;
    }

    // ── events ──

    /** Both bots feel {@code event} about each other (a party, a thread, a PQ...). */
    public static void together(int a, int b, BondEvent event) {
        long now = now();
        record(a, b, event, now, false);
        record(b, a, event, now, false);
    }

    /**
     * Bot {@code botId}'s feeling about bot {@code otherId} moves by {@code event} (scaled by its disposition). False
     * when nothing changed (switched off, not two distinct bots, or the pair's cooldown for it is running).
     */
    public static boolean record(int botId, int otherId, BondEvent event, long now, boolean ignoreCooldown) {
        if (!enabled() || botId == otherId || !isBot(botId) || !isBot(otherId)) {
            return false;
        }
        double[] shares = disposition.shares(botId);
        double delta = event.delta() * BondRules.scale(event.delta(), shares[0], shares[1]);
        BondSet set = sets.computeIfAbsent(botId, BondSet::new);
        AffinityState before;
        AffinityState after;
        Bond snapshot;
        synchronized (set) {
            Bond bond = set.bonds.get(otherId);
            boolean fresh = bond == null;
            if (fresh) {
                bond = new Bond(botId, otherId, 0, 0, 0, now);
            }
            before = BondRules.state(fresh ? null : bond, now);
            if (!BondRules.apply(bond, event, delta, now, ignoreCooldown)) {
                return false;
            }
            set.bonds.put(otherId, bond);
            // a shared event always earns its place (the weakest other bond goes); a sampled nod only beats the weakest
            boolean kept = bound(set, now, otherId, event != BondEvent.NEAR, fresh);
            if (!kept) {
                return false; // the newcomer was the weakest: it never made the list
            }
            after = BondRules.state(bond, now);
            snapshot = bond.snapshot();
        }
        dirtyBots.add(botId);
        if (before != after) {
            crewCache.remove(botId);
            crewCache.remove(otherId);
            onStateChange(botId, otherId, before, after, event, snapshot);
        }
        return true;
    }

    /**
     * Keeps at most {@link #maxBonds()} bonds in the set, dropping the weakest (faded ones first); false when the bond
     * of {@code touched} was itself dropped. Caller holds the set's lock.
     */
    static boolean bound(BondSet set, long now, int touched) {
        return bound(set, now, touched, true, false);
    }

    /**
     * As {@link #bound(BondSet, long, int)}. {@code keepTouched}: the touched bond is never the one dropped (the
     * weakest other goes, as {@link BotAffinity#bound} does); otherwise it competes and loses ties. A
     * {@code touchedFresh} newcomer that loses was never stored, so nothing is deleted for it.
     */
    static boolean bound(BondSet set, long now, int touched, boolean keepTouched, boolean touchedFresh) {
        int max = maxBonds();
        boolean kept = true;
        while (set.bonds.size() > max) {
            Bond weakest = null;
            double weakestAbs = Double.MAX_VALUE;
            for (Bond b : set.bonds.values()) {
                if (keepTouched && b.otherId == touched) {
                    continue;
                }
                double abs = Math.abs(BondRules.scoreAt(b, now));
                // ties: the newcomer loses to an established bond (no churn on equal scores)
                if (abs < weakestAbs || abs == weakestAbs && b.otherId == touched) {
                    weakest = b;
                    weakestAbs = abs;
                }
            }
            if (weakest == null) {
                break;
            }
            set.bonds.remove(weakest.otherId);
            boolean newcomer = weakest.otherId == touched;
            if (!(newcomer && touchedFresh)) {
                deletes.add(key(set.botId, weakest.otherId));
            }
            if (newcomer) {
                kept = false;
            }
        }
        return kept;
    }

    private static void onStateChange(int botId, int otherId, AffinityState before, AffinityState after,
                                      BondEvent cause, Bond snapshot) {
        if (after == AffinityState.FRIEND) {
            SocialProfile.Push push = cause.partyLike() ? SocialProfile.Push.PARTY : SocialProfile.Push.CHAT;
            try {
                writer.execute(() -> pushes.push(botId, push));
            } catch (RuntimeException e) {
                log.debug("No chart push for bot {}", botId, e);
            }
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) { // !qa bb-3 (runs that watch the bot's map, or every map)
            Character holder = lookup.online(botId);
            QaLog.map(QaLog.Topic.AFFINITY, holder == null ? 0 : holder.getMapId(), "BONDS " + name(botId) + " -> " + name(otherId) + " "
                    + before.label() + " -> " + after.label().toUpperCase(Locale.ROOT) + " (" + cause.key()
                    + ", score " + Math.round(snapshot.score) + ", kind " + snapshot.kind + ", hostile "
                    + snapshot.hostile + ")");
        }
    }

    // ── hooks (all memory work; call sites wrap them in MapleRootSolo.runHook) ──

    /** {@code joiner} joined a party with {@code other}; both bots. */
    public static void onPartyJoin(Character joiner, Character other) {
        if (joiner == null || other == null) {
            return;
        }
        together(joiner.getId(), other.getId(), BondEvent.PARTY);
        if (sameGuild(joiner, other)) {
            together(joiner.getId(), other.getId(), BondEvent.GUILD);
        }
    }

    /** A bot thread between two bots ran ({@code BotThreads.run}); a guild-beef thread sours, the rest warm. */
    public static void onThread(int a, int b, boolean spat) {
        together(a, b, spat ? BondEvent.SPAT : BondEvent.THREAD);
    }

    /**
     * A party quest done together (slice S3's PQ hook calls this): every pair of bots in {@code members} counts.
     * Members are a party (at most 6), so this is at most 15 pairs.
     */
    public static void onPqTogether(Collection<Character> members) {
        if (members == null) {
            return;
        }
        List<Integer> bots = new ArrayList<>();
        for (Character c : members) {
            if (c != null && isBot(c.getId()) && bots.size() < 6) {
                bots.add(c.getId());
            }
        }
        forEachPair(bots, (a, b) -> together(a, b, BondEvent.PQ));
    }

    /** A bot's buff or heal landed on another bot: the one buffed is grateful. */
    public static void onBuff(Character from, Character to) {
        if (from != null && to != null) {
            record(to.getId(), from.getId(), BondEvent.BUFF, now(), false);
        }
    }

    /** A trade between two bots went through. A one-sided trade is a gift to the side that got something. */
    public static void onTrade(Character a, boolean aGave, Character b, boolean bGave) {
        if (a == null || b == null || !aGave && !bGave) {
            return;
        }
        long now = now();
        if (aGave && !bGave) {
            record(b.getId(), a.getId(), BondEvent.GIFT, now, false);
        } else if (bGave && !aGave) {
            record(a.getId(), b.getId(), BondEvent.GIFT, now, false);
        } else {
            together(a.getId(), b.getId(), BondEvent.TRADE);
        }
    }

    /**
     * Before a bot's hit lands on {@code mob} (bot combat, {@code BotAttackEffects}): on an observed map, its first
     * hit on a mob another bot (not in its party) hit in the last {@link #KS_WINDOW_MS} is a KS, and the other bot
     * resents it. Unobserved maps fight abstractly, so this never runs for them.
     */
    public static void beforeBotHit(Character bot, Monster mob) {
        if (!enabled() || bot == null || mob == null || !isBot(bot.getId()) || bot.getMap() == null) {
            return;
        }
        if (!observed(bot.getMapId()) || mob.isAttackedBy(bot)) {
            return;
        }
        long now = now();
        int victimId = mob.recentAttackerAtOrAbove(BotStore.BOT_ID_BASE, bot.getId(), now - KS_WINDOW_MS);
        if (victimId < 0) {
            return;
        }
        Character victim = bot.getMap().getCharacterById(victimId);
        if (victim == null || !victim.isAlive() || sameParty(bot, victim)) {
            return;
        }
        record(victimId, bot.getId(), BondEvent.KS, now, false);
    }

    private static boolean sameParty(Character a, Character b) {
        Party pa = a.getParty();
        Party pb = b.getParty();
        return pa != null && pb != null && pa.getId() == pb.getId();
    }

    private static boolean sameGuild(Character a, Character b) {
        return a.getGuildId() > 0 && a.getGuildId() == b.getGuildId();
    }

    // ── the minute step ──

    /** From the population director's tick: at most once a minute. Starts the load, ticks parties and maps, flushes. */
    public static void maybeStep(long now) {
        if (!enabled()) {
            return;
        }
        SocialGate.addCrewSource(CREW); // idempotent; a gate reset (tests, GM) drops it
        ensureLoaded(now);
        if (now - lastStepAt < STEP_MS) {
            return;
        }
        lastStepAt = now;
        try {
            step(soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values().stream()
                    .map(soloMapling.ArtificialPlayer.BotSM::getChr).toList(), ThreadLocalRandom.current());
        } catch (RuntimeException e) {
            log.debug("Bot bonds step failed", e);
        }
        if (now - lastFlushAt >= FLUSH_MS) {
            lastFlushAt = now;
            requestFlush();
        }
        crewCache.values().removeIf(c -> now - c[0] > CREW_TTL_MS);
    }

    /**
     * One minute over the online bots (one pass): every bot pair in one party gets {@link BondEvent#PARTY_TIME}
     * (a party holds at most 6, so at most 15 pairs each), and each observed map with two or more bots gets at most
     * {@link #pairsPerMap()} sampled pairs ({@link BondEvent#NEAR}, or {@link BondEvent#GUILD} for guild mates).
     */
    static void step(Collection<Character> bots, Random rng) {
        Map<Integer, List<Character>> byParty = new HashMap<>();
        Map<Long, List<Character>> byMap = new HashMap<>();
        Map<Integer, Boolean> observed = new HashMap<>();
        for (Character chr : bots) {
            if (chr == null || !isBot(chr.getId()) || chr.getMap() == null) {
                continue;
            }
            Party p = chr.getParty();
            if (p != null) {
                byParty.computeIfAbsent(p.getId(), k -> new ArrayList<>()).add(chr);
            }
            int mapId = chr.getMapId();
            if (observed.computeIfAbsent(mapId, BotBonds::observed)) {
                long k = ((long) chr.getMap().getChannelId() << 32) | (mapId & 0xffffffffL);
                byMap.computeIfAbsent(k, x -> new ArrayList<>()).add(chr);
            }
        }
        int partyPairs = 0;
        for (List<Character> members : byParty.values()) {
            if (members.size() < 2) {
                continue;
            }
            List<Character> six = members.size() > 6 ? members.subList(0, 6) : members;
            for (int i = 0; i < six.size(); i++) {
                for (int j = i + 1; j < six.size(); j++) {
                    Character a = six.get(i);
                    Character b = six.get(j);
                    together(a.getId(), b.getId(), BondEvent.PARTY_TIME);
                    if (sameGuild(a, b)) {
                        together(a.getId(), b.getId(), BondEvent.GUILD);
                    }
                    partyPairs++;
                }
            }
        }
        int sampled = 0;
        int cap = pairsPerMap();
        for (List<Character> here : byMap.values()) {
            sampled += samplePairs(here, cap, rng, (a, b) -> together(a.getId(), b.getId(),
                    sameGuild(a, b) ? BondEvent.GUILD : BondEvent.NEAR));
        }
        lastPartyPairs = partyPairs;
        lastSampledPairs = sampled;
    }

    private static volatile java.util.function.IntPredicate observedMap =
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement::isMapObserved;

    private static boolean observed(int mapId) {
        try {
            return observedMap.test(mapId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Something done with one pair. */
    @FunctionalInterface
    public interface PairSink<T> {
        void accept(T a, T b);
    }

    /**
     * At most {@code max} distinct random pairs out of {@code people} (never all pairs): O(max) draws, a few retries
     * for a collision. Returns how many pairs it handed to {@code sink}.
     */
    public static <T> int samplePairs(List<T> people, int max, Random rng, PairSink<T> sink) {
        int n = people.size();
        if (n < 2 || max <= 0) {
            return 0;
        }
        int want = (int) Math.min(max, (long) n * (n - 1) / 2);
        Set<Long> seen = new HashSet<>();
        int given = 0;
        for (int tries = 0; given < want && tries < want * 4; tries++) {
            int i = rng.nextInt(n);
            int j = rng.nextInt(n - 1);
            if (j >= i) {
                j++;
            }
            long k = Math.min(i, j) * (long) n + Math.max(i, j);
            if (seen.add(k)) {
                sink.accept(people.get(i), people.get(j));
                given++;
            }
        }
        return given;
    }

    private static <T> void forEachPair(List<T> xs, PairSink<T> sink) {
        for (int i = 0; i < xs.size(); i++) {
            for (int j = i + 1; j < xs.size(); j++) {
                sink.accept(xs.get(i), xs.get(j));
            }
        }
    }

    // ── persistence ──

    /** The first step reads the whole table off the calling thread; a failed read is retried every 10 minutes. */
    static void ensureLoaded(long now) {
        if (loaded || loading || loadTriedAt > 0 && now - loadTriedAt < LOAD_RETRY_MS) {
            return;
        }
        synchronized (BotBonds.class) {
            if (loaded || loading) {
                return;
            }
            loading = true;
            loadTriedAt = now;
        }
        try {
            writes.submit("bonds", BotBonds::loadNow);
        } catch (RuntimeException e) {
            loading = false;
        }
    }

    /** Reads every stored bond (this thread). Memory wins over a row for a pair already touched this run. */
    static boolean loadNow() {
        try {
            long now = now();
            List<Bond> rows = store.loadAll();
            if (rows == null) {
                log.warn("Could not read the bot bonds; trying again later");
                return false;
            }
            int kept = 0;
            Set<Integer> touched = new HashSet<>();
            for (Bond row : rows) {
                if (!isBot(row.botId) || !isBot(row.otherId) || row.botId == row.otherId
                        || BondRules.faded(row, now)) {
                    deletes.add(key(row.botId, row.otherId));
                    continue;
                }
                BondSet set = sets.computeIfAbsent(row.botId, BondSet::new);
                synchronized (set) {
                    if (set.bonds.putIfAbsent(row.otherId, row) == null) {
                        kept++;
                    }
                }
                touched.add(row.botId);
            }
            for (int botId : touched) {
                BondSet set = sets.get(botId);
                synchronized (set) {
                    bound(set, now, -1);
                }
            }
            loaded = true;
            crewCache.clear();
            log.info("Bot bonds: {} loaded for {} bots ({} dropped)", kept, touched.size(), deletes.size());
            return true;
        } finally {
            loading = false;
        }
    }

    /** Queues a flush of what changed (deletes first, then upserts) after any earlier one. */
    static void requestFlush() {
        writes.submit("bonds", BotBonds::flush);
    }

    /** Writes what changed now (this thread); the number of rows written. */
    static int flush() {
        List<Long> gone = new ArrayList<>();
        for (java.util.Iterator<Long> it = deletes.iterator(); it.hasNext(); ) {
            gone.add(it.next());
            it.remove();
        }
        List<Bond> saves = new ArrayList<>();
        long now = now();
        for (java.util.Iterator<Integer> it = dirtyBots.iterator(); it.hasNext(); ) {
            int botId = it.next();
            it.remove();
            BondSet set = sets.get(botId);
            if (set == null) {
                continue;
            }
            synchronized (set) {
                for (java.util.Iterator<Bond> bi = set.bonds.values().iterator(); bi.hasNext(); ) {
                    Bond b = bi.next();
                    if (BondRules.faded(b, now)) {
                        bi.remove();
                        gone.add(key(botId, b.otherId));
                        continue;
                    }
                    if (b.dirty) {
                        b.dirty = false;
                        saves.add(b.snapshot());
                    }
                }
            }
        }
        if (gone.isEmpty() && saves.isEmpty()) {
            return 0;
        }
        try {
            store.write(gone, saves);
        } catch (RuntimeException e) {
            log.warn("Could not write {} bot bonds", saves.size() + gone.size(), e);
        }
        return gone.size() + saves.size();
    }

    // ── reading: memory only ──

    /** How bot {@code botId} feels about bot {@code otherId} now. */
    public static AffinityState state(int botId, int otherId) {
        BondSet set = sets.get(botId);
        if (set == null || !enabled()) {
            return AffinityState.NEUTRAL;
        }
        long now = now();
        synchronized (set) {
            AffinityState s = BondRules.state(set.bonds.get(otherId), now);
            return s.hostile() && !BotAffinity.hostilityEnabled() ? AffinityState.NEUTRAL : s;
        }
    }

    public static double score(int botId, int otherId) {
        BondSet set = sets.get(botId);
        if (set == null) {
            return 0;
        }
        long now = now();
        synchronized (set) {
            Bond b = set.bonds.get(otherId);
            return b == null ? 0 : BondRules.scoreAt(b, now);
        }
    }

    public static boolean friend(int botId, int otherId) {
        return state(botId, otherId) == AffinityState.FRIEND;
    }

    /** Both bots count each other as a FRIEND: the edge crews are made of. */
    public static boolean mutualFriends(int a, int b) {
        return a != b && friend(a, b) && friend(b, a);
    }

    /** This bot's bonds, strongest feeling first (at most {@link #maxBonds()}). */
    public static List<View> bondsOf(int botId) {
        BondSet set = sets.get(botId);
        List<View> out = new ArrayList<>();
        if (set == null) {
            return out;
        }
        long now = now();
        boolean hostility = BotAffinity.hostilityEnabled();
        synchronized (set) {
            for (Bond b : set.bonds.values()) {
                AffinityState s = BondRules.state(b, now);
                if (s.hostile() && !hostility) {
                    s = AffinityState.NEUTRAL;
                }
                out.add(new View(b.otherId, s, BondRules.scoreAt(b, now), b.kind, b.hostile));
            }
        }
        out.sort(Comparator.comparingDouble((View v) -> -Math.abs(v.score())));
        return out;
    }

    /** Bots this bot counts as FRIEND, strongest first. */
    public static List<Integer> friendsOf(int botId) {
        return bondsOf(botId).stream().filter(v -> v.state() == AffinityState.FRIEND).map(View::otherId).toList();
    }

    /** Bots this bot counts as RIVAL or NEMESIS, strongest first (empty with hostility off). */
    public static List<Integer> rivalsOf(int botId) {
        return bondsOf(botId).stream().filter(v -> v.state().hostile()).map(View::otherId).toList();
    }

    // ── crews ──

    /**
     * The bot's crew: itself first, then its mutual friends (strongest first), then their mutual friends, at most
     * {@link #CREW_MAX} bots in all. Just itself when it has no mutual friend. Cached for {@link #CREW_TTL_MS}; a bond
     * changing state drops the cache of both bots. At most 1 + K + K x K bond reads on a miss.
     */
    public static List<Integer> crewOf(int botId) {
        long now = now();
        long[] cached = crewCache.get(botId);
        if (cached != null && now - cached[0] <= CREW_TTL_MS) {
            List<Integer> out = new ArrayList<>(cached.length - 1);
            for (int i = 1; i < cached.length; i++) {
                out.add((int) cached[i]);
            }
            return out;
        }
        LinkedHashSet<Integer> crew = new LinkedHashSet<>();
        crew.add(botId);
        ArrayDeque<int[]> frontier = new ArrayDeque<>();
        frontier.add(new int[]{botId, 0});
        while (!frontier.isEmpty() && crew.size() < CREW_MAX) {
            int[] at = frontier.poll();
            if (at[1] >= 2) {
                continue;
            }
            for (int f : friendsOf(at[0])) {
                if (crew.size() >= CREW_MAX) {
                    break;
                }
                if (!crew.contains(f) && friend(f, at[0])) {
                    crew.add(f);
                    frontier.add(new int[]{f, at[1] + 1});
                }
            }
        }
        long[] entry = new long[crew.size() + 1];
        entry[0] = now;
        int i = 1;
        for (int id : crew) {
            entry[i++] = id;
        }
        crewCache.put(botId, entry);
        return new ArrayList<>(crew);
    }

    /** {@link SocialCrew} for the gate: whether {@code b} is in {@code a}'s crew (mutual friends count). */
    public static boolean crew(int a, int b) {
        if (a == b || !enabled()) {
            return false;
        }
        if (mutualFriends(a, b)) {
            return true;
        }
        return crewOf(a).contains(b);
    }

    /** The crew mates of this bot that are online now, and where (memory only: one lookup each). */
    public static List<CrewMate> crewWhereabouts(int botId) {
        List<CrewMate> out = new ArrayList<>();
        for (int id : crewOf(botId)) {
            if (id == botId) {
                continue;
            }
            Character c = lookup.online(id);
            if (c == null || c.getMap() == null) {
                continue;
            }
            out.add(new CrewMate(id, c.getName(), c.getMap().getChannelId(), c.getMapId(), c.getMap().isTown()));
        }
        return out;
    }

    /** A town map a crew mate is in on this channel ("a crew-mate is on map X", for S8's HANG_OUT pull), or -1. */
    public static int crewTownMap(int botId, int channel) {
        for (CrewMate m : crewWhereabouts(botId)) {
            if (m.town() && m.channel() == channel) {
                return m.mapId();
            }
        }
        return -1;
    }

    /** A map a crew mate is on on this channel other than {@code exceptMap} (for S8's MOVE_MAP pull), or -1. */
    public static int crewMap(int botId, int channel, int exceptMap) {
        for (CrewMate m : crewWhereabouts(botId)) {
            if (m.channel() == channel && m.mapId() != exceptMap) {
                return m.mapId();
            }
        }
        return -1;
    }

    // ── GM / QA ──

    /** The lines {@code !botwhy} shows: the top bonds and the crew. */
    public static List<String> describe(int botId) {
        return describe(botId, BotBonds::name, 6);
    }

    static List<String> describe(int botId, IntFunction<String> names, int top) {
        List<String> lines = new ArrayList<>();
        List<View> bonds = bondsOf(botId);
        if (!enabled()) {
            lines.add("Bonds: switched off (solomapling.bonds=false)");
            return lines;
        }
        StringBuilder b = new StringBuilder("Bonds " + bonds.size() + "/" + maxBonds() + ":");
        if (bonds.isEmpty()) {
            b.append(" none yet");
        }
        for (View v : bonds.subList(0, Math.min(top, bonds.size()))) {
            b.append(' ').append(names.apply(v.otherId())).append(' ').append(v.state().label()).append(' ')
                    .append(Math.round(v.score())).append(" (k").append(v.kind()).append(" h").append(v.hostile())
                    .append(")").append(',');
        }
        if (b.charAt(b.length() - 1) == ',') {
            b.setLength(b.length() - 1);
        }
        lines.add(b.toString());
        List<Integer> crew = crewOf(botId);
        if (crew.size() < 2) {
            lines.add("Crew: none (no mutual friend yet)");
        } else {
            StringBuilder c = new StringBuilder("Crew of " + crew.size() + ":");
            Map<Integer, CrewMate> where = new HashMap<>();
            for (CrewMate m : crewWhereabouts(botId)) {
                where.put(m.id(), m);
            }
            for (int id : crew) {
                if (id == botId) {
                    continue;
                }
                CrewMate m = where.get(id);
                c.append(' ').append(names.apply(id)).append(m == null ? " (offline)"
                        : " (ch " + m.channel() + " map " + m.mapId() + (m.town() ? " town" : "") + ")").append(',');
            }
            c.setLength(c.length() - 1);
            lines.add(c.toString());
        }
        return lines;
    }

    /** One line about a pair for GMs and QA: both directions, the crew answer. */
    public static String describePair(int a, int b) {
        return name(a) + " -> " + name(b) + ": " + state(a, b).label() + " " + Math.round(score(a, b)) + "; "
                + name(b) + " -> " + name(a) + ": " + state(b, a).label() + " " + Math.round(score(b, a))
                + "; crew " + (crew(a, b) ? "yes" : "no");
    }

    /** GM / QA: puts both directions of a pair in a state now. */
    public static void stage(int a, int b, AffinityState state) {
        long now = now();
        for (int[] p : new int[][]{{a, b}, {b, a}}) {
            BondSet set = sets.computeIfAbsent(p[0], BondSet::new);
            synchronized (set) {
                Bond bond = set.bonds.computeIfAbsent(p[1], o -> new Bond(p[0], o, 0, 0, 0, now));
                BondRules.stage(bond, state, now);
                if (state == AffinityState.NEUTRAL) {
                    set.bonds.remove(p[1]);
                    deletes.add(key(p[0], p[1]));
                } else {
                    bound(set, now, p[1]);
                }
            }
            dirtyBots.add(p[0]);
        }
        crewCache.remove(a);
        crewCache.remove(b);
    }

    /**
     * QA acceleration ({@code !qa bb-3 do fast}): {@code minutes} of being in one party, applied now without the
     * per-pair cooldown (one {@link BondEvent#PARTY_TIME} per 5 minutes, as the minute step would). The count.
     */
    public static int accelerateParty(int a, int b, int minutes) {
        long now = now();
        int ticks = Math.max(1, minutes / 5);
        int counted = 0;
        for (int i = 0; i < ticks; i++) {
            if (record(a, b, BondEvent.PARTY_TIME, now, true)) {
                counted++;
            }
            record(b, a, BondEvent.PARTY_TIME, now, true);
        }
        return counted;
    }

    /** Forgets every bond of and about a bot (a staged {@code !qa} bot that left). O(bots), GM use only. */
    public static void forgetBot(int botId) {
        BondSet own = sets.remove(botId);
        if (own != null) {
            synchronized (own) {
                for (int other : own.bonds.keySet()) {
                    deletes.add(key(botId, other));
                }
            }
        }
        for (BondSet set : sets.values()) {
            synchronized (set) {
                if (set.bonds.remove(botId) != null) {
                    deletes.add(key(set.botId, botId));
                }
            }
        }
        crewCache.clear();
    }

    /** Diagnostics: bots holding bonds, bonds in all, pairs touched by the last step (party, sampled). */
    public static String stats() {
        int total = 0;
        for (BondSet set : sets.values()) {
            synchronized (set) {
                total += set.bonds.size();
            }
        }
        return "bonds " + total + " held by " + sets.size() + " bots (K " + maxBonds() + "), last step party pairs "
                + lastPartyPairs + ", sampled " + lastSampledPairs + ", loaded " + loaded + ", dirty "
                + dirtyBots.size() + ", deletes " + deletes.size();
    }

    // ── defaults behind the seams ──

    /** Bartle shares from the bot's life memory when it is loaded (memory only), else its seeded point. */
    static double[] seededShares(int botId) {
        LifeMemory m = LifeMemoryStore.peek(botId);
        SocialProfile p = m != null ? m.social() : SocialProfile.forCharacter(botId);
        return new double[]{p.share(SocialProfile.Type.SOCIALIZER), p.share(SocialProfile.Type.KILLER)};
    }

    /** The real push: the bot's life memory (may read the profile table once; runs on the writer). */
    private static void pushChart(int botId, SocialProfile.Push push) {
        try {
            LifeMemoryStore.of(botId).push(push);
        } catch (RuntimeException e) {
            log.debug("Could not push bot {}'s chart", botId, e);
        }
    }

    private static Character worldLookup(int id) {
        try {
            net.server.world.World world = soloMapling.server.SoloMaplingUtilities.world();
            return world == null ? null : world.getPlayerStorage().getCharacterById(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String name(int id) {
        Character c = lookup.online(id);
        return c != null ? c.getName() : "#" + id;
    }

    // ── test seams ──

    /** Tests: a store, writer, clock, disposition, pushes and lookup (nulls restore the real ones); forgets everything. */
    public static void useForTest(BondStore testStore, Executor testWriter, LongSupplier testClock,
                                  Disposition testDisposition, Pushes testPushes, Lookup testLookup) {
        store = testStore != null ? testStore : new BondStore.Jdbc();
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runAsync;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        disposition = testDisposition != null ? testDisposition : BotBonds::seededShares;
        pushes = testPushes != null ? testPushes : BotBonds::pushChart;
        lookup = testLookup != null ? testLookup : BotBonds::worldLookup;
        observedMap = soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement::isMapObserved;
        sets.clear();
        deletes.clear();
        dirtyBots.clear();
        crewCache.clear();
        loaded = false;
        loading = false;
        loadTriedAt = 0;
        lastStepAt = 0;
        lastFlushAt = 0;
    }

    /** Tests: which maps count as observed. */
    static void useObservedForTest(java.util.function.IntPredicate test) {
        observedMap = test;
    }

    /** Tests: how many bonds a bot holds. */
    static int sizeOf(int botId) {
        BondSet set = sets.get(botId);
        if (set == null) {
            return 0;
        }
        synchronized (set) {
            return set.bonds.size();
        }
    }
}
