package soloMapling.ArtificialPlayer.BotSocialSystem;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The loop breaker for the social layer: one guard every bot-to-bot social act asks before it acts. It is a safety net
 * against a runaway loop (two bots greeting each other forever, a failing act retried every tick, a map flooded with
 * talk), not new pacing: the defaults sit far above what {@code BotTalk}'s own gaps let through, so a healthy town
 * never sees a refusal.
 *
 * <p>Six limits, checked in this order by {@link #allow}:
 * <ol>
 *   <li><b>backoff</b>: the same actor, act and target failed {@link Limits#failsBeforeBackoff} times in a row; the
 *       next try waits {@link Limits#backoffBaseMs}, then twice that, then four times, up to {@link Limits#backoffMaxMs};</li>
 *   <li><b>turn limit</b>: A to B, B to A, A to B... within {@link Limits#turnWindowMs} of each other; after
 *       {@link Limits#maxTurns} turns the pair is quiet until the chain has been silent for the window;</li>
 *   <li><b>pair cooldown</b>: the same pair and the same kind of act (the text before a colon: {@code ask:PARTY} is an
 *       {@code ask}) again too soon; per-kind gaps in {@link Limits#pairCooldownMs};</li>
 *   <li><b>actor rate</b>: acts by one bot in the last minute;</li>
 *   <li><b>map rate</b>: acts on one map in the last minute;</li>
 *   <li><b>global rate</b>: acts in the whole world in the last minute.</li>
 * </ol>
 *
 * <p>{@code allow} only reads (and counts the refusal); {@code record} is what an act that really happened calls, so a
 * refused or never-started act uses up nothing and a refusal can never extend itself. Time is an argument (callers
 * pass {@link SocialSim#now()} or the clock they already hold), so a test drives it by hand. State is in memory only,
 * under one short lock (the callers are the bot-talk tick and the act threads), and swept when stale.
 *
 * <p>{@code -Dslums.socialbudget=false} switches it off (every ask is allowed, nothing is tracked). Operators can move
 * a limit with {@code -Dslums.socialbudget.<limit>=<number>} (names in {@link Limits#fromProperties()}).
 */
public final class SocialBudget {

    public static final String SWITCH = "slums.socialbudget";

    /** Act kinds the current callers use; any other string is a kind of its own with the default cooldown. */
    public static final String GREET = "greet";
    public static final String CHAT = "chat";
    public static final String ASK = "ask";

    /** Why an act was refused (or {@link #OK}). The name, lower-cased with dashes, is the counter key. */
    public enum Reason {
        OK, BACKOFF, TURN_LIMIT, PAIR_COOLDOWN, ACTOR_RATE, MAP_RATE, GLOBAL_RATE;

        public String label() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /** The answer: whether to go ahead, why not, and the earliest time to ask again (0 when allowed or unknown). */
    public record Verdict(boolean allowed, Reason reason, long retryAtMs) {
        public static final Verdict OK = new Verdict(true, Reason.OK, 0);
    }

    /** What happened to an act: feed it to {@link #record}. {@code success} false counts toward the backoff. */
    public record Outcome(int actorId, int targetId, long mapKey, String act, long now, boolean success) {
    }

    /** The numbers. {@link #defaults()} is generous on purpose. */
    public record Limits(long defaultCooldownMs, Map<String, Long> pairCooldownMs, int actorPerMin, int mapPerMin,
                         int globalPerMin, int maxTurns, long turnWindowMs, int failsBeforeBackoff, long backoffBaseMs,
                         long backoffMaxMs, long failResetMs) {

        public static final long MINUTE = 60_000L;

        /**
         * Per-pair gaps by act family. The first three are the bot-talk acts; the rest are the stage-4 social acts
         * ({@code SocialActions.budgetKey}): a repeat of the same act between the same two bots waits this long, so a
         * grudge is not re-argued every tick and a compliment is not repeated.
         */
        static final Map<String, Long> ACT_GAPS = Map.ofEntries(Map.entry(GREET, 10 * MINUTE),
                Map.entry(CHAT, MINUTE), Map.entry(ASK, 15_000L),
                Map.entry("compliment", 45 * MINUTE), Map.entry("argue", 30 * MINUTE),
                Map.entry("apologize", 20 * MINUTE), Map.entry("reconcile", 30 * MINUTE),
                Map.entry("avoid", 45 * MINUTE), Map.entry("spendtime", 20 * MINUTE),
                Map.entry("gift", 6 * 60 * MINUTE), Map.entry("invite", 10 * MINUTE),
                Map.entry("reply", 5_000L), Map.entry("offerhelp", 15 * MINUTE),
                Map.entry("shareinfo", 5 * MINUTE), Map.entry("rumor", 20 * MINUTE));

        public static Limits defaults() {
            return new Limits(30_000L, ACT_GAPS, 10, 40, 200, 6, 90_000L, 3, 30_000L, 60 * MINUTE, 6 * 60 * MINUTE);
        }

        /**
         * Defaults, moved by {@code -Dslums.socialbudget.<name>}: {@code greetCooldownS}, {@code chatCooldownS},
         * {@code askCooldownS}, {@code actorPerMin}, {@code mapPerMin}, {@code globalPerMin}, {@code maxTurns},
         * {@code turnWindowS}, {@code failsBeforeBackoff}, {@code backoffBaseS}, {@code backoffMaxS}.
         */
        public static Limits fromProperties() {
            Limits d = defaults();
            Map<String, Long> cd = new HashMap<>(d.pairCooldownMs);
            cd.put(GREET, (long) (num("greetCooldownS", d.pairCooldownMs.get(GREET) / 1000.0) * 1000));
            cd.put(CHAT, (long) (num("chatCooldownS", d.pairCooldownMs.get(CHAT) / 1000.0) * 1000));
            cd.put(ASK, (long) (num("askCooldownS", d.pairCooldownMs.get(ASK) / 1000.0) * 1000));
            return new Limits(d.defaultCooldownMs, Map.copyOf(cd),
                    (int) num("actorPerMin", d.actorPerMin), (int) num("mapPerMin", d.mapPerMin),
                    (int) num("globalPerMin", d.globalPerMin), (int) num("maxTurns", d.maxTurns),
                    (long) (num("turnWindowS", d.turnWindowMs / 1000.0) * 1000),
                    (int) num("failsBeforeBackoff", d.failsBeforeBackoff),
                    (long) (num("backoffBaseS", d.backoffBaseMs / 1000.0) * 1000),
                    (long) (num("backoffMaxS", d.backoffMaxMs / 1000.0) * 1000), d.failResetMs);
        }

        long cooldownOf(String act) {
            return pairCooldownMs.getOrDefault(family(act), defaultCooldownMs);
        }

        private static double num(String key, double fallback) {
            String raw = System.getProperty(SWITCH + "." + key);
            if (raw == null) return fallback;
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    private SocialBudget() {
    }

    /** On unless {@code -Dslums.socialbudget=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty(SWITCH));
    }

    // ── state (all under LOCK) ──

    private static final Object LOCK = new Object();
    private static final long WINDOW_MS = Limits.MINUTE;
    private static final long SWEEP_EVERY_MS = Limits.MINUTE;

    private record PairAct(long pair, String family) {
    }

    private record FailKey(int actorId, String act, int targetId) {
    }

    private static final class Fails {
        int count;
        long lastAt;
        long blockedUntil;
    }

    private static final class Chain {
        int lastActor;
        int turns;
        long lastAt;
    }

    private static volatile Limits limits;
    private static final Map<PairAct, Long> pairLast = new HashMap<>();
    private static final Map<Integer, ArrayDeque<Long>> byActor = new HashMap<>();
    private static final Map<Long, ArrayDeque<Long>> byMap = new HashMap<>();
    private static final ArrayDeque<Long> world = new ArrayDeque<>();
    private static final Map<Long, Chain> chains = new HashMap<>();
    private static final Map<FailKey, Fails> fails = new HashMap<>();
    private static final EnumMap<Reason, Long> verdicts = new EnumMap<>(Reason.class);
    private static long recorded, failuresRecorded, backoffsStarted;
    private static long lastSweep;

    private static Limits limits() {
        Limits l = limits;
        return l != null ? l : Limits.fromProperties();
    }

    // ── the two calls ──

    /** {@link #allow(int, int, long, String, long)} at {@link SocialSim#now()}. */
    public static Verdict allow(int actorId, int targetId, long mapKey, String act) {
        return allow(actorId, targetId, mapKey, act, SocialSim.now());
    }

    /**
     * May {@code actorId} do {@code act} to {@code targetId} (0 or negative: no target) on {@code mapKey} at
     * {@code now}? Reads state and counts the verdict; changes nothing else.
     */
    public static Verdict allow(int actorId, int targetId, long mapKey, String act, long now) {
        if (!enabled()) {
            return Verdict.OK;
        }
        Limits l = limits();
        synchronized (LOCK) {
            Verdict v = judge(l, actorId, targetId, mapKey, act, now);
            verdicts.merge(v.reason(), 1L, Long::sum);
            return v;
        }
    }

    private static Verdict judge(Limits l, int actorId, int targetId, long mapKey, String act, long now) {
        Fails f = fails.get(new FailKey(actorId, act, targetId));
        if (f != null && f.blockedUntil > now) {
            return new Verdict(false, Reason.BACKOFF, f.blockedUntil);
        }
        long pair = pair(actorId, targetId);
        Chain c = chains.get(pair);
        if (c != null && c.turns >= l.maxTurns() && fresh(now, c.lastAt, l.turnWindowMs())) {
            return new Verdict(false, Reason.TURN_LIMIT, c.lastAt + l.turnWindowMs());
        }
        Long last = pairLast.get(new PairAct(pair, family(act)));
        long cooldown = l.cooldownOf(act);
        if (last != null && fresh(now, last, cooldown)) {
            return new Verdict(false, Reason.PAIR_COOLDOWN, last + cooldown);
        }
        long retry = full(byActor.get(actorId), now, l.actorPerMin());
        if (retry > 0) {
            return new Verdict(false, Reason.ACTOR_RATE, retry);
        }
        retry = full(byMap.get(mapKey), now, l.mapPerMin());
        if (retry > 0) {
            return new Verdict(false, Reason.MAP_RATE, retry);
        }
        retry = full(world, now, l.globalPerMin());
        if (retry > 0) {
            return new Verdict(false, Reason.GLOBAL_RATE, retry);
        }
        return Verdict.OK;
    }

    /** The per-pair gap of an act family now in force (a read of the limits; counts nothing). */
    public static long cooldownMs(String act) {
        return limits().cooldownOf(act);
    }

    /** An act that really happened (or really failed). Uses up the rate windows, the pair cooldown and the turn chain. */
    public static void record(Outcome o) {
        if (!enabled()) {
            return;
        }
        Limits l = limits();
        synchronized (LOCK) {
            long now = o.now();
            recorded++;
            long pair = pair(o.actorId(), o.targetId());
            pairLast.put(new PairAct(pair, family(o.act())), now);
            window(byActor.computeIfAbsent(o.actorId(), k -> new ArrayDeque<>()), now);
            window(byMap.computeIfAbsent(o.mapKey(), k -> new ArrayDeque<>()), now);
            window(world, now);
            byActor.get(o.actorId()).addLast(now);
            byMap.get(o.mapKey()).addLast(now);
            world.addLast(now);
            turn(l, pair, o.actorId(), now);
            FailKey fk = new FailKey(o.actorId(), o.act(), o.targetId());
            if (o.success()) {
                fails.remove(fk);
            } else {
                failure(l, fk, now);
            }
            if (now - lastSweep >= SWEEP_EVERY_MS || now < lastSweep) {
                lastSweep = now;
                sweep(l, now);
            }
        }
    }

    private static void turn(Limits l, long pair, int actor, long now) {
        Chain c = chains.computeIfAbsent(pair, k -> new Chain());
        if (c.turns == 0 || !fresh(now, c.lastAt, l.turnWindowMs())) {
            c.turns = 1;                    // a new exchange
        } else if (c.lastActor != actor) {
            c.turns++;                      // the other one answered: one more turn of the back-and-forth
        }                                   // the same bot speaking again is no reply: the count stays
        c.lastActor = actor;
        c.lastAt = now;
    }

    private static void failure(Limits l, FailKey key, long now) {
        Fails f = fails.computeIfAbsent(key, k -> new Fails());
        if (f.count > 0 && !fresh(now, f.lastAt, l.failResetMs())) {
            f.count = 0;                    // an old streak: start again
            f.blockedUntil = 0;
        }
        f.count++;
        f.lastAt = now;
        failuresRecorded++;
        if (f.count >= l.failsBeforeBackoff()) {
            int doublings = Math.min(30, f.count - l.failsBeforeBackoff());
            long wait = Math.min(l.backoffMaxMs(), l.backoffBaseMs() << doublings);
            if (wait <= 0) wait = l.backoffMaxMs(); // shifted past a long: the cap
            f.blockedUntil = now + wait;
            backoffsStarted++;
        }
    }

    // ── windows ──

    /** {@code then} is still inside {@code span} of {@code now} (a time in the future, after a clock step back, is not). */
    private static boolean fresh(long now, long then, long span) {
        return then <= now && now - then < span;
    }

    /** Drops the entries older than the window (or from a clock that has since stepped back). */
    private static void window(ArrayDeque<Long> times, long now) {
        while (!times.isEmpty() && !fresh(now, times.peekFirst(), WINDOW_MS)) {
            times.pollFirst();
        }
    }

    /** 0 when the window has room, else the time its oldest entry leaves it. */
    private static long full(ArrayDeque<Long> times, long now, int max) {
        if (times == null) return 0;
        window(times, now);
        return times.size() >= max ? times.peekFirst() + WINDOW_MS : 0;
    }

    private static void sweep(Limits l, long now) {
        pairLast.entrySet().removeIf(e -> !fresh(now, e.getValue(), l.cooldownOf(e.getKey().family())));
        chains.values().removeIf(c -> !fresh(now, c.lastAt, l.turnWindowMs()));
        fails.values().removeIf(f -> f.blockedUntil <= now && !fresh(now, f.lastAt, l.failResetMs()));
        byActor.values().removeIf(d -> {
            window(d, now);
            return d.isEmpty();
        });
        byMap.values().removeIf(d -> {
            window(d, now);
            return d.isEmpty();
        });
        window(world, now);
    }

    static String family(String act) {
        int colon = act.indexOf(':');
        return colon < 0 ? act : act.substring(0, colon);
    }

    static long pair(int a, int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xffffffffL);
    }

    // ── observability ──

    /**
     * Counters for the dashboard and the QA log: {@code allowed}, {@code denied <reason>} for every reason (zero
     * included, so the shape is stable), the acts and failures recorded, backoffs started, and what is tracked now.
     */
    public static Map<String, Long> counters() {
        Map<String, Long> out = new TreeMap<>();
        synchronized (LOCK) {
            out.put("allowed", verdicts.getOrDefault(Reason.OK, 0L));
            long denied = 0;
            for (Reason r : Reason.values()) {
                if (r == Reason.OK) continue;
                long n = verdicts.getOrDefault(r, 0L);
                out.put("denied " + r.label(), n);
                denied += n;
            }
            out.put("denied", denied);
            out.put("recorded", recorded);
            out.put("failuresRecorded", failuresRecorded);
            out.put("backoffsStarted", backoffsStarted);
            out.put("trackedPairs", (long) pairLast.size());
            out.put("trackedFailures", (long) fails.size());
        }
        return out;
    }

    // ── test seams ──

    /** Test seam: fixed limits (null: back to the defaults and properties). Pair with {@link #resetForTest()}. */
    public static void useLimitsForTest(Limits testLimits) {
        limits = testLimits;
    }

    /** Forget every act, counter and limit. */
    public static void resetForTest() {
        synchronized (LOCK) {
            limits = null;
            pairLast.clear();
            byActor.clear();
            byMap.clear();
            world.clear();
            chains.clear();
            fails.clear();
            verdicts.clear();
            recorded = 0;
            failuresRecorded = 0;
            backoffsStarted = 0;
            lastSweep = 0;
        }
    }
}
