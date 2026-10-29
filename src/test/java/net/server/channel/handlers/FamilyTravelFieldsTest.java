package net.server.channel.handlers;

import constants.id.MapId;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Auto-QA t-fy-3 on TEST (2026-10-29) failed because the pretend player stood in the Free Market entrance (910000000,
 * fieldLimit 8428) and its regular junior near Papulatus (220080000, fieldLimit 72): both carry CANNOTVIPROCK, so the
 * stock Family rules refuse Reunion into the one and Summon into the other. That is correct; the check now stages on
 * maps the rule allows, through this rule.
 */
class FamilyTravelFieldsTest {

    static MapleMap map(int id, int fieldLimit) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getFieldLimit()).thenReturn(fieldLimit);
        when(m.getForcedReturnId()).thenReturn(MapId.NONE);
        return m;
    }

    @Test
    void theFreeMarketAndThePapulatusLobbyRefuseFamilyTeleportsAndHenesysTakesThem() {
        MapleMap fm = map(910000000, 8428), clocktower = map(220080000, 72), henesys = map(100000000, 0),
                field = map(104040000, 0);
        assertFalse(FamilyUseHandler.travelAllowed(fm, clocktower), "Reunion from the FM to the Papulatus lobby");
        assertFalse(FamilyUseHandler.travelAllowed(clocktower, fm), "Summon into the FM");
        assertFalse(FamilyUseHandler.travelAllowed(henesys, clocktower), "nobody teleports into the Papulatus lobby");
        assertTrue(FamilyUseHandler.travelAllowed(henesys, field));
        assertTrue(FamilyUseHandler.travelAllowed(field, henesys));
    }
}
