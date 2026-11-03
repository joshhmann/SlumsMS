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
 * Ticket 164, the tenth row primitive: a stage whose script never says "done" at all.
 *
 * <p>Ellin PQ's first stage is the case - `scripts/portal/party6_stage.js:9-18` refuses the passage while
 * `cm.getMap().getMonsters()` is non-empty, and no property is written anywhere in the event. Until now a row could only
 * clear on an event property (`{ eventProperty, equals }`) or on the instance's own flag (`{ instance: true }`), so such
 * a stage could not be expressed and the run would have to guess.
 *
 * <p>Fail-before pair: {@link #aRowWhoseStageClearsWhenTheMapIsEmptyAdvancesPastIt} and
 * {@link #withoutTheNewKindThereIsNoClearToGiveIt} - the second parses the same row with the old vocabulary and shows
 * the reader refuses it rather than inventing a clear.
 */
class MapEmptyClearTest {

    private static final int LEADER = 1;
    private static final int MEMBER = 2;

    private static MechanicsModel.Content miniRow(Path dir, String clearBlock, List<String> errors) throws Exception {
        Files.writeString(dir.resolve("index.txt"), "mini.yaml\n");
        Files.writeString(dir.resolve("mini.yaml"), """
                id: mini
                name: Map-empty mini row
                kind: PQ
                eventScript: MiniPQ
                entryNpc: 9000000
                recruitMap: 100000000
                exitMap: 100000000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 30
                flag: slums.mechanics.mini
                sources:
                  - "the mini row is the test's own; the map-empty rule is Ellin PQ stage 1 (party6_stage.js:9-18)"
                stages:
                  - stage: 1
                    map: 930000100
                    name: Empty the room
                    solver: fight-room
                    roles:
                      - id: fighter
                        who: ANY
                        min: 1
                        max: party
                    objectives:
                      - id: clear-room
                        kind: WAIT
                        role: all
                """ + clearBlock);
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(result.errors().isEmpty(), "the row must read cleanly: " + result.errors());
        assertEquals(1, result.content().size());
        errors.addAll(result.errors());
        return result.content().get(0);
    }

    @Test
    void aRowWhoseStageClearsWhenTheMapIsEmptyAdvancesPastIt(@TempDir Path dir) throws Exception {
        List<String> errors = new java.util.ArrayList<>();
        MechanicsModel.Content c = miniRow(dir, "    clear:\n      mapEmpty: true\n", errors);
        MechanicsModel.Stage stage = c.stages().get(0);
        assertTrue(stage.clear().mapEmpty(), "the row says what it means: { mapEmpty: true }");
        assertFalse(stage.clear().onInstance(), "and it is not the instance flag");

        View v = new View(stage, 3);
        assertFalse(v.cleared(), "three monsters left is not a clear");
        v.mobs = 0;
        assertTrue(v.cleared(), "an empty map is");
    }

    @Test
    void withoutTheNewKindThereIsNoClearToGiveIt(@TempDir Path dir) throws Exception {
        List<String> errors = new java.util.ArrayList<>();
        // the row as it would have had to be written before this change: no property, no instance, no mapEmpty
        Files.writeString(dir.resolve("index.txt"), "mini.yaml\n");
        Files.writeString(dir.resolve("mini.yaml"), """
                id: mini
                name: Map-empty mini row
                kind: PQ
                eventScript: MiniPQ
                entryNpc: 9000000
                recruitMap: 100000000
                exitMap: 100000000
                minPlayers: 1
                maxPlayers: 6
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 30
                flag: slums.mechanics.mini
                sources:
                  - "the mini row is the test's own; the map-empty rule is Ellin PQ stage 1 (party6_stage.js:9-18)"
                stages:
                  - stage: 1
                    map: 930000100
                    name: Empty the room
                    solver: fight-room
                    roles:
                      - id: fighter
                        who: ANY
                        min: 1
                        max: party
                    objectives:
                      - id: clear-room
                        kind: WAIT
                        role: all
                """);
        StageData.Result bare = StageData.loadIndexed(dir + "/", Path::of);
        assertTrue(bare.content().isEmpty(), "a stage with no clear at all is still refused");
        List<String> bareErrors = bare.errors();
        assertTrue(bareErrors.stream().anyMatch(e -> e.contains("no clear")),
                "and the error says what to write: " + bareErrors);
        assertTrue(bareErrors.stream().anyMatch(e -> e.contains("mapEmpty")),
                "including the new kind, so the next reader finds it: " + bareErrors);
    }

    @Test
    void aMapEmptyStageIsNotClearedByAnInstanceFlagOrAProperty(@TempDir Path dir) throws Exception {
        List<String> errors = new java.util.ArrayList<>();
        MechanicsModel.Content c = miniRow(dir, "    clear:\n      mapEmpty: true\n", errors);
        View v = new View(c.stages().get(0), 0);
        v.clearedOnInstance = true;
        assertTrue(v.cleared(), "no monsters is the whole test, and the instance flag does not undo it");
        v.mobs = 5;
        assertFalse(v.cleared(), "monsters back on the map means the stage is not over");
    }

    private static final class View implements StageView {
        private final MechanicsModel.Stage stage;
        private int mobs;
        private boolean clearedOnInstance;

        View(MechanicsModel.Stage stage, int mobs) {
            this.stage = stage;
            this.mobs = mobs;
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public int liveMobs() {
            return mobs;
        }

        @Override
        public boolean eventCleared() {
            return clearedOnInstance;
        }

        @Override
        public List<RunMember> members() {
            return List.of(new RunMember(LEADER, true, true, MechanicsPersona.PERFECT),
                    new RunMember(MEMBER, false, true, MechanicsPersona.PERFECT));
        }

        @Override
        public String roleOf(int characterId) {
            return "fighter";
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
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }

        @Override
        public Map<String, Object> stageMemory() {
            return new java.util.HashMap<>();
        }
    }
}
