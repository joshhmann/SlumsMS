package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.MiniGame;
import testutil.Mocks;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The READY/UN_READY/START/GIVE_UP/REQUEST_TIE/ANSWER_TIE/SKIP/MOVE_OMOK/SELECT_CARD branches dereferenced
 * {@code chr.getMiniGame()} with no null check. That is null whenever the player is not in a room; the throw was
 * caught at Client.java:222 and logged, but the {@code PacketCreator.enableActions()} next to it is commented out, so
 * the client was left with actions disabled. START also used to put a match in progress with no opponent, and
 * MOVE_OMOK fed unbounded coordinates straight into the board.
 */
class MiniGameHandlerGuardTest {

    private final PlayerInteractionHandler handler = new PlayerInteractionHandler();

    private Client client;
    private Character player;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        player = Mocks.chr("player");
        when(client.getPlayer()).thenReturn(player);
        when(client.tryacquireClient()).thenReturn(true);
    }

    private InPacket inPacket(int mode, int... bytes) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(mode);
        for (int b : bytes) {
            buf.writeByte(b);
        }
        return new ByteBufInPacket(buf);
    }

    private InPacket omokMove(int x, int y, int type) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(PlayerInteractionHandler.Action.MOVE_OMOK.getCode());
        buf.writeIntLE(x);
        buf.writeIntLE(y);
        buf.writeByte(type);
        return new ByteBufInPacket(buf);
    }

    private MiniGame roomWithOwner() {
        MiniGame game = new MiniGame(player, "", "");
        game.setGameType(MiniGame.MiniGameType.OMOK);
        when(player.getMiniGame()).thenReturn(game);
        when(player.getMap()).thenReturn(mock(MapleMap.class));
        return game;
    }

    @Test
    void omokMoveOutsideARoomDoesNotThrowAndReenablesActions() {
        when(player.getMiniGame()).thenReturn(null);

        assertDoesNotThrow(() -> handler.handlePacket(omokMove(0, 0, 1), client));

        verify(client).sendPacket(any(Packet.class));    // enableActions: the client is not left frozen
    }

    @Test
    void aReadyOutsideARoomDoesNotThrow() {
        when(player.getMiniGame()).thenReturn(null);

        assertDoesNotThrow(() -> handler.handlePacket(
                inPacket(PlayerInteractionHandler.Action.READY.getCode()), client));
        verify(client).sendPacket(any(Packet.class));
    }

    @Test
    void startingWithoutAnOpponentDoesNotStartAMatch() {
        MiniGame game = roomWithOwner();
        when(player.getMiniGame()).thenReturn(game);

        assertDoesNotThrow(() -> handler.handlePacket(
                inPacket(PlayerInteractionHandler.Action.START.getCode()), client));

        assertFalse(game.isMatchInProgress(), "a game with no visitor must not be in progress");
    }

    @Test
    void aMoveOffTheBoardDoesNotBreakTheRoom() {
        MiniGame game = roomWithOwner();
        game.minigameMatchStarted();

        assertDoesNotThrow(() -> handler.handlePacket(omokMove(0, 100, 1), client));

        assertTrue(game.isMatchInProgress(), "a bogus move must not end or corrupt the match");
    }

    @Test
    void aCardPickWithAnOutOfRangeSlotDoesNotThrow() {
        MiniGame game = new MiniGame(player, "", "");
        game.setMatchesToWin(6);
        game.setGameType(MiniGame.MiniGameType.MATCH_CARD);
        when(player.getMiniGame()).thenReturn(game);

        // turn = 1 (first pick) with a slot past the 12-card list
        assertDoesNotThrow(() -> handler.handlePacket(
                inPacket(PlayerInteractionHandler.Action.SELECT_CARD.getCode(), 1, 99), client));

        assertEquals(0, game.getFirstSlot(), "an out-of-range slot must not be remembered as the first pick");
        verify(client).sendPacket(any(Packet.class));
    }
}
