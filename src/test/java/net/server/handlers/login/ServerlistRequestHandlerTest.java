package net.server.handlers.login;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 41: the login screen lists Bera (id 1) alone, preselected, with its 8 channels. */
class ServerlistRequestHandlerTest {

    private static World bera() {
        List<Channel> channels = new ArrayList<>();
        for (int ch = 1; ch <= 8; ch++) {
            Channel c = mock(Channel.class);
            when(c.getId()).thenReturn(ch);
            when(c.getChannelCapacity()).thenReturn(0);
            channels.add(c);
        }
        World w = mock(World.class);
        when(w.getId()).thenReturn(1);
        when(w.getFlag()).thenReturn(0);
        when(w.getEventMessage()).thenReturn("Bera!");
        when(w.getChannels()).thenReturn(channels);
        return w;
    }

    private static InPacket read(Packet packet) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
    }

    @Test
    void beraAloneIsTheOnlyWorldListedAndIsPreselected() {
        List<Packet> packets = ServerlistRequestHandler.serverListPackets(List.of(bera()), 1,
                List.of(new Pair<>(1, "Welcome to Bera!")));
        assertEquals(4, packets.size());   // one world, end of list, last-connected world, recommended

        InPacket world = read(packets.get(0));
        assertEquals(SendOpcode.SERVERLIST.getValue(), world.readShort());
        assertEquals(1, world.readByte());
        assertEquals("Bera", world.readString());
        assertEquals(0, world.readByte());          // flag
        assertEquals("Bera!", world.readString());  // event message
        world.skip(5);                               // rate bytes
        assertEquals(8, world.readByte());
        assertEquals("Bera-1", world.readString());

        InPacket end = read(packets.get(1));
        assertEquals(SendOpcode.SERVERLIST.getValue(), end.readShort());
        assertEquals((byte) 0xFF, end.readByte());

        InPacket select = read(packets.get(2));
        assertEquals(SendOpcode.LAST_CONNECTED_WORLD.getValue(), select.readShort());
        assertEquals(1, select.readInt());

        InPacket recommended = read(packets.get(3));
        assertEquals(SendOpcode.RECOMMENDED_WORLD_MESSAGE.getValue(), recommended.readShort());
        assertEquals(1, recommended.readByte());
        assertEquals(1, recommended.readInt());
        assertEquals("Welcome to Bera!", recommended.readString());
    }
}
