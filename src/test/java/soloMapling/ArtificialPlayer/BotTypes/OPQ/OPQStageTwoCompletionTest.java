package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "PQ bots are not great": stage 2 never completed. Completion was decided over every registered OPQ bot, and the
 * lobby's ten to fifteen idle recruiters register too and never report a box, so the party always fell through
 * STAGE_2_WAIT's two-minute cap and the bots left the run.
 */
class OPQStageTwoCompletionTest {

    private static Character in(MapleMap map, int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getMap()).thenReturn(map);
        return chr;
    }

    @Test
    void lobbyBotsDoNotHoldUpTheRunInTheInstance() {
        MapleMap instance = mock(MapleMap.class);
        MapleMap lobby = mock(MapleMap.class);
        List<Character> all = List.of(in(instance, 1), in(instance, 2), in(instance, 3), in(lobby, 4), in(lobby, 5));

        assertTrue(OPQOrchestrator.allCompleteIn(instance, all, id -> id <= 3));
    }

    @Test
    void oneBotStillBreakingItsBoxKeepsTheStageOpen() {
        MapleMap instance = mock(MapleMap.class);
        List<Character> all = List.of(in(instance, 1), in(instance, 2));

        assertFalse(OPQOrchestrator.allCompleteIn(instance, all, id -> id == 1));
    }

    @Test
    void aStageWithNoBotInItIsNotComplete() {
        MapleMap instance = mock(MapleMap.class);
        List<Character> all = List.of(in(mock(MapleMap.class), 1));

        assertFalse(OPQOrchestrator.allCompleteIn(instance, all, Set.of(1)::contains));
        assertFalse(OPQOrchestrator.allCompleteIn(null, all, id -> true));
    }
}
