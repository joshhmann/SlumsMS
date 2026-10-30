package soloMapling.ArtificialPlayer.BotGearSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/*
 * Real bots 10: picks the best gear a bot owns, slot by slot, by what its main attack would hit for. Pure: no item
 * data, no character - BotGear hands it the bot's base stats, what it wears, what it carries and how to value a
 * loadout, and applies the answer.
 *
 * After cosmic_agents' AgentEquipmentOptimizer (Kiwiism, AGPL-3.0), cut down for thousands of bots: a bot carries a
 * handful of equips, so instead of its per-weapon Pareto DP this climbs from what the bot wears - one slot at a
 * time, a top with its pants, and a piece together with one other piece whose stats it needs (requirement
 * chaining) - until nothing improves. Its rules are kept:
 *  - every piece must be wearable in some order from the bot's base stats: each one's STR/DEX/INT/LUK need is met
 *    by the base plus the pieces put on before it, as the server's canWearEquipment checks one equip at a time;
 *  - level, job and fame requirements as the server has them (cosmic_agents' EquipRequirementChecker);
 *  - an overall takes the pants slot, a two-handed weapon the shield;
 *  - an equip the item data does not know is refused (Facts answers null) - MapleRoot's custom equips.
 * Our own on top: what the bot already wears counts as wearable (the server never takes gear off a player whose
 * stats fell), a slot is never emptied except by an overall or a two-handed weapon, and a change has to be strictly
 * better - damage first, then defence and the rest - so a bot keeps its look unless it gains something.
 */
public final class BotGearPlanner {

    private BotGearPlanner() {}

    static final short TOP = -5;
    static final short PANTS = -6;
    static final short SHIELD = -10;
    static final short WEAPON = -11;

    /* The body slots the optimizer dresses: cap, face, eye, earring, top, pants, shoes, gloves, cape, shield, weapon. */
    public static final short[] SLOTS = {-1, -2, -3, -4, TOP, PANTS, -7, -8, -9, SHIELD, WEAPON};

    // A bot carrying a pile of one kind of equip is only weighed on the few it could best use.
    static final int MAX_PER_SLOT = 6;
    private static final int MAX_PASSES = 4;

    /* What the item data says about an equip the bot may wear. */
    public record Known(short slot, int reqLevel, int reqJob, int reqStr, int reqDex, int reqInt, int reqLuk,
                        int reqFame) {
    }

    /*
     * One equip: the held item it stands for, where it is now (a body slot when worn, an inventory slot when not),
     * what it adds, and its requirements (null for an equip the bot must not be dressed in).
     */
    public record Piece(Object item, int itemId, short position, Known known, int str, int dex, int int_, int luk,
                        int watk, int matk, int sturdiness, int acc) {
        /* Without accuracy (it only steers the Pareto optimizer, BotGearOptimizer). */
        public Piece(Object item, int itemId, short position, Known known, int str, int dex, int int_, int luk,
                     int watk, int matk, int sturdiness) {
            this(item, itemId, position, known, str, dex, int_, luk, watk, matk, sturdiness, 0);
        }
    }

    /* The bot without its gear: job niche (0 beginner, 1 warrior ... 5 pirate), level, fame, base stats. */
    public record Wearer(int jobNiche, int level, int fame, int str, int dex, int int_, int luk, int freeEquipSlots) {
    }

    /* How good a loadout is: the main attack's average with this weapon and these gear totals. */
    public interface Valuer {
        double value(int weaponItemId, int str, int dex, int int_, int luk, int watk, int matk);
    }

    /* What the bot may hold: a weapon it may fight with, and whether a weapon lets it carry a shield. */
    public record Rules(IntPredicate weaponAllowed, IntPredicate takesShield) {
    }

    /* The loadout to wear (body slot to piece; a missing slot is empty) and the value before and after. */
    public record Plan(Map<Short, Piece> wear, double before, double after) {
    }

    /* The best loadout from what the bot wears and carries, or null when it already wears it. */
    public static Plan plan(Wearer wearer, Map<Short, Piece> worn, List<Piece> owned, Valuer valuer, Rules rules) {
        Search search = new Search(wearer, worn, valuer, rules);
        Map<Short, List<Piece>> pools = search.pools(owned);
        if (pools.isEmpty()) {
            return null;
        }
        Map<Short, Piece> start = new LinkedHashMap<>(worn);
        Score before = search.score(start);
        Map<Short, Piece> best = start;
        Score bestScore = before;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Map<Short, Piece> next = search.bestStep(best, bestScore, pools);
            if (next == null) {
                break;
            }
            best = next;
            bestScore = search.score(best);
        }
        if (best == start) {
            return null;
        }
        return new Plan(best, before.damage, bestScore.damage);
    }

    /*
     * 1.6.1 ticket 30: whether this wearer may wear the piece on its own: its level, job and fame, and each stat need
     * met by its base stats alone (a reborn level 1 checking the gear it still has on).
     */
    static boolean fitsAlone(Wearer w, Known k) {
        return k.reqLevel() <= w.level() && jobMayWear(w.jobNiche(), k.reqJob())
                && (k.reqFame() <= 0 || k.reqFame() <= w.fame()) && k.reqStr() <= w.str() && k.reqDex() <= w.dex()
                && k.reqInt() <= w.int_() && k.reqLuk() <= w.luk();
    }

    /* A two-handed weapon (by weapon class): no shield with it. */
    static boolean twoHanded(int itemId) {
        int cat = itemId / 10000 % 100;
        return itemId / 1000000 == 1 && cat >= 40 && cat <= 49;
    }

    static boolean overall(int itemId) {
        return itemId / 10000 == 105;
    }

    /* cosmic_agents' EquipRequirementChecker.matchesReqJob: 0 is anyone's, else the job niche's bit. */
    static boolean jobMayWear(int jobNiche, int reqJob) {
        if (reqJob == 0) {
            return true;
        }
        int mask = jobNiche >= 1 && jobNiche <= 5 ? 1 << (jobNiche - 1) : 0;
        return (reqJob & mask) != 0;
    }

    record Score(double damage, long sturdiness) {
        boolean beats(Score other) {
            double margin = 1e-9 * Math.max(1.0, Math.abs(other.damage));
            if (damage > other.damage + margin) {
                return true;
            }
            return damage >= other.damage - margin && sturdiness > other.sturdiness;
        }
    }

    static final class Search {
        final Wearer wearer;
        final Map<Short, Piece> worn;
        final Set<Piece> wornPieces;
        final Valuer valuer;
        final Rules rules;

        Search(Wearer wearer, Map<Short, Piece> worn, Valuer valuer, Rules rules) {
            this.wearer = wearer;
            this.worn = worn;
            this.wornPieces = new HashSet<>(worn.values());
            this.valuer = valuer;
            this.rules = rules;
        }

        // Each body slot's candidates: the carried equips the bot may wear there, best-looking first.
        Map<Short, List<Piece>> pools(List<Piece> owned) {
            Map<Short, List<Piece>> pools = new HashMap<>();
            for (Piece p : owned) {
                Known k = p.known();
                if (k == null || !isSlot(k.slot()) || k.reqLevel() > wearer.level()
                        || !jobMayWear(wearer.jobNiche(), k.reqJob()) || (k.reqFame() > 0 && k.reqFame() > wearer.fame())
                        || (k.slot() == WEAPON && !rules.weaponAllowed().test(p.itemId()))) {
                    continue;
                }
                pools.computeIfAbsent(k.slot(), s -> new ArrayList<>()).add(p);
            }
            for (Map.Entry<Short, List<Piece>> e : pools.entrySet()) {
                List<Piece> pool = e.getValue();
                pool.sort(Comparator.comparingInt((Piece p) -> p.known().reqLevel())
                        .thenComparingInt(p -> p.watk() + p.matk() + p.str() + p.dex() + p.int_() + p.luk())
                        .reversed());
                if (pool.size() > MAX_PER_SLOT) {
                    e.setValue(new ArrayList<>(pool.subList(0, MAX_PER_SLOT)));
                }
            }
            return pools;
        }

        static boolean isSlot(short slot) {
            for (short s : SLOTS) {
                if (s == slot) {
                    return true;
                }
            }
            return false;
        }

        // The best loadout one move away (one piece, a top with pants, or a piece with the piece it needs), or null.
        Map<Short, Piece> bestStep(Map<Short, Piece> from, Score fromScore, Map<Short, List<Piece>> pools) {
            Map<Short, Piece> best = null;
            Score bestScore = fromScore;
            List<Map<Short, Piece>> needy = new ArrayList<>();
            for (Map.Entry<Short, List<Piece>> e : pools.entrySet()) {
                for (Piece p : e.getValue()) {
                    Map<Short, Piece> trial = with(from, e.getKey(), p);
                    if (trial == null) {
                        continue;
                    }
                    if (!wearable(trial)) {
                        needy.add(trial);   // may become wearable with one more piece
                        continue;
                    }
                    Score s = score(trial);
                    if (s.beats(bestScore)) {
                        best = trial;
                        bestScore = s;
                    }
                }
            }
            // A top with pants: leaving an overall takes both.
            for (Piece top : pools.getOrDefault(TOP, List.of())) {
                if (overall(top.itemId())) {
                    continue;
                }
                for (Piece pants : pools.getOrDefault(PANTS, List.of())) {
                    Map<Short, Piece> trial = place(from, TOP, top);
                    trial = trial == null ? null : finish(from, place(trial, PANTS, pants));
                    if (trial != null && wearable(trial)) {
                        Score s = score(trial);
                        if (s.beats(bestScore)) {
                            best = trial;
                            bestScore = s;
                        }
                    }
                }
            }
            // Requirement chaining: a piece the bot cannot wear yet, with one other piece that lifts the stat it needs.
            for (Map<Short, Piece> base : needy) {
                for (Map.Entry<Short, List<Piece>> e : pools.entrySet()) {
                    for (Piece helper : e.getValue()) {
                        if (base.get(e.getKey()) == helper || base.containsValue(helper)) {
                            continue;
                        }
                        Map<Short, Piece> trial = with(base, e.getKey(), helper);
                        if (trial == null || !sameNewPieces(base, trial, e.getKey()) || !wearable(trial)) {
                            continue;
                        }
                        Score s = score(trial);
                        if (s.beats(bestScore)) {
                            best = trial;
                            bestScore = s;
                        }
                    }
                }
            }
            return best;
        }

        // The helper must not have replaced the piece it is helping.
        private static boolean sameNewPieces(Map<Short, Piece> base, Map<Short, Piece> trial, short helperSlot) {
            for (Map.Entry<Short, Piece> e : base.entrySet()) {
                if (e.getKey() != helperSlot && trial.get(e.getKey()) != e.getValue()) {
                    return false;
                }
            }
            return true;
        }

        // The loadout with p in slot, or null when it cannot go there with what else is worn.
        Map<Short, Piece> with(Map<Short, Piece> from, short slot, Piece p) {
            return finish(from, place(from, slot, p));
        }

        // p put in slot, with what it takes off; null when it cannot go there at all.
        Map<Short, Piece> place(Map<Short, Piece> from, short slot, Piece p) {
            if (from.get(slot) == p) {
                return null;
            }
            Map<Short, Piece> trial = new LinkedHashMap<>(from);
            trial.values().remove(p);   // a piece is worn in one slot at most
            trial.put(slot, p);
            if (slot == TOP && overall(p.itemId())) {
                trial.remove(PANTS);
            } else if (slot == PANTS) {
                Piece top = trial.get(TOP);
                if (top != null && overall(top.itemId())) {
                    return null;   // the overall goes with a top (the pair move)
                }
            } else if (slot == WEAPON) {
                if (twoHanded(p.itemId()) || !rules.takesShield().test(p.itemId())) {
                    trial.remove(SHIELD);
                }
            } else if (slot == SHIELD) {
                Piece weapon = trial.get(WEAPON);
                if (weapon == null || twoHanded(weapon.itemId()) || !rules.takesShield().test(weapon.itemId())) {
                    return null;
                }
            }
            return trial;
        }

        // The trial if the bot may end up in it from `from`: not bare-legged, and room for what it takes off.
        Map<Short, Piece> finish(Map<Short, Piece> from, Map<Short, Piece> trial) {
            if (trial == null) {
                return null;
            }
            if (bareLegged(trial) && !bareLegged(from)) {
                return null;   // never out of an overall into a top with no pants
            }
            // Only an overall or a two-handed weapon empties a slot, and what it takes off needs a free inventory slot.
            int movedIn = 0;
            for (Piece q : trial.values()) {
                if (!wornPieces.contains(q)) {
                    movedIn++;
                }
            }
            int movedOut = 0;
            for (Piece q : wornPieces) {
                if (!trial.containsValue(q)) {
                    movedOut++;
                }
            }
            return movedOut - movedIn <= wearer.freeEquipSlots() ? trial : null;
        }

        static boolean bareLegged(Map<Short, Piece> loadout) {
            Piece top = loadout.get(TOP);
            return loadout.get(PANTS) == null && (top == null || !overall(top.itemId()));
        }

        /*
         * Whether the bot can put every piece on in some order: what it wears already stays on, then each new piece
         * goes on once the base stats plus the pieces before it meet its STR/DEX/INT/LUK need.
         */
        boolean wearable(Map<Short, Piece> loadout) {
            int str = wearer.str();
            int dex = wearer.dex();
            int int_ = wearer.int_();
            int luk = wearer.luk();
            List<Piece> pending = new ArrayList<>();
            for (Map.Entry<Short, Piece> e : loadout.entrySet()) {
                Piece p = e.getValue();
                if (worn.get(e.getKey()) == p) {
                    str += p.str();
                    dex += p.dex();
                    int_ += p.int_();
                    luk += p.luk();
                } else {
                    pending.add(p);
                }
            }
            boolean progress = true;
            while (!pending.isEmpty() && progress) {
                progress = false;
                for (int i = 0; i < pending.size(); i++) {
                    Piece p = pending.get(i);
                    Known k = p.known();
                    if (k.reqStr() <= str && k.reqDex() <= dex && k.reqInt() <= int_ && k.reqLuk() <= luk) {
                        str += p.str();
                        dex += p.dex();
                        int_ += p.int_();
                        luk += p.luk();
                        pending.remove(i--);
                        progress = true;
                    }
                }
            }
            return pending.isEmpty();
        }

        Score score(Map<Short, Piece> loadout) {
            int str = 0;
            int dex = 0;
            int int_ = 0;
            int luk = 0;
            int watk = 0;
            int matk = 0;
            long sturdiness = 0;
            for (Piece p : loadout.values()) {
                str += p.str();
                dex += p.dex();
                int_ += p.int_();
                luk += p.luk();
                watk += p.watk();
                matk += p.matk();
                sturdiness += p.sturdiness();
            }
            Piece weapon = loadout.get(WEAPON);
            if (weapon != null && !rules.weaponAllowed().test(weapon.itemId())) {
                // A weapon its skills cannot use (a changed job, the casual look) loses to any it may fight with.
                return new Score(-1, sturdiness);
            }
            double damage = valuer.value(weapon != null ? weapon.itemId() : 0, str, dex, int_, luk, watk, matk);
            return new Score(damage, sturdiness);
        }
    }
}
