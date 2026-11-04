package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.Ring;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.server.MapleRootSolo;
import tools.Pair;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ticket 188: a marriage record with no ring item must never soft-lock a player (F2 / MA-01 / F13). */
class RinglessMarriageTest {

    @Test
    void resetRingIdSurvivesAMissingRing() {
        Character chr = mock(Character.class);
        when(chr.getMarriageRing()).thenReturn(null);
        assertDoesNotThrow(() -> RingActionHandler.resetRingId(chr));
    }

    @Test
    void breakingARinglessBotMarriageClearsEverythingAndReenablesActions() {
        int bot = BotStore.BOT_ID_BASE + 5;
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        World world = mock(World.class);
        when(chr.getPartnerId()).thenReturn(bot);
        when(chr.getMarriageRing()).thenReturn(null);
        when(chr.getClient()).thenReturn(client);
        when(client.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(mock(net.server.PlayerStorage.class));
        try (var root = mockStatic(MapleRootSolo.class)) {
            assertDoesNotThrow(() -> RingActionHandler.breakMarriageRecord(chr));
        }
        verify(world).deleteRelationship(chr.getId(), bot);
        verify(chr).setPartnerId(-1);
        verify(chr).setMarriageItemId(-1);
        verify(chr).addMarriageRing(null);
        verify(chr, org.mockito.Mockito.atLeast(2)).sendPacket(any(net.packet.Packet.class)); // partner reset + enableActions
    }

    @Test
    void aPartnerWithoutRingOrTokenIsOrphaned() {
        assertTrue(RingActionHandler.isOrphanedPartner(12, -1, null));
        assertFalse(RingActionHandler.isOrphanedPartner(12, 4031357, null), "a real engagement");
        assertFalse(RingActionHandler.isOrphanedPartner(12, -1, mock(Ring.class)), "a real marriage");
        assertFalse(RingActionHandler.isOrphanedPartner(-1, -1, null), "single");
    }
}
