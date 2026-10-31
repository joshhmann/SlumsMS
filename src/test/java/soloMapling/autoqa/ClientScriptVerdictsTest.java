package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 166 / b222: a client script's verdict lands under its t-id in the report, and a release run counts it - a
 * fresh script PASS stands in for the SKIP a MANUAL check always gets, so an id can leave the allow-list; a fresh
 * script FAIL fails the release; anything else (no verdict, another build, a review still owed) is still a SKIP.
 */
class ClientScriptVerdictsTest {

    static final String MANUAL = "t-cl-1"; // on the allow-list, MANUAL
    static final String AUTO = "t-af-1";   // a headless check

    static Map<String, Object> record(String id, String status, Object fresh, String commit) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("folder", "/root/work/qa-runs/2026-10-31/" + id + "-20261031T031205Z");
        run.put("run_id", id + "-20261031T031205Z");
        run.put("result", "PASS".equals(status) ? "PASS" : "FAIL");
        run.put("started_utc", "2026-10-31T03:12:05.120Z");
        run.put("finished_utc", "2026-10-31T03:14:40.900Z");
        run.put("trigger", "manual by a test");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("format", 1);
        r.put("id", id);
        r.put("status", status);
        r.put("reason", "status " + status);
        r.put("fresh", fresh);
        r.put("commit", commit);
        r.put("run", run);
        r.put("script", Map.of("id", "a-script", "sha256", "abc", "status", "draft"));
        r.put("review", null);
        r.put("evidence", List.of(String.valueOf(run.get("folder")), "frames/arrive/", "server/journal.txt"));
        return r;
    }

    static Path write(Path dataDir, Map<String, Object> record) throws IOException {
        Path dir = dataDir.resolve(ClientScriptVerdicts.DIR);
        Files.createDirectories(dir);
        Path f = dir.resolve(record.get("id") + ".json");
        Files.writeString(f, soloMapling.server.Json.write(record));
        return f;
    }

    @SafeVarargs
    static Map<String, Map<String, Object>> verdicts(Map<String, Object>... records) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : records) {
            out.put(String.valueOf(r.get("id")), r);
        }
        return out;
    }

    // -- the release verdict --

    @Test
    void aFreshScriptPassCarriesAManualCheckAndAFreshScriptFailFailsTheRun() {
        AutoQaRelease.Verdict ok = AutoQaRelease.judge(AutoQaCatalog.all(), AutoQaReleaseTest.fullRun(Map.of()),
                AutoQaRelease.allowList(), verdicts(record(MANUAL, "PASS", true, "5e5c0ab1d")));
        assertTrue(ok.pass(), ok.line());
        assertFalse(ok.allowedSkips().contains(MANUAL), "the script proved it, so it is not an allowed skip");

        AutoQaRelease.Verdict bad = AutoQaRelease.judge(AutoQaCatalog.all(), AutoQaReleaseTest.fullRun(Map.of()),
                AutoQaRelease.allowList(), verdicts(record(MANUAL, "FAIL", true, "5e5c0ab1d")));
        assertFalse(bad.pass(), bad.line());
        assertEquals(List.of(MANUAL), bad.fails());
        assertEquals("release: FAIL (FAIL: " + MANUAL + ")", bad.line());
    }

    @Test
    void anOldOrUnjudgedVerdictIsStillASkip() {
        AutoQaRelease.Verdict old = AutoQaRelease.judge(AutoQaCatalog.all(), AutoQaReleaseTest.fullRun(Map.of()),
                AutoQaRelease.allowList(), verdicts(record(MANUAL, "PASS", false, "5e5c0ab1d")));
        assertTrue(old.pass(), "an allowed skip stays allowed: " + old.line());
        assertTrue(old.allowedSkips().contains(MANUAL));

        AutoQaRelease.Verdict unasked = AutoQaRelease.judge(AutoQaCatalog.all(), AutoQaReleaseTest.fullRun(Map.of()),
                AutoQaRelease.allowList(), verdicts(record(MANUAL, "SKIP", null, "5e5c0ab1d")));
        assertTrue(unasked.pass(), unasked.line());
        assertTrue(unasked.allowedSkips().contains(MANUAL));
    }

    @Test
    void anOldScriptPassDoesNotCoverAnIdOffTheAllowList() {
        AutoQaRelease.Verdict v = AutoQaRelease.judge(AutoQaCatalog.all(), AutoQaReleaseTest.fullRun(Map.of()),
                Map.of(), verdicts(record(MANUAL, "PASS", false, "5e5c0ab1d")));
        assertFalse(v.pass(), "only fresh evidence ships: " + v.line());
        assertTrue(v.unexpectedSkips().contains(MANUAL), v.line());
    }

    // -- the report entry --

    @Test
    void applyPutsTheVerdictUnderItsIdWithTheRunFolderAsEvidence(@TempDir Path dataDir) throws IOException {
        write(dataDir, record(MANUAL, "PASS", true, "5e5c0ab1d"));
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        assertEquals(1, ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa"));
        Map<?, ?> e = entry(report, MANUAL);
        assertEquals("PASS", e.get("status"));
        assertEquals("MANUAL", e.get("kind"));
        assertEquals("client-script", e.get("mode"));
        assertEquals("client-script", e.get("source"));
        assertEquals(true, e.get("fresh"));
        assertEquals("5e5c0ab1d", e.get("commit"));
        assertEquals("2026-10-31T03:12:05.120Z", e.get("started"));
        assertEquals(155L, e.get("seconds"));
        assertEquals("a-script", ((Map<?, ?>) e.get("script")).get("id"));
        List<?> ev = (List<?>) e.get("evidence");
        assertEquals("/root/work/qa-runs/2026-10-31/" + MANUAL + "-20261031T031205Z", ev.get(0));
        assertTrue(ev.contains("frames/arrive/"));
        assertEquals(1, ((Map<?, ?>) report.get("summary")).get("PASS"));
    }

    @Test
    void aPassFromAnotherBuildShowsAsOldAndIsNotCountedAsPass(@TempDir Path dataDir) throws IOException {
        write(dataDir, record(MANUAL, "PASS", false, "5e5c0ab1d"));
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa");
        Map<?, ?> e = entry(report, MANUAL);
        assertTrue(String.valueOf(e.get("status")).startsWith("PASS(old, "), String.valueOf(e.get("status")));
        assertEquals(false, e.get("fresh"));
        assertEquals(1, ((Map<?, ?>) report.get("summary")).get("OLD"));
    }

    @Test
    void aFailedScriptShowsAsFail(@TempDir Path dataDir) throws IOException {
        write(dataDir, record(MANUAL, "FAIL", true, "5e5c0ab1d"));
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa");
        assertEquals("FAIL", entry(report, MANUAL).get("status"));
        assertEquals(1, ((Map<?, ?>) report.get("summary")).get("FAIL"));
    }

    @Test
    void aResultTheRunnerMeasuredStandsOverAScript(@TempDir Path dataDir) throws IOException {
        write(dataDir, record(AUTO, "PASS", true, "5e5c0ab1d"));
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        Map<String, Object> record = record(AUTO, "PASS", true, "5e5c0ab1d");
        ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa");
        assertEquals("client-script", entry(report, AUTO).get("mode"), "a NOT_RUN row may be filled");

        @SuppressWarnings("unchecked")
        Map<String, Object> checks = (Map<String, Object>) report.get("checks");
        Map<String, Object> ran = new LinkedHashMap<>();
        ran.put("status", "FAIL");
        ran.put("mode", "normal");
        checks.put(AUTO, ran);
        ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa");
        assertEquals("FAIL", entry(report, AUTO).get("status"));
        assertEquals("normal", entry(report, AUTO).get("mode"));
        assertNull(ClientScriptVerdicts.releaseState(null));
        assertTrue(record.containsKey("id"));
    }

    @Test
    void unknownIdsBrokenFilesAndOversizedFilesAreIgnored(@TempDir Path dataDir) throws IOException {
        Path dir = dataDir.resolve(ClientScriptVerdicts.DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("t-zz-9.json"), soloMapling.server.Json.write(record("t-zz-9", "PASS", true, "x")));
        Files.writeString(dir.resolve(MANUAL + ".json"), "{not json");
        Files.writeString(dir.resolve(AUTO + ".json"), "x".repeat(ClientScriptVerdicts.MAX_BYTES + 1));
        assertTrue(ClientScriptVerdicts.load(dataDir, AutoQaCatalog.all()).isEmpty());
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        assertEquals(0, ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa"));
    }

    @Test
    void aFileNamingAnotherIdInsideIsIgnored(@TempDir Path dataDir) throws IOException {
        Files.createDirectories(dataDir.resolve(ClientScriptVerdicts.DIR));
        Files.writeString(dataDir.resolve(ClientScriptVerdicts.DIR).resolve(MANUAL + ".json"),
                soloMapling.server.Json.write(record(AUTO, "PASS", true, "5e5c0ab1d")));
        assertTrue(ClientScriptVerdicts.load(dataDir, AutoQaCatalog.all()).isEmpty());
    }

    @Test
    void noVerdictsDirectoryIsNotAProblem(@TempDir Path dataDir) {
        assertTrue(ClientScriptVerdicts.load(dataDir, AutoQaCatalog.all()).isEmpty());
        Map<String, Object> report = AutoQaReport.empty("1.6.2-qa", "2026-10-31", AutoQaCatalog.all());
        assertEquals(0, ClientScriptVerdicts.apply(report, dataDir, AutoQaCatalog.all(), "1.6.2-qa"));
    }

    /**
     * The wire contract with {@code tools/qa-runner/verdict}: this fixture was written by that tool (a real run folder
     * from {@code qarunner}, judged on 187), so a rename on either side fails here instead of on TEST.
     */
    @Test
    void theRecordTheVerdictToolWritesIsImported(@TempDir Path dataDir) throws IOException {
        Path dir = dataDir.resolve(ClientScriptVerdicts.DIR);
        Files.createDirectories(dir);
        try (InputStream in = getClass().getResourceAsStream("client-verdict-t-cl-1.json")) {
            assertNotNull(in, "the fixture is on the test classpath");
            Files.copy(in, dir.resolve(MANUAL + ".json"));
        }
        Map<String, Map<String, Object>> loaded = ClientScriptVerdicts.load(dataDir, AutoQaCatalog.all());
        assertEquals(List.of(MANUAL), List.copyOf(loaded.keySet()));
        Map<?, ?> e = ClientScriptVerdicts.entry(loaded.get(MANUAL), AutoQaCatalog.find(MANUAL), "1.6.2-qa");
        assertEquals("PASS", e.get("status"));
        assertEquals(true, e.get("fresh"));
        assertEquals("5e5c0ab1d", e.get("commit"));
        assertEquals("b140-bot-heal-column", ((Map<?, ?>) e.get("script")).get("id"));
        assertEquals("both Heal columns drew over the Bishop and the HP bar rose",
                ((Map<?, ?>) e.get("review")).get("summary"));
        assertEquals("2 frame item(s) seen and the server checks passed", e.get("reason"));
        assertEquals(0L, e.get("seconds"));
        assertTrue(String.valueOf(((List<?>) e.get("evidence")).get(0)).endsWith("/b140-bot-heal-column-20261031T031205Z"));
        assertEquals("PASS", ClientScriptVerdicts.releaseState(loaded.get(MANUAL)));
    }

    @SuppressWarnings("unchecked")
    static Map<?, ?> entry(Map<String, Object> report, String id) {
        return (Map<?, ?>) ((Map<String, Object>) report.get("checks")).get(id);
    }
}
