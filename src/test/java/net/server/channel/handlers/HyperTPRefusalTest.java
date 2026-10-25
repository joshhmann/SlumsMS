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
        assertNotNull(go(HENESYS, false, 211060000, 0, NONE));
        assertNull(HyperTPHandler.refusal(HENESYS, false, true, 211060000, 0, NONE, 1));
    }
}
