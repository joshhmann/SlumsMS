package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 161-rebirth2 (TEST b034, t-rb-2 FAIL "the crew is gone"): a bot never logs in, so Character.isLoggedinWorld is
 * false for every bot. The run's crew check dropped its whole crew on the first beat, and the event and expedition code
 * that asked the same left bots out of the instance (registerPlayer), the active members (start, boss log) and
 * exitPlayer.
 */
class RebirthRunCrewTest {

    private static final int BOT_ID = 1_000_000_000 + 7501;

    @BeforeEach
    void setUp() {
        RebirthBossRuns.resetForTest();
    }

    @AfterEach
    void tearDown() {
        RebirthBossRuns.resetForTest();
        RebirthBossRuns.liveForTest(true);
    }

    private static Character botOn(MapleMap map, int id) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(id);
        bot.setName("Crew" + (id % 10));
        bot.setMap(map);
        return bot;
    }

    @Test
    void aBotOnAMapIsInTheWorldThoughItNeverLoggedIn() {
        Character bot = botOn(mock(MapleMap.class), BOT_ID);
        assertFalse(bot.isLoggedinWorld(), "bots never log in");
        assertTrue(bot.isPresentInWorld());
        assertFalse(botOn(null, BOT_ID + 1).isPresentInWorld(), "no map: not in the world");
    }

    @Test
    void aRunKeepsItsBotCrewOnTheFirstBeat() {
        Character bot = botOn(mock(MapleMap.class), BOT_ID);
        long now = System.currentTimeMillis();
        RebirthBossRuns.Run r = new RebirthBossRuns.Run(Boss.VONLEON, bot, true, 0, 0, now);
        r.crew.add(bot);
        RebirthBossRuns.step(r, now + 250);
        assertEquals(RebirthBossRuns.Phase.GATHER, r.phase(), "b034: " + r.result());
        assertEquals(List.of(bot.getName()), r.crewNames());
    }

    @Test
    void botMembersAreActiveExpeditionMembers() throws Exception {
        MapleMap map = mock(MapleMap.class);
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(map.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        Character leader = botOn(map, BOT_ID);
        Character member = botOn(map, BOT_ID + 2);
        when(storage.getCharacterById(BOT_ID)).thenReturn(leader);
        when(storage.getCharacterById(BOT_ID + 2)).thenReturn(member);
        Expedition ex = new Expedition(leader, ExpeditionType.VONLEON, true, 0, 0);
        java.lang.reflect.Field registering = Expedition.class.getDeclaredField("registering");
        registering.setAccessible(true);
        registering.set(ex, true);   // as the channel's addExpedition opens it
        assertEquals(0, ex.addMemberInt(member));
        assertEquals(2, ex.getActiveMembers().size(), "the event takes in, and the boss log writes, active members");
    }
}
