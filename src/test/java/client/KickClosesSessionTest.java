package client;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.AbstractPacketHandler;
import net.PacketProcessor;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.server.Server;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ThreadManager;
import tools.DatabaseConnection;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * b352: a server-side kick (!dc, ban, autoban, a duplicate login, a cheat check) ran the logout save on another
 * thread and never closed the socket, so the kicked client's packets kept being handled - also after the save had
 * been taken, where an item drop leaves the map with the item while the database still holds it. The interleaving is
 * forced: the drop arrives from inside the logout itself, right after the save.
 */
class KickClosesSessionTest {

    private MockedStatic<ThreadManager> threads;
    private MockedStatic<Server> server;
    private MockedStatic<DatabaseConnection> db;
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> applied = new ArrayList<>();

    /** Stands in for the item-drop handler: records that the server acted on the packet. */
    private final AbstractPacketHandler dropHandler = new AbstractPacketHandler() {
        @Override
        public void handlePacket(InPacket p, Client c) {
            applied.add("drop");
        }
    };

    @BeforeEach
    void setUp() {
        ThreadManager tm = mock(ThreadManager.class);
        doAnswer(inv -> tasks.add(inv.getArgument(0))).when(tm).newTask(any());
        threads = mockStatic(ThreadManager.class);
        threads.when(ThreadManager::getInstance).thenReturn(tm);
        Server s = mock(Server.class, RETURNS_DEEP_STUBS);
        when(s.getWorld(anyInt())).thenReturn(mock(World.class, RETURNS_DEEP_STUBS));
        server = mockStatic(Server.class);
        server.when(Server::getInstance).thenReturn(s);
        db = mockStatic(DatabaseConnection.class);
        db.when(DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
    }

    @AfterEach
    void tearDown() {
        db.close();
        server.close();
        threads.close();
    }

    private Client loggedInClient(EmbeddedChannel socket, Character chr) throws Exception {
        PacketProcessor processor = mock(PacketProcessor.class);
        when(processor.getHandler((short) RecvOpcode.ITEM_MOVE.getValue())).thenReturn(dropHandler);
        Client c = Client.createChannelClient(352, "127.0.0.1", processor, 0, 1);
        c.useChannelForTest(socket);
        Field loggedIn = Client.class.getDeclaredField("loggedIn");
        loggedIn.setAccessible(true);
        loggedIn.setBoolean(c, true);
        c.setPlayer(chr);
        return c;
    }

    private static InPacket drop() {
        short op = (short) RecvOpcode.ITEM_MOVE.getValue();
        return new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{(byte) op, (byte) (op >> 8), 0, 0}));
    }

    /** A logged-in character whose logout save is followed, on the "netty thread", by a drop packet. */
    private Character characterDroppingAfterSave(Client[] self, List<String> order) {
        Character chr = mock(Character.class, RETURNS_DEEP_STUBS);
        when(chr.isLoggedin()).thenReturn(true);
        when(chr.isOwnedAlt()).thenReturn(false);
        when(chr.getClient()).thenAnswer(inv -> self[0]);
        when(chr.getMessenger()).thenReturn(null);
        when(chr.getGuild()).thenReturn(null);
        when(chr.getMGC()).thenReturn(null);
        when(chr.getMap()).thenReturn(null);
        when(chr.getEventInstance()).thenReturn(null);
        when(chr.getMonsterCarnival()).thenReturn(null);
        when(chr.getAriantColiseum()).thenReturn(null);
        doAnswer(inv -> { // logOff runs right after the save: a packet read now is a post-save packet
            if (savedBefore(chr)) {
                order.add("save");
            }
            order.add("post-save packet");
            self[0].channelRead(null, drop());
            return null;
        }).when(chr).logOff();
        return chr;
    }

    /** The logout save (saveCharToDB, whatever its signature on this branch) ran before now. */
    private static boolean savedBefore(Character chr) {
        return org.mockito.Mockito.mockingDetails(chr).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("saveCharToDB"));
    }

    @Test
    void aKickedClientsPacketAfterTheLogoutSaveIsNotActedOn() throws Exception {
        EmbeddedChannel socket = new EmbeddedChannel();
        Client[] self = new Client[1];
        List<String> order = new ArrayList<>();
        Client c = loggedInClient(socket, characterDroppingAfterSave(self, order));
        self[0] = c;

        c.channelRead(null, drop()); // still in game: handled
        assertEquals(List.of("drop"), applied);

        c.disconnect(false, false); // !dc / ban / duplicate login: the logout is queued on another thread
        c.channelRead(null, drop()); // a packet that arrives while the logout is pending
        assertEquals(1, tasks.size());
        tasks.remove(0).run(); // the logout itself: removal, save, then the post-save packet

        assertEquals(List.of("save", "post-save packet"), order, "the interleaving happened");
        assertEquals(List.of("drop"), applied, "no packet after the kick began may be acted on");
        assertFalse(socket.isOpen(), "a server-side kick closes the socket");
    }

    @Test
    void aNormalQuitStillSavesAndClosesOnce() throws Exception {
        EmbeddedChannel socket = new EmbeddedChannel();
        Client[] self = new Client[1];
        List<String> order = new ArrayList<>();
        Character chr = characterDroppingAfterSave(self, order);
        Client c = loggedInClient(socket, chr);
        self[0] = c;

        socket.close(); // the player quits: the socket is gone first
        c.channelInactive(null);
        assertEquals(1, tasks.size());
        tasks.remove(0).run();

        assertTrue(savedBefore(chr), "the logout save still runs");
        assertFalse(socket.isOpen());
    }

    @Test
    void aChannelChangeNeitherRefusesPacketsNorClosesTheSocket() throws Exception {
        EmbeddedChannel socket = new EmbeddedChannel();
        Client[] self = new Client[1];
        Client c = loggedInClient(socket, characterDroppingAfterSave(self, new ArrayList<>()));
        self[0] = c;

        c.channelRead(null, drop());
        c.setCharacterOnSessionTransitionState(1); // the channel change hands the character over
        c.channelInactive(null); // the client leaves for the new channel: no logout (in transition)

        assertEquals(List.of("drop"), applied);
        assertTrue(tasks.isEmpty(), "a channel change does not log out");
    }
}
