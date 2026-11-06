package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.5 run-min-room (TEST 6 Nov 21:33, QA's exped.door run): Horntail started with 5 of the 6 registered - exped.door
 * left MSbeta out because it was not at the altar - and the row's 6-member minimum refused the mechanics run, so the
 * four regulars that went in fought on follower AI, where b311's stand-spot fix does not reach. A boss room plays with
 * whoever the expedition put in it; a party quest's minimum (its script's own entry rule) still holds.
 */
class BossRoomMinimumTest {

    private static List<RunMember> party(int size) {
        List<RunMember> out = new ArrayList<>();
        out.add(new RunMember(1, true, false, MechanicsPersona.PERFECT));
        for (int id = 2; id <= size; id++) {
            out.add(new RunMember(id, false, true, MechanicsPersona.PERFECT));
        }
        return out;
    }

    private static RunState start(Content c, int size) {
        return new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0).start(c, Tier.NORMAL, party(size));
    }

    @Test
    void horntailStartsWithFiveWhenTheDoorLeftTheSixthOut() {
        Content horntail = MechanicsRuns.contentFor("HorntailBattle");
        assertEquals(6, horntail.minPlayers(), "the row's minimum is the expedition's");
        RunState run = start(horntail, 5);
        assertFalse(run.ended(), "five in the room: " + run.endedBecause());
    }

    @Test
    void aBossRunThatStartedWithFiveEndsWhenItIsDownToFour() {
        Content horntail = MechanicsRuns.contentFor("HorntailBattle");
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(horntail, Tier.NORMAL, party(5));
        System.setProperty("slums.bossfight.revivepause", "false");
        try {
            o.memberLost(run, 5, "left", 1_000L);
        } finally {
            System.clearProperty("slums.bossfight.revivepause");
        }
        assertTrue(run.ended(), "the floor is what it started with");
        assertTrue(run.endedBecause().contains("too few members left (4 < 5)"), run.endedBecause());
    }

    @Test
    void aLeaderAloneStillHasNoRun() {
        RunState run = start(MechanicsRuns.contentFor("HorntailBattle"), 1);
        assertTrue(run.ended(), "nobody to drive");
    }

    @Test
    void aPartyQuestKeepsItsRowsMinimum() {
        Content kpq = MechanicsRuns.contentFor("KerningPQ");
        int min = kpq.minPlayers();
        assertTrue(min > 1, "KPQ asks for a party");
        RunState run = start(kpq, min - 1);
        assertTrue(run.ended(), "one short of the script's own entry rule");
    }
}
