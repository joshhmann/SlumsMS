package server.maps;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testutil.Mocks;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code denyTie} used to OR its bit straight into {@code inprogress}, the field {@code isMatchInProgress()} reads.
 * REQUEST_TIE/ANSWER_TIE have no isMatchInProgress gate in the handler, so a client that asked for a tie and then
 * denied it before START left a room that claimed to be mid-match; removeVisitor then credited the owner a forfeit
 * win for a game that never started.
 *
 * <p>The crossed bits themselves are correct and unchanged: {@code denyTie} records <em>my</em> refusal and
 * {@code isTieDenied(X)} reads the opponent's bit.
 */
class MiniGameTieDenialTest {

    private Character owner;
    private Character visitor;
    private MiniGame game;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        owner = Mocks.chr("owner");
        visitor = Mocks.chr("visitor");
        when(owner.getMap()).thenReturn(mock(MapleMap.class));

        game = new MiniGame(owner, "", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        when(owner.getMiniGame()).thenReturn(game);    // the announce-box packet reads it back off the player

        // MiniGame.addVisitor builds a packet through addCharLook, which needs a real character's look; seat the
        // visitor directly instead. Nothing under test here looks at it beyond its identity.
        Field v = MiniGame.class.getDeclaredField("visitor");
        v.setAccessible(true);
        v.set(game, visitor);
    }

    @Test
    void aTieDenialBeforeTheMatchStartsDoesNotFabricateAMatchInProgress() {
        game.denyTie(owner);

        assertFalse(game.isMatchInProgress(), "no game has started: a tie refusal is not a running match");
    }

    @Test
    void aTieDenialStillReportsTheOpponentsRefusal() {
        game.denyTie(owner);

        assertTrue(game.isTieDenied(visitor), "the visitor asking for a tie must see the owner's refusal");
        assertFalse(game.isTieDenied(owner), "the owner's own refusal is not what the owner reads back");
    }

    @Test
    void aTieDenialDuringAMatchDoesNotEndIt() {
        game.minigameMatchStarted();

        game.denyTie(visitor);

        assertTrue(game.isMatchInProgress(), "refusing a tie must not end the game");
        assertTrue(game.isTieDenied(owner), "the owner asking for a tie must see the visitor's refusal");
        assertFalse(game.isTieDenied(visitor));
    }

    @Test
    void aNewGameForgetsThePreviousGamesTieRefusal() {
        game.minigameMatchStarted();
        game.denyTie(owner);
        game.minigameMatchVisitorWins(true);

        game.minigameMatchStarted();

        assertFalse(game.isTieDenied(visitor), "the next game must be able to ask for a tie again");
    }
}
