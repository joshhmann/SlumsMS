package server.maps;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.Mocks;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The board was cleared only inside the five-in-a-row branches of {@code setPiece}, so a game that ended by forfeit
 * (GIVE_UP, or the visitor leaving while a match was running) left every stone in place. The next START then ran on
 * that dirty board: a player could complete five in a row more or less immediately and be credited a real win.
 */
class MiniGameBoardResetTest {

    private Character owner;
    private MiniGame game;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        owner = Mocks.chr("owner");
        MapleMap map = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map);

        game = new MiniGame(owner, "", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        when(owner.getMiniGame()).thenReturn(game);    // the announce-box packet reads it back off the player

        // The result packets read the visitor's stats, and MiniGame.addVisitor would build one through addCharLook,
        // which needs a real character's look. Seat the visitor directly instead: the scoring and board paths under
        // test never look at anything but its identity.
        Field visitor = MiniGame.class.getDeclaredField("visitor");
        visitor.setAccessible(true);
        visitor.set(game, Mocks.chr("visitor"));
    }

    /** Places stones for the owner; four of them never complete a line. */
    private void placeStonesInFirstRow(int count) {
        for (int x = 0; x < count; x++) {
            game.setPiece(x, 0, 1, owner);
        }
    }

    @Test
    void aGameEndedByForfeitLeavesNoStonesForTheNextGame() {
        game.minigameMatchStarted();
        placeStonesInFirstRow(4);
        assertEquals(1, game.getPiece(0, 0), "a stone the game placed");

        game.minigameMatchVisitorWins(true);   // what GIVE_UP and removeVisitor both end the game with
        assertFalse(game.isMatchInProgress());
        assertEquals(1, game.getPiece(0, 0), "a forfeit does not sweep the board by itself");

        game.minigameMatchStarted();           // the next game
        for (int x = 0; x < 4; x++) {
            assertEquals(0, game.getPiece(x, 0), "stone left over from the forfeited game at x=" + x);
        }

        game.setPiece(4, 0, 1, owner);         // completes five in a row against the previous game's stones
        assertTrue(game.isMatchInProgress(), "a lone stone must not complete a five-in-a-row on last game's board");
    }

    @Test
    void aForfeitDoesNotCarryTheRoundTallyIntoTheNextSeries() {
        game.setMatchesToWin(2);
        game.setGameType(MiniGame.MiniGameType.MATCH_CARD);

        game.minigameMatchStarted();
        game.setOwnerPoints();                 // the owner takes the first round of a best-of-two
        assertTrue(game.isMatchInProgress(), "one round of two does not decide the series");

        game.minigameMatchVisitorWins(true);   // the series is aborted by a forfeit with the tally at 1-0
        assertFalse(game.isMatchInProgress());

        game.minigameMatchStarted();           // a fresh series
        game.setVisitorPoints();               // the visitor takes its first round: 0-1, not 1-1
        assertTrue(game.isMatchInProgress(), "0-1 of a best-of-two must not decide anything");
    }
}
