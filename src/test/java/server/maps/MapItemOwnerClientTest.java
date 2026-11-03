package server.maps;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
