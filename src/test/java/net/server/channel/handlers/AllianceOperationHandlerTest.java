package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.server.Server;
import net.server.guild.Alliance;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * b298 (ticket 168 class): a crafted alliance packet must not do what the client UI would not offer.
 * 0x06 (expel guild) is alliance-leader only and only for guilds of the sender's alliance.
 */
class AllianceOperationHandlerTest {

    private static final int ALLIANCE_ID = 7;
    private static final int SENDER_GUILD_ID = 11;
    private static final int OTHER_GUILD_ID = 12;

    private MockedStatic<Server> serverStatic;
    private Server server;
    private Alliance alliance;
    private Client c;

    @BeforeEach
    void setUp() {
        server = mock(Server.class);
        serverStatic = mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
        when(server.getGuild(anyInt())).thenAnswer(inv -> guild(inv.getArgument(0)));
        when(server.getGuild(anyInt(), anyInt())).thenAnswer(inv -> guild(inv.getArgument(0)));
        when(server.getGuild(anyInt(), anyInt(), any())).thenAnswer(inv -> guild(inv.getArgument(0)));

        alliance = mock(Alliance.class);
        when(alliance.getId()).thenReturn(ALLIANCE_ID);
        when(alliance.getName()).thenReturn("TestAlliance");
        when(alliance.getRankTitle(anyInt())).thenReturn("Rank");
        when(alliance.getGuilds()).thenReturn(List.of(SENDER_GUILD_ID, OTHER_GUILD_ID));
        when(alliance.getCapacity()).thenReturn(10);
        when(alliance.getNotice()).thenReturn("notice");

        c = mock(Client.class);
    }

    @AfterEach
    void tearDown() {
        serverStatic.close();
    }

    private static Guild guild(int id) {
        Guild g = mock(Guild.class);
        when(g.getId()).thenReturn(id);
        when(g.getName()).thenReturn("Guild" + id);
        when(g.getRankTitle(anyInt())).thenReturn("Rank");
        when(g.getMembers()).thenReturn(List.of());
        when(g.getCapacity()).thenReturn(10);
        when(g.getNotice()).thenReturn("notice");
        return g;
    }

    private static ByteBufInPacket expelGuildPacket(int guildId) {
        ByteBufOutPacket out = new ByteBufOutPacket();
        out.writeByte(0x06);
        out.writeInt(guildId);
        out.writeInt(ALLIANCE_ID);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    private void sender(int allianceRank) {
        Character chr = mock(Character.class);
        Guild guild = mock(Guild.class);
        GuildCharacter mgc = mock(GuildCharacter.class);
        when(chr.getGuild()).thenReturn(guild);
        when(chr.getGuildId()).thenReturn(SENDER_GUILD_ID);
        when(guild.getAllianceId()).thenReturn(ALLIANCE_ID);
        when(chr.getMGC()).thenReturn(mgc);
        when(mgc.getAllianceRank()).thenReturn(allianceRank);
        when(chr.getAlliance()).thenReturn(alliance);
        when(c.getPlayer()).thenReturn(chr);
    }

    @Test
    void aRankTwoSenderCannotExpelAGuild() {
        sender(2); // rank 2 passed the old shared gate

        new AllianceOperationHandler().handlePacket(expelGuildPacket(OTHER_GUILD_ID), c);

        verify(server, never()).removeGuildFromAlliance(anyInt(), anyInt());
        verify(c).sendPacket(any());
    }

    @Test
    void theLeaderCannotExpelAGuildOfAnotherAlliance() {
        sender(1);

        new AllianceOperationHandler().handlePacket(expelGuildPacket(99), c); // guild 99 is not in the alliance

        verify(server, never()).removeGuildFromAlliance(anyInt(), anyInt());
        verify(c).sendPacket(any());
    }
}
