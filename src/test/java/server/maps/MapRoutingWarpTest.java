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
    private static final int GOLLUX_ABDOMEN = 863010240;   // the client crash on TEST
    private static final int HEART_TREE = 863010000;       // Gollux: Abdomen's own returnMap
    private static final int ROSE_GARDEN = 211080000;      // Rose Garden : Gardener's Spot, loads fine
    private static final int ROSE_GARDEN_DEPTH = 211080100; // the first map past it: the client dies there
    private static final int ROOF_OF_THE_FIFTH_TOWER = 211061001;
    private static final int STORAGE_CELLAR = 993162016;

    private MapleMap fountain;
    private MapleMap cave;
    private MapleMap side;
    private MapleMap heartTree;
    private MapleMap roseGarden;
    private Client client;
    private Character chr;

    @BeforeEach
    void setUp() {
        fountain = new MapleMap(FOUNTAIN_SQUARE, 0, 1, 0, 1f);
        cave = new MapleMap(STORAGE_CAVE, 0, 1, 0, 1f);
        side = new MapleMap(SIDE_MAP, 0, 1, 0, 1f);
        heartTree = new MapleMap(HEART_TREE, 0, 1, 0, 1f);
        roseGarden = new MapleMap(ROSE_GARDEN, 0, 3, 0, 1f);

        MapManager maps = mock(MapManager.class);
        when(maps.getMap(FOUNTAIN_SQUARE)).thenReturn(fountain);
        when(maps.getMap(STORAGE_CAVE)).thenReturn(cave);
        when(maps.getMap(SIDE_MAP)).thenReturn(side);
        when(maps.getMap(HEART_TREE)).thenReturn(heartTree);
        when(maps.getMap(ROSE_GARDEN)).thenReturn(roseGarden);

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

        assertEquals(HEART_TREE, chr.getWarpMap(GOLLUX_ABDOMEN, null).getId());
    }

    @Test
    void aSideDoorIntoTheCrashMapLandsOnItsReturnMap() {
        chr.setMap(cave);

        assertEquals(HEART_TREE, chr.getWarpMap(GOLLUX_ABDOMEN, "east00").getId());
    }

    @Test
    void aScriptWarpIntoTheRoseGardenLandsAtTheGardener() {
        chr.setMap(new MapleMap(ROOF_OF_THE_FIFTH_TOWER, 0, 2, 0, 1f));   // the map before the Rose Garden

        MapleMap to = chr.getWarpMap(ROSE_GARDEN_DEPTH, null);

        assertEquals(ROSE_GARDEN, to.getId(), "the client dies past the entrance, so the door lands at the entrance");
        verify(client, times(1)).sendPacket(any());   // the one-line reroute message
    }

    @Test
    void theEastDoorOfTheRoseGardenIsADeadEnd() {
        chr.setMap(roseGarden);   // standing at Gardener's Spot, walking into east00

        MapleMap to = chr.getWarpMap(ROSE_GARDEN_DEPTH, "east00");

        assertNull(to, "the map past the entrance kills the client: there is nothing to warp to");
        verify(client, times(2)).sendPacket(any());   // the message and enableActions, as for Fountain Square's in00
        assertEquals(ROSE_GARDEN, chr.getMapId());
    }

    @Test
    void theWayIntoTheRoseGardenFromTheTowerIsUntouched() {
        chr.setMap(new MapleMap(ROOF_OF_THE_FIFTH_TOWER, 0, 2, 0, 1f));

        MapleMap to = chr.getWarpMap(ROSE_GARDEN, "next00");

        assertEquals(ROSE_GARDEN, to.getId(), "Gardener's Spot loads fine: the way in is an ordinary warp");
        verify(client, never()).sendPacket(any());
    }

    @Test
    void anOrdinaryWarpIsUntouched() {
        chr.setMap(fountain);

        MapleMap to = chr.getWarpMap(STORAGE_CAVE, "west00");

        assertEquals(STORAGE_CAVE, to.getId());
        verify(client, never()).sendPacket(any());
    }
}
