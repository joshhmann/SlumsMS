package soloMapling.ArtificialPlayer.BotTownSystem;

import client.BotClient;
import client.Character;
import constants.id.MapId;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 player likeness (audit #3): 5-15 AFK campers per channel, scaled by that channel's online bots, picked only
 * from free town bots, rotated, and let go the moment a commitment starts.
 */
class AfkCampersTest {

    private static final int BASE = BotStore.BOT_ID_BASE + 880_000;
    private final List<Integer> added = new ArrayList<>();
    private final Map<String, MapleMap> maps = new HashMap<>();

    @BeforeEach
    void seams() {
        AfkCampers.resetForTest();
        AfkCampers.rosterMember = slot -> true;
        AfkCampers.free = bot -> true;
    }

    @AfterEach
    void clear() {
        added.forEach(CharacterStorage::removeActiveBot);
        AfkCampers.resetForTest();
        System.clearProperty(AfkCampers.PROP);
    }

    private MapleMap map(int channel, int mapId) {
        return maps.computeIfAbsent(channel + ":" + mapId, k -> {
            MapleMap m = mock(MapleMap.class);
            when(m.getId()).thenReturn(mapId);
            when(m.getChannelId()).thenReturn(channel);
            when(m.getAllPlayers()).thenReturn(List.of());
            return m;
        });
    }

    private Character chr(int n, int channel, int mapId) {
        Character chr = spy(Character.getDefault(new BotClient(0, 0)));
        chr.setID(BASE + n);
        chr.setName("Camp" + n);
        chr.setPosition(new Point(100 + n, 200));
        MapleMap m = map(channel, mapId);
        doReturn(m).when(chr).getMap();
        doReturn(mapId).when(chr).getMapId();
        return chr;
    }

    /** A town wanderer standing in town, running. */
    private TownWandererBot wanderer(int n, int channel, int mapId) throws Exception {
        TownWandererBot bot = new TownWandererBot(chr(n, channel, mapId));
        Field state = BotSM.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(bot, BotSM.BotState.RUNNING);
        CharacterStorage.addActiveBot(bot.getChr().getId(), bot);
        added.add(bot.getChr().getId());
        return bot;
    }

    /** Any other online bot: counts towards the channel's population, never camps. */
    private void other(int n, int channel) {
        BotSM bot = mock(BotSM.class);
        Character chr = chr(n, channel, 104040000);
        when(bot.getChr()).thenReturn(chr);
        CharacterStorage.addActiveBot(chr.getId(), bot);
        added.add(chr.getId());
    }

    @Test
    void theTargetScalesWithTheChannelAndStaysBetweenFiveAndFifteen() {
        assertEquals(0, AfkCampers.targetFor(0));
        assertEquals(5, AfkCampers.targetFor(12));
        assertEquals(5, AfkCampers.targetFor(200));
        assertEquals(10, AfkCampers.targetFor(400));
        assertEquals(15, AfkCampers.targetFor(600));
        assertEquals(15, AfkCampers.targetFor(3000));
    }

    @Test
    void aPassFillsEachChannelToItsTargetSpreadOverTowns() throws Exception {
        int n = 0;
        int[] towns = {MapId.HENESYS, MapId.KERNING_CITY, MapId.FM_ENTRANCE, MapId.ELLINIA};
        for (int i = 0; i < 40; i++) {
            wanderer(n++, 17, towns[i % towns.length]);
        }
        for (int i = 0; i < 280; i++) {
            other(n++, 17); // ch1: 320 online -> 8 campers
        }
        for (int i = 0; i < 6; i++) {
            wanderer(n++, 18, MapId.HENESYS); // ch2: 6 online -> 5 wanted, but at most 4 on one map
        }
        AfkCampers.pass(System.currentTimeMillis());
        assertEquals(8, AfkCampers.campersOn(17));
        assertEquals(4, AfkCampers.campersOn(18), "a town never fills up with statues");

        AfkCampers.pass(System.currentTimeMillis());
        assertEquals(8, AfkCampers.campersOn(17), "a second pass keeps the count, it does not stack more");
    }

    @Test
    void botsWithACommitmentAreNeverPicked() throws Exception {
        TownWandererBot partied = wanderer(1, 17, MapId.HENESYS);
        doReturn(mock(Party.class)).when(partied.getChr()).getParty();
        TownWandererBot busy = wanderer(2, 17, MapId.HENESYS);
        TownWandererBot outOfTown = wanderer(3, 17, 104040000);
        AfkCampers.free = bot -> bot != busy; // the director's rule: talking to a player, a follower, a boss run...

        AfkCampers.pass(System.currentTimeMillis());
        assertEquals(0, AfkCampers.campers());
        assertFalse(AfkCampers.isCamping(partied.getChr().getId()));
        assertFalse(AfkCampers.isCamping(outOfTown.getChr().getId()));
    }

    @Test
    void aCamperSkipsItsTickUntilACommitmentOrTimeUpThenRotates() throws Exception {
        TownWandererBot bot = wanderer(1, 17, MapId.HENESYS);
        long now = System.currentTimeMillis();
        AfkCampers.pass(now);
        int id = bot.getChr().getId();
        assertTrue(AfkCampers.isCamping(id));
        assertTrue(AfkCampers.holds(bot), "AFK: the macro tick is skipped");
        assertFalse(bot.isAvailableForAmbientActions(), "no barks, chairs or nudges from the town ambience");

        doReturn(mock(Party.class)).when(bot.getChr()).getParty(); // a player invited it and it joined
        assertFalse(AfkCampers.holds(bot), "a commitment ends the camp on the next tick");
        assertFalse(AfkCampers.isCamping(id));

        doReturn(null).when(bot.getChr()).getParty();
        AfkCampers.pass(System.currentTimeMillis());
        assertFalse(AfkCampers.isCamping(id), "rotation: it camped not long ago");

        TownWandererBot other = wanderer(2, 17, MapId.HENESYS);
        AfkCampers.putForTest(new AfkCampers.Camp(other.getChr().getId(), 17, MapId.HENESYS, now - 1000, now - 1, false));
        assertFalse(AfkCampers.holds(other), "time up: back to its day");
        assertNull(AfkCampers.endReason(bot, bot.getChr(),
                new AfkCampers.Camp(id, 17, MapId.HENESYS, now, now + 60_000, false), now));
    }

    @Test
    void theSwitchReleasesEveryCamper() throws Exception {
        wanderer(1, 17, MapId.HENESYS);
        AfkCampers.pass(System.currentTimeMillis());
        assertEquals(1, AfkCampers.campers());
        System.setProperty(AfkCampers.PROP, "false");
        AfkCampers.pass(System.currentTimeMillis());
        assertEquals(0, AfkCampers.campers());
    }
}
