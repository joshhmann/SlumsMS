package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.command.commands.gm4.EnvironmentCommandAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: !env lod pin forces chosen maps to full simulation, at most five, TEST only. */
class LodPinTest {

    @AfterEach
    void clear() {
        ObserverTracker.clearPinsForTest();
    }

    @Test
    void aPinnedMapIsFullAndActiveUntilUnpinned() {
        int map = 910000930;
        assertFalse(ObserverTracker.isActiveMap(map));
        assertFalse(GCMovement.isMapObserved(map));
        assertNull(GCMovement.lodPin(map));
        assertTrue(ObserverTracker.isActiveMap(map), "the movement driver runs full physics there");
        assertTrue(GCMovement.isMapObserved(map), "combat, chat and broadcast treat it as watched");
        assertEquals("pinned", GCMovement.lodTier(map));
        assertTrue(GCMovement.lodUnpin(map));
        assertFalse(GCMovement.lodUnpin(map), "only once");
        assertFalse(ObserverTracker.isActiveMap(map));
        assertEquals("coarse", GCMovement.lodTier(map));
    }

    @Test
    void atMostFiveMaps() {
        for (int i = 0; i < ObserverTracker.MAX_PINS; i++) {
            assertNull(GCMovement.lodPin(910000940 + i));
        }
        assertNull(GCMovement.lodPin(910000940), "pinning a pinned map again is fine");
        assertTrue(GCMovement.lodPin(910000999).contains("the cap"));
        assertEquals(5, GCMovement.lodPins().size());
        assertTrue(GCMovement.lodPin(0).startsWith("not a map id"));
    }

    @Test
    void theCommandIsTestOnlyAndSpeaksPlainly() {
        assertTrue(EnvironmentCommandAccess.lod(new String[]{"lod", "pin", "100000000"}, false).get(0)
                .contains("test-server tool"));
        assertEquals(Set.of(), GCMovement.lodPins(), "refused: nothing pinned");
        List<String> pinned = EnvironmentCommandAccess.lod(new String[]{"lod", "pin", "100000000"}, true);
        assertTrue(pinned.get(0).startsWith("Map 100000000 runs full simulation"), pinned.toString());
        assertEquals(List.of("LOD pins (1/5): [100000000]"), EnvironmentCommandAccess.lod(new String[]{"lod", "list"}, true));
        assertTrue(EnvironmentCommandAccess.lod(new String[]{"lod", "pin", "abc"}, true).get(0).startsWith("Syntax"));
        assertEquals(List.of("Map 100000000 is back on the normal LOD rule."),
                EnvironmentCommandAccess.lod(new String[]{"lod", "unpin", "100000000"}, true));
    }
}
