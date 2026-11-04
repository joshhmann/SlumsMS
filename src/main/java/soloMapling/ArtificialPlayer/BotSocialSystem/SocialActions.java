package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Ticket 181 stage 4: the registry of the social acts and the one server-authoritative way to do one.
 *
 * <ul>
 *   <li>{@link #perform}: checks the act's preconditions, its execution requirement and the {@link SocialBudget},
 *       executes it now, records the outcome with the budget and the counters. Safe from the bot's own thread; never
 *       throws.</li>
 *   <li>{@link #best} / {@link #bestOf}: scores every legal (act, target) pair for one bot, adds personality-scaled noise
 *       from {@link SocialSim#random} and returns the best, or none when nothing clears the bot's own bar.</li>
 *   <li>{@link #tick}: the upkeep of what acts leave running: open invitations expire, an avoid record keeps the bot
 *       away from its target and expires, a spend-time pair is judged when its time is up.</li>
 *   <li>Readers for the other stages: {@link #avoiding}, {@link #avoidRecords}, {@link #pendingInvite}.</li>
 * </ul>
 *
 * <p>{@code -Dslums.socialacts=false} is the rollback ({@link #enabled}): nothing here runs and the bot-talk tick is
 * exactly what it was. All state is in memory (the stores the acts change are the existing ones: the bond axes, the
 * bot memory, the life memory's mind).
 */
public final class SocialActions {

    private static final Logger log = LoggerFactory.getLogger(SocialActions.class);

    public static final String SWITCH = "slums.socialacts";

    /** An open invitation lives this long; the invited bot answers on its own decision tick. */
    public static final long INVITE_TTL_MS = 3 * 60_000L;
    /** The nearest an avoiding bot stays to its target (px). */
    public static final int AVOID_KEEP_PX = 450;

    /** On unless {@code -Dslums.socialacts=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH));
    }

    // ── results ──

    /**
     * What {@link #perform} did. {@code happened}: the act was carried out now (false: a precondition, the requirement
     * or the budget held it back, {@code reason} says which). {@code reason}: {@code "ok"} or {@code "ok: ..."} when it
     * landed; anything else with {@code happened} true means it was carried out but did not land (an apology refused).
     * {@code utility}: what the actor expected from it (NaN when it was not scored).
     */
    public record Outcome(boolean happened, String reason, SocialActKind kind, double utility) {

        /** True when the act was carried out and landed. */
        public boolean landed() {
            return happened && reason != null && reason.startsWith("ok");
        }

        static Outcome no(SocialActKind kind, String reason) {
            return new Outcome(false, reason, kind, Double.NaN);
        }

        static Outcome ok(SocialActKind kind, double utility, String detail) {
            return new Outcome(true, detail == null || detail.isEmpty() ? "ok" : "ok: " + detail, kind, utility);
        }

        /** Carried out, did not land. */
        static Outcome missed(SocialActKind kind, double utility, String reason) {
            return new Outcome(true, reason, kind, utility);
        }

        Outcome withUtility(double u) {
            return new Outcome(happened, reason, kind, u);
        }
    }

    /** One act that was carried out, as the observer (QA trace, tests) sees it. */
    public record ActEvent(long now, int actor, int target, SocialActKind kind, Outcome outcome) {
    }

    /** Called after every act that was carried out (landed or not); null by default. Must not throw. */
    public static volatile java.util.function.Consumer<ActEvent> observer;

    /** One scored (act, target): {@code utility} is the act's own, {@code score} adds the noise. */
    public record Scored(SocialActKind kind, int targetId, double utility, double score) {
    }

    /** A party or come-along invitation one bot made another, until it answers or it lapses. */
    public record Invite(long id, int from, int to, String what, int mapId, long createdAt, long expiresAt,
                         Object payload) {
    }

    /** Where an avoiding bot stays away from: {@code actor} keeps {@code keepPx} from {@code target} until {@code until}. */
    public record Avoid(int actor, int target, long since, long until, int keepPx, String why) {
        public boolean activeAt(long now) {
            return now >= since && now < until;
        }
    }

    /** Two bots spending time together until {@code until}. */
    record Together(int a, int b, long mapKey, long since, long until) {
    }

    // ── seams and state ──

    private static volatile SocialWorld world;
    private static volatile SocialFlows flows;
    /** The gift primitive GIVE_GIFT calls: affinity only by default; the bot-to-bot gift plugs in here. */
    public static volatile GiftSeam giftSeam = GiftSeam.AFFINITY_ONLY;

    private static final Map<SocialActKind, SocialAction> REGISTRY = new EnumMap<>(SocialActKind.class);
    private static final Map<Long, Invite> invites = new ConcurrentHashMap<>();
    private static final Map<Long, Avoid> avoids = new ConcurrentHashMap<>();
    private static final Map<Long, Long> avoidWalkAt = new ConcurrentHashMap<>();
    private static final Map<Long, Together> togethers = new ConcurrentHashMap<>();
    private record LastKey(int actor, int target, SocialActKind kind) {
    }

    private static final Map<LastKey, Long> lastAct = new ConcurrentHashMap<>();
    private static final Map<Long, java.util.ArrayDeque<Long>> argueLog = new ConcurrentHashMap<>();
    private static final AtomicLong INVITE_IDS = new AtomicLong(1);

    static {
        for (SocialAction a : SocialActs.all()) {
            REGISTRY.put(a.kind(), a);
        }
    }

    private SocialActions() {
    }

    public static SocialWorld world() {
        SocialWorld w = world;
        if (w == null) {
            synchronized (SocialActions.class) {
                if (world == null) {
                    world = new soloMapling.ArtificialPlayer.BotConvo.GameSocialWorld();
                }
                w = world;
            }
        }
        return w;
    }

    public static SocialFlows flows() {
        SocialFlows f = flows;
        if (f == null) {
            synchronized (SocialActions.class) {
                if (flows == null) {
                    flows = new soloMapling.ArtificialPlayer.BotConvo.GameSocialFlows();
                }
                f = flows;
            }
        }
        return f;
    }

    /** The registered action of a kind (every kind has one). */
    public static SocialAction action(SocialActKind kind) {
        return REGISTRY.get(kind);
    }

    /** Test seam: the world, flows and gift seam (nulls restore the real ones) and forgets all act state. */
    public static void useForTest(SocialWorld testWorld, SocialFlows testFlows, GiftSeam testGifts) {
        world = testWorld;
        flows = testFlows;
        giftSeam = testGifts != null ? testGifts : GiftSeam.AFFINITY_ONLY;
        observer = null;
        resetForTest();
    }

    /** Forgets every invitation, avoid record, spend-time pair and act timestamp. */
    public static void resetForTest() {
        invites.clear();
        avoids.clear();
        avoidWalkAt.clear();
        togethers.clear();
        lastAct.clear();
        argueLog.clear();
    }

    /** The key of the {@link SocialBudget} family an act counts under (bot-talk's own families for the wrapped acts). */
    public static String budgetKey(SocialActKind kind) {
        return switch (kind) {
            case GREET -> SocialBudget.GREET;
            case CONVERSE -> SocialBudget.CHAT;
            case ASK_HELP -> SocialBudget.ASK + ":HELP";
            case OFFER_HELP -> "offerhelp";
            case INVITE -> "invite";
            case ACCEPT, DECLINE -> "reply";
            case GIVE_GIFT -> "gift";
            case SHARE_INFO -> "shareinfo";
            case SPREAD_RUMOR -> "rumor";
            case SPEND_TIME -> "spendtime";
            default -> kind.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    // ── perform ──

    /**
     * Carries out one act of {@code actorCharId} toward {@code targetCharId} now. Checks, in order: the switch, that
     * both are bots on one map, the act's preconditions, its execution requirement (free or idle), the loop breaker;
     * then executes and records the outcome. Acts toward a real player are refused here: player-facing talk stays on
     * {@code SocialGate}. Never throws.
     */
    public static Outcome perform(int actorCharId, int targetCharId, SocialActKind kind, long now) {
        try {
            if (!enabled()) {
                return Outcome.no(kind, "social acts are off");
            }
            SocialAction action = REGISTRY.get(kind);
            if (actorCharId < BotStore.BOT_ID_BASE) {
                return refused(kind, "actor is not a roster bot");
            }
            if (targetCharId < BotStore.BOT_ID_BASE) {
                return refused(kind, "a real player is never a target here (SocialGate owns that path)");
            }
            if (actorCharId == targetCharId) {
                return refused(kind, "not itself");
            }
            SocialContext ctx = build(actorCharId, targetCharId, now);
            if (ctx == null) {
                return refused(kind, "not both on a map");
            }
            String why = action.preconditions(ctx);
            if (why != null) {
                return refused(kind, why);
            }
            if (!ctx.actorFree() || !ctx.targetFree() && kind != SocialActKind.ACCEPT && kind != SocialActKind.DECLINE) {
                return refused(kind, "not free right now");
            }
            if (action.needsIdle() && !ctx.actorIdle()) {
                return refused(kind, "not idle enough to be moved");
            }
            String key = budgetKey(kind);
            long mapKey = ctx.actor().mapKey();
            SocialBudget.Verdict gate = SocialBudget.allow(actorCharId, targetCharId, mapKey, key, now);
            if (!gate.allowed()) {
                SocialMetrics.countAct(kind, "budget");
                return Outcome.no(kind, "held back by the social budget: " + gate.reason().label());
            }
            double utility = action.utility(ctx);
            Outcome out = action.execute(ctx).withUtility(utility);
            if (out.happened()) {
                lastAct.put(new LastKey(actorCharId, targetCharId, kind), now);
                if (!action.recordsItself()) {
                    SocialBudget.record(new SocialBudget.Outcome(actorCharId, targetCharId, mapKey, key, now, out.landed()));
                }
            }
            SocialMetrics.countAct(kind, !out.happened() ? "refused" : out.landed() ? "ok"
                    : out.reason().startsWith("rejected") ? "rejected" : "failed");
            java.util.function.Consumer<ActEvent> watch = observer;
            if (watch != null && out.happened()) {
                watch.accept(new ActEvent(now, actorCharId, targetCharId, kind, out));
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            SocialMetrics.countAct(kind, "error");
            log.debug("Social act {} failed", kind, e);
            return Outcome.no(kind, "error: " + e.getClass().getSimpleName());
        }
    }

    private static Outcome refused(SocialActKind kind, String why) {
        SocialMetrics.countAct(kind, "refused");
        return Outcome.no(kind, why);
    }

    // ── choosing ──

    /**
     * The best act this bot could do now toward one of {@code candidates} (character ids it can see), or empty when
     * nothing legal clears its bar. Scores every legal (act, target): the act's utility plus noise from
     * {@code SocialSim.random(actor, "social-act")} scaled by the bot's own impulsiveness and patience. O(candidates x 16).
     */
    public static java.util.Optional<Scored> best(int actorCharId, Collection<Integer> candidates, long now) {
        try {
            if (!enabled() || actorCharId < BotStore.BOT_ID_BASE) {
                return java.util.Optional.empty();
            }
            List<SocialContext> contexts = new ArrayList<>(candidates.size() + 1);
            for (int target : candidates) {
                if (target >= BotStore.BOT_ID_BASE && target != actorCharId) {
                    SocialContext c = build(actorCharId, target, now);
                    if (c != null) {
                        contexts.add(c);
                    }
                }
            }
            return bestOf(contexts, SocialSim.random(actorCharId, "social-act"));
        } catch (RuntimeException | LinkageError e) {
            log.debug("Social choice failed for {}", actorCharId, e);
            return java.util.Optional.empty();
        }
    }

    /** The bar an act's score must clear before the bot does anything: loners need more pull than socializers. */
    public static double bar(SocialContext.Self self) {
        return 0.30 + 0.15 * (1 - self.t(Trait.SOCIABILITY));
    }

    /** How much the draw can move a score: impulsive bots swing more, patient ones less (0.03..~0.2). */
    public static double noiseAmplitude(SocialContext.Self self) {
        return Math.max(0.02, 0.05 + 0.12 * self.t(Trait.IMPULSIVENESS) + 0.05 * (1 - self.t(Trait.PATIENCE)) - 0.03);
    }

    /**
     * Pure: the best legal (act, target) over {@code contexts} (all of one actor), with noise from {@code rng}, or
     * empty when no legal score clears {@link #bar}. The draw order is fixed (contexts as given, acts in enum order), so
     * the same state and the same stream give the same answer.
     */
    public static java.util.Optional<Scored> bestOf(List<SocialContext> contexts, Random rng) {
        Scored best = null;
        double bar = 0;
        for (SocialContext c : contexts) {
            bar = bar(c.self());
            for (Scored s : rank(c, rng)) {
                if (best == null || s.score() > best.score()) {
                    best = s;
                }
            }
        }
        return best != null && best.score() >= bar ? java.util.Optional.of(best) : java.util.Optional.empty();
    }

    /** Every legal act toward one target, scored (noise drawn from {@code rng}, one draw per legal act), in enum order. */
    public static List<Scored> rank(SocialContext c, Random rng) {
        List<Scored> out = new ArrayList<>();
        double amp = noiseAmplitude(c.self());
        for (SocialActKind kind : SocialActKind.values()) {
            SocialAction a = REGISTRY.get(kind);
            if (a.preconditions(c) != null) {
                continue;
            }
            if (!c.actorFree() || !c.targetFree() && kind != SocialActKind.ACCEPT && kind != SocialActKind.DECLINE
                    || a.needsIdle() && !c.actorIdle()) {
                continue;
            }
            double u = a.utility(c);
            out.add(new Scored(kind, c.target().id(), u, u + amp * (rng.nextDouble() * 2 - 1)));
        }
        return out;
    }

    // ── what a bot knows ──

    /** Notes that count as a fight, an apology and a making-up, read by their wording (the texts name nobody). */
    private static final EnumSet<BotMemory.Kind> PAIR_KINDS = EnumSet.of(BotMemory.Kind.CHAT, BotMemory.Kind.GIFT,
            BotMemory.Kind.PARTY, BotMemory.Kind.HELP, BotMemory.Kind.FAVOR, BotMemory.Kind.SCAM);

    /** The live context of {@code actor} toward {@code target}: null when either is not on a map. Memory reads only. */
    public static SocialContext build(int actorId, int targetId, long now) {
        SocialWorld w = world();
        SocialWorld.Body a = w.body(actorId);
        SocialWorld.Body t = w.body(targetId);
        if (a == null || t == null) {
            return null;
        }
        SocialContext.Self self = selfOf(actorId, now);
        Axes edge = BotBonds.axes(actorId, targetId);
        Stage stage = BotBonds.stage(actorId, targetId);
        SocialContext.Recollection memory = SocialContext.Recollection.NONE;
        try {
            BotMemory.Query q = BotMemory.Query.of(PAIR_KINDS, 0, a.mapId(), SocialActs.Notes.TOPIC, targetId);
            memory = SocialActs.read(w.recall(actorId, targetId, q, now, 10), now);
        } catch (RuntimeException | LinkageError e) {
            // no memory read: the bot has no history with it
        }
        List<SocialContext.Company> company = new ArrayList<>(3);
        if (a.mapKey() == t.mapKey()) {
            for (SocialWorld.Body o : w.around(actorId, t.x(), t.y(), SocialContext.WITH_X, SocialContext.WITH_Y)) {
                if (o.id() == actorId || o.id() == targetId || o.player()) {
                    continue;
                }
                Axes oe = BotBonds.axes(actorId, o.id());
                Stage os = BotBonds.stage(actorId, o.id());
                if (os != Stage.STRANGER || oe.familiarity() >= 0.04) { // it only counts what it knows of them
                    company.add(new SocialContext.Company(o.id(), oe, os));
                    if (company.size() >= 3) {
                        break;
                    }
                }
            }
        }
        return new SocialContext(now, a, t, self, edge, stage, memory, company, w.free(actorId), w.free(targetId),
                w.idle(actorId), w.idle(targetId), avoiding(actorId, targetId, now), pendingInvite(actorId, targetId, now));
    }

    /** Milliseconds since {@code actor} last did {@code kind} to {@code target} (Long.MAX_VALUE: never). */
    public static long sinceLast(int actorId, int targetId, SocialActKind kind, long now) {
        Long t = lastAct.get(new LastKey(actorId, targetId, kind));
        return t == null || t > now ? Long.MAX_VALUE : now - t;
    }

    /** The bot's mind at {@code now}, from memory only; a bot whose memory is not loaded reads as an even-tempered one. */
    static SocialContext.Self selfOf(int botId, long now) {
        LifeMemory mem = LifeMemoryStore.peek(botId);
        return mem == null ? SocialContext.Self.flat(0.5, 0.3) : mem.mind(m -> SocialContext.Self.of(m, now));
    }

    // ── effects on minds (memory only; a bot whose memory is not loaded feels nothing) ──

    /** Runs {@code f} on the bot's mind under its lock; false when the memory is not loaded. */
    static boolean mind(int botId, java.util.function.Consumer<SocialMind> f) {
        LifeMemory mem = LifeMemoryStore.peek(botId);
        if (mem == null) {
            return false;
        }
        mem.mind((Function<SocialMind, Void>) m -> {
            f.accept(m);
            return null;
        });
        return true;
    }

    // ── invitations ──

    static long pairKey(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    /** The invitation {@code from} made {@code to} that is still open, or null. */
    public static Invite pendingInvite(int to, int from, long now) {
        Invite i = invites.get(pairKey(from, to));
        return i != null && i.expiresAt() > now ? i : null;
    }

    static Invite openInvite(int from, int to, String what, int mapId, Object payload, long now) {
        Invite i = new Invite(INVITE_IDS.getAndIncrement(), from, to, what, mapId, now, now + INVITE_TTL_MS, payload);
        Invite old = invites.put(pairKey(from, to), i);
        if (old != null) {
            flows().release(old);
        }
        return i;
    }

    static void closeInvite(Invite i) {
        invites.remove(pairKey(i.from(), i.to()), i);
    }

    // ── avoid records ──

    /** Whether {@code actor} is avoiding {@code target} now. */
    public static boolean avoiding(int actorId, int targetId, long now) {
        Avoid a = avoids.get(pairKey(actorId, targetId));
        return a != null && a.activeAt(now);
    }

    /** The avoid records {@code actor} holds now (a copy). */
    public static List<Avoid> avoidRecords(int actorId, long now) {
        List<Avoid> out = new ArrayList<>(2);
        for (Avoid a : avoids.values()) {
            if (a.actor() == actorId && a.activeAt(now)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Every avoid record that is active now (for the dashboard and the tests). */
    public static List<Avoid> allAvoids(long now) {
        List<Avoid> out = new ArrayList<>();
        for (Avoid a : avoids.values()) {
            if (a.activeAt(now)) {
                out.add(a);
            }
        }
        return out;
    }

    static void startAvoid(Avoid a) {
        avoids.put(pairKey(a.actor(), a.target()), a);
        avoidWalkAt.put(pairKey(a.actor(), a.target()), a.since());
    }

    /** Ends both directions of avoidance between two bots (a made-up pair stops keeping away). */
    static void clearAvoid(int a, int b) {
        avoids.remove(pairKey(a, b));
        avoids.remove(pairKey(b, a));
    }

    // ── spend time ──

    static boolean together(int a, int b) {
        return togethers.containsKey(pairKey(Math.min(a, b), Math.max(a, b)));
    }

    static void startTogether(Together t) {
        togethers.put(pairKey(Math.min(t.a(), t.b()), Math.max(t.a(), t.b())), t);
    }

    // ── argue bound ──

    /** At most this many arguments per pair in a rolling day (on top of the budget's per-pair gap). */
    static final int ARGUES_PER_DAY = 3;

    static boolean argueRoom(int a, int b, long now) {
        java.util.ArrayDeque<Long> log = argueLog.get(pairKey(Math.min(a, b), Math.max(a, b)));
        if (log == null) {
            return true;
        }
        synchronized (log) {
            while (!log.isEmpty() && (now - log.peekFirst() > 24L * 3_600_000 || log.peekFirst() > now)) {
                log.pollFirst();
            }
            return log.size() < ARGUES_PER_DAY;
        }
    }

    static void argued(int a, int b, long now) {
        java.util.ArrayDeque<Long> log = argueLog.computeIfAbsent(pairKey(Math.min(a, b), Math.max(a, b)),
                k -> new java.util.ArrayDeque<>());
        synchronized (log) {
            log.addLast(now);
        }
    }

    // ── upkeep ──

    /**
     * Upkeep, called by the decision tick: lapsed invitations are given back, avoid records keep their bot away (the
     * walk is re-issued at most every 20 s while the target is closer than the record's distance) and expire, spend-time
     * pairs are judged when their time is up. Cost: the number of live records (a handful).
     */
    public static void tick(long now) {
        try {
            SocialWorld w = world();
            for (Invite i : new ArrayList<>(invites.values())) {
                if (i.expiresAt() <= now) {
                    closeInvite(i);
                    flows().release(i);
                    SocialMetrics.countAct(SocialActKind.INVITE, "expired");
                    mind(i.from(), m -> m.feel(Emotion.SADNESS, 0.03, now));
                }
            }
            for (Map.Entry<Long, Avoid> e : new ArrayList<>(avoids.entrySet())) {
                Avoid a = e.getValue();
                if (a.until() <= now) {
                    avoids.remove(e.getKey(), a);
                    avoidWalkAt.remove(e.getKey());
                    SocialMetrics.countAct(SocialActKind.AVOID, "expired");
                    continue;
                }
                SocialWorld.Body me = w.body(a.actor());
                SocialWorld.Body them = w.body(a.target());
                if (me == null || them == null || me.mapKey() != them.mapKey() || me.dx(them) >= a.keepPx()) {
                    continue;
                }
                Long walked = avoidWalkAt.get(e.getKey());
                if ((walked == null || now - walked >= 20_000L) && w.idle(a.actor())) {
                    avoidWalkAt.put(e.getKey(), now);
                    w.walkTo(a.actor(), w.awayX(me, them.x(), a.keepPx() + 50), me.y());
                    SocialMetrics.countAct(SocialActKind.AVOID, "kept-away");
                }
            }
            for (Map.Entry<Long, Together> e : new ArrayList<>(togethers.entrySet())) {
                Together t = e.getValue();
                if (t.until() <= now) {
                    togethers.remove(e.getKey(), t);
                    SocialActs.finishTogether(t, now);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("Social upkeep failed", e);
        }
    }

    /** Open state sizes for the dashboard: invitations, avoid records, spend-time pairs. */
    public static Map<String, Integer> openState() {
        return Map.of("invites", invites.size(), "avoids", avoids.size(), "together", togethers.size());
    }
}
