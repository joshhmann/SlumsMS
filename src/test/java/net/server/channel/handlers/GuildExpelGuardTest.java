package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.server.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.5 P1-E2: a guildless player sending an expel packet is refused, not thrown at. */
class GuildExpelGuardTest {

    private static ByteBufInPacket expelPacket() {
        ByteBufOutPacket out = new ByteBufOutPacket();
        out.writeByte(0x08);
        out.writeInt(42);
        out.writeString("Victim");
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void aGuildlessExpelIsIgnoredWithoutThrowing() {
        Client c = mock(Client.class);
        Character sender = mock(Character.class);
        when(c.getPlayer()).thenReturn(sender);
        when(sender.getName()).thenReturn("Loner");
        when(sender.getGuildId()).thenReturn(0);
        when(sender.getGuild()).thenReturn(null);

        Server server = mock(Server.class);
        try (MockedStatic<Server> serverStatic = mockStatic(Server.class)) {
            serverStatic.when(Server::getInstance).thenReturn(server);

            assertDoesNotThrow(() -> new GuildOperationHandler().handlePacket(expelPacket(), c));

            verify(server, never()).expelMember(any(), anyString(), anyInt());
        }
    }
}
