package server.maps;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 81 round 3 (owner 2026-10-28, draft): the Future and lower Grandis open at level 120 without rebirths; the
 * rest of Grandis and Arcane River stay rebirth 3.
 */
class AreaGatesTest {

    private static final int FUTURE_TOWN = 271010000;        // Future Henesys
    private static final int TWILIGHT_PERION = 273000000;
    private static final int PANTHEON = 400000000;
    private static final int FOX_RIDGE = 410000010;          // Eclipse Hill 1, lv 113
    private static final int SANCTUARY_FOREST = 402000517;   // Eastern Crystal Forest 1, lv 117
    private static final int HELISEUM_HQ = 401000000;        // upper town, lv 195+ fields behind it
    private static final int FOX_TREE_TOP = 410000100;
    private static final int SCRAPYARD = 402000124;          // lv 175, next to the lower Garbage Dump
    private static final int ARCANE = 450001000;

    @Test
    void theFutureOpensAtLevel120OrTwoRebirths() {
        assertNotNull(AreaGates.denyReason(FUTURE_TOWN, 119, 0));
        assertNull(AreaGates.denyReason(FUTURE_TOWN, 120, 0));
        assertNull(AreaGates.denyReason(TWILIGHT_PERION, 150, 0));
        assertNull(AreaGates.denyReason(TWILIGHT_PERION, 30, 2), "two rebirths still let a low level in, as before");
    }

    @Test
    void lowerGrandisOpensAtLevel120OrThreeRebirths() {
        for (int map : new int[]{PANTHEON, FOX_RIDGE, SANCTUARY_FOREST}) {
            assertTrue(AreaGates.isLowerGrandis(map), "lower Grandis: " + map);
            assertNotNull(AreaGates.denyReason(map, 119, 2));
            assertNull(AreaGates.denyReason(map, 120, 0));
            assertNull(AreaGates.denyReason(map, 10, 3));
        }
    }

    @Test
    void theRestOfGrandisAndArcaneStayRebirthThree() {
        for (int map : new int[]{HELISEUM_HQ, FOX_TREE_TOP, SCRAPYARD, ARCANE}) {
            assertFalse(AreaGates.isLowerGrandis(map), "upper: " + map);
            assertNotNull(AreaGates.denyReason(map, 200, 2), "level alone is not enough for " + map);
            assertNull(AreaGates.denyReason(map, 1, 3));
        }
    }

    @Test
    void otherMapsAreOpen() {
        assertNull(AreaGates.denyReason(100000000, 1, 0));
        assertNull(AreaGates.denyReason(270000000, 1, 0), "the Temple of Time itself");
    }

    @Test
    void theLowerGrandisListIsGrandisOnly() {
        assertTrue(AreaGates.lowerGrandis().size() > 150, "list loaded: " + AreaGates.lowerGrandis().size());
        for (int map : AreaGates.lowerGrandis()) {
            assertTrue(map >= 400000000 && map < 420000000, "not a Grandis map: " + map);
        }
    }

    @Test
    void theHyperRockUsesTheSameGates() {
        assertNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(FUTURE_TOWN, 0, 120));
        assertNotNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(FUTURE_TOWN, 0, 119));
        assertNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(PANTHEON, 0, 120));
        assertNotNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(HELISEUM_HQ, 2, 200));
    }

    @Test
    void theTeleporterAndTheGateOfTimeAskAreaGates() throws Exception {
        assertTrue(Files.readString(Path.of("scripts/npc/9010002.js")).contains("AreaGates.denyReason"));
        assertTrue(Files.readString(Path.of("scripts/portal/270000000.10.js")).contains("denyReason(player, 271000000)"));
    }
}
