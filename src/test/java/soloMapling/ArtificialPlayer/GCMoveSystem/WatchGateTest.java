package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 player likeness (audit #9): a bot a real player can see never snaps. A recovery teleport walks instead, and a
 * trip's fallback warp waits for the walk (then gives up) while a player is on the bot's map; with nobody there both
 * stay instant. {@code -Dsolomapling.walkWhenWatched=false} restores the old snaps.
 */
class WatchGateTest {

    private static final int BOT_ID = 1_000_666_001;
    private static final int MAP = 100000000;
    private final AtomicBoolean playerHere = new AtomicBoolean();

    @BeforeEach
    void seam() {
        WatchGate.resetForTest();
        WatchGate.realPlayerOn = map -> playerHere.get();
    }

    @AfterEach
    void clear() {
        System.clearProperty(WatchGate.PROP);
        WatchGate.resetForTest();
    }

    /** A bot its channel no longer holds: GCMovement will not start a driver for it, so nothing here needs a map. */
    private Character bot() {
        Character bot = spy(Character.getDefault(new BotClient(0, 0)));
        bot.setID(BOT_ID);
        bot.setName("Watchd");
        bot.setPosition(new Point(10, 20));
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(new PlayerStorage());
        doReturn(client).when(bot).getClient();
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        doReturn(map).when(bot).getMap();
        doReturn(MAP).when(bot).getMapId();
        doNothing().when(bot).changeMap(anyInt());
        return bot;
    }

    @Test
    void aWatchedBotMayNotSnapAnUnwatchedOneMay() {
        Character bot = bot();
        assertTrue(WatchGate.mayReposition(bot, "test"));
        playerHere.set(true);
        assertTrue(WatchGate.watched(bot));
        assertFalse(WatchGate.mayReposition(bot, "test"));
        assertEquals(1, WatchGate.averted());

        System.setProperty(WatchGate.PROP, "false");
        assertTrue(WatchGate.mayReposition(bot, "test"), "switch off: the old snap");
        assertEquals(1, WatchGate.snappedWatched(), "and it is counted as a snap in front of a player");
    }

    @Test
    void aRecoveryTeleportInFrontOfAPlayerWalksInstead() {
        Character bot = bot();
        playerHere.set(true);
        assertFalse(GCMovement.teleportTo(bot, 900, -300, "test"));
        assertEquals(new Point(10, 20), bot.getPosition(), "no snap across the map");
        assertEquals(1, WatchGate.averted());
    }

    @Test
    void aTripsFallbackWarpWaitsWhileWatchedThenGivesUp() {
        Character bot = bot();
        AtomicReference<Boolean> result = new AtomicReference<>();
        GCTravel.Trip trip = new GCTravel.Trip(bot, 104000000, result::set);

        playerHere.set(true);
        GCTravel.warpOrHold(trip, bot, 104000000, "test: no walkable portal");
        verify(bot, never()).changeMap(anyInt());
        assertNull(result.get(), "still walking, the trip is not over");
        assertTrue(trip.watchedHoldSinceMs > 0);

        trip.watchedHoldSinceMs -= GCTravel.WATCHED_HOLD_MS + 1; // held for longer than the cap
        GCTravel.warpOrHold(trip, bot, 104000000, "test: no walkable portal");
        verify(bot, never()).changeMap(anyInt());
        assertEquals(Boolean.FALSE, result.get(), "watched too long: the trip fails instead of a visible warp");

        playerHere.set(false);
        GCTravel.Trip unwatched = new GCTravel.Trip(bot, 104000000, ok -> { });
        GCTravel.warpOrHold(unwatched, bot, 104000000, "test: no walkable portal");
        verify(bot, times(1)).changeMap(104000000);
    }
}
