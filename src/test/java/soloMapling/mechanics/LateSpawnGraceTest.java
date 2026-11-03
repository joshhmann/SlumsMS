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
 * P15 from `/root/work/PQ-UNFINISHED-REPORT.md`: a boss room may take minutes to produce its first target, and a global
 * 30-second grace abandoned fights that had not started. {@code spawnWaitSeconds:} is the row's own answer - a fact
 * about the content, not a tolerance - and {@link BossPhasesSolver#allowedEmptyMs} turns it into the deadline, with a
 * 30-second floor no row can argue below.
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
                  - "P15 in /root/work/PQ-UNFINISHED-REPORT.md: the Hard trio's first target arrives at 60-240 s"
                stages:
                  - stage: 1
                    map: 280030000
                    name: The late room
                    solver: boss-phases
                    summonProperty: summoned
                    mobs: [8800002]
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
    void aRowThatNamesNoSpawnWaitKeepsTheSolversThirtySeconds(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "").stages().get(0);
        assertEquals(0, stage.spawnWaitSeconds(), "0 means 'no expectation', which is what most existing rows say");
        assertEquals(30_000L, BossPhasesSolver.allowedEmptyMs(stage), "the solver's floor, not the row's silence");
    }

    @Test
    void aRowCanSayItsBossTakesMinutesToAppear(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "    spawnWaitSeconds: 240\n").stages().get(0);
        assertEquals(240, stage.spawnWaitSeconds(),
                "Hard Will's first target is behind four minutes of scripted waiting, not thirty seconds");
    }

    @Test
    void theDeadlineTheSolverUsesIsTheRows(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage named = bossRow(dir, "    spawnWaitSeconds: 240\n").stages().get(0);
        assertEquals(240_000L, BossPhasesSolver.allowedEmptyMs(named));
        MechanicsModel.Stage plain = bossRow(newDir(dir), "").stages().get(0);
        assertEquals(BossPhasesSolver.NOTHING_TO_HIT_GRACE_MS, BossPhasesSolver.allowedEmptyMs(plain));
    }

    @Test
    void theFloorIsThirtySecondsWhateverTheRowSays(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage tight = bossRow(dir, "    spawnWaitSeconds: 8\n").stages().get(0);
        assertEquals(30_000L, BossPhasesSolver.allowedEmptyMs(tight),
                "a row states when its mobs arrive; it cannot ask for a shorter leash than the solver's floor");
    }

    @Test
    void aSummonedRoomWithNothingToHitAbandonsOnlyWhenTheRowsWaitHasRunOut(@TempDir Path dir) throws Exception {
        MechanicsModel.Stage stage = bossRow(dir, "    spawnWaitSeconds: 60\n").stages().get(0);
        View v = new View(stage);
        BossPhasesSolver solver = new BossPhasesSolver();
        // the summon property is set and there is no part on the map: inside the row's wait it waits
        v.now = 1_000;
        assertInstanceOf(SolverStep.Wait.class, solver.next(v, v.members.get(0)), "inside the wait the run waits");
        v.now = 61_000;
        SolverStep.Abandon a = assertInstanceOf(SolverStep.Abandon.class, solver.next(v, v.members.get(0)),
                "past the row's own wait it gives up, and the line names that wait in seconds");
        assertTrue(a.why().contains("60 s"), "the message quotes the row's own number: " + a.why());
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
