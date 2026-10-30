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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --failed}: a carried PASS keeps its own identity and is never read as a fresh one, and the guard refuses to
 * carry across a redeploy or a fast/normal switch.
 */
class AutoQaRerunTest {

    private static final String COMMIT = "1c1e5dd9c0ffee";

    private static Map<String, Object> run(String mode, String commit, Map<String, Object> results) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("trigger", "!autoqa run all --fast");
        run.put("started", "2026-10-24T10:00:00Z");
        run.put("finished", "2026-10-24T10:20:00Z");
        run.put("mode", mode);
        run.put("timeScale", "fast".equals(mode) ? "0.4x" : "1.0x");
        if (commit != null) {
            run.put("commit", commit);
        }
        run.put("results", results);
        return run;
    }

    private static Map<String, Object> report(Map<String, Object> run) {
        Map<String, Object> passed = new LinkedHashMap<>();
        passed.put("status", "PASS");
        passed.put("title", "passed earlier");
        passed.put("kind", "AUTO");
        passed.put("build", "1.5.0");
        passed.put("mode", "fast");
        passed.put("started", "2026-10-24T10:01:00Z");
        passed.put("finished", "2026-10-24T10:02:00Z");
        passed.put("seconds", 60);
        passed.put("evidence", new ArrayList<>(List.of("ok: earlier")));
        Map<String, Object> checks = new LinkedHashMap<>();
        for (Check c : AutoQaCatalog.all()) {
            if (c.kind() != Check.Kind.MANUAL) {
                checks.put(c.id(), new LinkedHashMap<>(passed));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checks", checks);
        report.put("runs", new ArrayList<>(List.of(run)));
        return report;
    }

    private static Map<String, Object> results(String af1, String bg1) {
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("t-af-1", af1);
        results.put("t-bg-1", bg1);
        return results;
    }

    private static List<String> ids(List<Check> checks) {
        List<String> out = new ArrayList<>();
        for (Check c : checks) {
            out.add(c.id());
        }
        return out;
    }

    @Test
    void carriesThePassesAndRerunsTheRest() {
        Map<String, Object> report = report(run("fast", COMMIT, results("PASS", "FAIL")));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertNull(plan.refused());
        // every check that did not run in that run is rerun too; only the recorded PASS is carried
        assertFalse(ids(plan.checks()).contains("t-af-1"));
        assertTrue(ids(plan.checks()).contains("t-bg-1"));
        assertTrue(plan.carried().containsKey("t-af-1"));
        assertEquals("2026-10-24T10:00:00Z", plan.sourceRun());
        assertEquals("PASS", plan.carried().get("t-af-1").get("status"));
        assertEquals("2026-10-24T10:02:00Z", plan.carried().get("t-af-1").get("finished"));
    }

    @Test
    void aCheckThatNeverRanIsRerun() {
        Map<String, Object> report = report(run("fast", COMMIT, results("PASS", "FAIL")));
        Map<String, Object> results = new LinkedHashMap<>(results("PASS", "FAIL"));
        results.remove("t-bg-1");
        report.put("runs", new ArrayList<>(List.of(run("fast", COMMIT, results))));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertTrue(ids(plan.checks()).contains("t-bg-1"));
        assertFalse(ids(plan.checks()).contains("t-af-1"));
    }

    @Test
    void aRedeployRefusesToCarryAnything() {
        Map<String, Object> report = report(run("fast", "0ldc0mmit1234", results("PASS", "PASS")));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertNotNull(plan.refused());
        assertTrue(plan.refused().contains("redeployed"), plan.refused());
        assertTrue(plan.carried().isEmpty());
        assertTrue(plan.checks().size() > 2, "everything runs");
    }

    @Test
    void aModeSwitchRefusesToCarryAnything() {
        Map<String, Object> report = report(run("normal", COMMIT, results("PASS", "PASS")));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertNotNull(plan.refused());
        assertTrue(plan.refused().contains("fast") && plan.refused().contains("normal"), plan.refused());
        assertTrue(plan.carried().isEmpty());
    }

    @Test
    void anUnknownDeployedCommitRefusesToCarry() {
        Map<String, Object> report = report(run("fast", COMMIT, results("PASS", "PASS")));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, null);
        assertNotNull(plan.refused());
        assertTrue(plan.carried().isEmpty());
    }

    @Test
    void anEarlierRunWithoutResultsRefusesToCarry() {
        Map<String, Object> run = run("fast", COMMIT, results("PASS", "PASS"));
        run.remove("results");
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report(run), 0.4, COMMIT);
        assertNotNull(plan.refused());
        assertTrue(plan.carried().isEmpty());
    }

    @Test
    void noReportAtAllRunsEverything() {
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), null, 1.0, COMMIT);
        assertNotNull(plan.refused());
        assertTrue(plan.carried().isEmpty());
        assertFalse(plan.checks().isEmpty());
    }

    @Test
    void nothingToRerunIsSaidNotRunned() {
        Map<String, Object> all = new LinkedHashMap<>();
        for (Check c : AutoQaCatalog.all()) {
            if (c.kind() != Check.Kind.MANUAL) {
                all.put(c.id(), "PASS");
            }
        }
        all.put("t-bg-1", "PASS");
        Map<String, Object> report = report(run("fast", COMMIT, all));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertTrue(plan.checks().isEmpty());
        assertNotNull(plan.refused());
        assertTrue(plan.refused().contains("nothing to rerun"), plan.refused());
        assertTrue(plan.carried().containsKey("t-af-1"));
    }

    @Test
    void aCarriedCheckSaysItWasNotRerunAndKeepsItsIdentity() {
        Map<String, Object> report = report(run("fast", COMMIT, results("PASS", "PASS")));
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        Map<String, Object> check = plan.carried().get("t-af-1");

        AutoQaReport.carry(report, Map.of("t-af-1", check), plan.sourceRun(), "fast", COMMIT);

        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) report.get("checks")).get("t-af-1");
        // b222: a carried row is labelled old, never a fresh PASS
        assertEquals("PASS(old, 2026-10-24T10:00:00Z, 1c1e5dd9c)", entry.get("status"));
        assertEquals(Boolean.FALSE, entry.get("fresh"));
        assertEquals("1.5.0", entry.get("build"));
        assertEquals("2026-10-24T10:02:00Z", entry.get("finished"));
        assertEquals("fast", entry.get("mode"));
        Map<?, ?> from = (Map<?, ?>) entry.get("carriedFrom");
        assertEquals("2026-10-24T10:00:00Z", from.get("run"));
        assertEquals("1.5.0", from.get("build"));
        assertEquals("fast", from.get("mode"));
        List<?> evidence = (List<?>) entry.get("evidence");
        assertTrue(String.valueOf(evidence.get(0)).startsWith("not rerun - passed on 1.5.0"), evidence.toString());
        assertEquals("ok: earlier", evidence.get(1));
        Map<?, ?> summary = (Map<?, ?>) report.get("summary");
        assertEquals(1, ((Number) summary.get("carried")).intValue());
    }

    @Test
    void aSecondRerunCarriesWhatTheFirstCarriedInsteadOfRerunningIt() {
        List<String> headless = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            if (c.kind() != Check.Kind.MANUAL) {
                headless.add(c.id());
            }
        }
        // run 1: everything ran, t-af-1 and t-bg-1 failed
        Map<String, Object> r1 = new LinkedHashMap<>();
        for (String id : headless) {
            r1.put(id, "t-af-1".equals(id) || "t-bg-1".equals(id) ? "FAIL" : "PASS");
        }
        Map<String, Object> run1 = run("fast", COMMIT, r1);
        // run 2: --failed reran the two, t-af-1 passed, t-bg-1 still failed, the rest carried
        Map<String, Object> r2 = new LinkedHashMap<>();
        r2.put("t-af-1", "PASS");
        r2.put("t-bg-1", "FAIL");
        List<String> carriedBy2 = new ArrayList<>(headless);
        carriedBy2.remove("t-af-1");
        carriedBy2.remove("t-bg-1");
        Map<String, Object> run2 = run("fast", COMMIT, r2);
        run2.put("started", "2026-10-24T11:00:00Z");
        run2.put("carried", carriedBy2.size());
        run2.put("carriedIds", carriedBy2);
        run2.put("carriedFrom", new LinkedHashMap<>(Map.of("run", "2026-10-24T10:00:00Z")));

        Map<String, Object> report = report(run1);
        ((List<Object>) report.get("runs")).add(run2);
        Map<String, Object> tAf1 = new LinkedHashMap<>();
        tAf1.put("status", "PASS");
        tAf1.put("build", "1.5.0");
        tAf1.put("mode", "fast");
        tAf1.put("finished", "2026-10-24T11:01:00Z");
        tAf1.put("evidence", new ArrayList<>(List.of("ok: answered")));
        Map<String, Object> tAf2 = new LinkedHashMap<>(plan(run1, COMMIT).carried().get("t-af-2"));
        tAf2.put("evidence", new ArrayList<>(List.of("not rerun - passed on 1.5.0 2026-10-24T10:02:00Z fast", "ok: earlier")));
        Map<String, Object> from1 = new LinkedHashMap<>();
        from1.put("run", "2026-10-24T10:00:00Z");
        from1.put("build", "1.5.0");
        from1.put("mode", "fast");
        tAf2.put("carriedFrom", from1);
        ((Map<String, Object>) report.get("checks")).put("t-af-1", tAf1);
        ((Map<String, Object>) report.get("checks")).put("t-af-2", tAf2);

        // run 3: --failed again - only t-bg-1 is still failing, the run-2 carries must stay carried
        AutoQaRerun.Plan plan = AutoQaRerun.plan(AutoQaCatalog.all(), report, 0.4, COMMIT);
        assertNull(plan.refused());
        assertEquals(List.of("t-bg-1"), ids(plan.checks()));
        assertTrue(plan.carried().containsKey("t-af-1"));
        assertTrue(plan.carried().containsKey("t-af-2"));
        assertFalse(plan.carried().containsKey("t-bg-1"));
        assertEquals("2026-10-24T10:00:00Z", plan.sourceRun(), "the origin run, not the intermediate one");

        AutoQaReport.carry(report, plan.carried(), plan.sourceRun(), "fast");
        Map<?, ?> twice = (Map<?, ?>) ((Map<?, ?>) report.get("checks")).get("t-af-2");
        assertEquals("2026-10-24T10:00:00Z", ((Map<?, ?>) twice.get("carriedFrom")).get("run"));
        assertEquals("2026-10-24T10:02:00Z", twice.get("finished"), "identity unchanged by the chain");
        List<?> evidence = (List<?>) twice.get("evidence");
        assertEquals(2, evidence.size(), evidence.toString());
        assertTrue(String.valueOf(evidence.get(0)).startsWith("not rerun - passed on 1.5.0"));
    }

    private static AutoQaRerun.Plan plan(Map<String, Object> run, String commit) {
        return AutoQaRerun.plan(AutoQaCatalog.all(), report(run), 0.4, commit);
    }
}
