package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BossRush PQ (`/root/work/PQ-UNFINISHED-REPORT.md:438-462`) is the one event whose stages repeat: the portal
 * (`scripts/portal/raid_stage.js:28`) sends the party back while the map is empty and the run ends when the five-minute
 * clock does. A row of N stages could not express that before - every stage was terminal-on-clear - so the primitive is
 * a stage field, not a solver: {@code loopTo:} sends the run back instead of forward, and the run's own budget is what
 * ends it.
 */
class LoopStageTest {

    private static final int FIRST = 970030100;
    private static final int SECOND = 970030200;

    private static MechanicsModel.Content row(Path dir, String stageTwoTail) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "loop.yaml\n");
        Files.writeString(dir.resolve("loop.yaml"), """
                id: loop
                name: A wave that repeats
                kind: PQ
                eventScript: BossRush
                entryNpc: 9000037
                recruitMap: 970030000
                exitMap: 970030000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 1
                flag: slums.mechanics.loop
                sources:
                  - "PQ-UNFINISHED-REPORT.md:438-462 (BossRushPQ) and scripts/portal/raid_stage.js:28"
                stages:
                  - stage: 1
                    map: %d
                    name: Wave one
                    solver: fight-room
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
                      eventProperty: cleared
                      equals: 1
                  - stage: 2
                    map: %d
                    name: Wave two
                    solver: fight-room
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
                      eventProperty: cleared
                      equals: 1
                """.formatted(FIRST, SECOND) + stageTwoTail);
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        return result.content().get(0);
    }

    @Test
    void aRowThatNamesNoLoopIsUnchanged(@TempDir Path dir) throws Exception {
        MechanicsModel.Content c = row(dir, "");
        assertEquals(0, c.stages().get(1).loopTo(), "0 means 'advance', which is every other row on this branch");
    }

    @Test
    void aStageCanSendTheRunBack(@TempDir Path dir) throws Exception {
        MechanicsModel.Content c = row(dir, "");
        assertEquals(2, c.stages().size());
        MechanicsModel.Content looping = row(newDir(dir), "    loopTo: 1\n");
        assertEquals(1, looping.stages().get(1).loopTo(), "the last stage of the set points back at the first");
    }

    @Test
    void theRunGoesBackInsteadOfEndingAndTheClockIsWhatStopsIt(@TempDir Path dir) throws Exception {
        MechanicsModel.Content c = row(dir, "    loopTo: 1\n");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new Random(7), 0, 0);
        RunState run = orchestrator.start(c, MechanicsModel.Tier.NORMAL, List.of(
                new RunMember(1, true, true, MechanicsPersona.PERFECT)));

        // the world clears whatever stage the run is on, one tick after it arrives, until the budget runs out
        World world = new World(c);
        List<Integer> order = new ArrayList<>();
        int last = -1;
        for (int tick = 0; tick < 200 && !run.ended(); tick++) {
            if (run.currentStage() != last) {
                last = run.currentStage();
                order.add(last);
                world.cleared = false; // a fresh stage starts unclear
            } else {
                world.cleared = true; // and clears on the next tick
            }
            orchestrator.tick(run, world, world.now);
            world.now += 1_000;
        }

        assertFalse(run.cleared(), "a looping row never reports a clear: the event has no clearPQ, only a clock");
        assertTrue(order.size() >= 5, "the wave repeated instead of ending the run: " + order);
        assertEquals(List.of(1, 2, 1, 2, 1), order.subList(0, 5), "the loop goes back to stage 1, not forward");
        assertTrue(run.ended(), "the row's own 1-minute clock is what ends it: " + run.endedBecause());
    }

    private static Path newDir(Path base) throws Exception {
        Path d = base.resolve("second");
        Files.createDirectories(d);
        return d;
    }

    /** Clears on demand; nothing else matters to the orchestrator's loop branch. */
    private static final class World implements StageView {
        private final MechanicsModel.Content content;
        private final List<RunMember> members = List.of(new RunMember(1, true, true, MechanicsPersona.PERFECT));
        private final java.util.Map<String, Object> memory = new java.util.concurrent.ConcurrentHashMap<>();
        private boolean cleared;
        private long now = 1_000;

        World(MechanicsModel.Content content) {
            this.content = content;
        }

        @Override public MechanicsModel.Stage stage() { return content.stages().get(0); }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int id) { return "fighter"; }
        @Override public OptionalInt answerFor(int id) { return OptionalInt.empty(); }
        @Override public int itemCount(int id, int item) { return 0; }
        @Override public Optional<String> eventProperty(String name) { return Optional.empty(); }
        @Override public int[] positionOf(int id) { return new int[]{0, 0}; }
        @Override public int mapOf(int id) { return firstStageMap(); }
        @Override public java.util.Map<String, Object> stageMemory() { return memory; }
        @Override public long nowMs() { return now; }
        @Override public boolean cleared() { return cleared; }

        private int firstStageMap() { return FIRST; }
    }
}
