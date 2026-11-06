package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.MapManager;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b335: {@code BotChannels.moveToChannel} swaps a bot onto the target channel's <b>shared</b> headless client
 * ({@code bot.setClient(BotClientHandler.getBotClient(ch))}). An owned alt does not use the shared client: it has a
 * private {@link BotClient} whose player is the alt ({@code OwnedAltBackend.load}), and its inventory operations and
 * disconnect go through that client. Swapping it out leaves the alt on a client with no player of its own - the shape
 * of b331's headless-disconnect path. An alt changes channel with its owner the alt way (saved and reloaded,
 * {@code OwnedAltService.ownerChangingChannel}), so the hop refuses an alt and leaves its client alone.
 * {@code BotFamily.arrive} is the caller that sends an alt through it today.
 */
class OwnedAltChannelMoveTest {

    private static final int FROM = 1;
    private static final int TO = 2;
    private static final int MAP = 100000000;

    @Test
    void anOwnedAltIsNeverPutOnTheChannelsSharedClient() {
        Channel from = channel(FROM);
        Channel to = channel(TO);
        BotClient own = mock(BotClient.class);
        when(own.getChannelServer()).thenReturn(from);
        when(own.getChannel()).thenReturn(FROM);

        Character alt = mock(Character.class);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getId()).thenReturn(46_000_001);
        when(alt.getName()).thenReturn("AltOfOwner");
        when(alt.getClient()).thenReturn(own);
        MapleMap here = mock(MapleMap.class);
        when(here.getId()).thenReturn(MAP);
        when(alt.getMap()).thenReturn(here);
        when(alt.getMapId()).thenReturn(MAP);
        when(alt.getPosition()).thenReturn(new Point(0, 0));
        when(alt.getWarpLock()).thenReturn(new ReentrantLock());

        try (MockedStatic<BotChannels> channels = Mockito.mockStatic(BotChannels.class, Mockito.CALLS_REAL_METHODS)) {
            channels.when(() -> BotChannels.channel(TO)).thenReturn(to);
            channels.when(() -> BotChannels.channel(FROM)).thenReturn(from);
            boolean moved;
            try {
                moved = BotChannels.moveToChannel(alt, TO);
            } catch (RuntimeException e) {
                moved = true; // it went past the swap and tripped on a mock further on: the swap is what is tested
            }
            assertFalse(moved, "an owned alt does not hop channels on the shared bot client");
        }
        verify(alt, never()).setClient(any());
        verify(from, never()).removePlayer(any());
        assertSame(own, alt.getClient(), "it keeps its own client");
    }

    private static Channel channel(int id) {
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(id);
        MapManager maps = mock(MapManager.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        when(maps.getMap(anyInt())).thenReturn(map);
        when(ch.getMapFactory()).thenReturn(maps);
        return ch;
    }
}
