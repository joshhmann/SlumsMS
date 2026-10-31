package net.server;

import client.Character;
import client.Client;
import client.command.commands.gm2.WarpCommand;
import config.YamlConfig;
import constants.id.MapId;
import net.server.channel.Channel;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.maps.GenericPortal;
import server.maps.MapManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * s-maproute round 2: every way into a map, not only {@code getWarpMap}. Each of these used to throw or move the player
 * into the silent fallback map (Henesys for an id with no image):
 * <ul>
 *     <li>a range portal ({@code GenericPortal.enterPortal} for Fountain Square's {@code in00} to the Storage Cellar),
 *     which must leave the player standing with an enableActions and no exception;</li>
 *     <li>a script warp ({@code cm.warp}) and a script force warp, which are rerouted;</li>
 *     <li>{@code !warp <id>}, which must say the id is invalid instead of moving the GM to Henesys;</li>
 *     <li>an event instance's own map, which is entered as asked: the table is about ids the server cannot load, not
 *     about a live instance.</li>
 * </ul>
 * The map objects are mocks, so {@code addPlayer} is asserted rather than performed (a real one starts the map's item
 * monitor, which needs a booted world); {@code Character} and {@code MapRouting} are real.
 */
class MapRoutingEntryPathsTest {

    private static final int HENESYS = 100000000;
    private static final int FOUNTAIN_SQUARE = 410000300;
    private static final int STORAGE_CAVE = 410000310;
    private static final int GOLLUX_ABDOMEN = 863010240;      // the client crash on TEST
    private static final int HEART_TREE = 863010000;          // Gollux: Abdomen's own returnMap
    private static final int STORAGE_CELLAR = 993162016;
    private static final int WORLD = 1;

    private int savedVisitedSize;
    private MapleMap fountain;
    private MapleMap cave;
    private MapleMap heartTree;
    private MapManager maps;
    private Channel channel;
    private Client client;
    private Character chr;

    @BeforeEach
    void setUp() {
        // not loaded from config.yaml here: visitMap removes the oldest entry once the list is full, so 0 breaks it
        savedVisitedSize = YamlConfig.config.server.MAP_VISITED_SIZE;
        if (savedVisitedSize == 0) {
            YamlConfig.config.server.MAP_VISITED_SIZE = 10;
        }

        World world = mock(World.class);        // a warp's closePlayerInteractions reads the character's world
        when(world.getId()).thenReturn(WORLD);
        Server.getInstance().resetServerWorlds();
        Server.getInstance().registerWorld(world, 0, Map.of(), "map routing test");

        fountain = map(FOUNTAIN_SQUARE);
        cave = map(STORAGE_CAVE);
        heartTree = map(HEART_TREE);

        maps = mock(MapManager.class);
        when(maps.getMap(FOUNTAIN_SQUARE)).thenReturn(fountain);
        when(maps.getMap(STORAGE_CAVE)).thenReturn(cave);
        when(maps.getMap(HEART_TREE)).thenReturn(heartTree);

        PlayerStorage storage = mock(PlayerStorage.class);
        channel = mock(Channel.class);
        when(channel.getMapFactory()).thenReturn(maps);
        when(channel.getPlayerStorage()).thenReturn(storage);

        client = mock(Client.class);
        when(client.getChannelServer()).thenReturn(channel);

        chr = Character.getDefault(client);
        chr.setName("Walker");
        chr.setWorld(WORLD);
        when(client.getPlayer()).thenReturn(chr);
        when(storage.getCharacterById(anyInt())).thenReturn(chr);
        chr.setMap(fountain);
    }

    @AfterEach
    void tearDown() {
        Server.getInstance().resetServerWorlds();
        YamlConfig.config.server.MAP_VISITED_SIZE = savedVisitedSize;
    }

    private static MapleMap map(int mapid) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapid);
        Portal spawn = portal(0, "sp", MapId.NONE);
        when(map.getPortal(0)).thenReturn(spawn);
        when(map.getPortal("sp")).thenReturn(spawn);
        when(map.getRandomPlayerSpawnpoint()).thenReturn(spawn);
        return map;
    }

    private static GenericPortal portal(int id, String name, int targetMapId) {
        GenericPortal portal = new GenericPortal(0);
        portal.setId(id);
        portal.setName(name);
        portal.setPosition(new Point(0, 0));
        portal.setTarget(name);
        portal.setTargetMapId(targetMapId);
        return portal;
    }

    @Test
    void theFountainSquareRangePortalSaysSoAndLeavesThePlayerStanding() {
        GenericPortal in00 = portal(3, "in00", STORAGE_CELLAR);   // the door to the Storage Cellar, whose image is missing

        assertDoesNotThrow(() -> in00.enterPortal(client));

        assertEquals(FOUNTAIN_SQUARE, chr.getMapId(), "a door that leads nowhere keeps the player where they are");
        assertSame(fountain, chr.getMap());
        // MapRouting's message and enableActions, then enterPortal's own enableActions: never a stuck input
        verify(client, times(3)).sendPacket(any());
        verify(maps, never()).getMap(STORAGE_CELLAR);
    }

    @Test
    void aScriptWarpIntoTheMissingMapLandsInFountainSquare() {
        chr.setMap(cave);                // from Fountain Square the cellar is a dead end (see the portal test)
        chr.changeMap(STORAGE_CELLAR);   // cm.warp(993162016)

        assertEquals(FOUNTAIN_SQUARE, chr.getMapId());
        assertSame(fountain, chr.getMap());
        verify(fountain).addPlayer(chr);
        verify(maps, never()).getMap(STORAGE_CELLAR);
    }

    @Test
    void aScriptForceWarpIntoTheCrashMapLandsOnItsReturnMap() {
        MapleMap crashMap = map(GOLLUX_ABDOMEN);
        chr.setMap(cave);

        assertDoesNotThrow(() -> chr.forceChangeMap(crashMap, null));

        assertEquals(HEART_TREE, chr.getMapId(), "the client crashes on 863010240, so it must not be entered");
        verify(heartTree).addPlayer(chr);
        verify(crashMap, never()).addPlayer(chr);
    }

    @Test
    void aGmWarpToAMapWithNoImageIsRefusedByNameInsteadOfLandingInHenesys() {
        Character gm = mock(Character.class);
        when(gm.isAlive()).thenReturn(true);
        when(gm.isGM()).thenReturn(true);
        Client gmClient = mock(Client.class);
        when(gmClient.getPlayer()).thenReturn(gm);
        when(gmClient.getChannelServer()).thenReturn(channel);   // without it the command's catch would hide a passing test

        new WarpCommand().execute(gmClient, new String[]{String.valueOf(STORAGE_CELLAR)});

        verify(gm).yellowMessage("Map ID " + STORAGE_CELLAR + " is invalid.");
        verify(gm, never()).changeMap(any(MapleMap.class), any(Portal.class));
        verify(gm, never()).changeMap(any(MapleMap.class));
        verify(maps, never()).getMap(HENESYS);
    }

    @Test
    void anEventInstanceWarpEntersTheInstanceMapAsAsked() {
        MapleMap instance = map(GOLLUX_ABDOMEN);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getMapInstance(GOLLUX_ABDOMEN)).thenReturn(instance);
        when(instance.getEventInstance()).thenReturn(eim);
        chr.setEventInstance(eim);

        assertDoesNotThrow(() -> chr.changeMap(GOLLUX_ABDOMEN));

        assertSame(instance, chr.getMap(), "an instance map is loaded and live; the routing table does not apply to it");
        assertEquals(GOLLUX_ABDOMEN, chr.getMapId());
        assertNotEquals(HEART_TREE, chr.getMapId());
        verify(maps, never()).getMap(HENESYS);
    }
}
