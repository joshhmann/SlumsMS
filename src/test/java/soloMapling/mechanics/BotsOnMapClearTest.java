package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.5 b344: in a Zakum PQ stage 1 party with a real player in it, the bots broke the hub chest and never stayed in a
 * mine room. TEST 2026-11-06 02:38-02:41 UTC (PreZakum0 ch 1, the owner and xGoonie): every time the bot walked
 * through the door to 280010110, the run logged "xGoonie: through to 280010000 (stage 1 is in this room)" and put it
 * back on the hub, eleven times in three minutes, until the run ended.
 *
 * <p>The cause is the clear. Every ZPQ stage cleared on {@code partyOnMap}: the <b>whole</b> party in the next room. A
 * real player walks the maze his own way and the run never steers him, so stage 1 never cleared, and the orchestrator
 * warps a bot that is ahead of the current stage back to that stage's map. The mines are free to roam (no script
 * warps the team, unlike Ellin PQ's {@code partyOnMap} hand-in), so a ZPQ room is done when the run's own bots have
 * walked on: {@code botsOnMap}.
 */
class BotsOnMapClearTest {

    private static final int HUB = 280010000;
    private static final int ROOM = 280010110;
    private static final int PLAYER = 46;
    private static final int BOT = 1_000_536_685;

    @Test
    void aZakumRoomClearsWhenItsBotsHaveWalkedOnWhereverTheRealPlayerIs() throws Exception {
        MechanicsModel.Stage first = zakumRow().stages().get(0);
        assertEquals(HUB, first.mapId(), "the row's first stage is the hub");
        Party party = new Party(first, Map.of(PLAYER, false, BOT, true));

        party.at.put(BOT, ROOM);
        party.at.put(PLAYER, 280010100); // the owner went through a different door
        assertTrue(party.cleared(), "the bot is in the next room: stage 1 is done, the player is not the run's to walk");
    }

    @Test
    void aBotStillOnTheHubHoldsTheClear() throws Exception {
        MechanicsModel.Stage first = zakumRow().stages().get(0);
        Party party = new Party(first, Map.of(PLAYER, false, BOT, true, BOT + 1, true));
        party.at.put(BOT, ROOM);
        party.at.put(PLAYER, ROOM);
        assertFalse(party.cleared(), "one of two bots is still on the hub");
        party.at.put(BOT + 1, ROOM);
        assertTrue(party.cleared());
    }

    @Test
    void aPartyWithNoBotIsNeverClearedByIt() throws Exception {
        MechanicsModel.Stage first = zakumRow().stages().get(0);
        Party party = new Party(first, Map.of(PLAYER, false));
        party.at.put(PLAYER, ROOM);
        assertFalse(party.cleared(), "no bot walked anywhere: nothing was done by the run");
    }

    @Test
    void theRoomTheMinerWalksToIsStillTheClearsMap() throws Exception {
        MechanicsModel.Stage first = zakumRow().stages().get(0);
        assertEquals(ROOM, first.clear().partyOnMap(), "ZakumMinesSolver leaves through the door to this map");
    }

    private static MechanicsModel.Content zakumRow() throws Exception {
        Path row = Path.of("src/main/java/soloMapling/mechanics/content/zakumpq.yaml");
        Path dir = Files.createTempDirectory("b344");
        Files.copy(row, dir.resolve("zakumpq.yaml"));
        Files.writeString(dir.resolve("index.txt"), "zakumpq.yaml\n");
        StageData.Result result = StageData.loadIndexed(dir + "/", Path::of);
        assertEquals(List.of(), result.errors(), "the zakumpq row loads");
        return result.content().stream().filter(c -> c.id().equals("zakumpq")).findFirst().orElseThrow();
    }

    /** Where each member stands, and which of them are bots. */
    private static final class Party implements StageView {
        private final MechanicsModel.Stage stage;
        private final List<RunMember> members;
        private final java.util.Map<Integer, Integer> at = new java.util.HashMap<>();

        Party(MechanicsModel.Stage stage, Map<Integer, Boolean> who) {
            this.stage = stage;
            this.members = who.entrySet().stream()
                    .map(e -> new RunMember(e.getKey(), !e.getValue(), e.getValue(), MechanicsPersona.PERFECT)).toList();
            who.keySet().forEach(id -> at.put(id, stage.mapId()));
        }

        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return members; }
        @Override public int mapOf(int characterId) { return at.getOrDefault(characterId, stage.mapId()); }
        @Override public String roleOf(int characterId) { return null; }
        @Override public OptionalInt answerFor(int characterId) { return OptionalInt.empty(); }
        @Override public int itemCount(int characterId, int itemId) { return 0; }
        @Override public Optional<String> eventProperty(String name) { return Optional.empty(); }
        @Override public int[] positionOf(int characterId) { return new int[]{0, 0}; }
    }
}
