package soloMapling.ArtificialPlayer.BotGearSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 162 part 1: the port of nutnnut's Pareto optimizer keeps every rule of the climb and finds what it cannot.
class BotGearOptimizerTest {

    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short SHOES = -7;
    private static final short GLOVES = -8;
    private static final short CAPE = -9;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;

    private static final int SWORD = 1302000;
    private static final int SWORD_2H = 1402000;
    private static final int OVERALL = 1050000;
    private static final int TOP_ID = 1040000;
    private static final int PANTS_ID = 1060000;

    private static final Valuer WARRIOR = (weapon, str, dex, int_, luk, watk, matk) -> watk * 10.0 + str;
    // A superadditive yardstick, like the real damage formula: (4 x STR + DEX) x attack.
    private static final Valuer FORMULA = (weapon, str, dex, int_, luk, watk, matk) ->
            (4.0 * (60 + str) + (20 + dex)) * (watk + 1) / 100.0;
    private static final Rules ANY = new Rules(id -> true, id -> !BotGearPlanner.twoHanded(id));

    private static int nextPosition = 1;

    private static Wearer warrior(int level, int str, int freeSlots) {
        return new Wearer(1, level, 0, str, 4, 4, 4, freeSlots);
    }

    private static Piece piece(int itemId, short slot, int reqStr, int str, int watk) {
        return new Piece(new Object(), itemId, (short) nextPosition++,
                new Known(slot, 10, 0, reqStr, 0, 0, 0, 0), str, 0, 0, 0, watk, 0, 0);
    }

    private static Piece piece(int itemId, short slot, int reqStr, int reqDex, int str, int dex, int watk) {
        return new Piece(new Object(), itemId, (short) nextPosition++,
                new Known(slot, 10, 0, reqStr, reqDex, 0, 0, 0), str, dex, 0, 0, watk, 0, 0);
    }

    private static Piece worn(Piece p, short slot) {
        return new Piece(p.item(), p.itemId(), slot, p.known(), p.str(), p.dex(), p.int_(), p.luk(), p.watk(),
                p.matk(), p.sturdiness(), p.acc());
    }

    private static Map<Short, Piece> wearing(Piece... pieces) {
        Map<Short, Piece> worn = new LinkedHashMap<>();
        for (Piece p : pieces) {
            worn.put(p.position(), p);
        }
        return worn;
    }

    @Test
    void chainsARequirementThroughTwoPiecesTheClimbCannotSee() {
        // The sword needs 65 STR; the bot has 55. Only the new gloves AND the new shoes (+5 STR each, no attack, each
        // worse alone than what it wears) get it there. The climb tries a piece with one helper at most.
        Piece oldSword = worn(piece(SWORD, WEAPON, 0, 0, 20), WEAPON);
        Piece oldGloves = worn(piece(1082000, GLOVES, 0, 0, 2), GLOVES);
        Piece oldShoes = worn(piece(1072000, SHOES, 0, 0, 1), SHOES);
        Piece sword = piece(SWORD + 1, WEAPON, 65, 0, 40);
        Piece strGloves = piece(1082001, GLOVES, 0, 5, 0);
        Piece strShoes = piece(1072001, SHOES, 0, 5, 0);
        Map<Short, Piece> worn = wearing(oldSword, oldGloves, oldShoes);
        List<Piece> owned = List.of(sword, strGloves, strShoes);

        assertNull(BotGearPlanner.plan(warrior(50, 55, 4), worn, owned, WARRIOR, ANY), "the climb misses it");
        Plan plan = BotGearOptimizer.plan(warrior(50, 55, 4), worn, owned, WARRIOR, ANY);
        assertNotNull(plan);
        assertSame(sword, plan.wear().get(WEAPON));
        assertSame(strGloves, plan.wear().get(GLOVES));
        assertSame(strShoes, plan.wear().get(SHOES));
        assertTrue(plan.after() > plan.before());
    }

    @Test
    void weighsEveryCarriedPieceNotJustTheFirstSix() {
        // Seven caps; the best one sorts last on the climb's level-then-stats shortlist (level 1, small stats) but
        // gives the most damage under a formula where DEX matters.
        Piece oldCap = worn(piece(1002000, CAP, 0, 0, 0), CAP);
        Piece sword = worn(piece(SWORD, WEAPON, 0, 0, 30), WEAPON);
        List<Piece> owned = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            owned.add(new Piece(new Object(), 1002010 + i, (short) nextPosition++,
                    new Known(CAP, 30, 0, 0, 0, 0, 0, 0), 0, 0, 9, 9, 0, 0, 0));   // INT/LUK: useless to it
        }
        Piece dexCap = new Piece(new Object(), 1002099, (short) nextPosition++,
                new Known(CAP, 1, 0, 0, 0, 0, 0, 0), 0, 8, 0, 0, 0, 0, 0);
        owned.add(dexCap);
        Map<Short, Piece> worn = wearing(oldCap, sword);
        assertNull(BotGearPlanner.plan(warrior(40, 60, 4), worn, owned, FORMULA, ANY), "the climb's shortlist drops it");
        assertSame(dexCap, BotGearOptimizer.plan(warrior(40, 60, 4), worn, owned, FORMULA, ANY).wear().get(CAP));
    }

    @Test
    void keepsWhatItWearsUnlessSomethingIsStrictlyBetter() {
        Piece cap = worn(piece(1002000, CAP, 0, 0, 0), CAP);
        Piece sameCap = piece(1002001, CAP, 0, 0, 0);
        assertNull(BotGearOptimizer.plan(warrior(30, 60, 4), wearing(cap), List.of(sameCap), WARRIOR, ANY));
    }

    @Test
    void neverPicksAWeaponItsSkillsCannotUse() {
        Piece old = worn(piece(SWORD, WEAPON, 0, 0, 20), WEAPON);
        Piece bow = piece(1452000, WEAPON, 0, 0, 90);
        Rules swordsOnly = new Rules(id -> id / 10000 == 130, id -> true);
        assertNull(BotGearOptimizer.plan(warrior(30, 60, 4), wearing(old), List.of(bow), WARRIOR, swordsOnly));
    }

    @Test
    void mutuallyDependentPiecesNeedARealWearOrder() {
        // Each needs 60 DEX and gives 5; the bot has 55. Upstream's "meets its need without itself" check passes both;
        // the server would refuse the first one put on.
        Piece a = piece(1002005, CAP, 0, 60, 0, 5, 5);
        Piece b = piece(1082005, GLOVES, 0, 60, 0, 5, 5);
        Wearer w = new Wearer(1, 30, 0, 60, 55, 4, 4, 4);
        assertNull(BotGearOptimizer.plan(w, wearing(), List.of(a, b), WARRIOR, ANY));
    }

    @Test
    void anOverallTakesThePantsOffOnlyWithRoomInTheBag() {
        Piece top = worn(piece(TOP_ID, TOP, 0, 0, 1), TOP);
        Piece pants = worn(piece(PANTS_ID, PANTS, 0, 0, 1), PANTS);
        Piece overall = piece(OVERALL, TOP, 0, 0, 5);
        Plan plan = BotGearOptimizer.plan(warrior(30, 60, 1), wearing(top, pants), List.of(overall), WARRIOR, ANY);
        assertSame(overall, plan.wear().get(TOP));
        assertFalse(plan.wear().containsKey(PANTS));
        assertNull(BotGearOptimizer.plan(warrior(30, 60, 0), wearing(top, pants), List.of(overall), WARRIOR, ANY),
                "a full bag has no room for the pants");
    }

    @Test
    void aTwoHandedWeaponTakesTheShieldOff() {
        Piece sword = worn(piece(SWORD, WEAPON, 0, 0, 20), WEAPON);
        Piece shield = worn(piece(1092000, SHIELD, 0, 0, 1), SHIELD);
        Piece big = piece(SWORD_2H, WEAPON, 0, 0, 40);
        Plan plan = BotGearOptimizer.plan(warrior(30, 60, 2), wearing(sword, shield), List.of(big), WARRIOR, ANY);
        assertSame(big, plan.wear().get(WEAPON));
        assertFalse(plan.wear().containsKey(SHIELD));
    }

    @Test
    void neverEmptiesASlotItWears() {
        // Taking the cape off would not lose damage, but a bot never strips a slot without a replacement.
        Piece cape = worn(piece(1102000, CAPE, 0, 0, 0), CAPE);
        Piece sword = worn(piece(SWORD, WEAPON, 0, 0, 20), WEAPON);
        Piece better = piece(SWORD + 1, WEAPON, 0, 0, 30);
        Plan plan = BotGearOptimizer.plan(warrior(30, 60, 4), wearing(cape, sword), List.of(better), WARRIOR, ANY);
        assertSame(cape, plan.wear().get(CAPE));
        assertSame(better, plan.wear().get(WEAPON));
    }

    @Test
    void neverDoesWorseThanTheClimb() {
        // Random bags: the optimizer's pick scores at least the climb's on every one.
        java.util.Random rng = new java.util.Random(162);
        for (int round = 0; round < 200; round++) {
            Wearer w = new Wearer(1, 50, 0, 40 + rng.nextInt(30), 4 + rng.nextInt(30), 4, 4, rng.nextInt(3));
            Map<Short, Piece> worn = new LinkedHashMap<>();
            worn.put(WEAPON, worn(piece(SWORD, WEAPON, 0, 0, 10 + rng.nextInt(10)), WEAPON));
            List<Piece> owned = new ArrayList<>();
            short[] slots = {CAP, TOP, PANTS, SHOES, GLOVES, CAPE, SHIELD, WEAPON};
            for (int i = 0; i < 12; i++) {
                short slot = slots[rng.nextInt(slots.length)];
                int id = slot == WEAPON ? SWORD + 1 + i : slot == TOP && rng.nextBoolean() ? OVERALL + i
                        : 1000000 + (-slot) * 10000 + i;
                owned.add(piece(id, slot, rng.nextInt(80), rng.nextInt(50), rng.nextInt(8), rng.nextInt(8),
                        rng.nextInt(slot == WEAPON ? 40 : 4)));
            }
            Plan climb = BotGearPlanner.plan(w, worn, owned, FORMULA, ANY);
            Plan pareto = BotGearOptimizer.plan(w, worn, owned, FORMULA, ANY);
            double climbAfter = climb == null ? Double.NEGATIVE_INFINITY : climb.after();
            double paretoAfter = pareto == null ? Double.NEGATIVE_INFINITY : pareto.after();
            assertTrue(paretoAfter >= climbAfter - 1e-9, "round " + round + ": " + paretoAfter + " < " + climbAfter);
        }
    }

    @Test
    void dexlessTargetRaisesTheSecondaryOnlyForAWeaponWorthIt() {
        // A warrior with 20 free AP. Weapon A needs 25 DEX base (15 more), weapon B none. DPS: (4 x STR + DEX) x watk.
        BotGearOptimizer.Dps a = (str, dex) -> (4.0 * str + dex) * 60;
        BotGearOptimizer.Dps b = (str, dex) -> (4.0 * str + dex) * 40;
        // A at 25 DEX: STR 50+5 -> (220+25)*60 = 14,700; B with all 20 in STR: (280+10)*40 = 11,600 -> A wins.
        assertEquals(25, BotGearOptimizer.secondaryTarget(4, 50, 10, 0, 20,
                List.of(new BotGearOptimizer.WeaponCand(25, a), new BotGearOptimizer.WeaponCand(0, b)), 0));
        // Gear gives the 15 DEX: the target stays at the base (dexless).
        assertEquals(10, BotGearOptimizer.secondaryTarget(4, 50, 10, 15, 20,
                List.of(new BotGearOptimizer.WeaponCand(25, a), new BotGearOptimizer.WeaponCand(0, b)), 0));
        // Never below what keeps the held weapon on.
        assertEquals(30, BotGearOptimizer.secondaryTarget(4, 50, 10, 0, 20,
                List.of(new BotGearOptimizer.WeaponCand(0, b)), 30));
    }
}
