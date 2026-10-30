package soloMapling.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 35: the live snapshot exporter. */
class DashboardExporterTest {

    @TempDir
    Path dir;

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long DAY1 = LocalDate.of(2026, 10, 19).atTime(10, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

    /** A made-up server the test changes between runs. */
    private static final class Fake implements DashboardExporter.Sources {
        List<LiveSnapshot.BotSample> bots = new ArrayList<>();
        long tickNanos;
        RuntimeException fail;
        int accountReads;

        @Override
        public Map<String, Object> accounts(long now) {
            accountReads++;
            return Map.of("at", now / 1000, "reads", accountReads);
        }

        @Override
        public List<LiveSnapshot.BotSample> bots() {
            if (fail != null) {
                throw fail;
            }
            return bots;
        }

        @Override
        public LiveSnapshot.Population population() {
            return new LiveSnapshot.Population(50, 12, 40, "on", List.of(), 2, new int[]{1, 2, 3, 4});
        }

        @Override
        public LiveSnapshot.Health health() {
            return new LiveSnapshot.Health(3_600_000, 512L << 20, 4096L << 20, 100L << 20, 0.25, 1.5, 80, 10, 50,
                    new int[]{2, 3, 5, 0}, new int[]{6, 4, 0},
                    List.of(new BotPerformanceMonitor.PhaseTotal("macro-tick", BotPerformanceMonitor.Bucket.BACKGROUND,
                                    false, tickNanos, tickNanos / 1000, 0),
                            new BotPerformanceMonitor.PhaseTotal("pathfind", BotPerformanceMonitor.Bucket.BACKGROUND,
                                    true, tickNanos / 2, 10, 0)));
        }

        @Override
        public IntFunction<String> mapNames() {
            return id -> id == 100000000 ? "Henesys" : "";
        }
    }

    private static LiveSnapshot.BotSample bot(int id, int level, String job, int map, String activity, long meso) {
        return new LiveSnapshot.BotSample(id, "bot" + id, level, job, map, "training", activity, meso);
    }

    private DashboardExporter.Runner runner(Fake fake, DerivedEconomy economy) {
        return new DashboardExporter.Runner(dir, UTC, fake, () -> economy);
    }

    private Map<?, ?> read(String file) throws Exception {
        return (Map<?, ?>) Json.parse(Files.readString(dir.resolve(file)));
    }

    @Test
    void offUnlessTheSystemPropertySaysOn() {
        assertFalse(DashboardExporter.enabled());
        DashboardExporter.startIfEnabled();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("dashboard-export")),
                "no exporter thread without -Dsolomapling.dashboard=true");
        assertFalse(Files.exists(dir.resolve("live.json")));
    }

    @Test
    void aRunWritesTheLiveSnapshotAndTheDaySeries() throws Exception {
        Fake fake = new Fake();
        fake.bots.add(bot(1, 34, "HUNTER", 100000000, "grind", 1_000));
        fake.bots.add(bot(2, 38, "HUNTER", 100000000, "travel", 50_000));
        fake.bots.add(bot(3, 71, "PRIEST", 104040000, "grind", 2_000_000));
        DashboardExporter.Runner r = runner(fake, new DerivedEconomy(UTC));

        r.runOnce(DAY1);
        fake.tickNanos = 6_000_000_000L; // 6 s of macro ticks over the next minute
        fake.bots.set(0, bot(1, 34, "HUNTER", 100000000, "grind", 4_000)); // looted 3,000
        r.runOnce(DAY1 + 60_000);

        Map<?, ?> live = read("live.json");
        Map<?, ?> bots = (Map<?, ?>) live.get("bots");
        assertEquals(3, ((Number) bots.get("online")).intValue());
        assertEquals(Map.of("grind", 2L, "travel", 1L), numbersAsLongs((Map<?, ?>) bots.get("activity")));
        assertEquals(List.of(List.of(30L, 2L), List.of(70L, 1L)), deepLongs(bots.get("levels")));
        List<?> topMap = (List<?>) ((List<?>) bots.get("maps")).get(0);
        // 1.6.0 towns: residents (boot environment) vs roster (director) bots ride along on each map row
        assertEquals(List.of(100000000L, "Henesys", 2L, 36L, 0L, 2L), deepLongs(topMap));
        // ticket 157: every map with bots, not only the top 40, for the site's world graph
        assertEquals(Map.of("100000000", 2L, "104040000", 1L), numbersAsLongs((Map<?, ?>) bots.get("mapCounts")));
        assertTrue(live.get("switches") instanceof Map<?, ?>, "the effective switches ride along");

        Map<?, ?> health = (Map<?, ?>) live.get("health");
        assertEquals(100.0, ((Number) health.get("tickMsPerS")).doubleValue(), 0.01, "6 s over 60 s, the nested phase left out");
        assertEquals(2L, ((Number) health.get("gateUsed")).longValue());

        Map<?, ?> economy = (Map<?, ?>) live.get("economy");
        assertEquals("derived", economy.get("source"));
        assertEquals(3_000L, ((Number) economy.get("mesosToday")).longValue());
        assertEquals(50_000L, ((Number) ((Map<?, ?>) economy.get("wealth")).get("p50")).longValue());

        Map<?, ?> series = read("series/2026-10-19.json");
        assertEquals(1, ((List<?>) series.get("points")).size(), "the series is saved every few runs, the first one at once");
        Map<?, ?> first = (Map<?, ?>) ((List<?>) series.get("points")).get(0);
        assertEquals(5, ((List<?>) first.get("ch")).size(), "chat replies per tier ride along in each point");
        assertEquals(Map.of("Henesys", List.of(0L, 2L)), deepLongMap((Map<?, ?>) first.get("towns")),
                "1.6.0 towns: residents vs roster per town in the series");
        assertEquals(List.of("2026-10-19"), read("days.json").get("series"));
        try (var files = Files.list(dir)) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")), "no temp files left behind");
        }
    }

    @Test
    void calibrationJsonIsWrittenOnlyWhileKillCalibrationIsOn() throws Exception {
        String prop = soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.PROPERTY;
        try {
            System.setProperty(prop, "false");
            soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.resetSwitch();
            DashboardExporter.Runner r = runner(new Fake(), new DerivedEconomy(UTC));
            r.runOnce(DAY1);
            assertFalse(Files.exists(dir.resolve("calibration.json")), "off (live): no file");
            System.setProperty(prop, "true");
            soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.resetSwitch();
            r.runOnce(DAY1 + 60_000);
            Map<?, ?> doc = read("calibration.json");
            assertEquals(1, ((Number) doc.get("version")).intValue());
            assertEquals(true, doc.get("reportOnly"));
            assertTrue(doc.get("buckets") instanceof List<?>);
        } finally {
            System.clearProperty(prop);
            soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration.resetSwitch();
        }
    }

    @Test
    void aFailingReadIsLoggedAndTheNextRunWorks() {
        Fake fake = new Fake();
        DashboardExporter.Runner r = runner(fake, new DerivedEconomy(UTC));
        fake.fail = new IllegalStateException("bot map changed under us");
        assertDoesNotThrow(r::runSafely);
        assertFalse(Files.exists(dir.resolve("live.json")));
        fake.fail = null;
        assertDoesNotThrow(r::runSafely);
        assertTrue(Files.exists(dir.resolve("live.json")));
    }

    @Test
    void theTurnOfTheDayWritesARollupAndARestartCarriesOn() throws Exception {
        Fake fake = new Fake();
        fake.bots.add(bot(1, 10, "BEGINNER", 1, "town", 0));
        DashboardExporter.Runner r = runner(fake, new DerivedEconomy(UTC));
        for (int i = 0; i < 3; i++) {
            r.runOnce(DAY1 + i * 60_000L);
        }
        r.runOnce(DAY1 + 86_400_000L); // next day
        Map<?, ?> rollup = read("daily/2026-10-19.json");
        assertEquals(3L, ((Number) rollup.get("points")).longValue());
        assertEquals(1.0, ((Number) rollup.get("onlineAvg")).doubleValue());
        assertEquals(List.of("2026-10-19"), read("days.json").get("daily"));

        // A restart the same day picks up the saved points.
        DashboardExporter.Runner again = runner(fake, new DerivedEconomy(UTC));
        again.runOnce(DAY1 + 86_400_000L + 60_000);
        assertEquals(2, again.seriesSize());
    }

    @Test
    void aDayTheServerWasDownForIsStillRolledUp() throws Exception {
        Fake fake = new Fake();
        runner(fake, new DerivedEconomy(UTC)).runOnce(DAY1);
        // the server comes back two days later
        runner(fake, new DerivedEconomy(UTC)).runOnce(DAY1 + 2 * 86_400_000L);
        assertTrue(Files.exists(dir.resolve("daily/2026-10-19.json")));
        assertEquals(List.of("2026-10-19"), read("days.json").get("daily"));
    }

    @Test
    void anotherEconomyFeedReplacesTheEstimate() throws Exception {
        EconomyFeed ledger = new EconomyFeed() {
            @Override
            public String source() {
                return "ledger";
            }

            @Override
            public List<MapIntake> mapsToday() {
                return List.of(new MapIntake(104040000, 9_000, 12, 20_000, 40, 3));
            }

            @Override
            public List<BotIntake> botsToday() {
                return List.of(new BotIntake(7, "Ash", 30, 9_000, 300_000, 0));
            }

            @Override
            public Map<String, Object> caps() {
                return Map.of("map share", 1.0);
            }
        };
        new DashboardExporter.Runner(dir, UTC, new Fake(), () -> ledger).runOnce(DAY1);
        Map<?, ?> economy = (Map<?, ?>) read("live.json").get("economy");
        assertEquals("ledger", economy.get("source"));
        assertEquals(12L, ((Number) economy.get("itemsToday")).longValue());
        assertEquals(List.of(104040000L, "", 9_000L, 12L, 20_000L, 40L, 3L), deepLongs(((List<?>) economy.get("maps")).get(0)));
    }

    @Test
    void theAccountsPanelReadsTheDatabaseAtMostEveryFiveMinutes() throws Exception {
        Fake fake = new Fake();
        DashboardExporter.Runner r = runner(fake, new DerivedEconomy(UTC));
        for (int i = 0; i < 10; i++) {
            r.runOnce(DAY1 + i * 60_000L); // a run a minute, as on live
        }
        assertEquals(2, fake.accountReads, "minutes 0 and 5 only");
        Map<?, ?> accounts = (Map<?, ?>) read("live.json").get("accounts");
        assertEquals(2L, ((Number) accounts.get("reads")).longValue(), "the last reading rides along in between");
        r.runOnce(DAY1 - 60_000L); // the clock went back: read again rather than wait
        assertEquals(3, fake.accountReads);
    }

    @Test
    void chatIsCountsOnlyNeverWhatWasSaid() throws Exception {
        soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.resetForTest();
        String said = "my secret password is hunter2";
        soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.reply(
                soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.Tier.LLM, 120, "Ash", said);
        soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.flagged("scam");
        runner(new Fake(), new DerivedEconomy(UTC)).runOnce(DAY1);
        String live = Files.readString(dir.resolve("live.json"));
        assertFalse(live.contains("hunter2"), "a player's line never reaches live.json");
        Map<?, ?> chat = (Map<?, ?>) read("live.json").get("chat");
        assertEquals(Map.of("scam", 1L), numbersAsLongs((Map<?, ?>) chat.get("flags")));
        soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats.resetForTest();
    }

    private static Map<Object, Long> numbersAsLongs(Map<?, ?> m) {
        Map<Object, Long> out = new java.util.HashMap<>();
        m.forEach((k, v) -> out.put(k, ((Number) v).longValue()));
        return out;
    }

    private static Map<Object, Object> deepLongMap(Map<?, ?> m) {
        Map<Object, Object> out = new java.util.HashMap<>();
        m.forEach((k, v) -> out.put(k, deepLongs(v)));
        return out;
    }

    private static Object deepLongs(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(DashboardExporterTest::deepLongs).toList();
        }
        return o instanceof Number n && !(o instanceof Double) ? n.longValue() : o;
    }
}
