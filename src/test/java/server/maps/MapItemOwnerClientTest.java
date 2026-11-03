package server.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Point;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

/**
 * {@code MapItem} remembers the client of whoever dropped it, and {@code MapleMap.searchItemReactors} reads it back
 * ({@code getOwnerClient()}) to run an item reactor's script when the reactor's state changes with the item already
 * lying in its box (the Guild Quest body that takes four pieces one after another). For a population bot that client is the
 * channel's shared headless one, whose {@code getPlayer()} is null: {@code ownerClient.getPlayer().isAwayFromWorld()} threw.
 * A bot's drop now remembers the bound client of {@link BotClient#forScripts}; a real player's and an owned alt's drop are
 * judged exactly as before - these tests hold both directions.
 */
class MapItemOwnerClientTest {

    private static MapItem dropBy(Character owner) {
        return new MapItem(mock(Item.class), new Point(0, 0), mock(MapObject.class), owner, owner.getClient(), (byte) 0, true);
    }

    @Test
    void aPopulationBotsDropGivesTheScriptTheBotAsItsPlayerInsteadOfThrowing() {
        BotClient shared = new BotClient(0, 2);
        Character bot = Character.getDefault(shared);
        bot.setID(1_000_000_000 + 1645);

        Client owner = dropBy(bot).getOwnerClient();

        assertNotNull(owner, "a bot is present for as long as it stands in the world: its away flag is never cleared");
        assertSame(bot, owner.getPlayer());
        assertNull(shared.getPlayer(), "nothing was written to the shared client");
    }

    @Test
    void aRealPlayersDropIsJudgedExactlyAsBefore() {
        Client own = mock(Client.class);
        Character human = mock(Character.class);
        when(human.getClient()).thenReturn(own);
        when(own.getPlayer()).thenReturn(human);
        when(own.isLoggedIn()).thenReturn(true);
        MapItem drop = dropBy(human);

        when(human.isAwayFromWorld()).thenReturn(false);
        assertSame(own, drop.getOwnerClient(), "his own client, not a bound one");

        when(human.isAwayFromWorld()).thenReturn(true);
        assertNull(drop.getOwnerClient(), "in the cash shop: not present, as before");
    }

    @Test
    void anOwnedAltsDropKeepsItsOwnClientAndItsAwayFlagStillCounts() {
        BotClient own = new BotClient(0, 2);
        Character alt = mock(Character.class);
        when(alt.getClient()).thenReturn(own);
        when(alt.isOwnedAlt()).thenReturn(true);
        own.setPlayer(alt); // OwnedAltBackend: a private headless client whose player is the alt
        MapItem drop = dropBy(alt);

        when(alt.isAwayFromWorld()).thenReturn(true);
        assertNull(drop.getOwnerClient(), "an alt that has not entered the world is away, exactly as before");
        when(alt.isAwayFromWorld()).thenReturn(false);
        assertSame(own, drop.getOwnerClient(), "an alt in the world: its own client, not a bound one");
    }

    // ---- the presence change does not touch who may loot ----------------------------------------------------

    private static Character looter(int id, boolean partyOfTheOwner) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getPartyId()).thenReturn(-1);
        when(c.needQuestItem(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(c.isPartyMember(org.mockito.ArgumentMatchers.anyInt())).thenReturn(partyOfTheOwner);
        return c;
    }

    @Test
    void whoMayLootABotsDropIsDecidedByOwnerAndPartyAndTheClockNotByTheClientOrThePresenceTheFixChanged() {
        // A meso drop skips the inventory-space check, so this is the ownership rule alone (MapItem.canBePickedBy).
        Character bot = Character.getDefault(new BotClient(0, 2));
        bot.setID(1_000_000_000 + 1649);
        MapItem drop = new MapItem(100, new Point(0, 0), mock(MapObject.class), bot, bot.getClient(), (byte) 0, true);
        drop.setDropTime(System.currentTimeMillis());

        assertEquals(bot.getId(), drop.getOwnerId());
        assertTrue(drop.canBePickedBy(looter(bot.getId(), false)), "the bot itself: yes, in the protection window");
        assertFalse(drop.canBePickedBy(looter(77, false)), "a human who is not in its party: NOT during the protection window");
        assertTrue(drop.canBePickedBy(looter(78, true)), "its party member: yes");

        drop.setDropTime(System.currentTimeMillis() - 16_000);
        assertTrue(drop.canBePickedBy(looter(77, false)), "after the 15 s window anyone: unchanged");

        // and the answer is the same whether the owner-client lookup (the part that changed) finds the bot present or not
        assertNotNull(drop.getOwnerClient());
    }
}
