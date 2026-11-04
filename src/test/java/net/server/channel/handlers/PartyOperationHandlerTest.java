package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import net.server.world.World;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * b298 (ticket 168 class): a crafted party packet must not do what the client UI would not offer.
 * Case 6 (change leader) is leader-only; before the fix any member could send it and a partyless player NPE'd.
 */
class PartyOperationHandlerTest {

    private static ByteBufInPacket changeLeaderPacket(int newLeaderId) {
        ByteBufOutPacket out = new ByteBufOutPacket();
        out.writeByte(6);
        out.writeInt(newLeaderId);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void aNonLeaderCannotChangeThePartyLeader() {
        Client c = mock(Client.class);
        Character sender = mock(Character.class);
        World world = mock(World.class);
        Party party = mock(Party.class);
        when(c.getPlayer()).thenReturn(sender);
        when(c.getWorldServer()).thenReturn(world);
        when(sender.getParty()).thenReturn(party);
        when(sender.isPartyLeader()).thenReturn(false);
        when(party.getLeaderId()).thenReturn(1); // the sender is not it

        new PartyOperationHandler().handlePacket(changeLeaderPacket(2), c);

        verify(world, never()).updateParty(anyInt(), any(PartyOperation.class), any(PartyCharacter.class));
        verify(c).sendPacket(any());
    }

    @Test
    void aPartylessSenderIsRefusedInsteadOfThrowing() {
        Client c = mock(Client.class);
        Character sender = mock(Character.class);
        World world = mock(World.class);
        when(c.getPlayer()).thenReturn(sender);
        when(c.getWorldServer()).thenReturn(world);
        when(sender.getParty()).thenReturn(null);
        when(sender.isPartyLeader()).thenReturn(false);

        new PartyOperationHandler().handlePacket(changeLeaderPacket(2), c);

        verify(world, never()).updateParty(anyInt(), any(PartyOperation.class), any(PartyCharacter.class));
        verify(c).sendPacket(any());
    }

    @Test
    void theLeaderCanStillHandLeadershipOver() {
        Client c = mock(Client.class);
        Character sender = mock(Character.class);
        World world = mock(World.class);
        Party party = mock(Party.class);
        PartyCharacter next = mock(PartyCharacter.class);
        when(c.getPlayer()).thenReturn(sender);
        when(c.getWorldServer()).thenReturn(world);
        when(sender.getParty()).thenReturn(party);
        when(sender.isPartyLeader()).thenReturn(true);
        when(party.getId()).thenReturn(5);
        when(party.getMemberById(2)).thenReturn(next);

        new PartyOperationHandler().handlePacket(changeLeaderPacket(2), c);

        verify(world).updateParty(5, PartyOperation.CHANGE_LEADER, next);
    }
}
