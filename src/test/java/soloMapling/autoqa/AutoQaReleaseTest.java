package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b222 (owner 2026-10-30): a release run FAILs on any SKIP off the allow-list (and on any FAIL); every run lists its
 * skips with reasons; carried rows read PASS(old, ...) and never count as fresh; every result cites commit, log, time.
 */
class AutoQaReleaseTest {

    /** The owner's initial allow-list: the client-only checks. */
    static final List<String> CLIENT_ONLY = List.of("t-ga-4", "t-rg-1", "t-rg-2", "t-mv-1", "t-jq-1", "t-cl-1", "t-cl-2",
            "t-cl-3", "t-cc-1", "t-mr-2", "t-la-1");

    static AutoQaRunner.CheckResult result(String id, Check.Status st, String reason, List<String> logs) {
        return new AutoQaRunner.CheckResult(id, "title " + id, Check.Kind.AUTO, st, reason, List.of("ok: line"),
                1_760_000_000_000L, 1_760_000_042_000L, logs);
    }

    /** A whole-catalogue run: MANUAL checks SKIP with their reason, the rest PASS, with overrides. */
    static List<AutoQaRunner.CheckResult> fullRun(Map<String, AutoQaRunner.CheckResult> overrides) {
        List<AutoQaRunner.CheckResult> out = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            AutoQaRunner.CheckResult r = overrides.get(c.id());
            if (r == null) {
                r = c.kind() == Check.Kind.MANUAL ? result(c.id(), Check.Status.SKIP, c.why(), List.of())
                        : result(c.id(), Check.Status.PASS, null, List.of());
            }
            out.add(r);
        }
        return out;
    }

    @Test
    void theAllowListIsTheOwnersClientOnlyChecksAndEachIsManualInTheCatalog() {
        Map<String, String> allow = AutoQaRelease.allowList();
        assertEquals(CLIENT_ONLY.stream().sorted().toList(), allow.keySet().stream().sorted().toList());
        for (String id : allow.keySet()) {
            Check c = AutoQaCatalog.find(id);
            assertNotNull(c, id + " is in the catalog");
            assertEquals(Check.Kind.MANUAL, c.kind(), id + " is client-only (MANUAL), so it can only SKIP");
            assertFalse(allow.get(id).isBlank(), id + " says why");
        }
        assertThrows(IllegalArgumentException.class, () -> AutoQaRelease.parse(List.of("t-xx-1")),
                "an allow-list line must say why");
    }

    @Test
    void aReleaseRunWithOnlyAllowedSkipsPasses() {
        AutoQaRelease.Verdict v = AutoQaRelease.judge(AutoQaCatalog.all(), fullRun(Map.of()), AutoQaRelease.allowList());
        assertTrue(v.pass(), v.line());
        assertEquals("release: PASS", v.line());
        assertEquals(CLIENT_ONLY.size(), v.allowedSkips().size());
    }

    @Test
    void anUnexpectedSkipFailsTheReleaseRunAndIsNamed() {
        Map<String, AutoQaRunner.CheckResult> o = new LinkedHashMap<>();
        o.put("t-mx-1", result("t-mx-1", Check.Status.SKIP, "!qa run mx-1 refused: needs -Dslums.mechanics=true", List.of()));
        o.put("t-bq-1", result("t-bq-1", Check.Status.SKIP, "nothing could be judged here", List.of()));
        AutoQaRelease.Verdict v = AutoQaRelease.judge(AutoQaCatalog.all(), fullRun(o), AutoQaRelease.allowList());
        assertFalse(v.pass());
        assertEquals("release: FAIL (unexpected SKIP: t-mx-1, t-bq-1)", v.line()); // catalogue order
        assertEquals(List.of("t-mx-1", "t-bq-1"), v.unexpectedSkips());
    }

    @Test
    void aFailOrAMissingCheckFailsTheReleaseRunToo() {
        List<AutoQaRunner.CheckResult> run = fullRun(Map.of("t-af-1", result("t-af-1", Check.Status.FAIL, "no greet",
                List.of())));
        run.removeIf(r -> r.id().equals("t-af-2"));
        AutoQaRelease.Verdict v = AutoQaRelease.judge(AutoQaCatalog.all(), run, AutoQaRelease.allowList());
        assertFalse(v.pass());
        assertEquals("release: FAIL (FAIL: t-af-1; not run: t-af-2)", v.line());
    }

    @Test
    void aReleaseRequestIsReadFromEitherSpellingAndRefusesShortcuts() {
        assertTrue(AutoQaService.parseRequest("all --release").release());
        assertTrue(AutoQaService.parseRequest("all release").release());
        assertEquals("all", AutoQaService.parseRequest("--release").selector());
        assertFalse(AutoQaService.parseRequest("all").release());
        assertNull(AutoQaRelease.refusal("all", false, false));
        assertNotNull(AutoQaRelease.refusal("all", false, true), "--failed carries old passes");
        assertNotNull(AutoQaRelease.refusal("all", true, false), "--fast shortens the windows");
        assertNotNull(AutoQaRelease.refusal("af", false, false), "a release run covers everything");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRunListsWhatItSkippedWithReasonsAndAReleaseRunItsVerdict() {
        Map<String, Object> r = AutoQaReport.empty("1.6.2", "2026-10-30", AutoQaCatalog.all());
        Map<String, AutoQaRunner.CheckResult> o = Map.of("t-mx-1",
                result("t-mx-1", Check.Status.SKIP, "!qa run mx-1 refused: needs -Dslums.mechanics=true", List.of()));
        List<AutoQaRunner.CheckResult> results = fullRun(o);
        AutoQaRelease.Verdict v = AutoQaRelease.judge(AutoQaCatalog.all(), results, AutoQaRelease.allowList());
        AutoQaReport.merge(r, "1.6.2", new AutoQaReport.Run("!autoqa run all release by a run request", 1, 2, 1.0,
                "b32a14214abc", results, Map.of(), null, v), null);
        Map<String, Object> run = AutoQaReport.lastRun(r);
        Map<String, Object> skipped = (Map<String, Object>) run.get("skipped");
        assertEquals(CLIENT_ONLY.size() + 1, skipped.size());
        assertEquals("!qa run mx-1 refused: needs -Dslums.mechanics=true", skipped.get("t-mx-1"));
        assertEquals("client art: eyes only", skipped.get("t-cl-1"));
        Map<String, Object> rel = (Map<String, Object>) run.get("release");
        assertEquals("FAIL", rel.get("verdict"));
        assertEquals("release: FAIL (unexpected SKIP: t-mx-1)", rel.get("line"));
        String summary = String.valueOf(run.get("summary"));
        assertTrue(summary.contains("skipped: ") && summary.contains("t-mx-1 (!qa run mx-1 refused")
                && summary.contains("t-cl-1 (client art: eyes only)"), summary);
        assertTrue(summary.endsWith("; release: FAIL (unexpected SKIP: t-mx-1)"), summary);
        List<String> lines = AutoQaReport.summaryLines(r, 40);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("Last run: ") && l.contains("release: FAIL")), lines.toString());

        // a nightly is no release run, but its skips are listed all the same
        Map<String, Object> n = AutoQaReport.empty("1.6.2", "2026-10-30", AutoQaCatalog.all());
        AutoQaReport.merge(n, "1.6.2", new AutoQaReport.Run("nightly by the schedule", 1, 2, 1.0, "abc", results,
                Map.of(), null), null);
        Map<String, Object> nightly = AutoQaReport.lastRun(n);
        assertTrue(((Map<String, Object>) nightly.get("skipped")).containsKey("t-mx-1"));
        assertNull(nightly.get("release"));
        assertFalse(String.valueOf(nightly.get("summary")).contains("release:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyResultCitesTheDeployedCommitItsLogAndTheTime() {
        Map<String, Object> r = AutoQaReport.empty("1.6.2", "2026-10-30", AutoQaCatalog.all());
        AutoQaReport.merge(r, "1.6.2", new AutoQaReport.Run("t", 1, 2, 1.0, "b32a14214abc",
                List.of(result("t-af-1", Check.Status.PASS, null, List.of("logs/qa/af-1-20261030T190258Z.log"))),
                Map.of(), null), null);
        Map<String, Object> e = (Map<String, Object>) ((Map<String, Object>) r.get("checks")).get("t-af-1");
        assertEquals("b32a14214abc", e.get("commit"));
        assertEquals(List.of("logs/qa/af-1-20261030T190258Z.log"), e.get("logs"));
        assertEquals("2025-10-09T08:53:20Z", e.get("started"));
        assertNotNull(e.get("finished"));
        assertEquals(Boolean.TRUE, e.get("fresh"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCarriedRowReadsOldCountsAsOldAndCanBeCarriedAgain() {
        Map<String, Object> r = AutoQaReport.empty("1.6.2", "2026-10-30", AutoQaCatalog.all());
        AutoQaReport.merge(r, "1.6.2", new AutoQaReport.Run("!autoqa run all", 1_760_000_000_000L, 1_760_000_100_000L,
                1.0, "b32a14214abc", List.of(result("t-af-1", Check.Status.PASS, null, List.of()),
                result("t-af-2", Check.Status.FAIL, "x", List.of())), Map.of(), null), null);
        Map<String, Object> checks = (Map<String, Object>) r.get("checks");
        Map<String, Object> af1 = new LinkedHashMap<>((Map<String, Object>) checks.get("t-af-1"));
        // --failed: t-af-2 reruns and passes, t-af-1 is carried
        AutoQaReport.merge(r, "1.6.2", new AutoQaReport.Run("!autoqa run all --failed", 1_760_000_200_000L,
                1_760_000_300_000L, 1.0, "b32a14214abc", List.of(result("t-af-2", Check.Status.PASS, null, List.of())),
                Map.of("t-af-1", af1), "2025-10-09T08:53:20Z"), null);
        Map<String, Object> carried = (Map<String, Object>) checks.get("t-af-1");
        assertEquals("PASS(old, 2025-10-09T08:53:20Z, b32a14214)", carried.get("status"));
        assertEquals(Boolean.FALSE, carried.get("fresh"));
        assertEquals("PASS", ((Map<String, Object>) carried.get("carriedFrom")).get("status"));
        Map<String, Object> s = (Map<String, Object>) r.get("summary");
        assertEquals(1, s.get("OLD"));
        assertEquals(1, s.get("PASS"), "only t-af-2 is a fresh pass");
        List<String> lines = AutoQaReport.summaryLines(r, 40);
        assertTrue(lines.get(0).contains(", 1 old (carried, not rerun)"), lines.get(0));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("OLD (carried, not rerun): t-af-1 PASS(old, ")),
                lines.toString());
        AutoQaReport.Snapshot snap = AutoQaReport.snapshot(r);
        assertEquals(1, snap.count("OLD"));
        assertEquals(1, snap.count("PASS"));
        // a second --failed carries it again, still pointing at the run that really ran it
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), r, 1.0, "b32a14214abc");
        assertTrue(plan.carried().containsKey("t-af-1"), "an old pass is carried, not rerun");
        assertTrue(plan.carried().containsKey("t-af-2"));
        AutoQaReport.carry(r, plan.carried(), plan.sourceRun(), "normal", "b32a14214abc");
        assertEquals("PASS(old, 2025-10-09T08:53:20Z, b32a14214)",
                ((Map<String, Object>) checks.get("t-af-1")).get("status"));
    }
}
