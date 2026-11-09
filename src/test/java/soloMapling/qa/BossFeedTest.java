package soloMapling.qa;

import client.Character;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.7 boss-feed-lines: the helper's two sinks, the Pianus lines and the skill rate limits. */
class BossFeedTest {

    private final List<String> feed = new CopyOnWriteArrayList<>();
    private final List<int[]> feedChars = new CopyOnWriteArrayList<>();
    private final List<String> log = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private QaLog.SinkHandle handle;

    @BeforeEach
    void setUp() {
        BossFeed.useForTests(log::add, now::get);
        handle = QaLog.sink((topic, mapId, ids, line) -> {
            if (line.contains("boss") || line.startsWith("BOSS")) {
                feed.add(line);
                feedChars.add(ids);
            }
        });
    }

    @AfterEach
    void tearDown() {
        handle.close();
        BossFeed.useForTests(null, null);
    }

    private static Character human(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(false);
        return c;
    }

    private static Character bot(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(true);
        return c;
    }

    private static EventInstanceManager eimWith(Character... members) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getPlayers()).thenReturn(new ArrayList<>(Arrays.asList(members)));
        return eim;
    }

    private static Monster pianus(int mobId, MapleMap map) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(mobId);
        when(m.isBoss()).thenReturn(true);
        when(m.getMap()).thenReturn(map);
        when(m.getExp()).thenReturn(1_300_000);
        when(m.getMaxHp()).thenReturn(30_000_000L);
        when(m.getHp()).thenReturn(15_000_000L);
        return m;
    }

    private static MapleMap pianusMap(Character... players) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(BossFeed.PIANUS_MAP);
        when(map.getAllPlayers()).thenReturn(new ArrayList<>(Arrays.asList(players)));
        return map;
    }

    // ---------------------------------------------------------------- the two sinks

    @Test
    void anEventLineGoesToTheFeedAndTheLogWithHumanIdsOnly() {
        handle.topics(List.of(QaLog.Topic.MECHANICS));

        BossFeed.line(eimWith(human(11), bot(1_000_000_005), human(12)), "RUN boss zakum arms down: all 8 after 40 s [zakum, leader X]");

        assertEquals(List.of("RUN boss zakum arms down: all 8 after 40 s [zakum, leader X]"), feed);
        assertEquals(feed, log, "the log copy is the feed line");
        assertEquals("[11, 12]", Arrays.toString(feedChars.get(0)), "characters by id, bots not named");
    }

    @Test
    void withTheFeedOffTheLogStillGetsTheLine() {
        handle.topics(List.of());   // nobody polls

        assertFalse(BossFeed.on());
        BossFeed.line(eimWith(human(11)), "RUN boss horntail head 1 down after 5 s");

        assertEquals(List.of(), feed);
        assertEquals(List.of("RUN boss horntail head 1 down after 5 s"), log);
    }

    @Test
    void theLogCopyIsInfoUnderTheLoggerNamedBossFeed() {
        BossFeed.useForTests(null, now::get);   // the real logger sink
        List<String> seen = new ArrayList<>();
        List<Level> levels = new ArrayList<>();
        LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        AbstractAppender app = new AbstractAppender("bossfeed-test", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                seen.add(e.getLoggerName() + "|" + e.getMessage().getFormattedMessage());
                levels.add(e.getLevel());
            }
        };
        app.start();
        LoggerConfig lc = new LoggerConfig(BossFeed.LOGGER_NAME, Level.INFO, false);
        lc.addAppender(app, null, null);
        cfg.addLogger(BossFeed.LOGGER_NAME, lc);
        ctx.updateLoggers();
        try {
            BossFeed.line(eimWith(human(1)), "RUN boss zakum cleared after 9 s: defeatedBoss=1");
        } finally {
            cfg.removeLogger(BossFeed.LOGGER_NAME);
            ctx.updateLoggers();
            app.stop();
        }
        assertEquals(List.of("BossFeed|RUN boss zakum cleared after 9 s: defeatedBoss=1"), seen);
        assertEquals(List.of(Level.INFO), levels);
    }

    @Test
    void aBrokenLogSinkNeverBreaksTheCaller() {
        BossFeed.useForTests(s -> {
            throw new IllegalStateException("disk full");
        }, now::get);
        BossFeed.line(eimWith(human(1)), "RUN boss zakum cleanup");
    }

    // ---------------------------------------------------------------- Pianus

    @Test
    void aFieldLineNamesItsChannelSoTwoCopiesOfTheMapStayApart() {
        MapleMap map = pianusMap(human(21));
        when(map.getChannelId()).thenReturn(3);
        BossFeed.spawned(map, pianus(BossFeed.PIANUS_R, map));
        assertEquals(List.of("BOSS spawn pianus R map 230040420 source timer ch 3"), log);
    }

    @Test
    void pianusSpawnKillAndRewardLinesCarryTheirSourceAndCounts() {
        handle.topics(List.of(QaLog.Topic.MECHANICS));
        MapleMap map = pianusMap(human(21), bot(1_000_000_007));
        Monster r = pianus(BossFeed.PIANUS_R, map);

        BossFeed.spawned(map, r);                                   // the map's spawn timer
        BossFeed.asGm(() -> BossFeed.spawned(map, pianus(BossFeed.PIANUS_L, map)));   // !pianus
        now.addAndGet(95_000);
        BossFeed.killed(r, 3);

        assertEquals(List.of(
                "BOSS spawn pianus R map 230040420 source timer",
                "BOSS spawn pianus L map 230040420 source gm",
                "BOSS kill pianus R after 95 s, damagers 3",
                "BOSS reward pianus R exp 1300000 to 3 members"), log);
        assertEquals(log, feed);
        assertEquals("[21]", Arrays.toString(feedChars.get(0)));
    }

    @Test
    void aBossThatIsNotPianusWritesNoSpawnOrKillLine() {
        MapleMap map = pianusMap();
        Monster zakumBody = pianus(8800002, map);

        BossFeed.spawned(map, zakumBody);
        BossFeed.killed(zakumBody, 6);

        assertEquals(List.of(), log);
    }

    // ---------------------------------------------------------------- skill lines and their limits

    @Test
    void healSummonAndImmuneEachGiveOneLineAndTheDeathGivesTheSummary() {
        handle.topics(List.of(QaLog.Topic.MECHANICS));
        MapleMap map = pianusMap();
        Monster p = pianus(BossFeed.PIANUS_L, map);

        BossFeed.healed(p, 120_000);
        BossFeed.summoned(p, 5, 8510100);
        BossFeed.immune(p, true, 35);
        BossFeed.immune(p, false, 35);   // inside the limit: counted, not written
        BossFeed.killed(p, 2);

        assertEquals(List.of(
                "BOSS heal pianus L +120000 hp (now 50%)",
                "BOSS summon pianus L bloody-boom 5",
                "BOSS immune pianus L weapon 35 s"), log.subList(0, 3));
        assertTrue(log.contains("BOSS skills pianus L heals 1 (+120000 hp), summons 1 (5 mobs), immunes 2"), log.toString());
    }

    @Test
    void theFeedLimitsToOneLinePerThreeSecondsAndTheLogToOnePerThirty() {
        handle.topics(List.of(QaLog.Topic.MECHANICS));
        Monster p = pianus(8800007, pianusMap());   // any isBoss() mob: here a Zakum arm

        for (int s = 0; s < 600; s++) {      // ten minutes of a heal every second
            BossFeed.healed(p, 1_000);
            now.addAndGet(1_000);
        }

        assertEquals(20, log.size(), "log: at most one per 30 s");
        assertEquals(200, feed.size(), "feed: one per 3 s");
        BossFeed.killed(p, 1);
        assertEquals(21, log.size(), "plus one summary");
        assertEquals("BOSS skills mob 8800007 heals 600 (+600000 hp), summons 0 (0 mobs), immunes 0", log.get(20));
    }

    @Test
    void aThousandBloodyBoomsInOneUseAreOneSummonLine() {
        Monster p = pianus(BossFeed.PIANUS_R, pianusMap());

        BossFeed.summoned(p, 1_000, 8510100);
        BossFeed.summoned(p, 0, 8510100);       // a use that spawned nothing writes nothing

        assertEquals(List.of("BOSS summon pianus R bloody-boom 1000"), log);
    }

    @Test
    void twoBossesAndTwoKindsKeepSeparateLimits() {
        MapleMap map = pianusMap();
        Monster a = pianus(BossFeed.PIANUS_R, map);
        Monster b = pianus(BossFeed.PIANUS_L, map);

        BossFeed.healed(a, 10);
        BossFeed.healed(b, 10);
        BossFeed.immune(a, true, 35);
        BossFeed.healed(a, 10);   // same boss, same kind, inside 30 s

        assertEquals(3, log.size(), log.toString());
    }
}
