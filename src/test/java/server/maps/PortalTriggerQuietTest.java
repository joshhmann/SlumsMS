package server.maps;

import org.junit.jupiter.api.Test;
import scripting.portal.PortalScriptManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.2 b089: the Marketplace (410000200) range trigger pcs_410000200 had no script and spammed the log. */
class PortalTriggerQuietTest {

    @Test
    void theMarketplaceTriggerHasAScriptThatDoesNothing() {
        assertTrue(PortalScriptManager.getInstance().hasPortalScript("pcs_410000200"));
        Portal p = mock(Portal.class);
        when(p.getScriptName()).thenReturn("pcs_410000200");
        assertEquals(PortalScriptManager.Outcome.REFUSED, PortalScriptManager.getInstance().runPortalScript(p, null));
    }

    @Test
    void theSameLineIsLoggedOnceAMinute() {
        String line = GenericPortal.scriptLogLine("pcs_test_quiet", "Ann", 410000200, "refused: x");
        assertTrue(GenericPortal.firstInAWhile(line, 1_000_000));
        assertFalse(GenericPortal.firstInAWhile(line, 1_006_000));
        assertTrue(GenericPortal.firstInAWhile(GenericPortal.scriptLogLine("pcs_test_quiet", "Bob", 410000200, "refused: x"), 1_006_000));
        assertTrue(GenericPortal.firstInAWhile(line, 1_061_000));
    }
}
