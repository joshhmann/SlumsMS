package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 audit #3 (b112): "watched" was keyed by map id only, so one player on the Temple of Time on channel 1 made
 * the bots on channels 2-5 run real quest scripts and take real rewards. It is now keyed by the map instance.
 */
class ObservedInstanceTest {

    private static final int TEMPLE = 270000100;

    @AfterEach
    void clear() {
        ObserverTracker.observeForTest(Set.of());
        ObserverTracker.clearPinsForTest();
    }

    private static MapleMap instance(int id) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    private static Character botOn(MapleMap map) {
        Character bot = mock(Character.class);
        when(bot.getMap()).thenReturn(map);
        int id = map.getId();
        when(bot.getMapId()).thenReturn(id);
        return bot;
    }

    @Test
    void aPlayerOnChannelOneDoesNotWatchChannelTwo() {
        MapleMap ch1 = instance(TEMPLE), ch2 = instance(TEMPLE);
        ObserverTracker.observeForTest(Set.of(ch1));
        assertTrue(GCMovement.isMapObserved(botOn(ch1)), "same instance: watched");
        assertFalse(GCMovement.isMapObserved(botOn(ch2)), "same map id, other channel: offscreen");
        assertFalse(GCMovement.isMapObserved(ch2));
        assertTrue(GCMovement.isMapObserved(TEMPLE), "the id-only read stays 'some instance' (LOD, diagnostics)");
    }

    @Test
    void theEntryOverrideMarksOnlyItsInstance() {
        MapleMap ch1 = instance(TEMPLE + 1), ch2 = instance(TEMPLE + 1);
        GCMovement.markObservedNow(ch1);
        assertTrue(GCMovement.isMapObserved(botOn(ch1)));
        assertFalse(GCMovement.isMapObserved(botOn(ch2)), "the other channel is not watched");
        assertTrue(ObserverTracker.isActiveMap(TEMPLE + 1), "the id still gets full LOD physics");
    }

    @Test
    void aLodPinStillWatchesEveryInstance() {
        MapleMap ch1 = instance(TEMPLE + 2), ch2 = instance(TEMPLE + 2);
        GCMovement.lodPin(TEMPLE + 2);
        assertTrue(GCMovement.isMapObserved(botOn(ch1)));
        assertTrue(GCMovement.isMapObserved(botOn(ch2)), "ticket 157: a GM pin is by map id");
    }

    @Test
    void aBotRunsHoldOnFullPhysicsWithoutWatchingTheMap() {
        int kpqStage1 = 103000800;
        MapleMap stage = instance(kpqStage1);
        assertFalse(ObserverTracker.isActiveMap(kpqStage1), "no player there: coarse movement");
        GCMovement.holdFullPhysics(stage);
        assertTrue(ObserverTracker.isActiveMap(kpqStage1), "b153: a bot-driven PQ map moves with full physics");
        assertFalse(GCMovement.isMapObserved(botOn(stage)), "but it is not watched (no real player's rules)");
    }
}
