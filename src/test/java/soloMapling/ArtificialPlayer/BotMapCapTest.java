package soloMapling.ArtificialPlayer;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.5.0 (3k bots): the per-map bot cap's pure parts and its full set. */
class BotMapCapTest {

    static final int HENESYS = 100000000, FM1 = 910000001, FIELD = 104040000;

    private int savedCap;
    private String savedOverrides;

    @BeforeEach
    void save() {
        savedCap = YamlConfig.config.server.BOT_MAP_CAP;
        savedOverrides = YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES;
        BotMapCap.resetForTest();
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.BOT_MAP_CAP = savedCap;
        YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES = savedOverrides;
        BotMapCap.resetForTest();
    }

    /* 1.6.0 towns: while the town pull is on, a town map takes BOT_TOWN_MAP_MAX over BOT_MAP_CAP (an override of its
     * own still wins); off (shipped), nothing changes; fields never change. */
    @Test
    void aTownMapTakesTheTownMaxOnlyWhileTheTownPullIsOn() {
        var server = YamlConfig.config.server;
        double savedPull = server.BOT_TOWN_PULL;
        int savedMax = server.BOT_TOWN_MAP_MAX;
        try {
            server.BOT_MAP_CAP = 50;
            server.BOT_MAP_CAP_OVERRIDES = "";
            server.BOT_TOWN_MAP_MAX = 200;
            server.BOT_TOWN_PULL = 0;
            assertEquals(50, BotMapCap.cap(HENESYS), "off: BOT_MAP_CAP as before");
            server.BOT_TOWN_PULL = .3;
            assertEquals(200, BotMapCap.cap(HENESYS));
            assertEquals(200, BotMapCap.cap(220000000));
            assertEquals(50, BotMapCap.cap(FIELD), "a field keeps BOT_MAP_CAP");
            assertEquals(200, BotMapCap.cap(FM1), "an FM room is in the Free Market family");
            server.BOT_MAP_CAP_OVERRIDES = "100000000:70";
            assertEquals(70, BotMapCap.cap(HENESYS), "a map's own override wins");
            server.BOT_MAP_CAP_OVERRIDES = "";
            server.BOT_MAP_CAP = 0;
            assertEquals(0, BotMapCap.cap(HENESYS), "no cap stays no cap");
        } finally {
            server.BOT_TOWN_PULL = savedPull;
            server.BOT_TOWN_MAP_MAX = savedMax;
        }
    }

    @Test
    void theShippedDefaultIs50WithNoOverrides() {
        assertEquals(50, new config.ServerConfig().BOT_MAP_CAP);
        assertEquals("", new config.ServerConfig().BOT_MAP_CAP_OVERRIDES);
        assertEquals(50, BotMapCap.DEFAULT_CAP);
    }

    @Test
    void overridesParseAndBadEntriesAreSkippedNotFatal() {
        List<String> bad = new ArrayList<>();
        Map<Integer, Integer> o = BotMapCap.parseOverrides(" 910000000:60, 100000000 : 70,,x:5,1:-3,12:3:4,104040000:0 ", bad);
        assertEquals(Map.of(910000000, 60, 100000000, 70, 104040000, 0), o);
        assertEquals(List.of("x:5", "1:-3", "12:3:4"), bad);
        assertTrue(BotMapCap.parseOverrides("", null).isEmpty());
        assertTrue(BotMapCap.parseOverrides(null, null).isEmpty());
    }

    @Test
    void aMapsCapIsItsOverrideElseTheDefaultAndZeroMeansNone() {
        Map<Integer, Integer> o = Map.of(HENESYS, 70, FIELD, 0);
        assertEquals(70, BotMapCap.capFor(HENESYS, 50, o));
        assertEquals(0, BotMapCap.capFor(FIELD, 50, o));
        assertEquals(50, BotMapCap.capFor(FM1, 50, o));
        assertEquals(0, BotMapCap.capFor(FM1, -1, o));
    }

    @Test
    void roomMeansBelowTheCap() {
        assertTrue(BotMapCap.hasRoom(49, 50));
        assertFalse(BotMapCap.hasRoom(50, 50));
        assertFalse(BotMapCap.hasRoom(57, 50));
        assertTrue(BotMapCap.hasRoom(5000, 0)); // no cap
    }

    @Test
    void capReadsConfigYamlAndFollowsAChange() {
        YamlConfig.config.server.BOT_MAP_CAP = 40;
        YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES = "100000000:70";
        assertEquals(70, BotMapCap.cap(HENESYS));
        assertEquals(40, BotMapCap.cap(FM1));
        YamlConfig.config.server.BOT_MAP_CAP = 0;
        YamlConfig.config.server.BOT_MAP_CAP_OVERRIDES = "";
        assertEquals(0, BotMapCap.cap(HENESYS));
    }

    @Test
    void aLoginTakesThePreferredChannelElseTheEmptiestWithRoom() {
        assertEquals(1, BotMapCap.pickChannel(1, new int[]{49, 10, 0, 0, 0}, 50));
        assertEquals(3, BotMapCap.pickChannel(1, new int[]{50, 12, 4, 9, 4}, 50), "ties go to the lower channel");
        assertEquals(2, BotMapCap.pickChannel(4, new int[]{50, 1, 50, 50, 50}, 50));
        assertEquals(-1, BotMapCap.pickChannel(2, new int[]{50, 51, 50}, 50), "every instance full");
        assertEquals(2, BotMapCap.pickChannel(2, new int[]{500, 500}, 0), "no cap");
    }

    @Test
    void excludeFullAddsTheFullMapsOfTheBotsChannelButNotItsOwnMap() {
        Character bot = bot(2, FIELD);
        Set<Integer> cooldown = Set.of(123);
        assertSame(cooldown, BotMapCap.excludeFull(bot, cooldown), "nothing full: the same set, no copy");

        BotMapCap.markForTest(2, HENESYS, true);
        BotMapCap.markForTest(2, FIELD, true);   // where it stands: grinding on adds nobody
        BotMapCap.markForTest(1, FM1, true);     // another channel's instance
        assertEquals(Set.of(123, HENESYS), BotMapCap.excludeFull(bot, cooldown));
        assertFalse(BotMapCap.mayEnter(bot, HENESYS));
        assertTrue(BotMapCap.mayEnter(bot, FIELD), "already there");
        assertTrue(BotMapCap.mayEnter(bot, FM1), "full on channel 1 only");
        assertEquals(0, BotMapCap.turnedAway());
        assertFalse(BotMapCap.admit(bot, HENESYS));
        assertEquals(1, BotMapCap.turnedAway());

        BotMapCap.markForTest(2, HENESYS, false);
        assertTrue(BotMapCap.mayEnter(bot, HENESYS));
    }

    @Test
    void theReadOutListsTheBusiestPerChannelAndMarksOverCap() {
        List<BotMapCap.Load> loads = new ArrayList<>(List.of(
                new BotMapCap.Load(1, HENESYS, 57, 50),
                new BotMapCap.Load(1, FM1, 12, 50),
                new BotMapCap.Load(1, FIELD, 30, 50),
                new BotMapCap.Load(2, HENESYS, 50, 50),
                new BotMapCap.Load(3, FIELD, 3, 0)));
        loads.sort(BotMapCap.BUSIEST);
        Map<Integer, List<BotMapCap.Load>> top = BotMapCap.topPerChannel(loads, 2);
        assertEquals(List.of(HENESYS, FIELD), top.get(1).stream().map(BotMapCap.Load::mapId).toList());
        List<String> lines = BotMapCap.describe(loads, 2, Map.of(HENESYS, 70), 50, 7);
        assertEquals("Bot map cap: 50 per map instance, overrides {100000000=70}; 7 pick(s) turned away since boot",
                lines.get(0));
        assertEquals("5 map instance(s) hold bots; 2 at or over the cap, 1 over it", lines.get(1));
        assertEquals("ch1: 100000000 57/50! 104040000 30/50", lines.get(2));
        assertEquals("ch2: 100000000 50/50", lines.get(3));
        assertEquals("ch3: 104040000 3/-", lines.get(4));
    }

    static Character bot(int channel, int mapId) {
        MapleMap map = mock(MapleMap.class);
        when(map.getChannelId()).thenReturn(channel);
        when(map.getId()).thenReturn(mapId);
        Character chr = mock(Character.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(mapId);
        return chr;
    }

    @Test
    void aWorldEventsCeilingNeverLowersAMapsOwnCap() {
        assertEquals(200, new config.ServerConfig().WORLD_EVENT_MAP_MAX, "the shipped default");
        assertEquals(200, BotMapCap.raisedCap(50, 200));
        assertEquals(300, BotMapCap.raisedCap(300, 200), "an override above it stays");
        assertEquals(0, BotMapCap.raisedCap(0, 200), "no cap stays no cap");
    }
}
