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
        assertTrue(String.valueOf(r.get("summary")).contains("foothold FAIL"));
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
}
