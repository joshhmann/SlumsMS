package soloMapling.itemPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/*
 * Ticket 162 part 5: nutnnut/Cosmic's reproduction-cost valuer (GreenCatMS, https://github.com/nutnnut/Cosmic, AGPL-3.0,
 * src/main/java/server/bots/BotScrollValuer.java at dev 502bb23e2), ported near-verbatim (only their perf-monitor
 * hook is dropped). Used by UpgradeSimulator's price (-Dslums.scroll.ev) in place of the naive "(cost + scroll) /
 * success" chain, and as the value curve of the EV scroll planner (soloMapling.ArtificialPlayer.BotScrollSystem
 * .ScrollPlanner). The original javadoc follows.
 */
/**
 * Reproduction-cost valuation for bot equips, denominated in meso. Produces the convex value curve
 * that {@code ScrollPlanner} consumes as an equip's {@code value}: how much an item is worth =
 * the cheapest expected meso to <em>reproduce</em> one this good from scratch.
 *
 * <h2>Model</h2>
 * To make an item of a given type reach a weighted stat-score {@code >= target}, you buy a clean base
 * ({@code baseCostMeso} each, with {@code tuc} upgrade slots) and scroll it. A regular scroll consumes
 * a slot win-or-lose, so the only "retry" is at the item level: a base that runs out of slots below
 * the target (or is destroyed) is discarded and a fresh one bought. The cheapest expected meso to
 * reach the target is a small stochastic DP over {@code (slots_remaining, score)} with an explicit
 * abandon-and-rebuy option; {@code D = } the restart value is a 1-D fixed point solved by iteration.
 *
 * <p>The resulting curve is flat at {@code baseCostMeso} for scores at/below the clean base and
 * <b>convex</b> above it (the binomial upper tail under a fixed slot budget makes the last points
 * exponentially dearer). That convexity is what lets the planner snowball an already-strong piece and
 * abandon one a failure has crippled — see docs/bot/economy-design.md §"Validated computational model".
 *
 * <p>v1 scope (owned inventory, no live market): the scroll set and {@code baseCostMeso} are supplied
 * by the wiring layer — scroll costs from {@code shopitems}, the clean-base cost a configurable stub
 * until the rarity→meso / economy ledger lands. This class is pure and unit tested in isolation.
 */
public final class ScrollValuer {

    /** Iterations for the restart fixed point; D converges geometrically so this is comfortably ample
     *  even for high-restart (low success-rate) targets where the contraction rate approaches 1. */
    private static final int RESTART_ITERS = 120;
    /** Ours: the Newton solver's step cap, and the restart value past which a target counts as out of reach. */
    private static final int NEWTON_ITERS = 200;
    private static final double UNREACHABLE = 1e18;
    /** Fixed-point convergence tolerance, RELATIVE to the value: reproduction costs run from a few
     *  thousand meso (cheap low-level bases) to the millions (scrolled rares), so a flat meso epsilon
     *  either over-iterates the millions or terminates cheap items prematurely. Stop when the restart
     *  value moves less than {@code REL} of itself, floored by {@code FLOOR} meso. At 0.1% of value that
     *  is a few-thousand-meso residual on a multi-million valuation — far below any decision-relevant
     *  precision — and lands in ~10 iterations instead of the ~20 a 1-meso tolerance needed. The 1-meso
     *  floor keeps the fixed point exact on the small synthetic values the unit tests assert, where the
     *  relative term would otherwise over-loosen convergence (residual amplifies with the contraction
     *  rate). Past ~1M the relative term dominates and gives a few-hundred-meso tolerance — negligible. */
    private static final double RESTART_EPSILON_REL = 0.0003;
    private static final double RESTART_EPSILON_FLOOR_MESO = 1.0;

    /** Convergence tolerance for the restart fixed point at a given value (see {@link #RESTART_EPSILON_REL}). */
    private static double convergenceEps(double value) {
        return Math.max(RESTART_EPSILON_FLOOR_MESO, Math.abs(value) * RESTART_EPSILON_REL);
    }

    private ScrollValuer() {}

    /** A scroll usable in reproduction: success probability, weighted stat-score gain, meso cost. */
    public record ScrollSpec(double successRate, double statGain, double mesoCost) {}

    /**
     * Reproduction-cost value curve in meso. {@code value(score)} = cheapest expected meso to produce
     * an item of this type with weighted stat-score {@code >= score}. Lazily memoized per queried score.
     *
     * @param baseScore   the clean base's weighted stat-score (curve floor).
     * @param tuc         the item type's total upgrade slots.
     * @param scrolls     obtainable scrolls applicable to this item (success, gain, meso cost).
     * @param baseCostMeso meso cost to acquire one clean base.
     */
    public static DoubleUnaryOperator reproductionValue(double baseScore, int tuc,
                                                 List<ScrollSpec> scrolls, double baseCostMeso) {
        double base = Math.max(0.0, baseCostMeso);
        if (tuc <= 0 || scrolls == null || scrolls.isEmpty()) {
            return score -> base; // can't be improved -> worth exactly its base cost everywhere
        }
        Map<Long, Double> curve = new ConcurrentHashMap<>();
        return score -> {
            // Quantize the queried score to 0.1 — the same resolution the inner DP memoizes at
            // (costFrom keys score at round(a*10)), so it can't distinguish anything finer anyway.
            // The planner samples many reachable stat-scores per candidate at 0.001 (BotScrollPlanner),
            // and each distinct score on a cold curve is a full DP solve (`scroll-dp`); merging them into
            // 0.1 bands collapses ~100x of those near-duplicate solves into memo hits with no
            // decision-relevant precision loss (curve values are sampled at integer stat bands).
            double q = Math.round(score * 10.0) / 10.0;
            return curve.computeIfAbsent(Math.round(q * 10.0),
                    k -> productionCost(q, baseScore, tuc, scrolls, base));
        };
    }

    /** Cheapest expected meso to produce an item reaching {@code >= target} (restart-on-ruin DP). */
    private static double productionCost(double target, double baseScore, int tuc,
                                         List<ScrollSpec> scrolls, double baseCost) {
        if (target <= baseScore) {
            return baseCost;
        }
        double restart = restartValue(tuc, baseScore, target, scrolls, baseCost);
        return baseCost + restart;
    }

    /** One row of the reproduction-cost table (debug/export). */
    record CurveRow(double target,
                    double cost,
                    int firstScroll) {} // index into the scroll list; -1 = abandon+rebuy, -2 = at/below base

    /**
     * Reproduction-cost table sampled on an integer stat-score grid from the clean base up to the max
     * reachable score, each row tagged with the optimal FIRST scroll to throw. For the debug command.
     */
    static List<CurveRow> explain(double baseScore, int tuc, List<ScrollSpec> scrolls, double baseCostMeso) {
        double base = Math.max(0.0, baseCostMeso);
        List<CurveRow> rows = new ArrayList<>();
        if (tuc <= 0 || scrolls == null || scrolls.isEmpty()) {
            rows.add(new CurveRow(baseScore, base, -2));
            return rows;
        }
        double maxGain = 0.0;
        for (ScrollSpec s : scrolls) {
            maxGain = Math.max(maxGain, Math.max(0.0, s.statGain()));
        }
        int lo = (int) Math.ceil(baseScore);
        int hi = (int) Math.ceil(baseScore + tuc * maxGain);
        for (int t = lo; t <= hi; t++) {
            double[] cp = productionCostAndPolicy(t, baseScore, tuc, scrolls, base);
            rows.add(new CurveRow(t, cp[0], (int) cp[1]));
        }
        return rows;
    }

    /** {@code [cost, firstScrollIndex]} for a target — same DP as {@link #productionCost} plus the root move. */
    private static double[] productionCostAndPolicy(double target, double baseScore, int tuc,
                                                    List<ScrollSpec> scrolls, double baseCost) {
        if (target <= baseScore) {
            return new double[]{baseCost, -2};
        }
        double restart = restartValue(tuc, baseScore, target, scrolls, baseCost);
        Map<Long, Double> memo = new HashMap<>();
        double best = baseCost + restart; // abandon+rebuy at the root
        int move = -1;
        for (int k = 0; k < scrolls.size(); k++) {
            ScrollSpec sc = scrolls.get(k);
            double p = clamp01(sc.successRate());
            double na = Math.min(baseScore + sc.statGain(), target);
            double v = sc.mesoCost()
                    + p * costFrom(tuc - 1, na, target, scrolls, baseCost, restart, memo)
                    + (1.0 - p) * costFrom(tuc - 1, baseScore, target, scrolls, baseCost, restart, memo);
            if (v < best) {
                best = v;
                move = k;
            }
        }
        return new double[]{baseCost + restart, move};
    }

    /**
     * Ours (ticket 162): the restart value D = costFrom(D) by Newton steps on the optimal policy instead of plain
     * iteration. costFrom is a minimum of affine functions of D (each policy's cost plus its chance of starting over
     * times D), so each step solves the current policy's line exactly and the policy settles in a few steps. Upstream's
     * 120 plain iterations stop far short for a long-shot target (a 1-in-10,000 item contracts by 0.9999 a step),
     * which priced every item past some score at the same capped value. Infinite when the target cannot be reached.
     */
    private static double restartValue(int tuc, double baseScore, double target, List<ScrollSpec> scrolls,
                                       double baseCost) {
        double d = 0.0;
        for (int iter = 0; iter < NEWTON_ITERS; iter++) {
            Map<Long, double[]> memo = new HashMap<>();
            double[] cb = costAndRestart(tuc, baseScore, target, scrolls, baseCost, d, memo);
            double restartChance = cb[1];
            double next;
            if (restartChance >= 1.0 - 1e-12) {
                // Starting over beats every way to finish at this D: D is far too low. Grow it fast (an overshoot is
                // fine: from above, the Newton steps come down monotonically).
                next = Math.max(cb[0], 2.0 * d + baseCost + 1.0);
                if (next > UNREACHABLE) {
                    return Double.POSITIVE_INFINITY;
                }
            } else {
                next = (cb[0] - restartChance * d) / (1.0 - restartChance);
            }
            if (Math.abs(next - d) <= convergenceEps(next)) {
                return next;
            }
            d = next;
        }
        return d;
    }

    /** {@code [cost, chance of starting over]} from {@code (s, a)} under the optimal policy at restart value D. */
    private static double[] costAndRestart(int s, double a, double target, List<ScrollSpec> scrolls,
                                           double baseCost, double D, Map<Long, double[]> memo) {
        if (a >= target) {
            return new double[]{0.0, 0.0};
        }
        if (s <= 0) {
            return new double[]{baseCost + D, 1.0};
        }
        long key = s * 100000007L + Math.round(a * 10.0);
        double[] cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        double[] best = {baseCost + D, 1.0};
        for (ScrollSpec sc : scrolls) {
            double p = clamp01(sc.successRate());
            double na = Math.min(a + sc.statGain(), target);
            double[] win = costAndRestart(s - 1, na, target, scrolls, baseCost, D, memo);
            double[] lose = costAndRestart(s - 1, a, target, scrolls, baseCost, D, memo);
            double v = sc.mesoCost() + p * win[0] + (1.0 - p) * lose[0];
            if (v < best[0]) {
                best = new double[]{v, p * win[1] + (1.0 - p) * lose[1]};
            }
        }
        memo.put(key, best);
        return best;
    }

    /** Expected meso from state {@code (s slots, a score)} to a finished item, given restart value D. */
    private static double costFrom(int s, double a, double target, List<ScrollSpec> scrolls,
                                   double baseCost, double D, Map<Long, Double> memo) {
        if (a >= target) {
            return 0.0;
        }
        if (s <= 0) {
            return baseCost + D; // ruined: rebuy a fresh base and start over
        }
        // Memoize score at 0.1-stat resolution, not 0.001: fractional weighted-stat gains make the
        // reachable-score set combinatorially dense, and at 0.001 nearly every (s,a) is a distinct memo
        // entry — the DP was ~156ms/call. The curve feeds million-meso valuations sampled at integer
        // bands, so 0.1-score merging is far below any decision-relevant precision.
        long key = s * 100000007L + Math.round(a * 10.0);
        Double cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        double best = baseCost + D; // option: abandon this base now and rebuy
        for (ScrollSpec sc : scrolls) {
            double p = clamp01(sc.successRate());
            double na = Math.min(a + sc.statGain(), target);
            double v = sc.mesoCost()
                    + p * costFrom(s - 1, na, target, scrolls, baseCost, D, memo)
                    + (1.0 - p) * costFrom(s - 1, a, target, scrolls, baseCost, D, memo);
            if (v < best) {
                best = v;
            }
        }
        memo.put(key, best);
        return best;
    }

    private static double clamp01(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return Math.min(v, 1.0);
    }
}
