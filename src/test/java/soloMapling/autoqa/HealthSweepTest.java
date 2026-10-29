package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.autoqa.FootholdCheck.Pose.AIRBORNE;
import static soloMapling.autoqa.FootholdCheck.Pose.GROUNDED;

class HealthSweepTest {

    static final int HENESYS = 100000000;
    static final List<FootholdCheck.Seg> GROUND = List.of(new FootholdCheck.Seg(-2000, 274, 2000, 274));

    static HealthSweep.BotSample bot(int id, int ch, int x, int y, FootholdCheck.Pose pose, boolean moving) {
        return new HealthSweep.BotSample(id, "Bot" + id, ch, HENESYS, x, y, pose, moving, true, null);
    }

    static HealthSweep.Snapshot snap(long now, List<HealthSweep.BotSample> bots, int[] perChannel, int errors) {
        return new HealthSweep.Snapshot(now, bots, bots.size(), 1, perChannel,
                BotChannels.shares("100,85,50,30,20", perChannel.length), errors, "test.log");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> check(Map<String, Object> result, String name) {
        return (Map<String, Object>) ((Map<String, Object>) result.get("checks")).get(name);
    }

    /** 1.6.1 (h): a chat-model endpoint that got calls and answered none since the last sweep is down (#66 / ch-06). */
    @Test
    void aDownChatModelEndpointFailsTheSweep() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = List.of(bot(1, 1, 0, 274, GROUNDED, false));
        int[] ch = {350, 300, 175, 105, 70};
        String main = "intent+con@192.168.0.17:8913", tone = "tone@192.168.0.17:8910";
        Map<String, HealthSweep.DecideTotals> t = new java.util.LinkedHashMap<>();
        assertEquals("OK", check(s.run(snap(0, bots, ch, 0), m -> GROUND, t), "chatModel").get("status"), "no calls yet");
        t.put(main, new HealthSweep.DecideTotals(20, 20, 0, 0, 0));
        t.put(tone, new HealthSweep.DecideTotals(20, 19, 1, 0, 0));
        assertEquals("OK", check(s.run(snap(60_000, bots, ch, 0), m -> GROUND, t), "chatModel").get("status"));
        // :8913 stops answering: 6 more calls, all failed fast
        t.put(main, new HealthSweep.DecideTotals(26, 20, 0, 6, 0));
        t.put(tone, new HealthSweep.DecideTotals(26, 25, 1, 0, 0));
        Map<String, Object> r = s.run(snap(120_000, bots, ch, 0), m -> GROUND, t);
        Map<String, Object> c = check(r, "chatModel");
        assertEquals("FAIL", c.get("status"));
        assertTrue(String.valueOf(c.get("summary")).contains(main) && String.valueOf(c.get("summary")).contains("rules only"),
                String.valueOf(c.get("summary")));
        assertFalse(String.valueOf(c.get("summary")).contains(tone), "the tone endpoint still answers");
        assertEquals("FAIL", r.get("status"));
        // back up: judged on the calls since the last sweep only
        t.put(main, new HealthSweep.DecideTotals(36, 30, 0, 6, 0));
        assertEquals("OK", check(s.run(snap(180_000, bots, ch, 0), m -> GROUND, t), "chatModel").get("status"));
        // slow: under half answered in time
        t.put(main, new HealthSweep.DecideTotals(46, 33, 7, 6, 0));
        assertEquals("WARN", check(s.run(snap(240_000, bots, ch, 0), m -> GROUND, t), "chatModel").get("status"));
        // the old two-argument run has no chat-model check
        assertEquals(null, check(new HealthSweep(HealthSweep.Limits.defaults()).run(snap(0, bots, ch, 0), m -> GROUND),
                "chatModel"));
    }

    @Test
    void aHealthyMapPasses() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bots.add(bot(i, 1, i * 100, 274, GROUNDED, false));
        }
        Map<String, Object> r = s.run(snap(0, bots, new int[]{350, 300, 175, 105, 70}, 0), m -> GROUND);
        assertEquals("OK", r.get("status"), String.valueOf(r.get("summary")));
        assertTrue(Json.isValid(Json.write(r)));
    }

    @Test
    void sunkBotsFailTheSweepAndAreListedDeepestFirst() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = List.of(
                bot(1, 1, 0, 274, GROUNDED, false),
                bot(2, 1, 100, 290, GROUNDED, false),   // 16 px under
                bot(3, 1, 200, 320, GROUNDED, false),   // 46 px under
                bot(4, 1, 300, 200, AIRBORNE, true));   // mid-jump: not checked
        Map<String, Object> r = s.run(snap(0, bots, new int[]{4, 0, 0, 0, 0}, 0), m -> GROUND);
        assertEquals("FAIL", r.get("status"));
        Map<String, Object> fh = check(r, "foothold");
        assertEquals("FAIL", fh.get("status"));
        assertEquals(3, fh.get("checked"));
        assertEquals(1, fh.get("skipped"));
        assertEquals(2, fh.get("bad"));
        List<Map<String, Object>> worst = (List<Map<String, Object>>) fh.get("worst");
        assertEquals(3, worst.get(0).get("id"));
        assertEquals(46.0, worst.get(0).get("dy"));
        assertEquals("SUNK", worst.get(0).get("kind"));
        assertEquals("GROUNDED", worst.get(0).get("pose"));
        assertEquals(true, worst.get(0).get("driven"));
        assertEquals(Map.of("AIRBORNE", 1), fh.get("skippedByPose"));
        assertTrue(String.valueOf(r.get("summary")).contains("foothold FAIL"));
    }

    @Test
    void botsMidCoarseMoveAreCountedAsSkippedNotOffTheirFoothold() {
        // rc5: 42 of the 50 worst bots of the first two sweeps sat on a baked nav edge, mid analytic move.
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = List.of(
                bot(1, 1, 0, 274, GROUNDED, false),
                bot(2, 1, 100, 90, FootholdCheck.Pose.COARSE, true),   // half-way up a rope edge
                bot(3, 1, 200, 400, FootholdCheck.Pose.COARSE, true)); // a drop edge passing under a platform
        Map<String, Object> fh = check(s.run(snap(0, bots, new int[]{3, 0, 0, 0, 0}, 0), m -> GROUND), "foothold");
        assertEquals("OK", fh.get("status"), String.valueOf(fh.get("summary")));
        assertEquals(1, fh.get("checked"));
        assertEquals(2, fh.get("skipped"));
        assertEquals(Map.of("COARSE", 2), fh.get("skippedByPose"));
    }

    @Test
    void stuckIsJudgedAcrossSweeps() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults()); // 3 min
        List<HealthSweep.BotSample> bots = List.of(bot(1, 1, 50, 274, GROUNDED, true), bot(2, 1, 500, 274, GROUNDED, false));
        int[] ch = {2, 0, 0, 0, 0};
        assertEquals("OK", check(s.run(snap(0, bots, ch, 0), m -> GROUND), "stuck").get("status"));
        assertEquals("OK", check(s.run(snap(120_000, bots, ch, 0), m -> GROUND), "stuck").get("status"));
        Map<String, Object> st = check(s.run(snap(240_000, bots, ch, 0), m -> GROUND), "stuck");
        assertEquals("WARN", st.get("status"));
        assertEquals(1, st.get("stuck"));
    }

    @Test
    void clumpsWarnButFreeMarketShopsDoNot() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bots.add(bot(i, 1, 100 + i * 2, 274, GROUNDED, false));
            bots.add(new HealthSweep.BotSample(100 + i, "Shop" + i, 1, 910000001, i * 2, 274, GROUNDED, false, true, null));
        }
        Map<String, Object> c = check(s.run(snap(0, bots, new int[]{12, 0, 0, 0, 0}, 0), m -> GROUND), "clumps");
        assertEquals("WARN", c.get("status"));
        assertEquals(1, ((List<?>) c.get("clumps")).size());
    }

    @Test
    void invalidMapsAndErrorSpikesFail() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> ok = List.of(bot(1, 1, 0, 274, GROUNDED, false));
        for (int i = 0; i < 5; i++) {
            s.run(snap(i, ok, new int[]{1}, 5), m -> GROUND);
        }
        List<HealthSweep.BotSample> jailed = List.of(new HealthSweep.BotSample(9, "Jailed", 1, 300000012, 0, 0, GROUNDED,
                false, true, GameHealthSource.specialMap(300000012)));
        Map<String, Object> r = s.run(snap(9, jailed, new int[]{1}, 200), m -> GROUND);
        assertEquals("FAIL", check(r, "invalidMaps").get("status"));
        assertEquals("FAIL", check(r, "errors").get("status"));
        assertEquals(null, GameHealthSource.specialMap(HENESYS));
    }

    @Test
    void theSweepWritesLatestAndADailyHistory(@TempDir Path dir) throws Exception {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        Map<String, Object> r = s.run(snap(0, List.of(bot(1, 1, 0, 274, GROUNDED, false)), new int[]{1}, 0), m -> GROUND);
        long t = java.time.Instant.parse("2026-10-23T01:02:03Z").toEpochMilli();
        HealthSweeper.write(r, dir, t);
        HealthSweeper.write(r, dir, t + 1000);
        assertTrue(Json.isValid(Files.readString(dir.resolve("health-latest.json"))));
        List<String> lines = Files.readAllLines(dir.resolve("health").resolve("2026-10-23.jsonl"));
        assertEquals(2, lines.size());
        assertTrue(Json.isValid(lines.get(1)));
    }

    @Test
    void aMapOverItsBotCapWarnsAndListsTheWorstFirst() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = List.of(bot(1, 1, 0, 274, GROUNDED, false));
        var loads = List.of(
                new soloMapling.ArtificialPlayer.BotMapCap.Load(1, HENESYS, 54, 50),
                new soloMapling.ArtificialPlayer.BotMapCap.Load(2, 910000001, 50, 50),   // full, not over
                new soloMapling.ArtificialPlayer.BotMapCap.Load(1, 600020300, 61, 50),
                new soloMapling.ArtificialPlayer.BotMapCap.Load(3, 104040000, 80, 0));   // no cap there
        Map<String, Object> r = s.run(new HealthSweep.Snapshot(0, bots, 1, 1, new int[]{1, 0, 0, 0, 0},
                BotChannels.shares("100,85,50,30,20", 5), 0, "test.log", loads), m -> GROUND);
        Map<String, Object> cap = check(r, "mapCap");
        assertEquals("WARN", cap.get("status"));
        assertEquals("2 map instance(s) over the cap, worst map 600020300 ch1 61/50", cap.get("summary"));
        assertEquals(2, ((List<?>) cap.get("over")).size());
        assertEquals(3, cap.get("full"));
        assertEquals(80, cap.get("busiest"));
        assertTrue(String.valueOf(r.get("summary")).contains("mapCap WARN"));
        assertTrue(Json.isValid(Json.write(r)));
    }

    @Test
    void noMapOverItsCapIsOk() {
        HealthSweep s = new HealthSweep(HealthSweep.Limits.defaults());
        List<HealthSweep.BotSample> bots = List.of(bot(1, 1, 0, 274, GROUNDED, false));
        Map<String, Object> r = s.run(new HealthSweep.Snapshot(0, bots, 1, 1, new int[]{1, 0, 0, 0, 0},
                BotChannels.shares("100,85,50,30,20", 5), 0, "test.log",
                List.of(new soloMapling.ArtificialPlayer.BotMapCap.Load(1, HENESYS, 45, 50))), m -> GROUND);
        assertEquals("OK", check(r, "mapCap").get("status"));
        assertEquals("1 map instance(s) with bots, none over its cap (busiest 45)", check(r, "mapCap").get("summary"));
    }
}
