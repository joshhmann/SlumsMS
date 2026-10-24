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

        AutoQaReport.carry(report, Map.of("t-af-1", check), plan.sourceRun(), "fast");

        Map<?, ?> entry = (Map<?, ?>) ((Map<?, ?>) report.get("checks")).get("t-af-1");
        assertEquals("PASS", entry.get("status"));
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
}
