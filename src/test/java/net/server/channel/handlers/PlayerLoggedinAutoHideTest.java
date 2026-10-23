package net.server.channel.handlers;

import client.Client;
import client.SyntheticClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage: the test server auto-hides GMs, and a hidden GM is invisible to every bot system. */
class PlayerLoggedinAutoHideTest {

    @Test
    void aRealGmStillLogsInHidden() {
        assertTrue(PlayerLoggedinHandler.autoHides(true, true, null));
        assertTrue(PlayerLoggedinHandler.autoHides(true, true, Client.createMock()));
    }

    @Test
    void autoQasPretendGmNeverDoes() {
        assertFalse(PlayerLoggedinHandler.autoHides(true, true, new SyntheticClient(1, 1, 1)));
    }

    @Test
    void nothingHidesWhenTheSwitchIsOffOrForAPlayer() {
        assertFalse(PlayerLoggedinHandler.autoHides(true, false, null));
        assertFalse(PlayerLoggedinHandler.autoHides(false, true, null));
    }
}
