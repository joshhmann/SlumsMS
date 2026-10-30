package soloMapling.autoqa;

import client.QuestStatus;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 (ticket 163 section 4): the checks for the bot switches that had no auto-QA proof; verdicts and wiring. */
class Probes162Test {

    static final List<String> NEW = List.of("t-bq-1", "t-tm-1");

    @Test
    void theNewChecksAreAutomatedNightlyAndUseOnlyKnownProbes() {
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        for (String id : NEW) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id);
            assertEquals(Check.Kind.AUTO, c.kind(), id);
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
}
