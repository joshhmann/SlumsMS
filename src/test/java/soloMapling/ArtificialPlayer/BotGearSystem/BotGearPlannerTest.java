package soloMapling.ArtificialPlayer.BotGearSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real bots 10: the planner dresses a bot in the best gear it owns that it may wear, chaining stat requirements.
class BotGearPlannerTest {

    private static final short CAP = -1;
    private static final short TOP = -5;
    private static final short PANTS = -6;
    private static final short GLOVES = -8;
    private static final short SHIELD = -10;
    private static final short WEAPON = -11;

    private static final int SWORD = 1302000;
    private static final int SWORD_2H = 1402000;
    private static final int OVERALL = 1050000;
    private static final int TOP_ID = 1040000;
    private static final int PANTS_ID = 1060000;

    // A warrior-ish yardstick: attack counts most, then STR.
    private static final Valuer WARRIOR = (weapon, str, dex, int_, luk, watk, matk) -> watk * 10.0 + str;
    private static final Rules ANY = new Rules(id -> true, id -> !BotGearPlanner.twoHanded(id));

    private static Wearer warrior(int level, int str, int freeSlots) {
        return new Wearer(1, level, 0, str, 4, 4, 4, freeSlots);
    }

    private static int nextPosition = 1;

    private static Piece piece(int itemId, short slot, int reqLevel, int reqStr, int str, int watk) {
        return piece(itemId, slot, reqLevel, 0, reqStr, str, watk, 0);
    }

    private static Piece piece(int itemId, short slot, int reqLevel, int reqJob, int reqStr, int str, int watk,
                               int sturdiness) {
        return new Piece(new Object(), itemId, (short) nextPosition++,
                new Known(slot, reqLevel, reqJob, reqStr, 0, 0, 0, 0), str, 0, 0, 0, watk, 0, sturdiness);
    }

    private static Piece worn(Piece p, short slot) {
        return new Piece(p.item(), p.itemId(), slot, p.known(), p.str(), p.dex(), p.int_(), p.luk(), p.watk(),
                p.matk(), p.sturdiness());
    }

    private static Map<Short, Piece> wearing(Piece... pieces) {
        Map<Short, Piece> worn = new LinkedHashMap<>();
        for (Piece p : pieces) {
            worn.put(p.position(), p);
        }
        return worn;
    }

    @Test
    void takesTheStrongerWeaponItCarries() {
        Piece old = worn(piece(SWORD, WEAPON, 10, 0, 0, 20), WEAPON);
        Piece better = piece(SWORD + 1, WEAPON, 20, 0, 0, 35);
        Piece worse = piece(SWORD + 2, WEAPON, 5, 0, 0, 15);
        Plan plan = BotGearPlanner.plan(warrior(30, 60, 4), wearing(old), List.of(worse, better), WARRIOR, ANY);
        assertSame(better, plan.wear().get(WEAPON));
        assertTrue(plan.after() > plan.before());
    }

    @Test
    void keepsWhatItWearsUnlessSomethingIsStrictlyBetter() {
        Piece cap = worn(piece(1002000, CAP, 10, 0, 0, 0), CAP);
        Piece sameCap = piece(1002001, CAP, 10, 0, 0, 0);
        assertNull(BotGearPlanner.plan(warrior(30, 60, 4), wearing(cap), List.of(sameCap), WARRIOR, ANY));

        Piece sturdier = piece(1002002, CAP, 10, 0, 0, 0, 0, 25);   // same damage, more defence
        assertSame(sturdier, BotGearPlanner.plan(warrior(30, 60, 4), wearing(cap), List.of(sturdier), WARRIOR, ANY)
                .wear().get(CAP));
    }

    @Test
    void neverWearsAboveItsLevelJobOrUnknownGear() {
        Piece old = worn(piece(SWORD, WEAPON, 10, 0, 0, 20), WEAPON);
        Piece tooHigh = piece(SWORD + 1, WEAPON, 31, 0, 0, 90);
        Piece mageOnly = piece(SWORD + 2, WEAPON, 10, 2, 0, 0, 90, 0);
        Piece unknown = new Piece(new Object(), SWORD + 3, (short) 40, null, 0, 0, 0, 0, 90, 0, 0);
        assertNull(BotGearPlanner.plan(warrior(30, 60, 4), wearing(old), List.of(tooHigh, mageOnly, unknown),
                WARRIOR, ANY));
    }

    @Test
    void aWeaponItsSkillsCannotUseIsNeverPicked() {
        Piece old = worn(piece(SWORD, WEAPON, 10, 0, 0, 20), WEAPON);
        Piece bow = piece(1452000, WEAPON, 10, 0, 0, 90);
        Rules swordsOnly = new Rules(id -> id / 10000 == 130, id -> true);
        assertNull(BotGearPlanner.plan(warrior(30, 60, 4), wearing(old), List.of(bow), WARRIOR, swordsOnly));

        // Holding the wrong kind already, a weaker weapon of its own kind still wins.
        Piece wrong = worn(piece(1452001, WEAPON, 10, 0, 0, 90), WEAPON);
        Piece sword = piece(SWORD + 1, WEAPON, 10, 0, 0, 10);
        assertSame(sword, BotGearPlanner.plan(warrior(30, 60, 4), wearing(wrong), List.of(sword), WARRIOR,
                swordsOnly).wear().get(WEAPON));
    }

    @Test
    void chainsAStatRequirementThroughAnotherPiece() {
        // The sword needs 60 STR; the bot has 55, and only the new gloves (+5 STR, no attack) get it there - gloves
        // that alone are worse than the attack gloves it wears.
        Piece oldSword = worn(piece(SWORD, WEAPON, 10, 0, 0, 20), WEAPON);
        Piece oldGloves = worn(piece(1082000, GLOVES, 10, 0, 0, 2), GLOVES);
        Piece bigSword = piece(SWORD + 1, WEAPON, 25, 60, 0, 60);
        Piece strGloves = piece(1082001, GLOVES, 10, 0, 5, 0);

        assertNull(BotGearPlanner.plan(warrior(30, 55, 4), wearing(oldSword, oldGloves), List.of(bigSword),
                WARRIOR, ANY), "the sword alone cannot be worn");

        Plan plan = BotGearPlanner.plan(warrior(30, 55, 4), wearing(oldSword, oldGloves),
                List.of(bigSword, strGloves), WARRIOR, ANY);
        assertSame(bigSword, plan.wear().get(WEAPON));
        assertSame(strGloves, plan.wear().get(GLOVES));
    }

    @Test
    void chainedRequirementsMustBeMetInSomeOrderNotByEachOther() {
        // Two pieces that each need the other's stat cannot be put on from the base stats.
        Piece a = new Piece(new Object(), 1002005, (short) 50, new Known(CAP, 10, 0, 60, 0, 0, 0, 0),
                5, 0, 0, 0, 30, 0, 0);
        Piece b = new Piece(new Object(), 1082005, (short) 51, new Known(GLOVES, 10, 0, 60, 0, 0, 0, 0),
                5, 0, 0, 0, 30, 0, 0);
        assertNull(BotGearPlanner.plan(warrior(30, 55, 4), wearing(), List.of(a, b), WARRIOR, ANY));
    }

    @Test
    void anOverallTakesThePantsOffWhenThereIsRoom() {
        Piece top = worn(piece(TOP_ID, TOP, 10, 0, 1, 0), TOP);
        Piece pants = worn(piece(PANTS_ID, PANTS, 10, 0, 1, 0), PANTS);
        Piece overall = piece(OVERALL, TOP, 10, 0, 10, 0);

        Plan plan = BotGearPlanner.plan(warrior(30, 60, 1), wearing(top, pants), List.of(overall), WARRIOR, ANY);
        assertSame(overall, plan.wear().get(TOP));
        assertFalse(plan.wear().containsKey(PANTS));

        assertNull(BotGearPlanner.plan(warrior(30, 60, 0), wearing(top, pants), List.of(overall), WARRIOR, ANY),
                "no free slot for the pants");
    }

    @Test
    void outOfAnOverallOnlyIntoATopWithPants() {
        Piece overall = worn(piece(OVERALL, TOP, 10, 0, 2, 0), TOP);
        Piece top = piece(TOP_ID, TOP, 10, 0, 5, 0);
        assertNull(BotGearPlanner.plan(warrior(30, 60, 4), wearing(overall), List.of(top), WARRIOR, ANY),
                "a top alone would leave it bare-legged");

        Piece pants = piece(PANTS_ID, PANTS, 10, 0, 5, 0);
        Plan plan = BotGearPlanner.plan(warrior(30, 60, 4), wearing(overall), List.of(top, pants), WARRIOR, ANY);
        assertSame(top, plan.wear().get(TOP));
        assertSame(pants, plan.wear().get(PANTS));
    }

    @Test
    void aTwoHandedWeaponTakesTheShieldOff() {
        Piece sword = worn(piece(SWORD, WEAPON, 10, 0, 0, 20), WEAPON);
        Piece shield = worn(piece(1092000, SHIELD, 10, 0, 0, 1), SHIELD);
        Piece greatSword = piece(SWORD_2H, WEAPON, 10, 0, 0, 40);
        Plan plan = BotGearPlanner.plan(warrior(30, 60, 2), wearing(sword, shield), List.of(greatSword), WARRIOR,
                ANY);
        assertSame(greatSword, plan.wear().get(WEAPON));
        assertFalse(plan.wear().containsKey(SHIELD));

        // ...and a shield never goes on beside one.
        Piece held = worn(greatSword, WEAPON);
        assertNull(BotGearPlanner.plan(warrior(30, 60, 2), wearing(held), List.of(piece(1092001, SHIELD, 10, 0, 0, 5)),
                WARRIOR, ANY));
    }

    @Test
    void theJobBitsFollowTheServersCheck() {
        assertTrue(BotGearPlanner.jobMayWear(0, 0));
        assertFalse(BotGearPlanner.jobMayWear(0, 1), "a beginner wears common gear only");
        assertTrue(BotGearPlanner.jobMayWear(1, 1));
        assertTrue(BotGearPlanner.jobMayWear(4, 8 | 16));
        assertFalse(BotGearPlanner.jobMayWear(2, 1));
        assertEquals(true, BotGearPlanner.twoHanded(1472000), "claws are two-handed");
        assertEquals(false, BotGearPlanner.twoHanded(1372000));
    }
}
