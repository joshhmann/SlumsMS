package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMapCap;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** 1.6.0 towns: busyness targets, the pull, its budget and its bias. */
class TownPulseTest {
    static final Map<Integer, Double> WEIGHTS = TownPulse.channelWeights("1:1.0,2:0.7,3:0.5,4:0.4,5:0.4");
    static final int HENESYS = 100000000, KERNING = 103000000, EL_NATH = 211000000, ELLINIA = 101000000;

    @AfterEach
    void clear() {
        TownPulse.index(Map.of());
        config.YamlConfig.config.server.BOT_TOWN_PULL = 0;
    }

    static TownPulse.ChannelView view(int channel, boolean player, Set<Integer> towns) {
        return new TownPulse.ChannelView(channel, player, towns, id -> 0);
    }

    static List<TownPulse.ChannelView> world(int channels) {
        List<TownPulse.ChannelView> views = new ArrayList<>();
        for (int c = 1; c <= channels; c++) views.add(view(c, false, Set.of()));
        return views;
    }

    static int target(Map<Integer, List<TownPulse.Slot>> index, int channel, int mapId) {
        return index.getOrDefault(channel, List.of()).stream().filter(s -> s.mapId() == mapId)
                .mapToInt(TownPulse.Slot::target).sum();
    }

    @Test
    void shippedOffAndTheKnobDefaults() {
        var shipped = new config.ServerConfig();
        assertEquals(0, shipped.BOT_TOWN_PULL, "off: the jar is rollback-safe");
        assertEquals(200, shipped.BOT_TOWN_MAP_MAX, "owner 2026-10-27: a real client held 620 on one map");
        assertEquals(3, shipped.BOT_TOWN_PULL_PER_MIN);
        assertEquals(.45, shipped.BOT_TOWN_SHARE_MAX, 1e-12);
        assertEquals(1.5, shipped.BOT_TOWN_PLAYER_BOOST, 1e-12);
        assertFalse(TownPulse.on());
        TownPulse.refresh(1000, 1200); // off: no index, nothing read
        assertNull(TownPulse.pullFor(1, 1, 30, 104040000, 0));
    }

    @Test
    void peaksComeFromTheTableAndTheOverrideString() {
        var peaks = TownPulse.peaks("");
        assertEquals(63, peaks.get(HENESYS));
        assertEquals(35, peaks.get(KERNING));
        assertEquals(17, peaks.size());
        assertEquals(100, peaks.get(910000000), "the Free Market family: 100 on channel 1 at peak");
        var over = TownPulse.peaks("100000000:20,220000000:15,999:4,bad");
        assertEquals(20, over.get(HENESYS));
        assertEquals(15, over.get(220000000));
        assertFalse(over.containsKey(999));
        assertTrue(TownPulse.peaks("12").values().stream().allMatch(v -> v == 12), "a bare number sets every town");
        assertEquals(0.4, TownPulse.weightOf(WEIGHTS, 9), 1e-12, "past the list: the smallest weight");
    }

    @Test
    void targetsFollowTheCurveTheChannelAndThePlayers() {
        var peaks = TownPulse.peaks("");
        // at the peak, plenty of bots: channel 1 Henesys is 63 over its family (44.1 town, 6.3 market, park, pet park)
        var index = TownPulse.plan(world(5), peaks, WEIGHTS, 1, 1.5, 35, .45, 100_000);
        assertEquals(35, target(index, 1, HENESYS), "44 capped at a map max of 35");
        assertEquals(44, target(TownPulse.plan(world(5), peaks, WEIGHTS, 1, 1.5, 100, .45, 100_000), 1, HENESYS));
        assertEquals(6, target(index, 1, 100000100));
        assertEquals(35, target(index, 1, KERNING));
        assertEquals(Math.round(35 * .7), target(index, 2, KERNING), "channel 2 at 0.7");
        assertEquals(14, target(index, 5, KERNING), "channel 5 at 0.4");
        // a channel with a real player online counts as 1.0; the player's town gets x1.5, capped per instance
        var views = new ArrayList<>(world(5));
        views.set(4, view(5, true, Set.of(KERNING)));
        index = TownPulse.plan(views, peaks, WEIGHTS, 1, 1.5, 35, .45, 100_000);
        assertEquals(35, target(index, 5, KERNING), "52.5 capped at BOT_TOWN_MAP_MAX");
        assertEquals(35, target(index, 5, ELLINIA), "weight 1.0 with a player online");
        index = TownPulse.plan(views, peaks, WEIGHTS, 1, 1.5, 60, .45, 100_000);
        assertEquals(53, target(index, 5, KERNING), "x1.5 where a player stands");
        // at the trough the big eight keep 4, the rest 2 (a quiet town is never empty)
        index = TownPulse.plan(world(5), peaks, WEIGHTS, .05, 1.5, 35, .45, 100_000);
        assertEquals(4, target(index, 5, EL_NATH));
        assertEquals(2, target(index, 5, 120000000));
        assertEquals(3, target(index, 5, HENESYS), "Henesys's floor of 4 is split over its family");
    }

    @Test
    void allTownsTogetherStayUnderTheShareOfDirectorBots() {
        var peaks = TownPulse.peaks("");
        var index = TownPulse.plan(world(5), peaks, WEIGHTS, 1, 1.5, 35, .45, 1000);
        assertTrue(TownPulse.total(index) <= 450 + 50, "rounding over the instances at most: " + TownPulse.total(index));
        assertTrue(TownPulse.total(index) >= 450 - 50, TownPulse.total(index) + "");
        var full = TownPulse.plan(world(5), peaks, WEIGHTS, 1, 1.5, 35, .45, 100_000);
        assertTrue(target(index, 1, HENESYS) < target(full, 1, HENESYS), "scaled down alike");
        assertEquals(0, TownPulse.total(TownPulse.plan(world(5), peaks, WEIGHTS, 1, 1.5, 35, .45, 0)));
    }

    @Test
    void aFreeBotIsPulledOnlyToATownInItsBandOnItsContinentWithBudgetLeft() {
        config.YamlConfig.config.server.BOT_TOWN_PULL = .3;
        config.YamlConfig.config.server.BOT_TOWN_PULL_PER_MIN = 3;
        TownPulse.index(Map.of(1, List.of(new TownPulse.Slot(1, KERNING, 10, 4),
                new TownPulse.Slot(1, EL_NATH, 10, 0), new TownPulse.Slot(1, ELLINIA, 10, 10))));
        long now = 6_000_000;
        var pull = TownPulse.pullFor(7, 1, 25, 103010000, now);
        assertNotNull(pull);
        assertEquals(KERNING, pull.mapId(), "El Nath is out of a Lv25's band and Ellinia is full");
        assertEquals(.6, pull.deficit(), 1e-9);
        assertNull(TownPulse.pullFor(7, 2, 25, 103010000, now), "nothing indexed on channel 2");
        assertNull(TownPulse.pullFor(8, 1, 25, 200010000, now), "a Lv25 in Ossyria is not pulled to Victoria");
        var elNath = TownPulse.pullFor(8, 1, 80, 211040000, now);
        assertEquals(EL_NATH, elNath.mapId());
        for (int i = 0; i < 3; i++) TownPulse.consume(1, KERNING, now);
        assertNull(TownPulse.pullFor(7, 1, 25, 103010000, now), "Kerning's budget this minute is spent");
        assertNotNull(TownPulse.pullFor(7, 1, 25, 103010000, now + 60_000), "a new minute, a new budget");
    }

    @Test
    void botsOnTheirWayCountAgainstTheDeficit() {
        config.YamlConfig.config.server.BOT_TOWN_PULL = .3;
        TownPulse.index(Map.of(1, List.of(new TownPulse.Slot(1, KERNING, 4, 2))));
        var map = mock(MapleMap.class);
        when(map.getId()).thenReturn(KERNING); when(map.getChannelId()).thenReturn(1); when(map.getBotCount()).thenReturn(2);
        try (var caps = mockStatic(BotMapCap.class, CALLS_REAL_METHODS)) {
            caps.when(() -> BotMapCap.cap(KERNING)).thenReturn(50);
            assertTrue(LifeTownSlots.reserve(901, map));
            assertTrue(LifeTownSlots.reserve(902, map));
            assertEquals(2, LifeTownSlots.incoming(1, KERNING));
            assertEquals(0, LifeTownSlots.incoming(2, KERNING));
            assertNull(TownPulse.pullFor(7, 1, 25, 103010000, 0), "2 there + 2 on the way = the target");
            LifeTownSlots.release(901);
            assertNotNull(TownPulse.pullFor(7, 1, 25, 103010000, 0));
        } finally {
            LifeTownSlots.release(901);
            LifeTownSlots.release(902);
        }
    }

    @Test
    void theBiasPullsHangOutOnlyForAnOfferedFreeBot() {
        config.YamlConfig.config.server.BOT_TOWN_PULL = .3;
        var free = new LifePolicy.Context(new Needs(Map.of()), Map.of(Activity.HANG_OUT, "x"), 0, 0, 0, false, false,
                false, null, List.of(), List.of());
        var busy = new LifePolicy.Context(new Needs(Map.of()), Map.of(Activity.HANG_OUT, "x"), 0, 0, 0, false, false,
                true, null, List.of(), List.of());
        var situation = new LifePolicy.Situation(null, false, false, 0, false, null, 0, 55);
        assertEquals(0, TownPulse.BIAS.bonus(Activity.HANG_OUT, free, situation), 1e-12, "no offer, no pull");
        TownPulse.offer(55, new TownPulse.Pull(KERNING, .5));
        try {
            assertEquals(.15, TownPulse.BIAS.bonus(Activity.HANG_OUT, free, situation), 1e-12);
            assertEquals(.15, TownPulse.BIAS.bonus(Activity.SHOW_OFF, free, situation), 1e-12);
            assertEquals(0, TownPulse.BIAS.bonus(Activity.GRIND, free, situation), 1e-12);
            assertEquals(0, TownPulse.BIAS.bonus(Activity.HANG_OUT, busy, situation), 1e-12, "a commitment wins");
            config.YamlConfig.config.server.BOT_TOWN_PULL = 0;
            assertEquals(0, TownPulse.BIAS.bonus(Activity.HANG_OUT, free, situation), 1e-12, "off at 0");
        } finally {
            TownPulse.offer(55, null);
        }
        assertEquals(0, TownPulse.BIAS.bonus(Activity.HANG_OUT, free, situation), 1e-12, "cleared after the decision");
    }

    /* 1.6.0 towns (owner 2026-10-27): the Free Market, entrance and rooms, is one family of 100 on channel 1 at peak;
     * its staff count, visitors fill the rest; it is reached from any continent, and its pull leans FREE_MARKET too. */
    @Test
    void theFreeMarketIsAFamilyVisitorsFillFromAnywhere() {
        var index = TownPulse.plan(world(2), TownPulse.peaks(""), WEIGHTS, 1, 1.5, 200, .45, 100_000);
        assertEquals(34, target(index, 1, 910000000));
        assertEquals(3, target(index, 1, 910000007));
        int family = 0;
        for (int id = 910000000; id <= 910000022; id++) family += target(index, 1, id);
        assertEquals(100, family, "about 100 on channel 1 at peak");
        config.YamlConfig.config.server.BOT_TOWN_PULL = .3;
        TownPulse.index(Map.of(1, List.of(new TownPulse.Slot(1, 910000000, 34, 20))));
        var pull = TownPulse.pullFor(9, 1, 90, 240010000, 0);
        assertNotNull(pull, "a Lv90 in Leafre may visit the FM");
        assertEquals(910000000, pull.mapId());
        var free = new LifePolicy.Context(new Needs(Map.of()), Map.of(), 0, 0, 0, false, false, false, null,
                List.of(), List.of());
        var situation = new LifePolicy.Situation(null, false, false, 0, false, null, 0, 9);
        TownPulse.offer(9, pull);
        try {
            assertTrue(TownPulse.BIAS.bonus(Activity.FREE_MARKET, free, situation) > 0);
            assertTrue(TownPulse.BIAS.bonus(Activity.SELL, free, situation) > 0);
            TownPulse.offer(9, new TownPulse.Pull(KERNING, .5));
            assertEquals(0, TownPulse.BIAS.bonus(Activity.FREE_MARKET, free, situation), 1e-12, "only for the FM");
        } finally {
            TownPulse.offer(9, null);
        }
    }

    @Test
    void theReportCarriesBotsAgainstTargetsPerTown() {
        TownPulse.index(Map.of(1, List.of(new TownPulse.Slot(1, HENESYS, 20, 30), new TownPulse.Slot(1, 100000100, 8, 3)),
                2, List.of(new TownPulse.Slot(2, KERNING, 10, 4))));
        var report = TownPulse.report();
        assertEquals(38, report.get("targetSum"));
        @SuppressWarnings("unchecked")
        var rows = (Map<String, List<Integer>>) report.get("botsVsTarget");
        assertEquals(List.of(33, 28), rows.get("Henesys"));
        assertEquals(List.of(4, 10), rows.get("Kerning City"));
        assertTrue(report.containsKey("pulled") && report.containsKey("budgetFull") && report.containsKey("hangOutFrom"));
    }
}
