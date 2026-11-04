package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164: the fifth kind of clear - the party reached a map.
 *
 * <p>Ellin PQ's marble stage ends when `scripts/npc/2133001.js:93-94` warps the whole team to `930000500`; nothing is
 * written to the event, so before this kind the row could only hold. The rule is deliberately "every registered member",
 * not "any": a solver's `WarpTo` mid-stage would otherwise fake a clear on the first arrival.
 */
class PartyOnMapClearTest {

    @TempDir
    Path dir;

    private static final String ROW = """
            id: ellinpq
            name: Ellin PQ
            kind: PQ
            eventScript: EllinPQ
            recruitMap: 300030100
            exitMap: 930000800
            minPlayers: 4
            maxPlayers: 6
            minLevel: 44
            maxLevel: 255
            timeLimitMinutes: 30
            sources:
              - "this test's own row: the rule under test is ours"
            stages:
              - stage: 1
                map: 930000400
                name: The marble hand-in
                solver: hold
                roles:
                  - id: everyone
                    who: ANY
                    min: 1
                    max: party
                objectives:
                  - id: arrive
                    kind: WAIT
                    role: all
                clear:
                  partyOnMap: 930000500
            """;

    @Test
    void theRowReadsAndTheClearIsTheMapNotAProperty() throws IOException {
        Path file = dir.resolve("ellinpq.yaml");
        Files.writeString(file, ROW);
        Files.writeString(dir.resolve("index.txt"), "ellinpq.yaml\n");
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertEquals(List.of(), result.errors(), "the row loads without a complaint");
        MechanicsModel.Content content = result.content().stream()
                .filter(c -> c.eventScript().equals("EllinPQ")).findFirst().orElse(null);
        assertNotNull(content, "the row loads");
        MechanicsModel.Clear clear = content.stages().get(0).clear();
        assertEquals(930000500, clear.partyOnMap());
        assertFalse(clear.mapEmpty(), "not a map-empty stage");
        assertEquals(0, clear.reactorId(), "not a reactor-stage clear");
        assertEquals(null, clear.eventProperty(), "and no property: the warp IS the signal");
    }

    @Test
    void everyMemberHasToBeThereAndOneMemberIsNotEnough() {
        MechanicsModel.Stage stage = stage();
        Party party = new Party(stage, List.of(1, 2, 3));

        party.moves.put(1, 930000500);
        assertFalse(party.cleared(), "one member through the warp does not clear a stage for the other two");
        party.moves.put(2, 930000400);
        party.moves.put(3, 930000400);
        assertFalse(party.cleared(), "a member still on the stage map holds the clear");
        party.moves.put(2, 930000500);
        party.moves.put(3, 930000500);
        assertTrue(party.cleared(), "every registered member on the target map clears it");
    }

    @Test
    void anEmptyMemberListIsNotAClear() {
        Party party = new Party(stage(), List.of());
        assertFalse(party.cleared(), "nobody registered means nobody arrived, not everybody did");
    }

    private static MechanicsModel.Stage stage() {
        return new MechanicsModel.Stage(4, 930000400, "The marble hand-in", 0, "hold", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), MechanicsModel.Clear.ofPartyOnMap(930000500));
    }

    /** The smallest view the clear needs: where each member stands and who is registered. */
    private static final class Party implements StageView {
        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final java.util.Map<Integer, Integer> moves = new java.util.HashMap<>();

        Party(MechanicsModel.Stage stage, List<Integer> ids) {
            this.stage = stage;
            this.members = ids.stream().map(id -> new RunMember(id, id == 1, true, MechanicsPersona.PERFECT)).toList();
            ids.forEach(id -> moves.put(id, stage.mapId()));
        }

        @Override
        public MechanicsModel.Stage stage() {
            return stage;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public int mapOf(int characterId) {
            return moves.getOrDefault(characterId, stage.mapId());
        }

        @Override
        public String roleOf(int characterId) {
            return null;
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            return 0;
        }

        @Override
        public Optional<String> eventProperty(String name) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            return new int[]{0, 0};
        }
    }
}
