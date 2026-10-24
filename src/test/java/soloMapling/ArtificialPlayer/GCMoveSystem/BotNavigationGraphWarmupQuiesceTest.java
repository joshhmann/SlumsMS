package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.FootholdTree;
import server.maps.MapManager;
import server.maps.MapleMap;
import soloMapling.server.BotShutdown;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
 * Ticket 77: the nav-graph warm-up used to keep building graphs while shutdown disposed the maps, so the
 * warm-up thread logged a helpful NullPointerException ("footholds is null") from MapleMap.getFootholds().
 * The gate closes before the worlds and maps are torn down; these tests pin the two quiet exits: the gate
 * itself and a map that lost its footholds mid-flight. No server or WZ data is booted - the map is a mock
 * and the guards are called directly.
 */
class BotNavigationGraphWarmupQuiesceTest {

    /* The gate is one-way for the JVM; reopen it so the fresh fork's later classes are unaffected. */
    @AfterEach
    void reopenTheShutdownGate() throws ReflectiveOperationException {
        Method reset = BotShutdown.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    @Test
    void warmUpIsAllowedUntilTheShutdownGateCloses() {
        assertTrue(BotNavigationGraphProvider.warmUpAllowed(), "the server is up");
        BotShutdown.begin();
        assertFalse(BotNavigationGraphProvider.warmUpAllowed(), "shutdown quiesced the bots");
    }

    @Test
    void theWarmUpEntryPointsDoNoWorkOnceTheGateIsClosed() {
        BotShutdown.begin();

        MapleMap map = mock(MapleMap.class);
        BotNavigationGraphProvider.warmGraphAsync(map, null);
        verifyNoInteractions(map);

        MapManager mapFactory = mock(MapManager.class);
        BotNavigationGraphProvider.warmGraphsForRouteAsync(mapFactory, List.of(100000000), null);
        verifyNoInteractions(mapFactory);
    }

    @Test
    void aMapThatLostItsFootholdsIsSkippedQuietly() {
        MapleMap disposed = mock(MapleMap.class);
        when(disposed.getFootholds()).thenReturn(null);
        assertNull(BotNavigationGraphProvider.footholdsForWarmUp(disposed),
                "no footholds: the build is abandoned, not attempted");
    }

    @Test
    void aLiveMapHandsTheWarmUpItsFootholdsInOneRead() {
        MapleMap map = mock(MapleMap.class);
        FootholdTree footholds = mock(FootholdTree.class);
        when(map.getFootholds()).thenReturn(footholds);

        assertSame(footholds, BotNavigationGraphProvider.footholdsForWarmUp(map));
        // one read per warm-up: a dispose between two reads used to NPE the builder
        verify(map, times(1)).getFootholds();
    }
}
