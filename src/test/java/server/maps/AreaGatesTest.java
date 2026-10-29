package server.maps;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 81 round 3 (owner 2026-10-28, draft): the Future and lower Grandis open at level 120 without rebirths; the
 * rest of Grandis opens at the 1st rebirth and Arcane River at the 2nd, at any level (owner 2026-10-29; both were 3).
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
    private static final int TYRANTS_CASTLE = 401060000;     // Heliseum Heights Entry
    private static final int CERNIUM = 410000500;            // Cernium Square

    @Test
    void theFutureOpensAtLevel120OrTwoRebirths() {
        assertNotNull(AreaGates.denyReason(FUTURE_TOWN, 119, 0));
        assertNull(AreaGates.denyReason(FUTURE_TOWN, 120, 0));
        assertNull(AreaGates.denyReason(TWILIGHT_PERION, 150, 0));
        assertNull(AreaGates.denyReason(TWILIGHT_PERION, 30, 2), "two rebirths still let a low level in, as before");
    }

    @Test
    void lowerGrandisOpensAtLevel120OrTheFirstRebirth() {
        // owner 2026-10-29: level 120, or the 1st rebirth at any level (was 3 rebirths)
        for (int map : new int[]{PANTHEON, FOX_RIDGE, SANCTUARY_FOREST}) {
            assertTrue(AreaGates.isLowerGrandis(map), "lower Grandis: " + map);
            assertNotNull(AreaGates.denyReason(map, 119, 0));
            assertNull(AreaGates.denyReason(map, 120, 0));
            assertNull(AreaGates.denyReason(map, 10, 1), "one rebirth opens " + map + " at a low level");
            assertEquals(1, AreaGates.requiredRebirths(map));
        }
        assertTrue(AreaGates.denyReason(PANTHEON, 119, 0).contains("(or Rebirth level 1)"), "the message says 1");
    }

    /** Owner 2026-10-29: deeper Grandis opens at the 1st rebirth, at any level (was 3). */
    @Test
    void deeperGrandisOpensAtTheFirstRebirth() {
        for (int map : new int[]{HELISEUM_HQ, TYRANTS_CASTLE, FOX_TREE_TOP, SCRAPYARD, CERNIUM,
                410000002, 400020300, 401000002, 402000400, 402000401, 402000402}) {
            assertFalse(AreaGates.isLowerGrandis(map), "deeper: " + map);
            assertTrue(AreaGates.isDeeperGrandis(map), "deeper: " + map);
            assertNotNull(AreaGates.denyReason(map, 200, 0), "level alone is not enough for " + map);
            assertNull(AreaGates.denyReason(map, 1, 1), "one rebirth opens " + map + " at any level");
            assertEquals(1, AreaGates.requiredRebirths(map));
        }
        assertTrue(AreaGates.denyReason(HELISEUM_HQ, 200, 0).contains("Rebirth level 1"), "the message says 1");
    }

    /** Owner 2026-10-29: Arcane River opens at the 2nd rebirth, at any level (was 3). */
    @Test
    void arcaneRiverOpensAtTheSecondRebirth() {
        assertNotNull(AreaGates.denyReason(ARCANE, 200, 1));
        assertNull(AreaGates.denyReason(ARCANE, 1, 2));
        assertEquals(2, AreaGates.requiredRebirths(ARCANE));
        assertTrue(AreaGates.denyReason(ARCANE, 200, 1).contains("Rebirth level 2"), "the message says 2");
        // the Future is unchanged (level 120 or 2 rebirths)
        assertEquals(2, AreaGates.requiredRebirths(FUTURE_TOWN));
        assertNotNull(AreaGates.denyReason(FUTURE_TOWN, 119, 1));
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
        assertNotNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(HELISEUM_HQ, 0, 200));
        assertNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(HELISEUM_HQ, 1, 30));
        assertNotNull(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(ARCANE, 1, 200));
        assertTrue(net.server.channel.handlers.HyperTPRefusalTestAccess.refusal(ARCANE, 1, 200).contains("Rebirth level 2"));
    }

    @Test
    void theTeleporterAndTheGateOfTimeAskAreaGates() throws Exception {
        assertTrue(Files.readString(Path.of("scripts/npc/9010002.js")).contains("AreaGates.denyReason"));
        // owner 2026-10-29: the Temple of Time's Arcane River portal asks AreaGates too (it hard-coded 3 rebirths)
        assertTrue(Files.readString(Path.of("scripts/portal/arcaneriver.js")).contains("AreaGates').denyReason(pi.getPlayer(), 450001003)"));
        // 1.6.1 b040: the Gate of Time opens a menu (the Future or lower Grandis), which asks AreaGates
        assertTrue(Files.readString(Path.of("scripts/portal/270000000.10.js")).contains("openNpc(2140000, \"gateOfTime\")"));
        assertTrue(Files.readString(Path.of("scripts/npc/gateOfTime.js")).contains("AreaGates.denyReason(cm.getPlayer(), to)"));
    }
}
