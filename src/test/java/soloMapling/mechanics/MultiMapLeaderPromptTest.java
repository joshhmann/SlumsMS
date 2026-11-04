package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;

/**
 * A real leader is reminded of the part only he can do from ANY map his stage drives, not only the stage's first one.
 * Guild Quest's last stage opens on 990000800 but ends on 990000900, where the Rubian is handed to the Soul by the leader
 * ({@code scripts/npc/9040010.js:27}: {@code isEventLeader}); {@code Orchestrator.tick} used to ask for a reminder only
 * while the leader stood on the stage's own map, so a player leader on 900 was never told what the party waited for.
 */
class MultiMapLeaderPromptTest {

    private static final long START = 5_000_000L;

    @Test
    void aPlayerLeaderOnTheBossHallIsRemindedToHandInTheRubian() {
        Content row = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR)).content().stream()
                .filter(c -> c.id().equals("guildquest")).findFirst().orElseThrow();
        GqBossSolverTest.World w = new GqBossSolverTest.World(new int[][]{{1, GqBossSolver.M900}, {2, GqBossSolver.M900},
                {3, GqBossSolver.M900}, {4, GqBossSolver.M900}, {5, GqBossSolver.M900}, {6, GqBossSolver.M800}}, 1, 1);
        w.gate = 1;
        w.bossState = 5;
        w.rubianOnFloor = 1; // Ergoth is down and the Rubian lies there

        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        RunState run = o.start(row, Tier.NORMAL, w.members);
        run.markStarted(START);
        assertTrue(o.startAt(run, 7, START));

        w.now = START + 1_000;
        o.tick(run, w, w.now);
        w.now += Orchestrator.LEADER_PROMPT_FIRST_MS + 1_000;
        o.tick(run, w, w.now); // the bots have by now seen the boss hall is over: the leader's turn starts here
        w.now += Orchestrator.LEADER_PROMPT_FIRST_MS + 1_000;
        Map<Integer, SolverStep> later = o.tick(run, w, w.now);

        SolverStep.Say said = later.values().stream().filter(SolverStep.Say.class::isInstance)
                .map(SolverStep.Say.class::cast).findFirst().orElse(null);
        assertTrue(said != null, "one bot reminds the leader: " + later);
        assertTrue(said.line().contains("9040010") && said.line().startsWith("@1"), said.line());
    }
}
