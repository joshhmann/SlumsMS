package soloMapling.ArtificialPlayer.BotGearSystem;

import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Score;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Search;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Ticket 162 part 1: the full gear optimizer, a port of nutnnut/Cosmic's BotEquipManager optimizer (GreenCatMS,
 * https://github.com/nutnnut/Cosmic, AGPL-3.0, src/main/java/server/bots/BotEquipManager.java at dev 2771d9ea1:
 * runOptimizerWithExtras / solveForWeapon / paretoPruneNodes / dedupEquivalentNodes / pruneDominatedWithReqs /
 * scanReqRelevantDims / isAccRelevantJob, and chooseSecondaryTarget for the dexless AP build).
 *
 * The same contract as BotGearPlanner (Wearer, what it wears, what it carries, the Valuer and the weapon Rules in; a
 * Plan or null out), so BotGear switches between the two (-Dslums.gear.optimizer=pareto|climb, default climb).
 *
 * Upstream's method, kept:
 *  - the outer loop tries every weapon the bot may hold (and the one it holds), the inner loop is a Pareto DP over
 *    the other body slots, each slot "leave as is/empty" or one of its candidates;
 *  - after every slot the frontier is deduplicated on its signature and Pareto-pruned, and only a state whose own
 *    picks are wearable may prune another (a speculative state, whose piece needs a stat nothing gives yet, must not
 *    knock out the fallback); the frontier is capped (MAX_PARETO_STATES) by damage when it overflows;
 *  - requirement chaining comes from the DP itself: a state carries its stat totals, so a piece that only becomes
 *    wearable with another piece's STR/DEX/INT/LUK survives while the other piece is being considered;
 *  - per-slot candidate pools are pruned of dominated pieces whose requirements are no easier;
 *  - build-aware dimensions: the requirement-relevant stats (any candidate needs that stat) and accuracy for the jobs
 *    that need it to hit (warriors, brawlers, Thunder Breakers) stay as Pareto dimensions, the rest collapse into
 *    the damage number.
 * Ours, on top (the rules BotGearPlanner and BotGear already keep for 3,000 bots):
 *  - damage is our own formula (the Valuer, BotDamage.gearValue), not upstream's raw max x hit chance x cycle;
 *  - the Pareto vector holds every stat the Valuer reacts to (probed once), so pruning is exact for our valuer rather
 *    than upstream's projected "primary + watk" vector;
 *  - the final check is the real wear order (BotGearPlanner's wearable: worn gear counts, each new piece needs its
 *    stats from the base plus the pieces put on before it). Upstream's "each pick meets its need without itself"
 *    accepts two pieces that each need the other's DEX; the server does not;
 *  - a worn slot is never emptied but by an overall or a two-handed weapon, there must be bag room for what comes
 *    off, and a change must be strictly better (damage, then sturdiness);
 *  - the hill climb's answer is weighed too and the better one wins, so switching this on never dresses a bot worse
 *    than the climb would have.
 * Not ported: rings (our SLOTS has none), upstream's weapon preference rank (our Rules decide which weapons a bot may
 * hold), the map-mob hit chance and attack-cycle scaling (our Valuer is the yardstick), the debug dump.
 */
public final class BotGearOptimizer {

    private BotGearOptimizer() {}

    /* Upstream's hard cap on the Pareto frontier per DP step. */
    static final int MAX_PARETO_STATES = 2000;
    /* A slot's candidates after dominance pruning, best-looking first: far above what a bot carries per slot. */
    static final int MAX_PER_SLOT = 24;

    /* The DP's non-weapon slots, TOP before PANTS so an overall is known when the pants are weighed. */
    private static final short[] DP_SLOTS = {-1, -2, -3, -4, BotGearPlanner.TOP, BotGearPlanner.PANTS, -7, -8, -9,
            BotGearPlanner.SHIELD};

    // Stat dimensions of a DP state.
    private static final int STR = 0, DEX = 1, INT = 2, LUK = 3, WATK = 4, MATK = 5, ACC = 6, DIMS = 7;

    /* The best loadout, or null when what it wears is already the best. */
    public static Plan plan(Wearer wearer, Map<Short, Piece> worn, List<Piece> owned, Valuer valuer, Rules rules) {
        Search search = new Search(wearer, worn, valuer, rules);
        Map<Short, List<Piece>> pools = pools(wearer, worn, owned, rules);
        Map<Short, Piece> start = new LinkedHashMap<>(worn);
        Score before = search.score(start);
        Map<Short, Piece> best = null;
        Score bestScore = before;

        boolean[] dims = dims(wearer, pools, valuer);
        List<Piece> weapons = new ArrayList<>(pools.getOrDefault(BotGearPlanner.WEAPON, List.of()));
        if (weapons.isEmpty()) {
            weapons.add(null);   // bare-handed: the DP still dresses the rest
        }
        for (Piece weapon : weapons) {
            Map<Short, Piece> picked = solveForWeapon(search, wearer, worn, weapon, pools, valuer, rules, dims);
            if (picked == null) {
                continue;
            }
            Score s = search.score(picked);
            if (s.beats(bestScore)) {
                best = picked;
                bestScore = s;
            }
        }
        // The climb's answer as a floor: the optimizer never does worse than what it replaces.
        Plan climb = BotGearPlanner.plan(wearer, worn, owned, valuer, rules);
        if (climb != null) {
            Score s = search.score(climb.wear());
            if (s.beats(bestScore)) {
                best = climb.wear();
                bestScore = s;
            }
        }
        if (best == null || same(best, start)) {
            return null;
        }
        return new Plan(best, before.damage(), bestScore.damage());
    }

    private static boolean same(Map<Short, Piece> a, Map<Short, Piece> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Short, Piece> e : a.entrySet()) {
            if (b.get(e.getKey()) != e.getValue()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ candidates

    /*
     * Each body slot's candidates: what the bot wears there (always, even when its stats fell under the need) and the
     * carried pieces its level, job and fame allow, dominated pieces pruned (upstream pruneDominatedWithReqs).
     */
    static Map<Short, List<Piece>> pools(Wearer wearer, Map<Short, Piece> worn, List<Piece> owned, Rules rules) {
        Map<Short, List<Piece>> pools = new HashMap<>();
        for (Map.Entry<Short, Piece> e : worn.entrySet()) {
            Piece p = e.getValue();
            if (p != null && Search.isSlot(e.getKey())
                    && (e.getKey() != BotGearPlanner.WEAPON || rules.weaponAllowed().test(p.itemId()))) {
                pools.computeIfAbsent(e.getKey(), s -> new ArrayList<>()).add(p);
            }
        }
        for (Piece p : owned) {
            Known k = p.known();
            if (k == null || !Search.isSlot(k.slot()) || k.reqLevel() > wearer.level()
                    || !BotGearPlanner.jobMayWear(wearer.jobNiche(), k.reqJob())
                    || (k.reqFame() > 0 && k.reqFame() > wearer.fame())
                    || (k.slot() == BotGearPlanner.WEAPON && !rules.weaponAllowed().test(p.itemId()))) {
                continue;
            }
            pools.computeIfAbsent(k.slot(), s -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<Short, List<Piece>> e : pools.entrySet()) {
            Piece wornHere = worn.get(e.getKey());
            List<Piece> kept = pruneDominated(e.getValue(), wornHere);
            if (kept.size() > MAX_PER_SLOT) {
                kept.sort(Comparator.comparingInt((Piece p) -> p == wornHere ? 1 : 0)
                        .thenComparingInt(p -> p.watk() + p.matk() + p.str() + p.dex() + p.int_() + p.luk())
                        .reversed());
                kept = new ArrayList<>(kept.subList(0, MAX_PER_SLOT));
            }
            e.setValue(kept);
        }
        return pools;
    }

    /*
     * Drops a piece another one beats or ties in every stat with needs no harder, of the same kind (an overall only
     * against overalls, a weapon only against weapons of its class). The worn piece is never dropped; on a full tie
     * the earlier one stays.
     */
    static List<Piece> pruneDominated(List<Piece> items, Piece wornHere) {
        if (items.size() <= 1) {
            return new ArrayList<>(items);
        }
        List<Piece> kept = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Piece a = items.get(i);
            boolean dominated = false;
            if (a != wornHere) {
                for (int j = 0; j < items.size() && !dominated; j++) {
                    Piece b = items.get(j);
                    if (i == j || !sameKind(a, b)) {
                        continue;
                    }
                    int cmp = compareStats(b, a);
                    if (cmp < 0 || !(b == wornHere || reqsAtLeastAsEasy(b.known(), a.known()))) {
                        continue;
                    }
                    // strictly better somewhere, or an exact tie broken by order (the worn one first)
                    dominated = cmp > 0 || b == wornHere || j < i;
                }
            }
            if (!dominated) {
                kept.add(a);
            }
        }
        return kept;
    }

    private static boolean sameKind(Piece a, Piece b) {
        if (BotGearPlanner.overall(a.itemId()) != BotGearPlanner.overall(b.itemId())) {
            return false;
        }
        boolean wa = a.itemId() / 1000000 == 1 && a.itemId() / 10000 % 100 >= 30;
        boolean wb = b.itemId() / 1000000 == 1 && b.itemId() / 10000 % 100 >= 30;
        if (wa || wb) {
            return a.itemId() / 10000 == b.itemId() / 10000;   // a weapon only against its own class
        }
        return true;
    }

    // 1: b beats a somewhere and ties or beats it everywhere; 0: an exact tie; -1: b is worse somewhere.
    private static int compareStats(Piece b, Piece a) {
        int[] bs = {b.str(), b.dex(), b.int_(), b.luk(), b.watk(), b.matk(), b.acc(), b.sturdiness()};
        int[] as = {a.str(), a.dex(), a.int_(), a.luk(), a.watk(), a.matk(), a.acc(), a.sturdiness()};
        boolean strict = false;
        for (int k = 0; k < bs.length; k++) {
            if (bs[k] < as[k]) {
                return -1;
            }
            if (bs[k] > as[k]) {
                strict = true;
            }
        }
        return strict ? 1 : 0;
    }

    private static boolean reqsAtLeastAsEasy(Known b, Known a) {
        if (b == null || a == null) {
            return false;
        }
        return b.reqLevel() <= a.reqLevel() && b.reqStr() <= a.reqStr() && b.reqDex() <= a.reqDex()
                && b.reqInt() <= a.reqInt() && b.reqLuk() <= a.reqLuk() && b.reqFame() <= a.reqFame()
                && (b.reqJob() == 0 || b.reqJob() == a.reqJob());
    }

    /*
     * Which stats stay as their own Pareto dimension: those the Valuer reacts to (probed at the bot's totals), those
     * some candidate needs (upstream scanReqRelevantDims) and accuracy for the jobs that need it to hit (upstream
     * isAccRelevantJob: warriors and the brawler line).
     */
    static boolean[] dims(Wearer wearer, Map<Short, List<Piece>> pools, Valuer valuer) {
        boolean[] rel = new boolean[DIMS];
        for (List<Piece> pool : pools.values()) {
            for (Piece p : pool) {
                Known k = p.known();
                if (k == null) {
                    continue;
                }
                rel[STR] |= k.reqStr() > 0;
                rel[DEX] |= k.reqDex() > 0;
                rel[INT] |= k.reqInt() > 0;
                rel[LUK] |= k.reqLuk() > 0;
            }
        }
        int weaponId = 0;
        for (Piece p : pools.getOrDefault(BotGearPlanner.WEAPON, List.of())) {
            weaponId = p.itemId();
            break;
        }
        double base = valuer.value(weaponId, 0, 0, 0, 0, 0, 0);
        int[] probe = new int[6];
        for (int d = STR; d <= MATK; d++) {
            probe[d] = 10;
            double v = valuer.value(weaponId, probe[0], probe[1], probe[2], probe[3], probe[4], probe[5]);
            probe[d] = 0;
            rel[d] |= Math.abs(v - base) > 1e-9;
        }
        rel[ACC] = accRelevant(wearer);
        return rel;
    }

    /* Upstream isAccRelevantJob: warriors (the brawler line shares the pirate niche with gunslingers, so it is left out). */
    static boolean accRelevant(Wearer wearer) {
        return wearer.jobNiche() == 1;
    }

    // ------------------------------------------------------------------ the DP

    private static final class Node {
        final int[] stats;     // gear totals: str, dex, int, luk, watk, matk, acc
        final long sturdiness;
        final Piece[] picks;   // per DP slot; null = nothing there
        double damage = Double.NaN;

        Node(int[] stats, long sturdiness, Piece[] picks) {
            this.stats = stats;
            this.sturdiness = sturdiness;
            this.picks = picks;
        }

        Node with(int i, Piece p) {
            Piece old = picks[i];
            int[] s = stats.clone();
            long st = sturdiness;
            if (old != null) {
                add(s, old, -1);
                st -= old.sturdiness();
            }
            if (p != null) {
                add(s, p, 1);
                st += p.sturdiness();
            }
            Piece[] np = picks.clone();
            np[i] = p;
            return new Node(s, st, np);
        }

        static void add(int[] s, Piece p, int sign) {
            s[STR] += sign * p.str();
            s[DEX] += sign * p.dex();
            s[INT] += sign * p.int_();
            s[LUK] += sign * p.luk();
            s[WATK] += sign * p.watk();
            s[MATK] += sign * p.matk();
            s[ACC] += sign * p.acc();
        }

        double damage(Valuer valuer, int weaponId) {
            if (Double.isNaN(damage)) {
                damage = valuer.value(weaponId, stats[STR], stats[DEX], stats[INT], stats[LUK], stats[WATK],
                        stats[MATK]);
            }
            return damage;
        }
    }

    /* The best wearable loadout around this weapon (null weapon = bare-handed), or null when none is valid. */
    private static Map<Short, Piece> solveForWeapon(Search search, Wearer wearer, Map<Short, Piece> worn,
                                                    Piece weapon, Map<Short, List<Piece>> pools, Valuer valuer,
                                                    Rules rules, boolean[] dims) {
        int weaponId = weapon != null ? weapon.itemId() : 0;
        boolean shieldAllowed = weapon != null && !BotGearPlanner.twoHanded(weaponId)
                && rules.takesShield().test(weaponId);
        int n = DP_SLOTS.length;
        int[] start = new int[DIMS];
        long startSturdiness = 0;
        if (weapon != null) {
            Node.add(start, weapon, 1);
            startSturdiness = weapon.sturdiness();
        }
        List<Node> frontier = new ArrayList<>();
        frontier.add(new Node(start, startSturdiness, new Piece[n]));
        int topIdx = indexOf(BotGearPlanner.TOP);
        for (int i = 0; i < n; i++) {
            short slot = DP_SLOTS[i];
            List<Piece> pool = pools.getOrDefault(slot, List.of());
            Piece wornHere = worn.get(slot);
            List<Node> next = new ArrayList<>(Math.max(8, frontier.size() * (pool.size() + 1)));
            for (Node prev : frontier) {
                boolean overallOn = slot == BotGearPlanner.PANTS && prev.picks[topIdx] != null
                        && BotGearPlanner.overall(prev.picks[topIdx].itemId());
                boolean forcedEmpty = overallOn || (slot == BotGearPlanner.SHIELD && !shieldAllowed);
                // "Nothing there" only where the bot wears nothing, or an overall / a two-handed weapon empties it.
                if (wornHere == null || forcedEmpty) {
                    next.add(prev);
                }
                if (forcedEmpty) {
                    continue;
                }
                for (Piece cand : pool) {
                    next.add(prev.with(i, cand));
                }
            }
            frontier = prune(next, search, wearer, worn, weapon, valuer, weaponId, dims);
        }
        Map<Short, Piece> best = null;
        Score bestScore = null;
        for (Node node : frontier) {
            Map<Short, Piece> loadout = loadout(node, weapon);
            Map<Short, Piece> trial = search.finish(worn, loadout);
            if (trial == null || !search.wearable(trial)) {
                continue;
            }
            Score s = search.score(trial);
            if (bestScore == null || s.beats(bestScore)) {
                best = trial;
                bestScore = s;
            }
        }
        return best;
    }

    private static int indexOf(short slot) {
        for (int i = 0; i < DP_SLOTS.length; i++) {
            if (DP_SLOTS[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static Map<Short, Piece> loadout(Node node, Piece weapon) {
        Map<Short, Piece> out = new LinkedHashMap<>();
        for (int i = 0; i < DP_SLOTS.length; i++) {
            if (node.picks[i] != null) {
                out.put(DP_SLOTS[i], node.picks[i]);
            }
        }
        if (weapon != null) {
            out.put(BotGearPlanner.WEAPON, weapon);
        }
        return out;
    }

    /*
     * Upstream paretoPruneNodes: dedup on the signature (keeping the sturdier), then drop every state another wearable
     * state ties or beats in damage and every kept dimension; over MAX_PARETO_STATES, the most damaging survive.
     */
    private static List<Node> prune(List<Node> nodes, Search search, Wearer wearer, Map<Short, Piece> worn,
                                    Piece weapon, Valuer valuer, int weaponId, boolean[] dims) {
        if (nodes.size() <= 1) {
            return nodes;
        }
        // Dedup equal vectors, wearable and speculative apart (upstream dedupEquivalentNodes).
        Map<Sig, Node> byKey = new LinkedHashMap<>();
        Map<Node, Boolean> wearableOf = new java.util.IdentityHashMap<>();
        for (Node node : nodes) {
            boolean ok = wearable(node, weapon, wearer, worn);
            wearableOf.put(node, ok);
            Sig key = signature(node, valuer, weaponId, dims, ok);
            Node had = byKey.get(key);
            if (had == null || node.sturdiness > had.sturdiness) {
                byKey.put(key, node);
            }
        }
        // Skyline: most damaging first, so a state is only checked against the wearable states already kept (a
        // dominator has at least its damage, and anything that dominates a pruned state dominates via its pruner).
        List<Node> unique = new ArrayList<>(byKey.values());
        unique.sort(Comparator.comparingDouble((Node x) -> x.damage(valuer, weaponId)).reversed()
                .thenComparing(Comparator.comparingLong((Node x) -> x.sturdiness).reversed()));
        List<Node> kept = new ArrayList<>();
        List<double[]> keptOkVecs = new ArrayList<>();
        List<Long> keptOkSturdy = new ArrayList<>();
        for (Node node : unique) {
            if (kept.size() >= MAX_PARETO_STATES) {
                // Audit 163 #12 (b119): the cap inside the loop. States come most damaging first, so the first
                // MAX_PARETO_STATES kept are exactly the ones the old after-the-loop trim kept; the rest are never
                // compared against the whole kept set (that was the quadratic part).
                break;
            }
            double[] vec = vector(node, valuer, weaponId, dims);
            boolean dominated = false;
            for (int j = 0; j < keptOkVecs.size() && !dominated; j++) {
                dominated = dominates(keptOkVecs.get(j), vec, keptOkSturdy.get(j), node.sturdiness);
            }
            if (!dominated) {
                kept.add(node);
                if (wearableOf.get(node)) {
                    keptOkVecs.add(vec);
                    keptOkSturdy.add(node.sturdiness);
                }
            }
        }
        return kept;
    }

    private record Sig(long damage, int s0, int s1, int s2, int s3, int s4, int s5, int s6, boolean wearable) {
    }

    private static Sig signature(Node node, Valuer valuer, int weaponId, boolean[] dims, boolean wearable) {
        int[] v = new int[DIMS];
        for (int d = 0; d < DIMS; d++) {
            v[d] = dims[d] ? node.stats[d] : 0;
        }
        return new Sig(Math.round(node.damage(valuer, weaponId) * 1000), v[0], v[1], v[2], v[3], v[4], v[5], v[6],
                wearable);
    }

    /*
     * Search.wearable on a DP state without building its map: worn pieces count, then each new piece goes on once
     * the base stats plus what is on meet its STR/DEX/INT/LUK need.
     */
    private static boolean wearable(Node node, Piece weapon, Wearer wearer, Map<Short, Piece> worn) {
        int str = wearer.str(), dex = wearer.dex(), int_ = wearer.int_(), luk = wearer.luk();
        Piece[] pending = new Piece[DP_SLOTS.length + 1];
        int n = 0;
        for (int i = 0; i <= DP_SLOTS.length; i++) {
            Piece p = i < DP_SLOTS.length ? node.picks[i] : weapon;
            short slot = i < DP_SLOTS.length ? DP_SLOTS[i] : BotGearPlanner.WEAPON;
            if (p == null) {
                continue;
            }
            if (worn.get(slot) == p) {
                str += p.str();
                dex += p.dex();
                int_ += p.int_();
                luk += p.luk();
            } else {
                pending[n++] = p;
            }
        }
        boolean progress = true;
        while (n > 0 && progress) {
            progress = false;
            for (int i = 0; i < n; i++) {
                Known k = pending[i].known();
                if (k.reqStr() <= str && k.reqDex() <= dex && k.reqInt() <= int_ && k.reqLuk() <= luk) {
                    str += pending[i].str();
                    dex += pending[i].dex();
                    int_ += pending[i].int_();
                    luk += pending[i].luk();
                    pending[i--] = pending[--n];
                    progress = true;
                }
            }
        }
        return n == 0;
    }

    private static double[] vector(Node node, Valuer valuer, int weaponId, boolean[] dims) {
        int count = 1;
        for (boolean b : dims) {
            if (b) {
                count++;
            }
        }
        double[] v = new double[count];
        int k = 0;
        v[k++] = node.damage(valuer, weaponId);
        for (int d = 0; d < DIMS; d++) {
            if (dims[d]) {
                v[k++] = node.stats[d];
            }
        }
        return v;
    }

    // b dominates a: at least as good in every dimension, and better in one (or as good everywhere and sturdier).
    private static boolean dominates(double[] b, double[] a, long bSturdy, long aSturdy) {
        boolean strict = false;
        for (int i = 0; i < b.length; i++) {
            if (b[i] < a[i] - 1e-9) {
                return false;
            }
            if (b[i] > a[i] + 1e-9) {
                strict = true;
            }
        }
        return strict || bSturdy > aSturdy;
    }

    // ------------------------------------------------------------------ dexless AP build

    /*
     * Upstream chooseSecondaryTarget (BotEquipManager, "Autonomous AP build: secondary-stat target"): the base
     * secondary stat (DEX for a warrior) an AP build should aim for. A secondary point is worth about a quarter of a
     * primary one in the damage formula, so the build raises the secondary only to clear a weapon's need, and only
     * when that weapon out-damages staying on a cheaper one with the AP in the primary. As gear supplies the
     * secondary the target falls to the job's floor ("dexless"). Never below what keeps the held weapon wearable.
     *
     * Ours: AP already spent is never moved (our builds only spend new AP), so the free AP is what is unspent plus
     * what this level-up grants, and the damage is the caller's (DPS of a weapon at given totals).
     *
     * @param cands     each weapon the bot owns or holds: its secondary need and the DPS at given primary/secondary
     *                  base stats
     */
    public record WeaponCand(int reqSecondary, Dps dps) {
    }

    @FunctionalInterface
    public interface Dps {
        double at(int primaryBase, int secondaryBase);
    }

    public static int secondaryTarget(int floorSecondary, int primaryBase, int secondaryBase, int gearSecondary,
                                      int freeAp, List<WeaponCand> cands, int heldReqSecondary) {
        int movable = Math.max(0, freeAp);
        int bestNeed = Math.max(floorSecondary, secondaryBase);
        double bestDps = -1;
        for (WeaponCand w : cands) {
            if (w == null) {
                continue;
            }
            int need = Math.max(secondaryBase, w.reqSecondary() - gearSecondary);
            int secCost = need - secondaryBase;
            if (secCost > movable) {
                continue;   // even all the free AP cannot clear this weapon's need
            }
            double dps = w.dps().at(primaryBase + (movable - secCost), need);
            if (dps > bestDps) {
                bestDps = dps;
                bestNeed = need;
            }
        }
        int antiStrand = Math.max(floorSecondary, heldReqSecondary - gearSecondary);
        return Math.max(Math.max(floorSecondary, bestNeed), antiStrand);
    }
}
