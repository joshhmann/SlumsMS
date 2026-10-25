package net.server.channel.handlers;

import client.Character;
import client.Client;
import constants.id.MapId;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import server.maps.FieldLimit;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 handler sweep: MapleRoot turned the MTS button into an "FM button" that warps to the hub, and dropped the stock
 * MTS button's CANNOTMIGRATE check on the way, so the button walked a player out of jail or out of any boss or
 * party-quest map that was not an event instance.
 */
class EnterMTSHandlerTest {

    private static Character playerOn(int mapId, int fieldLimit, int characterId) {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(map.getFieldLimit()).thenReturn(fieldLimit);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(mapId);
        when(chr.getId()).thenReturn(characterId);
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    private static Client clientOf(Character chr) {
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);
        when(c.getPlayer()).thenReturn(chr);
        return c;
    }

    @Test
    void aJailedPlayerStaysInJail() {
        Character chr = playerOn(MapId.JAIL, 0, 9101);
        new EnterMTSHandler().handlePacket(mock(InPacket.class), clientOf(chr));
        verify(chr, never()).changeMap(any(MapleMap.class), anyInt());
        verify(chr, never()).saveLocation(any());
    }

    @Test
    void aMapThatForbidsLeavingKeepsThePlayer() {
        Character chr = playerOn(280030000, (int) FieldLimit.CANNOTMIGRATE.getValue(), 9102);
        new EnterMTSHandler().handlePacket(mock(InPacket.class), clientOf(chr));
        verify(chr, never()).changeMap(any(MapleMap.class), anyInt());
    }

    @Test
    void anOrdinaryMapStillWarpsToTheHubOncePerCooldown() {
        Character chr = playerOn(100000000, 0, 9103);
        Client c = clientOf(chr);
        new EnterMTSHandler().handlePacket(mock(InPacket.class), c);
        new EnterMTSHandler().handlePacket(mock(InPacket.class), c); // a spammed second press
        verify(chr, times(1)).changeMap(any(MapleMap.class), anyInt());
        verify(chr, times(1)).saveCharToDB();
    }

    @Test
    void refusalReasons() {
        assertNotNull(EnterMTSHandler.fmButtonRefusal(MapId.JAIL, 0));
        assertNotNull(EnterMTSHandler.fmButtonRefusal(MapId.MAPLEROOT_HUB, 0));
        assertNotNull(EnterMTSHandler.fmButtonRefusal(100000000, (int) FieldLimit.CANNOTMIGRATE.getValue()));
        assertNull(EnterMTSHandler.fmButtonRefusal(100000000, 0));
    }
}
