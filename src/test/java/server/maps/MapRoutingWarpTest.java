package server.maps;

import client.Character;
import client.Client;
import net.server.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * s-maproute: the character funnel every script warp, NPC warp, {@code !warp}, {@code @goto} and GM warp goes through
 * ({@code Character.getWarpMap} and the {@code changeMap} overloads that call it). No WZ and no database: the channel's
 * map factory is a mock that only has the maps the routes land on.
 *
 * Before this change the same calls loaded the map factory's null and threw (GenericPortal caught and printed it; a
 * script warp did not), or put the character in a map the client cannot survive.
 */
class MapRoutingWarpTest {

    private static final int FOUNTAIN_SQUARE = 410000300;
    private static final int STORAGE_CAVE = 410000310;
    private static final int SIDE_MAP = 410000325;
    private static final int RAMPART_RESIDENCES = 410000350;
    private static final int STORAGE_CELLAR = 993162016;

    private MapleMap fountain;
    private MapleMap cave;
    private MapleMap side;
    private Client client;
    private Character chr;

    @BeforeEach
    void setUp() {
        fountain = new MapleMap(FOUNTAIN_SQUARE, 0, 1, 0, 1f);
        cave = new MapleMap(STORAGE_CAVE, 0, 1, 0, 1f);
        side = new MapleMap(SIDE_MAP, 0, 1, 0, 1f);

        MapManager maps = mock(MapManager.class);
        when(maps.getMap(FOUNTAIN_SQUARE)).thenReturn(fountain);
        when(maps.getMap(STORAGE_CAVE)).thenReturn(cave);
        when(maps.getMap(SIDE_MAP)).thenReturn(side);

        Channel channel = mock(Channel.class);
        when(channel.getMapFactory()).thenReturn(maps);
        client = mock(Client.class);
        when(client.getChannelServer()).thenReturn(channel);

        chr = Character.getDefault(client);
        chr.setName("Walker");
    }

    @Test
    void theFountainSquareInDoorLeavesThePlayerWhereTheyAre() {
        chr.setMap(fountain);   // standing in Fountain Square, walking into in00

        MapleMap to = chr.getWarpMap(STORAGE_CELLAR, "in00");

        assertNull(to, "the Storage Cellar has no image: there is nothing to warp to");
        verify(client, times(2)).sendPacket(any());   // the one-line message and enableActions, so no stuck input
        assertEquals(FOUNTAIN_SQUARE, chr.getMapId());
    }

    @Test
    void aScriptWarpIntoAMissingMapIsReroutedNotThrown() {
        chr.setMap(cave);   // a script's warp(993162016), no door known

        MapleMap to = chr.getWarpMap(STORAGE_CELLAR, null);

        assertNotNull(to, "a script warp must land somewhere");
        assertEquals(FOUNTAIN_SQUARE, to.getId());
    }

    @Test
    void aScriptWarpIntoTheCrashMapLandsOnItsReturnMap() {
        chr.setMap(side);

        assertEquals(FOUNTAIN_SQUARE, chr.getWarpMap(RAMPART_RESIDENCES, null).getId());
    }

    @Test
    void aSideDoorIntoTheCrashMapLandsOnItsReturnMap() {
        chr.setMap(cave);

        assertEquals(FOUNTAIN_SQUARE, chr.getWarpMap(RAMPART_RESIDENCES, "east00").getId());
    }

    @Test
    void anOrdinaryWarpIsUntouched() {
        chr.setMap(fountain);

        MapleMap to = chr.getWarpMap(STORAGE_CAVE, "west00");

        assertEquals(STORAGE_CAVE, to.getId());
        verify(client, never()).sendPacket(any());
    }
}
