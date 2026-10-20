package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.OffscreenRewardBudget;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Ticket 15: bots cannot take more out of a map instance than its own yield, nor more mesos in a day than their
// cap - and players never pass through either meter. Real bot Characters (the shared headless BotClient), real
// drops and items; the map is a mock whose yield the test sets, and the clock is the test's.
class BotYieldCapsTest {

    private static final int POTION = 2000000;
    private static final long WINDOW_MS = 600_000;

    // 1,000 mesos and 10 item units a minute: a 10-minute window caps bots at 10,000 mesos and 100 units.
    private static final OffscreenRewardBudget.Expected FIELD = new OffscreenRewardBudget.Expected(1_000, 10);

    private final Map<MapleMap, OffscreenRewardBudget.Expected> yields = new HashMap<>();
    private final TestClock clock = new TestClock(Instant.parse("2026-10-19T12:00:00Z"));

    private MapleMap field;
    private MapleMap otherChannel;

    @BeforeEach
    void setUp() {
        BotYieldCaps.clearForTest();
        // A store with no database: the counts live in memory, as on a boot without the pool.
        BotYieldStore.useConnectionSourceForTest(() -> {
            throw new IllegalStateException("no pool in this test");
        });
        // Daily cap: max(1,000,000, level x 0) - out of the way unless a test lowers it.
        limits(1.0, 1_000_000, 0);
        field = mapWithYield(100010000, FIELD);
        otherChannel = mapWithYield(100010000, FIELD);
    }

    @AfterEach
    void tearDown() {
        BotYieldCaps.setForTest(null, null, null);
        BotYieldCaps.clearForTest();
        BotYieldStore.useConnectionSourceForTest(null);
    }

    private void limits(double share, long floor, long perLevel) {
        BotYieldCaps.setForTest(new BotYieldCaps.Limits(true, share, WINDOW_MS, perLevel, floor),
                map -> yields.getOrDefault(map, OffscreenRewardBudget.Expected.NONE), clock);
    }

    private MapleMap mapWithYield(int mapId, OffscreenRewardBudget.Expected perMinute) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(mapId);
        yields.put(map, perMinute);
        return map;
    }

    private static int nextId = 1;

    private static Character bot(MapleMap map, int level) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BotStore.BOT_ID_BASE + 500 + nextId++);
        bot.setLevel(level);
        bot.setMap(map);
        return bot;
    }

    private static MapItem mobMeso(Character looter, int meso) {
        return new MapItem(meso, new Point(0, 0), looter, looter, looter.getClient(), (byte) 0, false);
    }

    private static MapItem mobItem(Character looter, int quantity) {
        return new MapItem(new Item(POTION, (short) 0, (short) quantity, -1), new Point(0, 0), looter, looter,
                looter.getClient(), (byte) 0, false);
    }

    @Test
    void botsOnOneInstanceShareItsYieldAndAnotherInstanceHasItsOwn() {
        Character a = bot(field, 30);
        Character b = bot(field, 30);

        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 4_000)));
        assertTrue(BotYieldCaps.takeDrop(b, mobMeso(b, 4_000)));
        MapItem third = mobMeso(a, 4_000);
        assertFalse(BotYieldCaps.mayTakeDrop(a, third), "10,000 a window: 8,000 taken, 4,000 does not fit");
        assertFalse(BotYieldCaps.takeDrop(a, third));
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 2_000)), "what still fits is taken");

        Character elsewhere = bot(otherChannel, 30);
        assertTrue(BotYieldCaps.takeDrop(elsewhere, mobMeso(elsewhere, 9_000)),
                "the same map on another channel is its own instance with its own spawns");
    }

    @Test
    void theMapCapIsARateThatRefillsAsTheWindowPasses() {
        Character a = bot(field, 30);
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 10_000)));
        assertFalse(BotYieldCaps.mayTakeDrop(a, mobMeso(a, 1)));

        clock.advance(WINDOW_MS / 2);
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 5_000)), "half a window frees half the cap");
        assertFalse(BotYieldCaps.mayTakeDrop(a, mobMeso(a, 1)));

        clock.advance(WINDOW_MS * 10);
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 10_000)), "a quiet map refills to one window");
        assertFalse(BotYieldCaps.mayTakeDrop(a, mobMeso(a, 1)), "and never banks more than one");
    }

    @Test
    void itemUnitsAreCappedPerInstanceToo() {
        Character a = bot(field, 30);
        assertTrue(BotYieldCaps.takeDrop(a, mobItem(a, 60)));
        assertFalse(BotYieldCaps.takeDrop(a, mobItem(a, 60)), "100 units a window: 60 more do not fit");
        assertTrue(BotYieldCaps.takeDrop(a, mobItem(a, 40)));
        assertFalse(BotYieldCaps.takeItem(a, new Item(POTION, (short) 0, (short) 1, -1)),
                "an offscreen share draws on the same meter");
    }

    @Test
    void theShareScalesTheCap() {
        limits(0.5, 1_000_000, 0);
        Character a = bot(field, 30);
        assertFalse(BotYieldCaps.takeDrop(a, mobMeso(a, 6_000)), "half of 10,000");
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 5_000)));
    }

    @Test
    void theDailyMesoCapFollowsLevelAndResetsWithTheDay() {
        limits(1.0, 1_000, 100);   // max(1,000, level x 100)
        MapleMap town = mapWithYield(100000000, OffscreenRewardBudget.Expected.NONE);   // no map cap here
        Character bot = bot(town, 20);   // cap 2,000

        assertEquals(1_500, BotYieldCaps.takeMesos(bot, 1_500));
        assertEquals(500, BotYieldCaps.takeMesos(bot, 1_500), "an offscreen share is cut to what is left");
        assertEquals(0, BotYieldCaps.takeMesos(bot, 1_500));
        assertFalse(BotYieldCaps.takeDrop(bot, mobMeso(bot, 10)), "a drop is all or nothing");
        assertTrue(BotYieldCaps.takeDrop(bot, mobItem(bot, 1)), "items are not part of the meso cap");

        Character low = bot(town, 1);
        assertEquals(1_000, BotYieldCaps.takeMesos(low, 5_000), "the floor holds for a low level");

        clock.advance(24L * 60 * 60 * 1000);
        assertEquals(1_500, BotYieldCaps.takeMesos(bot, 1_500), "a new day, a new cap");
    }

    @Test
    void anOffscreenShareTakesWhatBothMetersLeave() {
        limits(1.0, 3_000, 0);
        Character bot = bot(field, 30);
        assertEquals(3_000, BotYieldCaps.takeMesos(bot, 8_000), "the daily cap is the tighter one");

        Character fresh = bot(field, 30);
        assertTrue(BotYieldCaps.takeDrop(fresh, mobMeso(fresh, 2_000)));
        Character third = bot(field, 30);
        assertEquals(3_000, BotYieldCaps.takeMesos(third, 9_000));
        Character fourth = bot(field, 30);
        assertEquals(2_000, BotYieldCaps.takeMesos(fourth, 9_000), "the map has 10,000 - 8,000 left");
    }

    @Test
    void aTakeWhoseCreditFailsIsGivenBack() {
        limits(1.0, 5_000, 0);
        Character a = bot(field, 30);
        MapItem drop = mobMeso(a, 5_000);
        assertTrue(BotYieldCaps.takeDrop(a, drop));
        BotYieldCaps.returnDrop(a, drop);
        assertEquals(0, BotYieldCaps.dailyMesosForTest(a.getId()));
        assertTrue(BotYieldCaps.takeDrop(a, mobMeso(a, 5_000)), "map and day both have it back");

        long taken = BotYieldCaps.takeMesos(a, 1);
        assertEquals(0, taken);
        BotYieldCaps.returnMesos(a, 5_000);
        assertEquals(5_000, BotYieldCaps.takeMesos(a, 5_000));
    }

    @Test
    void playersAndPlayerDropsAreNeverMetered() {
        limits(1.0, 0, 0);   // a bot could take no mesos at all
        Character bot = bot(field, 30);
        assertEquals(0, BotYieldCaps.takeMesos(bot, 100));

        Character player = mock(Character.class);   // no BotClient: a player
        when(player.getMap()).thenReturn(field);
        when(player.getId()).thenReturn(7);
        assertEquals(1_000_000, BotYieldCaps.takeMesos(player, 1_000_000));
        assertTrue(BotYieldCaps.takeDrop(player, mobMeso(player, 1_000_000)));
        assertTrue(BotYieldCaps.takeItem(player, new Item(POTION, (short) 0, (short) 1_000, -1)));

        MapItem handedOver = new MapItem(50_000, new Point(0, 0), player, bot, bot.getClient(), (byte) 2, true);
        assertTrue(BotYieldCaps.takeDrop(bot, handedOver), "a player's drop changes hands; it is not world yield");
        assertEquals(0, BotYieldCaps.dailyMesosForTest(bot.getId()));
    }

    @Test
    void switchedOffNothingIsMetered() {
        BotYieldCaps.setForTest(new BotYieldCaps.Limits(false, 0, WINDOW_MS, 0, 0),
                map -> FIELD, clock);
        Character bot = bot(field, 30);
        assertEquals(50_000, BotYieldCaps.takeMesos(bot, 50_000));
        assertTrue(BotYieldCaps.takeDrop(bot, mobItem(bot, 500)));
    }

    @Test
    void aRefusedMobDropStaysOnTheFloorAndInTheWorld() {
        limits(1.0, 1_000_000, 0);
        Character bot = bot(field, 30);
        assertTrue(BotHoldings.creditDrop(bot, mobMeso(bot, 10_000)));
        int before = bot.getMeso();

        MapItem over = mobMeso(bot, 500);
        assertFalse(BotHoldings.canCreditDrop(bot, over), "a bot does not walk to a drop it may not take");
        assertFalse(BotHoldings.creditDrop(bot, over));
        assertFalse(over.isPickedUp());
        assertEquals(before, bot.getMeso());
    }

    @Test
    void aCreditThatFailsGivesTheCapBack() {
        Character bot = bot(field, 30);
        bot.gainMeso(Integer.MAX_VALUE - bot.getMeso() - 10, false, false, false);   // no room for the drop
        assertFalse(BotHoldings.creditDrop(bot, mobMeso(bot, 9_000)));
        assertEquals(0, BotYieldCaps.dailyMesosForTest(bot.getId()));
        Character other = bot(field, 30);
        assertTrue(BotYieldCaps.takeDrop(other, mobMeso(other, 10_000)), "the map's room was given back");
    }

    @Test
    void storedCountsAreReadOnceAndAddedToAndChangedOnesWritten() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(con.prepareStatement(BotYieldStore.SELECT_DAY)).thenReturn(select);
        when(con.prepareStatement(BotYieldStore.UPSERT)).thenReturn(upsert);
        when(select.executeQuery()).thenReturn(rows);
        Character bot = bot(mapWithYield(100000000, OffscreenRewardBudget.Expected.NONE), 30);   // daily cap only
        int botId = bot.getId() - BotStore.BOT_ID_BASE;
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt("BotId")).thenReturn(botId);
        when(rows.getLong("Meso")).thenReturn(990_000L);
        BotYieldStore.resetForTest();
        BotYieldStore.useConnectionSourceForTest(() -> con);

        assertEquals(10_000, BotYieldCaps.takeMesos(bot, 50_000), "990,000 of 1,000,000 was taken before the restart");
        verify(select).setDate(1, Date.valueOf(LocalDate.of(2026, 10, 19)));

        BotYieldCaps.flush();
        verify(upsert).setInt(1, botId);
        verify(upsert).setDate(2, Date.valueOf(LocalDate.of(2026, 10, 19)));
        verify(upsert).setLong(3, 1_000_000L);
        verify(upsert).executeBatch();
    }

    @Test
    void withoutMigration006TheCountsLiveInMemoryAndAreNeverWritten() throws Exception {
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenThrow(new SQLException("Table 'botyield' doesn't exist", "42S02", 1146));
        BotYieldStore.resetForTest();
        BotYieldStore.useConnectionSourceForTest(() -> con);
        Character bot = bot(field, 30);

        assertEquals(5_000, BotYieldCaps.takeMesos(bot, 5_000));
        BotYieldCaps.flush();
        verify(con, never()).prepareStatement(BotYieldStore.UPSERT);
        assertTrue(BotYieldCaps.report(5).get(0).contains("migration 006 not applied"));
    }

    @Test
    void theReadOutNamesMapsAndBots() {
        Character bot = bot(field, 30);
        bot.setName("Capped");
        assertTrue(BotYieldCaps.takeDrop(bot, mobMeso(bot, 4_000)));
        String all = String.join("\n", BotYieldCaps.report(5));
        assertAll(
                () -> assertTrue(all.contains("map 100010000: 4,000 mesos"), all),
                () -> assertTrue(all.contains("Capped"), all),
                () -> assertTrue(BotYieldCaps.report(bot).contains("4,000 of 1,000,000"), BotYieldCaps.report(bot)));
    }

    @Test
    void theMigrationCreatesTheTableTheStoreWritesAndRollsItBack() throws Exception {
        String apply = Files.readString(Path.of("database/migrations/006-bot-yield-caps.sql"));
        String rollback = Files.readString(Path.of("database/migrations/006-bot-yield-caps.rollback.sql"));
        assertAll(
                () -> assertTrue(apply.contains("CREATE TABLE IF NOT EXISTS botyield")),
                () -> assertTrue(apply.contains("BotId INT UNSIGNED")),
                () -> assertTrue(apply.contains("Day   DATE")),
                () -> assertTrue(apply.contains("Meso  BIGINT UNSIGNED")),
                () -> assertTrue(apply.contains("migration_id) VALUES ('006-bot-yield-caps')")),
                () -> assertTrue(rollback.contains("DROP TABLE IF EXISTS botyield")),
                () -> assertTrue(rollback.contains("WHERE migration_id='006-bot-yield-caps'")));
    }

    private static final class TestClock extends Clock {
        private Instant now;

        TestClock(Instant start) {
            now = start;
        }

        void advance(long ms) {
            now = now.plusMillis(ms);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
