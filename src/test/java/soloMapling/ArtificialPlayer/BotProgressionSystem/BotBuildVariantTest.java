package soloMapling.ArtificialPlayer.BotProgressionSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Choice;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Variant;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 161 item 1: AP build variants (dexless, lukless, strless, pure, "25 dex") as a trait on the build. */
class BotBuildVariantTest {

    private static final BotBuild.SkillCatalog PLAN = BotBuild.SkillCatalog.PLAN;

    private static BotBuild.State build(int job, int level, Variant v) {
        return BotBuild.plan(job, level, new Choice(0, 7, v), PLAN);
    }

    private static int totalPoints(BotBuild.State s) {
        return s.str + s.dex + s.int_ + s.luk + s.remainingAp;
    }

    @Test
    void theStandardBuildIsUnchangedByTheNewField() {
        BotBuild.State a = BotBuild.plan(100, 100, new Choice(0, 7), PLAN);
        BotBuild.State b = build(100, 100, Variant.STANDARD);
        assertEquals(a.str, b.str);
        assertEquals(a.dex, b.dex);
        assertEquals(54, a.dex, "warrior dex is 4 + level/2, as before");
    }

    @Test
    void aDexlessWarriorKeepsDexAtFourAndPutsTheRestInStr() {
        BotBuild.State standard = build(100, 100, Variant.STANDARD);
        BotBuild.State s = build(100, 100, Variant.LESS);
        assertEquals(4, s.dex);
        assertEquals(standard.str + standard.dex - 4, s.str, "the dex points went to str");
        assertEquals(totalPoints(standard), totalPoints(s), "same AP in total");
    }

    @Test
    void aLuklessMageAndAStrlessBowmanKeepTheirSecondaryAtFour() {
        assertEquals(4, build(200, 100, Variant.LESS).luk);
        assertEquals(4, build(300, 100, Variant.LESS).str);
        assertEquals(4, build(300, 100, Variant.PURE).str);
        assertTrue(build(200, 100, Variant.LESS).int_ > build(200, 100, Variant.STANDARD).int_);
    }

    @Test
    void aThiefCannotGoBelowTheFirstJobDexOf25AndABrawlerBelow20() {
        assertEquals(25, build(400, 120, Variant.LESS).dex);
        assertEquals(25, build(400, 120, Variant.PURE).dex);
        assertEquals(20, BotBuild.plan(500, 120, new Choice(510, 7, Variant.LESS), PLAN).dex);
    }

    @Test
    void the25Build_followsTheStandardRuleUntilItReaches25ThenStops() {
        assertEquals(build(100, 20, Variant.STANDARD).dex, build(100, 20, Variant.FLAT25).dex, "level 20: 14 either way");
        assertEquals(25, build(100, 100, Variant.FLAT25).dex);
        assertEquals(25, build(200, 100, Variant.FLAT25).luk);
        assertEquals(25, build(300, 100, Variant.FLAT25).str);
        assertEquals(25, build(1100, 100, Variant.FLAT25).dex, "Cygnus classes follow the same rule");
    }

    @Test
    void everyVariantSpendsAllItsPointsAndKeepsTheSameJobAndSkills() {
        for (int job : new int[]{100, 200, 300, 400, 500, 1100, 1200, 1300, 1400, 1500, 2100}) {
            BotBuild.State std = build(job, 150, Variant.STANDARD);
            for (Variant v : Variant.values()) {
                BotBuild.State s = build(job, 150, v);
                assertEquals(std.job, s.job, job + " " + v);
                assertEquals(std.skills, s.skills, job + " " + v);
                assertEquals(totalPoints(std), totalPoints(s), job + " " + v);
                assertEquals(0, s.remainingAp, job + " " + v);
            }
        }
    }

    @Test
    void variantsAreNamedTheWayPlayersSayThem() {
        assertEquals("dexless", BotBuild.variantName(100, new Choice(0, 7, Variant.LESS)));
        assertEquals("lukless", BotBuild.variantName(200, new Choice(0, 7, Variant.LESS)));
        assertEquals("strless", BotBuild.variantName(300, new Choice(0, 7, Variant.LESS)));
        assertEquals("pure", BotBuild.variantName(100, new Choice(0, 7, Variant.PURE)));
        assertEquals("25 dex", BotBuild.variantName(100, new Choice(0, 7, Variant.FLAT25)));
        assertEquals("25 luk", BotBuild.variantName(200, new Choice(0, 7, Variant.FLAT25)));
        assertEquals("standard", BotBuild.variantName(100, new Choice(0, 7)));
    }

    @Test
    void theRollIsTheSameEveryTimeAndFollowsTheSharesPerClass() {
        Map<Variant, Integer> warriors = new EnumMap<>(Variant.class);
        Map<Variant, Integer> thieves = new EnumMap<>(Variant.class);
        int n = 20_000;
        for (int id = 0; id < n; id++) {
            Variant w = BotBuild.rollVariant(100, new Choice(0, id), id, 0);
            assertEquals(w, BotBuild.rollVariant(100, new Choice(0, id), id, 0), "same bot, same roll");
            warriors.merge(w, 1, Integer::sum);
            thieves.merge(BotBuild.rollVariant(400, new Choice(0, id), id, 0), 1, Integer::sum);
        }
        assertEquals(0.55, warriors.get(Variant.STANDARD) / (double) n, 0.02);
        assertEquals(0.22, warriors.get(Variant.LESS) / (double) n, 0.02);
        assertEquals(0.08, warriors.get(Variant.PURE) / (double) n, 0.02);
        assertEquals(0.15, warriors.get(Variant.FLAT25) / (double) n, 0.02);
        assertFalse(thieves.containsKey(Variant.PURE) || thieves.containsKey(Variant.FLAT25),
                "a thief's dex is pinned at 25 either way: only dexless or standard");
        assertEquals(0.40, thieves.get(Variant.LESS) / (double) n, 0.02);
    }

    @Test
    void aRebirthRollsAgain() {
        boolean differs = false;
        for (int id = 0; id < 200 && !differs; id++) {
            differs = BotBuild.rollVariant(100, new Choice(0, id), id, 0) != BotBuild.rollVariant(100, new Choice(0, id), id, 1);
        }
        assertTrue(differs, "the rebirth count is part of the roll");
    }

    @Test
    void aJobTheBuildDoesNotCoverRollsStandard() {
        assertEquals(Variant.STANDARD, BotBuild.rollVariant(900, new Choice(0, 1), 1, 0));
    }

    @Test
    void onlyNewSlotsAndRebornBotsFollowAVariantAndNeverWhenTheSwitchIsOff() {
        int base = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
        assertFalse(BotProgression.variantsFor(false, base + 9000, 2, 0), "switch off");
        assertFalse(BotProgression.variantsFor(true, base + 10, 0, 5000), "an old slot keeps its build");
        assertTrue(BotProgression.variantsFor(true, base + 5000, 0, 5000), "a new slot rolls");
        assertTrue(BotProgression.variantsFor(true, base + 10, 1, 5000), "a rebirth rebuilds from level 1");
        assertFalse(BotProgression.variantsFor(true, base + 10, 0, Integer.MAX_VALUE), "no cut-off set: only rebirths");
    }

    @Test
    void theSwitchesDefaultToOff() {
        assertFalse(soloMapling.ArtificialPlayer.BotSupportSwitches.apVariants());
        assertFalse(soloMapling.ArtificialPlayer.BotSupportSwitches.partySupport());
        assertFalse(soloMapling.ArtificialPlayer.BotSupportSwitches.fameRequest());
        assertFalse(soloMapling.ArtificialPlayer.BotSupportSwitches.magicGuard());
    }
}
