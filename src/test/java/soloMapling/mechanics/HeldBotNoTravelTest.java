package soloMapling.mechanics;

import client.BotClient;
import client.Character;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b344, the second half: while a mechanics run holds a bot, nothing else may walk it to another map.
 *
 * <p>TEST 2026-11-06 02:37-02:41 UTC (PreZakum0 ch 1): the run held xGoonie and stopped its brain, yet a trip to its
 * party leader's room ({@code intent: portal 2 -> map 280010100}) kept running; every 20 s GCTravel's soft-lock
 * escape warped it there and the run warped it back ("through to 280010000"), six times. The run itself never asks for
 * a cross-map trip (its steps walk to a portal, or warp inside the instance), so a held bot's trip or follow chase is
 * always someone else's and is refused, the way {@code OwnedAltCompanion} already steps aside for a held alt.
 */
class HeldBotNoTravelTest {

    private static final int BOT_ID = 1_000_344_001;
    private static final int HUB = 280010000;
    private static final int OWNERS_ROOM = 280010100;

    private final PlayerStorage storage = new PlayerStorage();
    private final List<Character> made = new ArrayList<>();

    @AfterEach
    void release() {
        for (Character c : made) {
            GCMovement.stop(c);
            MechanicsHold.release(c.getId());
        }
    }

    private Character onMap(int id, int mapId) {
        Character c = spy(Character.getDefault(new BotClient(0, 0)));
        c.setID(id);
        c.setName("B344_" + id);
        BotClient client = mock(BotClient.class);
        Channel channel = mock(Channel.class);
        when(client.getChannelServer()).thenReturn(channel);
        when(channel.getPlayerStorage()).thenReturn(storage);
        doReturn(client).when(c).getClient();
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        server.maps.FootholdTree floor = new server.maps.FootholdTree(new java.awt.Point(-30000, -30000),
                new java.awt.Point(30000, 30000));
        floor.insert(new server.maps.Foothold(new java.awt.Point(-500, 200), new java.awt.Point(1500, 200), 1));
        when(map.getFootholds()).thenReturn(floor);
        doReturn(map).when(c).getMap();
        doReturn(mapId).when(c).getMapId();
        doNothing().when(c).changeMap(anyInt());
        doNothing().when(c).changeMap(anyInt(), anyInt());
        storage.addPlayer(c);
        made.add(c);
        return c;
    }

    @Test
    void aBotNoRunHoldsStillGetsItsTrip() {
        Character bot = onMap(BOT_ID, HUB);
        GCMovement.travel(bot, OWNERS_ROOM, null);
        assertTrue(GCMovement.isTraveling(bot), "the control: an unheld bot travels as before");
    }

    @Test
    void aHeldBotGetsNoTripAndItsCallerHearsNo() {
        Character bot = onMap(BOT_ID + 1, HUB);
        MechanicsHold.hold(bot.getId());
        List<Boolean> heard = new ArrayList<>();

        GCMovement.travel(bot, OWNERS_ROOM, heard::add);

        assertFalse(GCMovement.isTraveling(bot), "the run drives this bot: no trip to another map");
        assertEquals(List.of(false), heard, "a chained errand is told the trip did not happen");
    }

    @Test
    void aHeldBotDoesNotChaseItsLeaderToAnotherRoom() throws InterruptedException {
        Character bot = onMap(BOT_ID + 2, HUB);
        Character leader = onMap(46, OWNERS_ROOM);
        MechanicsHold.hold(bot.getId());

        GCMovement.follow(bot, leader); // a follow re-armed by a brain tick that was already in flight at run start
        long until = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < until && GCMovement.isFollowing(bot) && !GCMovement.isTraveling(bot)) {
            Thread.sleep(50);
        }

        assertFalse(GCMovement.isTraveling(bot), "no trip toward the leader's room");
        assertFalse(GCMovement.isFollowing(bot), "and the follow session ends: the run owns the bot until it lets go");
    }
}
