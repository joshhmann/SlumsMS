package soloMapling.companions;

import client.Character;
import client.Client;
import net.packet.InPacket;
import net.server.Server;
import net.server.channel.Channel;
import net.server.channel.handlers.PlayerLoggedinHandler;
import net.server.coordinator.session.Hwid;
import net.server.coordinator.session.SessionCoordinator;
import net.server.world.World;
import net.server.PlayerStorage;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import tools.PacketCreator;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OwnedAltLoginTest {
    @Test void realLoginHookFinishesAltSaveBeforeReadingCharacterRow() throws Exception {
        var db = new OwnedAltServiceTest.Memory(); db.add(2);
        var owner = OwnedAltServiceTest.owner(1);
        var alts = new OwnedAltService(db, () -> true);
        Character alt = alts.summon(owner, "Alt2"); when(alt.isOwnedAlt()).thenReturn(true);
        Client c = mock(Client.class); when(c.tryacquireClient()).thenReturn(true);
        when(c.getWorld()).thenReturn(1); when(c.getChannel()).thenReturn(1);
        Server server = mock(Server.class); World world = mock(World.class); Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(server.getWorld(1)).thenReturn(world); when(world.getChannel(1)).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(2)).thenAnswer(inv -> db.online.contains(2) ? alt : null);
        when(server.validateCharacteridInTransition(c, 2)).thenReturn(true);
        SessionCoordinator sessions = mock(SessionCoordinator.class);
        when(sessions.pickLoginSessionHwid(c)).thenReturn(mock(Hwid.class));
        InPacket packet = mock(InPacket.class); when(packet.readInt()).thenReturn(2);
        AtomicBoolean loaded = new AtomicBoolean();
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<SessionCoordinator> session = mockStatic(SessionCoordinator.class);
             MockedStatic<OwnedAltService> service = mockStatic(OwnedAltService.class);
             MockedStatic<Character> chars = mockStatic(Character.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            session.when(SessionCoordinator::getInstance).thenReturn(sessions);
            service.when(OwnedAltService::getInstance).thenReturn(alts);
            chars.when(() -> Character.loadCharFromDB(2, c, true)).thenAnswer(inv -> {
                assertEquals(1, db.saves.get()); assertFalse(db.online.contains(2));
                assertThrows(IllegalStateException.class, () -> alts.summon(owner, "Alt2"));
                loaded.set(true); return null; // stop here: remaining player login is unchanged
            });
            new PlayerLoggedinHandler(null).handlePacket(packet, c);
        }
        assertTrue(loaded.get()); verify(c).releaseClient();
        try (var lease = alts.beginLogin(2)) { assertEquals(1, db.saves.get()); }
    }
    @Test void pendingSaveMakesHandlerReturnWithoutReadingDatabase() throws Exception {
        var alts = mock(OwnedAltService.class);
        when(alts.beginLogin(2)).thenThrow(new IllegalStateException("saving"));
        Client c = mock(Client.class); when(c.tryacquireClient()).thenReturn(true);
        Server server = mock(Server.class); World world = mock(World.class); Channel channel = mock(Channel.class);
        when(server.getWorld(anyInt())).thenReturn(world); when(world.getChannel(anyInt())).thenReturn(channel);
        when(world.getPlayerStorage()).thenReturn(mock(PlayerStorage.class));
        when(server.validateCharacteridInTransition(c, 2)).thenReturn(true);
        SessionCoordinator sessions = mock(SessionCoordinator.class);
        when(sessions.pickLoginSessionHwid(c)).thenReturn(mock(Hwid.class));
        InPacket packet = mock(InPacket.class); when(packet.readInt()).thenReturn(2);
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<SessionCoordinator> session = mockStatic(SessionCoordinator.class);
             MockedStatic<OwnedAltService> service = mockStatic(OwnedAltService.class);
             MockedStatic<Character> chars = mockStatic(Character.class);
             MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            session.when(SessionCoordinator::getInstance).thenReturn(sessions);
            service.when(OwnedAltService::getInstance).thenReturn(alts);
            new PlayerLoggedinHandler(null).handlePacket(packet, c);
            chars.verifyNoInteractions(); packets.verify(() -> PacketCreator.getAfterLoginError(10));
        }
        verify(c).releaseClient();
    }
    @Test void concurrentPacketOnSameClientCannotReleaseAnotherLoginLock() {
        Client c = mock(Client.class); when(c.tryacquireClient()).thenReturn(false);
        InPacket packet = mock(InPacket.class); when(packet.readInt()).thenReturn(2);
        try (MockedStatic<Server> servers = mockStatic(Server.class);
             MockedStatic<OwnedAltService> services = mockStatic(OwnedAltService.class)) {
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            new PlayerLoggedinHandler(null).handlePacket(packet, c);
            services.verifyNoInteractions(); verify(c,never()).releaseClient();
            verify(c,never()).disconnect(anyBoolean(),anyBoolean());
        }
    }

}
