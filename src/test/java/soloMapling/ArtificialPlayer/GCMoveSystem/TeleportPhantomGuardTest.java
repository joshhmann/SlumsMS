package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * NN-011, the v67 phantom shared-ground guard for TELEPORT edges (nutnnut/Cosmic 43b9f06c8, their graph v70 part b,
 * AGPL-3.0. Credit: NutNNut). v67 dropped JUMP/FLASH_JUMP edges whose landing sits on ground the source region already
 * covers: where two foothold chains overlap the bot stays on its own chain, the edge never changes region and the bot
 * parks at its launch gate. Teleport edges were not checked. On the Deity Room (600020100) a mage was routed by
 * TELEPORT r97->r73 onto the shared foot and parked on tele-pos.
 */
class TeleportPhantomGuardTest {

    private static final int DEITY_ROOM = 600020100;

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @AfterEach
    void restore() {
        NavPortSwitches.teleportPhantomGuard = false;
    }

    private static List<String> phantomTeleports(BotNavigationGraph graph) {
        List<String> phantoms = new ArrayList<>();
        for (BotNavigationGraph.Region region : graph.regions) {
            for (BotNavigationGraph.Edge edge : graph.getOutgoing(region.id)) {
                if (edge.type == BotNavigationGraph.EdgeType.TELEPORT
                        && BotNavigationGraphProvider.isPhantomCrossRegionLanding(region, edge.endPoint)) {
                    phantoms.add(NavStuckLog.describe(edge) + " lands " + edge.endPoint.x + "," + edge.endPoint.y);
                }
            }
        }
        return phantoms;
    }

    @Test
    void withTheSwitchOffTheDeityRoomHasAPhantomTeleport() {
        WzTestData.assumePresent("Map.wz");
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEITY_ROOM);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertFalse(phantomTeleports(graph).isEmpty(), "fixture: the shared foot authors a phantom teleport");
    }

    @Test
    void withTheSwitchOnNoTeleportLandsOnItsOwnSourceGround() {
        WzTestData.assumePresent("Map.wz");
        NavPortSwitches.teleportPhantomGuard = true;
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(DEITY_ROOM);
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        assertEquals(List.of(), phantomTeleports(graph));
        assertTrue(graph.regions.stream().anyMatch(r -> graph.getOutgoing(r.id).stream()
                .anyMatch(e -> e.type == BotNavigationGraph.EdgeType.TELEPORT)), "real teleports are still authored");
    }

    @Test
    void theSwitchGetsItsOwnGraphCacheFolder() {
        String plain = BotNavigationGraphProvider.cacheFolderName();
        NavPortSwitches.teleportPhantomGuard = true;
        assertEquals(plain + "-tp", BotNavigationGraphProvider.cacheFolderName());
    }
}
