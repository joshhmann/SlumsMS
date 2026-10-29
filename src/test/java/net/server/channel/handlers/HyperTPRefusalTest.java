package net.server.channel.handlers;

import constants.id.MapId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The free world-map teleport may not open the jail, leave an event, or land inside an instance map. */
class HyperTPRefusalTest {
    private static final int HENESYS = 100000000;
    private static final int ELLINIA = 101000000;
    private static final int NONE = MapId.NONE;

    private static String go(int from, boolean inEvent, int to, int fieldLimit, int forcedReturn) {
        return HyperTPHandler.refusal(from, inEvent, true, to, fieldLimit, forcedReturn, 0);
    }

    @Test void anOrdinaryHopStillWorks() {
        assertNull(go(HENESYS, false, ELLINIA, 0, NONE));
    }

    @Test void aTownThatReturnsToItselfIsStillReachable() {
        assertNull(go(HENESYS, false, 310000000, 0, 310000000)); // Edelstein
    }

    @Test void theJailCannotBeLeftOrEntered() {
        assertNotNull(go(MapId.JAIL, false, HENESYS, 0, NONE));
        assertNotNull(go(HENESYS, false, MapId.JAIL, 0, NONE));
    }

    @Test void aRunningEventCannotBeLeftByRock() {
        assertNotNull(go(103000800, true, HENESYS, 0, NONE));
    }

    @Test void instanceMapsAreClosed() {
        assertNotNull(go(HENESYS, false, 980041201, 0, 980040000)); // a Monster Carnival room
        assertNotNull(go(HENESYS, false, 180000000, 0, HENESYS)); // the GM map
        assertNotNull(go(HENESYS, false, 103000800, 0x40, 103000890)); // Kerning PQ stage 1
    }

    @Test void theDeadCannotTeleport() {
        assertNotNull(HyperTPHandler.refusal(HENESYS, false, false, ELLINIA, 0, NONE, 0));
    }

    @Test void rebirthGatesStillHold() {
        // 1.6.1 b040: only Von Leon's wing of Lion King's Castle keeps rebirth 1
        assertNotNull(go(HENESYS, false, 211070000, 0, NONE));
        assertNull(HyperTPHandler.refusal(HENESYS, false, true, 211070000, 0, NONE, 1));
    }

    /** 1.6.1 b040: the notes present Lion King's Castle and the Rose Garden as open lv 117-134 training. */
    @Test void lionKingsCastleTrainingNeedsNoRebirth() throws Exception {
        for (int map : new int[]{211060000, 211060010, 211060100, 211060300, 211060620, 211060830, 211061000,
                211080100, 211080600}) {
            assertNull(HyperTPHandler.gateRefusal(map, 0, 117), "Hyper Rock to " + map);
            assertTrue(soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(map, 0, 117), "bots to " + map);
        }
        assertNotNull(HyperTPHandler.gateRefusal(211070000, 0, 200), "Von Leon's wing keeps rebirth 1");
        assertFalse(soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates.mayEnter(211070100, 0, 200));
        for (String f : new String[]{"in_lkc", "211060000.4", "out_VLRoof4", "gotoNext1", "gotoNext2_1", "gotoNext2_2",
                "gotoNext3_1", "gotoNext3_2", "gotoNext3_3", "gotoNext4", "1stTowerTop", "2ndTowerTop", "3rdTowerTop"}) {
            String js = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/portal/" + f + ".js"));
            assertFalse(js.contains("getReborns"), f + ".js still asks for a rebirth");
        }
        // the towers keep their keys and quests
        assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/portal/gotoNext1.js"))
                .contains("haveItem(4032832) || pi.isQuestCompleted(3165)"));
    }
}
