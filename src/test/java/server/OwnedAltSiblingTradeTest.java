package server;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.companions.OwnedAltService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 ticket 99: a trade between two of one owner's alts is let through only while the giver is in a companion
 * handover (BotGiveTrade presses the headless side's buttons, so nothing sits pending); every other invite to an alt
 * is still refused (1.6.0.4 L2).
 */
class OwnedAltSiblingTradeTest {

    @AfterEach
    void tearDown() {
        BotGiveTrade.useForTest(null, null);
    }

    @Test
    void siblingsTradeOnlyInsideAHandover() {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(1);
        MapleMap map = mock(MapleMap.class);
        Character giver = alt(2, "Cleo", owner, map);
        Character sib = alt(3, "Sib", owner, map);
        assertEquals("Sib is an alt traveling with its owner and can't trade.",
                OwnedAltService.interactionRefusal(giver, sib, "trade"), "no handover: refused as before");

        BotGiveTrade.useForTest(new NoopWindow(), (ms, step) -> { });
        assertTrue(BotGiveTrade.start(new BotGiveTrade.Plan(giver, sib, List.of(), 5, null, null, null)));
        assertNull(OwnedAltService.interactionRefusal(giver, sib, "trade"));
        assertEquals("Sib is an alt traveling with its owner and can't join another party.",
                OwnedAltService.interactionRefusal(giver, sib, "join another party"), "trades only");

        Character stranger = mock(Character.class);
        when(stranger.getId()).thenReturn(9);
        Character othersAlt = alt(4, "Other", stranger, map);
        assertEquals("Sib is an alt traveling with its owner and can't trade.",
                OwnedAltService.interactionRefusal(othersAlt, sib, "trade"), "another owner's alt");
    }

    private static Character alt(int id, String name, Character owner, MapleMap map) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.isOwnedAlt()).thenReturn(true);
        when(c.getOwnedAltOwner()).thenReturn(owner);
        when(c.getMap()).thenReturn(map);
        Client client = mock(BotClient.class);
        when(c.getClient()).thenReturn(client);
        return c;
    }

    static final class NoopWindow implements BotGiveTrade.Window {
        public boolean invite(Character g, Character r) { return true; }
        public boolean open(Character g) { return true; }
        public boolean accepted(Character g) { return false; }
        public void accept(Character r, Character g) { }
        public Trade.PutResult put(Character g, client.inventory.InventoryType t, short p, short q, byte s) { return Trade.PutResult.ADDED; }
        public void mesos(Character g, int m) { }
        public void chat(Character g, String l) { }
        public boolean partnerLocked(Character g) { return false; }
        public void confirm(Character w) { }
        public void cancel(Character g) { }
        public void onTraded(Character g, Runnable r) { }
    }
}
