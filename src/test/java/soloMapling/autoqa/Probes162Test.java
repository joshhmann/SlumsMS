package soloMapling.autoqa;

import client.QuestStatus;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 (ticket 163 section 4): the checks for the bot switches that had no auto-QA proof; verdicts and wiring. */
class Probes162Test {

    static final List<String> NEW = List.of("t-bq-1", "t-tm-1", "t-gb-1", "t-gb-2", "t-gb-3", "t-gb-4", "t-gb-5", "t-gb-6", "t-su-1", "t-su-2", "t-su-3", "t-jb-1", "t-jb-2", "t-nn-1", "t-nn-2");

    @Test
    void theNewChecksAreAutomatedNightlyAndUseOnlyKnownProbes() {
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        for (String id : NEW) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id);
            assertEquals(id.startsWith("t-nn-") ? Check.Kind.HALF : Check.Kind.AUTO, c.kind(), id);
            assertTrue(c.nightly(), id + " stages nothing lasting");
            int probes = 0;
            for (Check.Expect e : c.expects()) {
                Matcher m = probe.matcher(e.describe());
                if (m.find()) {
                    assertTrue(Probes162.NAMES.contains(m.group(1)), id + " probes unknown " + m.group(1));
                    probes++;
                }
            }
            assertTrue(probes > 0, id);
        }
        assertNull(Probes162.probe("noSuchProbe", null), "an unknown name falls through to GameAutoQaWorld's FAIL");
        for (String n : Probes162.NAMES) {
            assertNull(Probes160.probe(n, null), n + " is not also a 1.6.0 probe name");
        }
    }

    // ── t-bq-1 ──

    @Test
    void aQuestRunPassesOnlyWhenTakenHuntedAndCompletedForReal() {
        assertTrue(Probes162.questProblems(BotQuests.Visit.STARTED, QuestStatus.Status.STARTED, true,
                BotQuests.Visit.COMPLETED, QuestStatus.Status.COMPLETED).isEmpty());
        List<String> offscreen = Probes162.questProblems(BotQuests.Visit.TAKEN_OFFSCREEN,
                QuestStatus.Status.NOT_STARTED, false, BotQuests.Visit.RECORDED, QuestStatus.Status.NOT_STARTED);
        assertEquals(5, offscreen.size(), offscreen.toString());
        assertTrue(offscreen.get(3).contains("recorded only"), offscreen.toString());
        List<String> capped = Probes162.questProblems(BotQuests.Visit.STARTED, QuestStatus.Status.STARTED, true,
                BotQuests.Visit.DEFERRED, QuestStatus.Status.STARTED);
        assertTrue(capped.get(0).contains("yield caps"), capped.toString());
    }

    // ── t-tm-1 ──

    @Test
    void theTempleCheckWantsTheNextQuestItsLaneAndTheGate() {
        assertTrue(Probes162.templeProblems(3500, null, 3501, 270010100, 270010100, true, false).isEmpty());
        assertTrue(Probes162.templeProblems(0, null, 0, -1, 270010100, true, false).get(0).contains("nothing"));
        List<String> noLane = Probes162.templeProblems(3500, null, 3501, -1, 270010100, true, false);
        assertEquals(1, noLane.size());
        assertTrue(noLane.get(0).contains("no lane entry"));
        List<String> leak = Probes162.templeProblems(3500, null, 3501, 270010100, 270010100, true, true);
        assertTrue(leak.get(0).contains("corridor gate lets"), leak.toString());
        List<String> loop = Probes162.templeProblems(3500, "take STARTED, turn-in NOT_READY", 3500, -1, 270010100,
                true, false);
        assertEquals(2, loop.size(), "the step that could not finish is offered again (audit B2)");
    }

    // ── t-gb-1 ──

    @Test
    void theParetoCheckWantsTheWholeChainWorn() {
        assertTrue(Probes162.chainProblems(2, 4, 6, 2, 4, 6, true).isEmpty());
        List<String> climb = Probes162.chainProblems(1, 3, 5, 2, 4, 6, false);
        assertEquals(3, climb.size(), climb.toString());
        List<String> half = Probes162.chainProblems(1, 4, 5, 2, 4, 6, true);
        assertTrue(half.get(1).contains("both are needed"), half.toString());
    }

    // ── t-gb-2 ──

    @Test
    void theDexlessCheckWantsTheSecondaryStoppedAtTheCap() {
        // warrior lv 50: class rule DEX 29, floor 4, a sword needing 10 DEX: 25 AP from DEX 4 -> 6 to DEX, 19 to STR
        assertEquals(10, Probes162.expectedSecondary(4, 29, 4, 10, 25));
        assertTrue(Probes162.dexlessProblems(10, 29, 4, 4, 10, 19, 25).isEmpty());
        List<String> uncapped = Probes162.dexlessProblems(10, 29, 4, 4, 29, 0, 25);
        assertEquals(1, uncapped.size(), uncapped.toString());
        assertEquals(2, Probes162.dexlessProblems(10, 29, 4, 4, 29, 25, 25).size(), "AP made up from nowhere");
        assertTrue(uncapped.get(0).contains("want 10"), uncapped.toString());
        assertTrue(Probes162.dexlessProblems(-1, 29, 4, 4, 29, 0, 25).get(0).contains("no gear cap"));
        assertTrue(Probes162.dexlessProblems(30, 29, 4, 4, 29, 0, 25).get(0).contains("does not bind"));
        assertEquals(12, Probes162.expectedSecondary(12, 29, 4, 10, 25), "AP never lowers a stat");
    }

    // ── t-gb-3 ──

    @Test
    void theGearFirstCheckWantsTheRarestDropAndAMapThatDropsIt() {
        java.util.Map<Integer, java.util.Set<Integer>> drops = new java.util.LinkedHashMap<>();
        drops.put(1, java.util.Set.of(1040000, 1302000));
        drops.put(2, java.util.Set.of(1040000));
        drops.put(3, java.util.Set.of(1040000, 1060000));
        assertEquals(1060000, Probes162.rarestDrop(drops), "lowest id among those on one map");
        assertEquals(0, Probes162.rarestDrop(java.util.Map.of(1, java.util.Set.of())));
        var at3 = new soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap(3, 20, 2, -1);
        assertTrue(Probes162.farmProblems(at3, true, java.util.Set.of(3), 1, 34).isEmpty());
        var at2 = new soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap(2, 40, 2, -1);
        assertEquals(3, Probes162.farmProblems(at2, false, java.util.Set.of(3), 1, 34).size());
        assertEquals(1, Probes162.farmProblems(null, false, java.util.Set.of(3), 1, 34).size());
    }

    /** TEST 2026-10-30: from the Free Market entrance the search found 0 maps; the probe searches from Henesys. */
    @Test
    void routedProbesStageOutsideTheFreeMarket() {
        assertEquals(100000000, Probes162.PROBE_FIELD, "Henesys: a Victoria town with field maps all round");
    }

    // ── t-gb-4 ──

    @Test
    void theJunkCheckFailsWhenAKeptPieceIsSold() {
        assertTrue(Probes162.junkProblems(14, 0, 0, 0, 2).isEmpty());
        assertTrue(Probes162.junkProblems(14, 2, 0, 0, 4).get(0).contains("sold 2 kept"));
        assertEquals(2, Probes162.junkProblems(0, 0, 14, 0, 4).size(), "nothing kept, shelf lost");
        assertTrue(Probes162.junkProblems(14, 0, 0, 2, 0).get(1).contains("sold nothing"));
    }

    // ── t-gb-5 ──

    @Test
    void theEvScrollCheckWantsCarefulDeclinesAndAGamble() {
        assertTrue(Probes162.scrollEvProblems(2043000, 0, 2043004, 0, 2043004).isEmpty());
        assertTrue(Probes162.scrollEvProblems(2043000, 0, 0, 0, 0).isEmpty(), "part B not judged");
        assertTrue(Probes162.scrollEvProblems(2043000, 2043000, 0, 0, 0).get(0).contains("careful bot"));
        List<String> blind = Probes162.scrollEvProblems(2043000, 0, 2043004, 0, 0);
        assertEquals(1, blind.size());
        assertTrue(blind.get(0).contains("gambler declined"), blind.toString());
        assertEquals(1, Probes162.scrollEvProblems(2043000, 0, 2043004, 2043004, 2043004).size());
    }

    // ── t-gb-6 ──

    @Test
    void theApVariantCheckFailsOnAnAutoThisBuildCannotRead() {
        assertTrue(Probes162.apVariantProblems("auto", 3001, false, true, 40, 1000).isEmpty());
        assertTrue(Probes162.apVariantProblems("auto", Integer.MAX_VALUE, false, false, 0, 0).get(0)
                .contains("reads fromSlot=auto as unset"));
        assertTrue(Probes162.apVariantProblems(null, Integer.MAX_VALUE, false, false, 0, 0).get(0).contains("unset"));
        assertEquals(3, Probes162.apVariantProblems("auto", 3001, true, false, 0, 1000).size());
    }

    @Test
    void theSeamFollowsTheSwitchAndTheSlot() {
        int base = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE;
        try {
            System.setProperty("slums.bots.apvariants", "true");
            System.setProperty("slums.bots.apvariants.fromSlot", "5000");
            assertTrue(soloMapling.ArtificialPlayer.BotProgressionSystem.ApVariantQa.rolls(base + 5000, 0));
            assertFalse(soloMapling.ArtificialPlayer.BotProgressionSystem.ApVariantQa.rolls(base + 4999, 0));
            assertTrue(soloMapling.ArtificialPlayer.BotProgressionSystem.ApVariantQa.rolls(base + 10, 1), "reborn");
            System.setProperty("slums.bots.apvariants", "false");
            assertFalse(soloMapling.ArtificialPlayer.BotProgressionSystem.ApVariantQa.rolls(base + 5000, 0));
        } finally {
            System.clearProperty("slums.bots.apvariants");
            System.clearProperty("slums.bots.apvariants.fromSlot");
        }
    }

    // ── t-su-1 ──

    @Test
    void theHealCheckWantsATickAndMoreHp() {
        assertTrue(Probes162.healProblems(true, true, 400, 700, 1000).isEmpty());
        assertEquals(1, Probes162.healProblems(false, false, 400, 400, 1000).size());
        assertEquals(2, Probes162.healProblems(true, false, 400, 400, 1000).size());
    }

    // ── t-su-2 ──

    @Test
    void theFameCheckWantsOneFameAndARefusal() {
        assertTrue(Probes162.fameProblems(false, 0, true, 1, true, 0).isEmpty());
        assertTrue(soloMapling.ArtificialPlayer.BotFameRequest.isFameRequest(Probes162.SU2_ASK));
        assertEquals(1, Probes162.fameProblems(false, 0, true, 1, true, 1).size(), "farmed twice");
        assertEquals(1, Probes162.fameProblems(true, 1, true, 1, true, 0).size(), "a stranger famed");
        assertEquals(1, Probes162.fameProblems(false, 0, false, 0, true, 0).size());
    }

    // ── t-su-3 ──

    @Test
    void theMagicGuardCheckWantsTheSplitOnlyWhileUp() {
        int[] want = soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.magicGuardSplit(100, 80, 500);
        assertTrue(Probes162.guardProblems(100, new int[]{100, 0}, want, want).isEmpty());
        assertEquals(1, Probes162.guardProblems(100, new int[]{100, 0}, new int[]{100, 0}, want).size(), "never split");
        assertEquals(1, Probes162.guardProblems(100, new int[]{20, 80}, want, want).size(), "split with none up");
    }

    // ── t-jb-1 ──

    @Test
    void theErrandCheckReadsTheCountersAndWantsAnAdvance() {
        long[] c = Probes162.errandCounts("jobErrand started=3 arrived=1 fellBack=2 blocked=0 active=1");
        assertEquals(List.of(3L, 1L, 2L, 0L, 1L), java.util.Arrays.stream(c).boxed().toList());
        assertNull(Probes162.errandCounts("nothing"));
        assertTrue(Probes162.errandProblems(true, true, 1, 0, 0, 100, 102000003, 102000003).isEmpty());
        assertTrue(Probes162.errandProblems(true, true, 0, 1, 0, 100, 100000000, 102000003).isEmpty(), "fell back");
        assertTrue(Probes162.errandProblems(false, true, 0, 0, 0, 100, 0, 0).get(0).contains("advanced at once"));
        assertEquals(2, Probes162.errandProblems(true, false, 0, 0, 0, 0, 0, 102000003).size());
        assertTrue(Probes162.errandProblems(true, true, 1, 0, 0, 100, 100000000, 102000003).get(0).contains("arrived"));
    }

    // ── t-jb-2 ──

    @Test
    void theBookCheckWantsAReadAndASpareThatMaySell() {
        assertTrue(Probes162.bookProblems("kept for its skill plan", 1, 1, 10, 20, null).isEmpty());
        assertTrue(Probes162.bookProblems("kept for its skill plan", 1, 0, 10, 10, "untradeable").isEmpty(),
                "a failed roll still reads the book; SaleRules may refuse a spare");
        assertEquals(1, Probes162.bookProblems(null, 1, 1, 10, 20, null).size());
        assertEquals(1, Probes162.bookProblems("kept for its skill plan", 0, 0, 10, 10, null).size());
        assertEquals(1, Probes162.bookProblems("kept for its skill plan", 1, 1, 10, 10, null).size());
        assertEquals(1, Probes162.bookProblems("kept for its skill plan", 1, 1, 10, 20, "kept for its skill plan")
                .size());
    }

    // ── t-nn-1 / t-nn-2 ──

    @Test
    void theNavChecksJudgeTheReplays() {
        assertTrue(Probes162.swimProblems(new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.SwimRun(-700, 250, 300))
                .isEmpty());
        assertEquals(2, Probes162.swimProblems(
                new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.SwimRun(-300, -60, 300)).size());
        assertTrue(Probes162.parkedProblems(
                new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.ParkedRun(40, 40, 5)).isEmpty());
        assertEquals(1, Probes162.parkedProblems(
                new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.ParkedRun(40, 40, 0)).size());
        assertEquals(2, Probes162.parkedProblems(
                new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.ParkedRun(40, 0, 0)).size());
        assertTrue(Probes162.parkedProblems(
                new soloMapling.ArtificialPlayer.GCMoveSystem.NavPortQa.ParkedRun(0, 0, 0)).get(0).contains("graph"));
    }
}
