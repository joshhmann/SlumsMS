package server.maps;

import client.Character;
import client.Client;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.Mocks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MOVE_OMOK/SELECT_CARD are raw wire input: {@code setPiece} indexed {@code piece[move2 * 15 + move1 + 1]} with no
 * range check (negative and past-the-end indices alike) and {@code getCardId} indexed the card list with the client's
 * slot. Both threw out of the handler. A match also finished cleanly only if it had an opponent.
 */
class MiniGameBoundsTest {

    private Character owner;
    private Client ownerClient;
    private MiniGame game;

    @BeforeEach
    void setUp() {
        owner = Mocks.chr("owner");
        ownerClient = mock(Client.class);
        when(owner.getMap()).thenReturn(mock(MapleMap.class));
        when(owner.getClient()).thenReturn(ownerClient);

        game = new MiniGame(owner, "", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        when(owner.getMiniGame()).thenReturn(game);
    }

    private void assertBoardEmpty() {
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 15; x++) {
                assertEquals(0, game.getPiece(x, y), "unexpected stone at (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void aPlacementOnAValidCellStillReachesTheBoard() {
        game.setPiece(3, 4, 1, owner);

        assertEquals(1, game.getPiece(3, 4));
        verify(ownerClient).sendPacket(any(Packet.class));    // the move is announced
    }

    @Test
    void aPlacementOffTheBoardWritesNoStone() {
        // 15 and -1 wrap onto real cells when the raw slot arithmetic is trusted: (15, 0) is slot 16 = cell (0, 1)
        game.setPiece(15, 0, 1, owner);
        game.setPiece(-1, 0, 1, owner);
        game.setPiece(0, 15, 1, owner);
        game.setPiece(0, 0, 3, owner);    // neither player's piece

        assertBoardEmpty();
        verify(ownerClient, never()).sendPacket(any(Packet.class));
    }

    @Test
    void aPlacementFarOffTheBoardDoesNotThrow() {
        assertDoesNotThrow(() -> game.setPiece(0, 100, 1, owner));
        assertDoesNotThrow(() -> game.setPiece(0, -1, 1, owner));
        assertDoesNotThrow(() -> game.setPiece(Integer.MIN_VALUE, 0, 1, owner));

        assertBoardEmpty();
    }

    @Test
    void anOutOfRangeCardSlotDoesNotThrow() {
        game.setMatchesToWin(6);
        game.setGameType(MiniGame.MiniGameType.MATCH_CARD);    // fills a 12-card list

        assertDoesNotThrow(() -> game.getCardId(12));
        assertDoesNotThrow(() -> game.getCardId(-1));
        assertEquals(-1, game.getCardId(12), "an out-of-range slot has no card");
        assertEquals(-1, game.getCardId(-1));
        assertTrue(game.isValidCardSlot(11));
        assertFalse(game.isValidCardSlot(12));
    }

    @Test
    void finishingAMatchWithNoOpponentScoresNothing() {
        game.minigameMatchStarted();

        assertDoesNotThrow(() -> game.minigameMatchOwnerWins(true));

        assertFalse(game.isMatchInProgress());
        // Character.setMiniGamePoints dereferences the opponent (visitor.omoklosses++), so it must not be called with
        // a null visitor at all; the result packet reads the visitor too.
        verify(owner, never()).setMiniGamePoints(any(), anyInt(), anyBoolean());
        assertNull(game.getVisitor());
    }

    @Test
    void drawingAMatchWithNoOpponentScoresNothing() {
        game.minigameMatchStarted();

        assertDoesNotThrow(() -> game.minigameMatchDraw());

        assertFalse(game.isMatchInProgress());
        verify(owner, never()).setMiniGamePoints(any(), anyInt(), anyBoolean());
    }
}
