package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoQaReportTest {

    static AutoQaRunner.CheckResult result(String id, Check.Status st, String reason) {
        return new AutoQaRunner.CheckResult(id, "title " + id, Check.Kind.AUTO, st, reason, List.of("ok: line"),
                1_760_000_000_000L, 1_760_000_042_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFreshReportListsEveryPageCheckKeyedById() {
        Map<String, Object> r = AutoQaReport.empty("1.5.0-rc5", "2026-10-23", AutoQaCatalog.all());
        Map<String, Object> checks = (Map<String, Object>) r.get("checks");
        assertEquals(AutoQaCatalog.all().size(), checks.size());
        Map<String, Object> cl1 = (Map<String, Object>) checks.get("t-cl-1");
        assertEquals("SKIP", cl1.get("status"));
        assertTrue(String.valueOf(cl1.get("reason")).contains("eyes"));
        assertEquals("NOT_RUN", ((Map<String, Object>) checks.get("t-af-1")).get("status"));
        Map<String, Object> s = (Map<String, Object>) r.get("summary");
        assertEquals(checks.size(), s.get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsMergeIntoTheDayAndALaterRunReplacesACheck(@TempDir Path dir) throws Exception {
        Map<String, Object> r = AutoQaReport.empty("1.5.0-rc5", "2026-10-23", AutoQaCatalog.all());
        AutoQaReport.merge(r, "1.5.0-rc5", "nightly by the schedule", 1, 2,
                List.of(result("t-af-1", Check.Status.FAIL, "no greet"), result("t-rv-1", Check.Status.PASS, null)),
                Map.of("status", "WARN", "summary", "clumps WARN", "time", "2026-10-23T10:00:00Z"));
        AutoQaReport.merge(r, "1.5.0-rc6", "!autoqa run af-1 by Owner", 3, 4,
                List.of(result("t-af-1", Check.Status.PASS, null)), null);
        Map<String, Object> af1 = (Map<String, Object>) ((Map<String, Object>) r.get("checks")).get("t-af-1");
        assertEquals("PASS", af1.get("status"));
        assertEquals("1.5.0-rc6", af1.get("build"));
        assertEquals(42L, ((Number) af1.get("seconds")).longValue());
        assertEquals(2, ((List<?>) r.get("runs")).size());
        assertEquals("WARN", ((Map<String, Object>) r.get("health")).get("status"));
        Map<String, Object> s = (Map<String, Object>) r.get("summary");
        assertEquals(2, s.get("PASS"));
        assertEquals(0, s.get("FAIL"));

        AutoQaReport.write(dir, "2026-10-23", r);
        String json = Files.readString(dir.resolve("latest.json"));
        assertTrue(Json.isValid(json));
        assertEquals(json, Files.readString(dir.resolve("report-2026-10-23.json")));
        Map<String, Object> back = AutoQaReport.read(dir.resolve("latest.json"));
        assertEquals("PASS", ((Map<String, Object>) ((Map<String, Object>) back.get("checks")).get("t-rv-1")).get("status"));
        // a read-back report takes another run (lists from the parser may be immutable)
        AutoQaReport.merge(back, "1.5.0-rc6", "again", 5, 6, List.of(result("t-rv-1", Check.Status.FAIL, "x")), null);
        assertEquals(3, ((List<?>) back.get("runs")).size());
    }

    @Test
    void theReportCarriesGroundingAsSentChatQualityAndSayDo() {
        Map<String, Object> r = AutoQaReport.empty("1.5.0-rc6", "2026-10-23", AutoQaCatalog.all());
        Map<String, Object> health = Map.of("status", "FAIL", "summary", "foothold (sent) FAIL: 1 of 3", "time", "x",
                "checks", Map.of("foothold", Map.of("status", "OK", "summary", "40 grounded bots on their footholds"),
                        HealthSweep.SENT_CHECK, Map.of("status", "FAIL", "summary", "1 of 3 sent positions off")));
        AutoQaReport.putInference(r, Map.of("headline", "chat 24h: 129 lines; relevance 82% (60 judged)",
                "problems", List.of("stiffness 31% > 25%")), Map.of("claims", 12L, "backed", 7L));
        AutoQaReport.merge(r, "1.5.0-rc6", "t", 1, 2, List.of(), health);
        Map<?, ?> h = (Map<?, ?>) r.get("health");
        assertEquals("FAIL: 1 of 3 sent positions off", h.get(HealthSweep.SENT_CHECK));
        List<String> lines = AutoQaReport.summaryLines(r, 25);
        assertTrue(lines.contains("Grounding: server OK: 40 grounded bots on their footholds | sent FAIL: 1 of 3 sent positions off"),
                lines.toString());
        assertTrue(lines.contains("Chat: chat 24h: 129 lines; relevance 82% (60 judged) PROBLEMS [stiffness 31% > 25%]"),
                lines.toString());
        assertTrue(lines.contains("Say-do since boot: 7 of 12 claims backed"), lines.toString());
    }

    @Test
    void theReportCommandShowsTotalsHealthAndFailures() {
        Map<String, Object> r = AutoQaReport.empty("1.5.0-rc5", "2026-10-23", AutoQaCatalog.all());
        AutoQaReport.merge(r, "1.5.0-rc5", "t", 1, 2, List.of(result("t-af-1", Check.Status.FAIL, "no greet")),
                Map.of("status", "FAIL", "summary", "foothold FAIL: 3 sunk", "time", "x"));
        List<String> lines = AutoQaReport.summaryLines(r, 25);
        assertTrue(lines.get(0).startsWith("Auto-QA 2026-10-23 (1.5.0-rc5): 0 pass, 1 fail"), lines.get(0));
        assertEquals("Health FAIL: foothold FAIL: 3 sunk", lines.get(1));
        assertTrue(lines.get(2).startsWith("Last run: t at "), lines.get(2)); // b222: the run's own summary line
        assertEquals("FAIL t-af-1 title t-af-1: no greet", lines.get(3));
        assertTrue(AutoQaReport.summaryLines(null, 5).get(0).startsWith("No auto-QA report yet"));
        assertTrue(AutoQaReport.summaryLines(r, 4).size() <= 5);
    }

    @Test
    void anUnreadableReportIsNull(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("latest.json"), "{nope");
        assertNull(AutoQaReport.read(dir.resolve("latest.json")));
        assertNull(AutoQaReport.read(dir.resolve("missing.json")));
    }
}
