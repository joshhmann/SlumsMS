package client;

import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.world.World;
import net.server.channel.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic ae5753df0): changeMapInternal checks that the character is still in
 * its channel's player storage and then adds it to the new map. A logout landing between the two (its own map
 * removal saw the OLD map) left the logged-out character in the new map's lists: a ghost that every later entrant
 * got spawn data for and, once disposed, broke map broadcasts. The warp now re-checks after addPlayer and unwinds.
 */
class WarpDisconnectUnwindTest {

    private static Character inStorage(PlayerStorage storage, MapleMap from) throws Exception {
        Client client = mock(Client.class);
        Channel channel = mock(Channel.class);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        Character chr = Character.getDefault(client);
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(chr, 4242);
        Field map = AbstractCharacterObject.class.getDeclaredField("map");
        map.setAccessible(true);
        map.set(chr, from);
        chr.setName("Warper");
        storage.addPlayer(chr);
        return chr;
    }

    private static void changeMapInternal(Character chr, MapleMap to) throws Exception {
        Method m = Character.class.getDeclaredMethod("changeMapInternal", MapleMap.class, Point.class, Packet.class);
        m.setAccessible(true);
        Server server = mock(Server.class);
        when(server.getWorld(anyInt())).thenReturn(mock(World.class));
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            m.invoke(chr, to, new Point(0, 0), mock(Packet.class));
        } catch (InvocationTargetException e) {
            // the rest of a real warp needs a real map; only the unwind decision is under test
        }
    }

    @Test
    void aLogoutLandingDuringTheMapAddIsUnwound() throws Exception {
        PlayerStorage storage = new PlayerStorage();
        Character chr = inStorage(storage, mock(MapleMap.class));
        MapleMap to = mock(MapleMap.class);
        doAnswer(inv -> {
            storage.removePlayer(chr.getId());   // the logout on another thread, landing right now
            return null;
        }).when(to).addPlayer(any(Character.class));

        changeMapInternal(chr, to);

        verify(to).removePlayer(chr);
    }

    @Test
    void anOrdinaryWarpIsNotUnwound() throws Exception {
        PlayerStorage storage = new PlayerStorage();
        Character chr = inStorage(storage, mock(MapleMap.class));
        MapleMap to = mock(MapleMap.class);

        changeMapInternal(chr, to);

        verify(to).addPlayer(chr);
        verify(to, never()).removePlayer(chr);
    }
}
