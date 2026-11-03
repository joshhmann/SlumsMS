package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15 from `/root/work/PQ-UNFINISHED-REPORT.md`: a boss room may take time to produce its first target, and a global
 * 30-second grace abandoned fights that had not started. {@code graceMs:} is the stage's own answer, and the default is
 * unchanged for every row that does not name one.
 *
 * <p>The dossier's figure for the four Normal-tier fights - "first target at 60/120/120/240 s" - is wrong: their scripts
 * schedule the spawn at <b>five seconds</b> (`NWillBattle.js:139-140` and its three siblings,
 * `spawnTimes = [5000]`). So the primitive is not what those four needed; the rows are (see
 * {@code RowRunSimulationTest.assertLateSpawn}). The test below stays because a script that really does take minutes is
 * the reason the field exists, and a row must be able to say so.
 */
class LateSpawnGraceTest {

    private static MechanicsModel.Content bossRow(Path dir, String extra) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "late.yaml\n");
        Files.writeString(dir.resolve("late.yaml"), """
                id: late
                name: Late spawn boss
                kind: BOSS
                eventScript: LateBattle
                entryNpc: 9000000
                recruitMap: 100000000
                exitMap: 100000000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 30
                flag: slums.mechanics.late
                sources:
                  - "P15: a boss room whose target is not there on the first tick - the shape NWillBattle.js and its
                    siblings would have had if their spawnTimes were minutes instead of 5000"
                stages:
                  - stage: 1
                    map: 280030000
                    name: The late room
                    solver: boss-phases
                    summonProperty: summoned
                    killTarget: 8800002
                    roles:
                      - id: fighter
                        who: ANY
                        min: 1
                        max: party
                    objectives:
                      - id: fight
                        kind: DEFEAT
                        role: all
                    clear:
                      eventProperty: defeatedBoss
                      equals: 1
                """ + extra);
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        return result.content().get(0);
    }

    @Test
    void aRowThatNamesNoGraceKeepsTheSolversThirtySeconds(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "").stages().get(0);
        assertEquals(0L, stage.graceMs(), "0 means 'the solver's own default', which is what every existing row says");
    }

    @Test
    void aRowCanSayItsBossTakesMinutesToAppear(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "    graceMs: 240000\n").stages().get(0);
        assertEquals(240_000L, stage.graceMs(),
                "a room whose script waits four minutes must be able to say so instead of being abandoned at thirty");
    }

    @Test
    void theGraceTheSolverUsesIsTheRows(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage named = bossRow(dir, "    graceMs: 240000\n").stages().get(0);
        assertEquals(240_000L, BossPhasesSolver.graceMs(new View(named)));
        MechanicsModel.Stage plain = bossRow(newDir(dir), "").stages().get(0);
        assertEquals(BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS, BossPhasesSolver.graceMs(new View(plain)));
    }

    @Test
    void aSummonedRoomWithNothingToHitAbandonsOnlyWhenTheRowsGraceHasRunOut(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "    graceMs: 10000\n").stages().get(0);
        View v = new View(stage);
        BossPhasesSolver solver = new BossPhasesSolver();
        // the summon property is set and there is no part on the map: inside the grace it waits
        v.now = 1_000;
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.members.get(0)), "inside the grace the run waits");
        v.now = 12_000;
        SolverStep.Abandon a = assertInstanceOf(SolverStep.Abandon.class, solver.next(v, v.members.get(0)),
                "past the row's own grace it gives up, and the line names that grace in seconds");
        assertTrue(a.why().contains("10 s"), "the message quotes the row's grace: " + a.why());
    }

    private static Path newDir(Path base) throws Exception {
        Path d = base.resolve("second");
        Files.createDirectories(d);
        return d;
    }

    /** A summoned boss room with no parts on the map. */
    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private final List<RunMember> members = new ArrayList<>();
        private final java.util.Map<String, Object> memory = new java.util.concurrent.ConcurrentHashMap<>();
        private long now;

        View(MechanicsModel.Stage stage) {
            this.stage = stage;
            members.add(new RunMember(1, true, true, MechanicsPersona.PERFECT));
        }

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int id) { return stage.roles().get(0).id(); }
        @Override public java.util.OptionalInt answerFor(int id) { return java.util.OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public java.util.Optional<String> eventProperty(String name) {
            return "summoned".equals(name) ? java.util.Optional.of("1") : java.util.Optional.empty();
        }
        @Override public int[] positionOf(int id) { return new int[]{0, 0}; }
        @Override public int mapOf(int id) { return stage.mapId(); }
        @Override public java.util.Map<String, Object> stageMemory() { return memory; }
        @Override public long nowMs() { return now; }
        @Override public List<BossTargetRules.Part> bossParts() { return List.of(); }
    }
}
