package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164, the ninth-row primitive: a stage whose clear is not an event property. Cafe PQ's, Treasure PQ's and
 * LudiMaze PQ's final stage write no property at all - the script only calls {@code eim.clearPQ()}, and
 * {@code EventInstanceManager.isEventCleared()} is the only signal there is.
 *
 * <p>The fail-before pair is {@link #aRowWhoseFinalStageClearsOnTheInstanceAdvancesPastIt} and
 * {@link #aStageWithNoPropertyToReadWasNeverClearBefore}: before this change {@code StageView.cleared()} returned false
 * for any stage whose data named no property, so such a row could never advance past its last stage, however many times
 * the instance said it was cleared.
 */
class InstanceClearTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    /** A one-stage row whose only clear is the instance's own flag, parsed through the real YAML reader. */
    private static MechanicsModel.Content miniRow(Path dir, String clearBlock) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "mini.yaml\n");
        Files.writeString(dir.resolve("mini.yaml"), """
                id: mini
                name: Instance clear mini row
                kind: PQ
                eventScript: MiniPQ
                entryNpc: 9000000
                recruitMap: 100000000
                exitMap: 100000000
                minPlayers: 2
                maxPlayers: 6
                minLevel: 10
                maxLevel: 255
                timeLimitMinutes: 10
                flag: slums.mechanics.mini
                sources:
                  - "not a shipped row: this test builds it to pin one clear form"
                stages:
                  - stage: 1
                    map: 100000000
                    name: The final stage
                    solver: kill-collect-hand-in
                    roles:
                      - id: leader
                        who: LEADER
                        min: 1
                        max: 1
                      - id: collector
                        who: NON_LEADER
                        min: 0
                        max: party-1
                    items:
                      - id: 4000000
                        cleanup: true
                    objectives:
                      - id: hunt
                        kind: KILL_FOR_ITEM
                        role: all
                        mob: 100100
                        item: 4000000
                        count: 1
                      - id: hand
                        kind: HAND_IN
                        role: leader
                        npc: 9000000
                        item: 4000000
                        count: 1
                %s""".formatted(clearBlock));
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        assertEquals(1, result.content().size());
        return result.content().get(0);
    }

    @Test
    void theInstanceFormParsesAndAPropertylessClearIsStillRejected(@TempDir Path dir) throws Exception {
        MechanicsModel.Clear clear = miniRow(dir, "    clear:\n      instance: true\n").stages().get(0).clear();
        assertNotNull(clear);
        assertTrue(clear.onInstance(), "clear: { instance: true } is the instance's own flag, not a property");
        assertEquals(null, clear.eventProperty(), "and it names no property at all");

        Path empty = Files.createTempDirectory("instance-clear-empty");
        StageData.Result broken;
        try {
            miniRow(empty, "    clear: {}\n");
            broken = null;
        } catch (AssertionError e) {
            broken = new StageData.Result(List.of(), List.of(String.valueOf(e.getMessage())));
        }
        assertNotNull(broken, "a clear that names neither a property nor the instance must be refused");
        assertTrue(broken.errors().stream().anyMatch(e -> e.contains("no clear")), broken.errors().toString());
    }

    @Test
    void aStageWithNoPropertyToReadWasNeverClearBefore(@TempDir Path dir) throws Exception {
        MechanicsModel.Content row = MechanicsRuns.contentFor("HenesysPQ");
        assertNotNull(row);
        assertFalse(row.stages().get(0).clear().onInstance(), "the hill's stage 1 is a property clear, a control");

        MechanicsModel.Stage instanceOnly = miniRow(dir, "    clear:\n      instance: true\n").stages().get(0);
        FakeView view = new FakeView(null, instanceOnly);
        assertFalse(view.cleared(), "an instance that has not cleared anything is not a clear");
        view.instanceCleared = true;
        assertTrue(view.cleared(), "and once it says cleared, the stage is clear - with no property anywhere");
    }

    @Test
    void aRowWhoseFinalStageClearsOnTheInstanceAdvancesPastIt(@TempDir Path dir) throws Exception {
        MechanicsModel.Content content = miniRow(dir, "    clear:\n      instance: true\n");
        Orchestrator orchestrator = new Orchestrator(Orchestrator.allSolvers(), new java.util.Random(3L), 0, 0);
        List<RunMember> members = List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
        RunState run = orchestrator.start(content, MechanicsModel.Tier.NORMAL, members);
        FakeView view = new FakeView(run, run.stageData());
        long now = 0;

        for (int i = 0; i < 10; i++) {
            orchestrator.tick(run, view, now);
            now += 100;
        }
        assertFalse(run.ended(), "nothing has cleared yet: the instance has not said so");
        assertEquals(1, run.currentStage());

        view.instanceCleared = true; // eim.clearPQ(): the last thing the finale's script does
        for (int i = 0; i < 10 && !run.ended(); i++) {
            orchestrator.tick(run, view, now);
            now += 100;
        }
        assertTrue(run.ended(), "the row's only stage clears on the instance's flag");
        assertEquals("cleared", run.endedBecause());
        assertTrue(run.cleared());
    }

    /** Only what the orchestrator and the generic solver read; every other {@code StageView} method is its default. */
    private static final class FakeView implements StageView {
        private final RunState run;
        private final MechanicsModel.Stage stage;
        private boolean instanceCleared;

        FakeView(RunState run, MechanicsModel.Stage stage) {
            this.run = run;
            this.stage = stage;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return run == null ? List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT))
                    : run.members();
        }

        @Override
        public boolean eventCleared() {
            return instanceCleared;
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public String roleOf(int characterId) {
            return "all";
        }

        @Override
        public java.util.OptionalInt answerFor(int characterId) {
            return java.util.OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public int mapOf(int characterId) {
            return stage.mapId();
        }

        @Override
        public int liveMobs() {
            return 0;
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public long nowMs() {
            return 0;
        }

        @Override
        public Map<String, Object> stageMemory() {
            return run == null ? Map.of() : run.stageMemory();
        }
    }
}
