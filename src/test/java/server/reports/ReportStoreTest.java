package server.reports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.server.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportStoreTest {
    @TempDir Path dir;

    static class MutableClock extends Clock {
        long now = 1_700_000_000_000L;
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    static ReportStore.Context ctx(String name) {
        return ctx(name, 7);
    }

    static ReportStore.Context ctx(String name, int account) {
        return new ReportStore.Context(name, account, 1, 2, 100000000, "Henesys", 10, -20, null);
    }

    ReportStore store(MutableClock clk) { return new ReportStore(dir.resolve("r/reports.jsonl"), clk); }

    ReportStore.Result submit(ReportStore s, String type, String text, String who) throws Exception {
        return s.submit(type, text, ctx(who)).get();
    }

    ReportStore.Result submit(ReportStore s, String type, String text, String who, int account) throws Exception {
        return s.submit(type, text, ctx(who, account)).get();
    }

    @Test
    void idsIncreaseAndSurviveRestart() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore a = store(clk);
        assertEquals(1, submit(a, "bug", "first problem", "A").id());
        assertEquals("I-2", submit(a, "feature", "second thing", "A").ref());
        ReportStore b = store(clk); // "restart"
        ReportStore.Result third = submit(b, "bug", "third problem", "B");
        assertEquals(3, third.id());
        assertEquals("B-3", third.ref());
        assertEquals(3, Files.readAllLines(dir.resolve("r/reports.jsonl")).size());
    }

    @Test
    void idsFollowLinesTheSiteAppended() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore a = store(clk);
        assertEquals(1, submit(a, "bug", "first problem", "A").id());
        // the website's report service appends its own line (its format: id first, ip_hash, no source)
        Files.writeString(dir.resolve("r/reports.jsonl"), "{\"id\":41,\"time\":\"2026-10-28T10:00:00+00:00\",\"type\":\"bug\","
                + "\"title\":\"site\",\"details\":\"from the site\",\"character\":\"\",\"where\":\"\",\"ip_hash\":\"ab\"}\n",
                StandardOpenOption.APPEND);
        assertEquals(42, submit(a, "bug", "after the site", "A", 8).id());
    }

    @Test
    void lengthChecks() throws Exception {
        ReportStore s = store(new MutableClock());
        assertEquals(ReportStore.Status.TOO_SHORT, submit(s, "bug", "abcd", "A", 1).status());
        assertEquals(ReportStore.Status.TOO_SHORT, submit(s, "bug", "   ab  ", "A", 1).status());
        assertEquals(ReportStore.Status.TOO_LONG, submit(s, "bug", "x".repeat(501), "A", 1).status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "x".repeat(500), "A", 1).status());
        assertEquals(ReportStore.Status.TOO_LONG, submit(s, "feature", "x".repeat(2001), "A", 2).status());
        assertEquals(ReportStore.Status.OK, submit(s, "feature", "x".repeat(2000), "A", 2).status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "abcde", "A", 3).status());
    }

    @Test
    void threePerTenMinutesPerAccount() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore s = store(clk);
        for (int i = 0; i < 3; i++) assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem " + i, "A").status());
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem x", "OtherAltSameAccount").status());
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "other account", "B", 99).status());
        clk.now += 10 * 60_000L;
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "ten minutes later", "A").status());
        s.resetLimitsForQa(7);
        for (int i = 0; i < 3; i++) assertEquals(ReportStore.Status.OK, submit(s, "bug", "after reset " + i, "A").status());
    }

    @Test
    void twentyADayPerAccount() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore s = store(clk);
        for (int batch = 0; batch < 7; batch++) {
            for (int i = 0; i < 3 && batch * 3 + i < 20; i++) {
                assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem " + i, "A").status());
            }
            clk.now += 11 * 60_000L;
        }
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem y", "A").status());
        clk.now += 24 * 3_600_000L;
        assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem z", "A").status());
    }

    @Test
    void globalLimit() throws Exception {
        ReportStore s = store(new MutableClock());
        for (int i = 0; i < ReportStore.GLOBAL_HOUR; i++) {
            assertEquals(ReportStore.Status.OK, submit(s, "bug", "problem", "P" + i, 1000 + i).status());
        }
        assertEquals(ReportStore.Status.RATE_LIMITED, submit(s, "bug", "problem", "Other", 5000).status());
    }

    @Test
    void rejectedReportsDoNotUseAnId() throws Exception {
        ReportStore s = store(new MutableClock());
        submit(s, "bug", "ab", "A");
        assertEquals(1, submit(s, "bug", "long enough", "A").id());
    }

    @Test
    void jsonEscapingAndFields() throws Exception {
        ReportStore s = store(new MutableClock());
        String text = "He said \"hi\"\nline2\ttab \\ back \u00e9\u65e5\u672c \ud83d\ude00 \u0001";
        submit(s, "feature", text, "Na\"me");
        String line = Files.readAllLines(dir.resolve("r/reports.jsonl")).get(0);
        assertFalse(line.contains("\n"));
        assertTrue(line.startsWith("{\"id\":1,"), "id first, like the site's lines: " + line);
        assertTrue(line.contains("\"details\":\"He said \\\"hi\\\"\\nline2\\ttab \\\\ back \u00e9\u65e5\u672c \ud83d\ude00 \\u0001\""), line);
        assertTrue(line.contains("\"character\":\"Na\\\"me\""));
        assertTrue(line.contains("\"where\":\"Henesys (100000000) ch2\""));
        assertTrue(line.contains("\"source\":\"ingame\""));
        assertTrue(line.contains("\"ref\":\"I-1\""));
        assertTrue(line.contains("\"type\":\"feature\""));
        assertTrue(line.contains("\"accountId\":7"));
        assertTrue(line.contains("\"time\":\"2023-11-14T22:13:20+00:00\""), "the site's time format: " + line);
        assertTrue(line.contains("\"title\":\"He said \\\"hi\\\" line2 tab"), "title collapses whitespace: " + line);
        Map<?, ?> parsed = (Map<?, ?>) Json.parse(line);
        assertEquals(text.strip(), parsed.get("details"));
    }

    @Test
    void titleIsEightyCodePointsWithoutSplittingSurrogates() {
        String text = "\ud83d\ude00".repeat(300);
        String json = ReportStore.toJson(1, "bug", text, ctx("A"), Instant.EPOCH);
        String title = (String) ((Map<?, ?>) Json.parse(json)).get("title");
        assertEquals(80, title.codePointCount(0, title.length()));
    }

    @Test
    void writeFailureIsReported() throws Exception {
        Path blocker = dir.resolve("file");
        Files.writeString(blocker, "x");
        ReportStore s = new ReportStore(blocker.resolve("sub/reports.jsonl"), new MutableClock());
        assertEquals(ReportStore.Status.WRITE_FAILED, s.submit("bug", "problem here", ctx("A")).get().status());
    }

    @Test
    void contextGoesToItsOwnFileAndTheLineKeepsASummary() throws Exception {
        ReportStore s = store(new MutableClock());
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("schema", 1);
        full.put("versions", Map.of("server", "1.6.1", "commit", "abc123def", "world", 1, "channel", 2,
                "client", Map.of("build", 10680, "release", "1.6.1", "caps", 3)));
        full.put("character", Map.of("name", "A", "id", 5, "level", 30, "x", 1, "y", 2,
                "job", Map.of("id", 100, "name", "WARRIOR"), "map", Map.of("id", 100000000)));
        full.put("serverLog", List.of("x".repeat(5000)));
        int[] calls = {0};
        ReportStore.Result r = s.submit("bug", "the door is stuck", ctx("A"), () -> {
            calls[0]++;
            return full;
        }).get();
        assertEquals("B-1", r.ref());
        assertEquals(1, calls[0]);
        Path cf = dir.resolve("r/context/B-1.json");
        assertTrue(Files.exists(cf));
        Map<?, ?> back = (Map<?, ?>) Json.parse(Files.readString(cf));
        assertEquals("B-1", back.get("ref"));
        assertEquals("x".repeat(5000), ((List<?>) back.get("serverLog")).get(0));
        String line = Files.readAllLines(dir.resolve("r/reports.jsonl")).get(0);
        assertTrue(line.length() < 1500, "the jsonl line stays short: " + line.length());
        Map<?, ?> sum = (Map<?, ?>) ((Map<?, ?>) Json.parse(line)).get("context");
        assertEquals("context/B-1.json", sum.get("file"));
        assertEquals(10680L, ((Number) sum.get("clientBuild")).longValue());
        assertEquals("abc123def", sum.get("commit"));
        assertEquals(1L, ((Number) sum.get("logLines")).longValue());
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("r/reports.jsonl"))));
            assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(cf)));
            assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("r/context"))));
        }
    }

    @Test
    void aRateLimitedReportCapturesNothing() throws Exception {
        MutableClock clk = new MutableClock();
        ReportStore s = store(clk);
        for (int i = 0; i < 3; i++) submit(s, "bug", "problem " + i, "A");
        int[] calls = {0};
        assertEquals(ReportStore.Status.RATE_LIMITED, s.submit("bug", "one more", ctx("A"), () -> {
            calls[0]++;
            return Map.of();
        }).get().status());
        assertEquals(0, calls[0]);
    }

    @Test
    void readBackFindUpdateAndQaRemove() throws Exception {
        ReportStore s = store(new MutableClock());
        s.submit("bug", "first bug here", ctx("A", 1), () -> new LinkedHashMap<>(Map.of("schema", 1))).get();
        Files.writeString(dir.resolve("r/reports.jsonl"), "{\"id\":2,\"time\":\"2026-10-28T10:00:00+00:00\",\"type\":\"bug\","
                + "\"title\":\"site\",\"details\":\"site report B-1\",\"character\":\"\",\"where\":\"\",\"ip_hash\":\"ab\"}\n",
                StandardOpenOption.APPEND);
        s.submit("feature", "an idea to try", ctx("B", 2)).get();
        List<Map<String, Object>> recent = s.recentInGame(10);
        assertEquals(List.of("I-3", "B-1"), recent.stream().map(m -> m.get("ref")).toList(), "newest first, site lines left out");
        assertEquals("first bug here", s.find("b1").get("details"));
        assertEquals("first bug here", s.find("#B-1").get("details"));
        assertEquals("first bug here", s.find("1").get("details"));
        assertNull(s.find("B-2"), "the site's line is not an in-game report");
        assertNull(s.find("nonsense"));

        assertTrue(s.updateContext("B-1", c -> {
            c.put("clientLogs", Map.of("bytes", 3));
            return c;
        }).get());
        assertEquals(Map.of("bytes", 3L), s.readContext("B-1").get("clientLogs"));
        assertFalse(s.updateContext("B-9", c -> c).get(), "no context file, nothing written");

        assertEquals(1, s.removeForQa("B-1").get());
        assertNull(s.find("B-1"));
        assertNull(s.readContext("B-1"));
        List<String> left = Files.readAllLines(dir.resolve("r/reports.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, left.size());
        assertTrue(left.get(0).contains("site report B-1"), "the site's line that merely mentions the ref stays");
        assertEquals(0, s.removeForQa("B-1").get());
        // ids keep counting past a removed line
        assertEquals(4, submit(s, "bug", "after the cleanup", "C", 3).id());
    }

    @Test
    void refs() {
        assertEquals("B-12", ReportStore.normalizeRef("B-12"));
        assertEquals("B-12", ReportStore.normalizeRef("#b12"));
        assertEquals("I-7", ReportStore.normalizeRef("i-7"));
        assertEquals("B-5", ReportStore.normalizeRef("5"));
        assertNull(ReportStore.normalizeRef("door"));
        assertNull(ReportStore.normalizeRef("B-"));
        assertEquals("B-3", ReportStore.refFor("bug", 3));
        assertEquals("I-3", ReportStore.refFor("feature", 3));
    }

    @Test
    void lineMatchesTheSiteFormatWithVersions() throws Exception {
        ReportStore s = store(new MutableClock());
        ReportStore.Context c = new ReportStore.Context("Tester", 7, 1, 2, 100000000, "Henesys", 10, -20, "10680 (1.6.1)",
                ReportStore.versions("1.6.1", "5e21fd275", "1.6.1", 10680));
        s.submit("bug", "the door is stuck", c).get();
        String line = Files.readAllLines(dir.resolve("r/reports.jsonl")).get(0);
        Map<?, ?> m = (Map<?, ?>) Json.parse(line);
        // the site's fields first, in its order (report_service.py: id, time, type, title, details, character, where,
        // source, versions), then the in-game extras
        assertEquals(List.of("id", "time", "type", "title", "details", "character", "where", "source", "versions", "ref"),
                new java.util.ArrayList<>(m.keySet()).subList(0, 10));
        assertEquals(Map.of("server", "1.6.1", "server_commit", "5e21fd275", "client", "1.6.1", "clientBuild", 10680L),
                m.get("versions"));
        assertEquals("ingame", m.get("source"));
        assertFalse(m.containsKey("ip_hash"), "no address of any kind");
        assertTrue(((String) m.get("time")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00"));
        assertEquals(1, Files.readAllLines(dir.resolve("r/reports.jsonl")).size(), "one report, one line, nowhere else");
        assertFalse(Files.exists(dir.resolve("r/context")), "no context: no attachment file either");
    }

    @Test
    void versionsLeaveOutWhatIsUnknownOrOdd() {
        assertEquals(Map.of("server", "1.6.1"), ReportStore.versions("1.6.1", null, "", 0));
        assertEquals(Map.of("server", "1.6.1", "clientBuild", 10600), ReportStore.versions("1.6.1", "not a sha!", "1.6 beta", 10600));
    }
}
