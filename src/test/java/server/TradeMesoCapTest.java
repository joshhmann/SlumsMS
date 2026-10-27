package server;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 release day (TEST t-nx-1 / t-nx-2, 2026-10-27 18:36): the staged NX merchants were lv 14 and lv 11, and the
 * "under level 15: 1m mesos a day" cap cancelled every 50m purchase (the buyer kept the mesos, got no code). The cap
 * is a player rule: a roster bot of any level takes the 50m; a player and a player's owned alt under 15 keep the cap.
 */
class TradeMesoCapTest {

    private static Character chr(int level, Client client, boolean ownedAlt) {
        Character c = mock(Character.class);
        when(c.getLevel()).thenReturn(level);
        when(c.getClient()).thenReturn(client);
        when(c.isOwnedAlt()).thenReturn(ownedAlt);
        return c;
    }

    @Test
    void aLowLevelMerchantBotCanTakeFiftyMillion() {
        assertFalse(Trade.dailyMesoCapApplies(chr(11, mock(BotClient.class), false)));
        assertFalse(Trade.dailyMesoCapApplies(chr(14, mock(BotClient.class), false)));
    }

    @Test
    void playersAndOwnedAltsUnderFifteenKeepTheCap() {
        assertTrue(Trade.dailyMesoCapApplies(chr(11, mock(Client.class), false)));
        assertTrue(Trade.dailyMesoCapApplies(chr(11, mock(BotClient.class), true)));
        assertFalse(Trade.dailyMesoCapApplies(chr(15, mock(Client.class), false)));
    }
}
