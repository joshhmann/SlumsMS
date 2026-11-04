package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import net.packet.InPacket;
import net.server.handlers.login.DeleteCharHandler;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import service.NoteService;
import soloMapling.companions.OwnedAltService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Ticket 189 stage 1 (MA-09, MA-10, deletion guard): an owned alt never takes part in a wedding by packet. */
class AltMarriageGuardsTest {

    private static Character alt(Character owner) {
        Character alt = mock(Character.class);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        when(alt.getName()).thenReturn("Second");
        when(alt.getId()).thenReturn(22);
        return alt;
    }

    @Test
    void theRefusalNoLongerGivesAdviceThatCannotWork() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(1);
        String line = OwnedAltService.interactionRefusal(owner, alt(owner), "answer a proposal");
        assertFalse(line.contains("@dismiss"), line);
        assertTrue(line.contains("is your alt"), line);
    }

    @Test
    void inviteModeRefusesAnAltNameWithNoGuestEntry() {
        Client c = mock(Client.class);
        World world = mock(World.class, RETURNS_DEEP_STUBS);
        Character me = mock(Character.class, RETURNS_DEEP_STUBS);
        when(me.getId()).thenReturn(1);
        when(c.getPlayer()).thenReturn(me);
        when(c.getWorldServer()).thenReturn(world);
        when(me.haveItem(anyInt())).thenReturn(true);
        when(me.getInventory(any()).getItem(anyShort()).getItemId()).thenReturn(4031395);
        Character second = alt(me);
        when(world.getPlayerStorage().getCharacterByName("Second")).thenReturn(second);
        InPacket p = mock(InPacket.class);
        when(p.readByte()).thenReturn((byte) 5, (byte) 1);
        when(p.readString()).thenReturn("Second");
        when(p.readInt()).thenReturn(77);

        new RingActionHandler(mock(NoteService.class)).handlePacket(p, c);

        verify(me).dropMessage(eq(5), contains("alt"));
        verify(world, never()).addMarriageGuest(anyInt(), anyInt());
        verify(world, never()).getMarriageQueuedLocation(anyInt());
    }

    private void acceptWith(boolean altIsSource) {
        Client c = mock(Client.class);
        World world = mock(World.class, RETURNS_DEEP_STUBS);
        Character me = mock(Character.class);
        Character other = mock(Character.class);
        Character alt = alt(me);
        when(me.getId()).thenReturn(1);
        Character source = altIsSource ? alt : other;
        Character target = altIsSource ? me : alt;
        when(other.getId()).thenReturn(22);
        when(source.getId()).thenReturn(22);
        when(source.getMarriageItemId()).thenReturn(4031357);
        when(source.haveItem(4031357)).thenReturn(true);
        when(source.isAlive()).thenReturn(true);
        when(target.isAlive()).thenReturn(true);
        when(source.getName()).thenReturn("Src");
        when(c.getPlayer()).thenReturn(target);
        when(c.getWorldServer()).thenReturn(world);
        when(source.getClient()).thenReturn(c);
        when(world.getPlayerStorage().getCharacterByName("Src")).thenReturn(source);
        InPacket p = mock(InPacket.class);
        when(p.readByte()).thenReturn((byte) 2, (byte) 1);
        when(p.readString()).thenReturn("Src");
        when(p.readInt()).thenReturn(22);
        try (MockedStatic<InventoryManipulator> inv = mockStatic(InventoryManipulator.class)) {
            inv.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), any())).thenReturn(true);
            inv.when(() -> InventoryManipulator.checkSpace(any(Character.class), anyInt(), anyInt(), any())).thenReturn(true);
            new RingActionHandler(mock(NoteService.class)).handlePacket(p, c);
        }
        verify(world, never()).createRelationship(anyInt(), anyInt());
        verify(source, never()).setPartnerId(anyInt());
        verify(target, never()).setPartnerId(anyInt());
    }

    @Test
    void acceptingAProposalFromAnAltMakesNoMarriageRow() {
        acceptWith(true);
    }

    @Test
    void acceptingAsAnAltMakesNoMarriageRow() {
        acceptWith(false);
    }

    @Test
    void deletingAnEngagedOrMarriedCharacterIsRefused() {
        assertTrue(DeleteCharHandler.blockedByMarriage(22));
        assertFalse(DeleteCharHandler.blockedByMarriage(-1));
        assertFalse(DeleteCharHandler.blockedByMarriage(0));
    }
}
