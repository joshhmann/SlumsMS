package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --failed}: reruns the checks whose last result was not a PASS, and carries the rest forward as they were.
 * <p>
 * A carried PASS keeps its own identity (status, times, build, mode) and is labelled not-rerun in the report, so a
 * partial rerun can never read as "all green". Carrying is refused whenever the earlier run cannot be trusted as the
 * same conditions: a different deployed commit, a different QA clock (fast vs normal), no recorded results at all, or
 * a missing check entry. In those cases every check runs.
 * <p>
 * This is a tool for iterating on a fix. Evidence for a release comes from a full run, and the checks a deploy touches
 * are rerun explicitly even when they were green before.
 */
final class AutoQaRerun {

    /**
     * What a {@code --failed} run does: the checks to run (catalogue order), the earlier entries to carry, the run the
     * carries come from, and - when carrying was refused - why, so the caller can say it out loud.
     */
    record Plan(List<Check> checks, Map<String, Map<String, Object>> carried, String sourceRun, String refused) {

        Plan {
            carried = carried == null ? Map.of() : carried;
        }
    }

    private AutoQaRerun() {
    }

    /**
     * Plans the rerun. {@code commit} is the commit the running server was deployed from ({@code DEPLOYED_COMMIT});
     * null when it cannot be read, which refuses carrying.
     */
    static Plan plan(List<Check> catalogue, Map<String, Object> report, double timeScale, String commit) {
        Map<String, Object> last = AutoQaReport.lastRun(report);
        if (last == null) {
            return everything(catalogue, "no earlier run to rerun");
        }
        String mode = AutoQaReport.mode(timeScale);
        Object wasMode = last.get("mode");
        if (!mode.equals(wasMode)) {
            return everything(catalogue, "the last run was " + (wasMode == null ? "not labelled" : wasMode)
                    + " and this one is " + mode + ", which moves every open-ended window: rerun all");
        }
        Object wasCommit = last.get("commit");
        if (commit == null) {
            return everything(catalogue, "cannot read the deployed commit (Server/DEPLOYED_COMMIT): rerun all");
        }
        if (!commit.equals(wasCommit)) {
            return everything(catalogue, "the server was redeployed since the last run (" + abbrev(wasCommit) + " -> "
                    + abbrev(commit) + "): rerun all");
        }
        if (!(last.get("results") instanceof Map<?, ?> results)) {
            return everything(catalogue, "the last run did not record its results: rerun all");
        }
        Map<String, Map<String, Object>> entries = entries(report);
        Map<String, Map<String, Object>> carried = new LinkedHashMap<>();
        List<Check> rerun = new ArrayList<>();
        for (Check c : catalogue) {
            if (c.kind() == Check.Kind.MANUAL) {
                continue; // client-only: never run headless, so never rerun and never carried either
            }
            Map<String, Object> entry = entries.get(c.id());
            if ("PASS".equals(results.get(c.id())) && "PASS".equals(entry == null ? null : entry.get("status"))) {
                carried.put(c.id(), entry);
            } else {
                rerun.add(c);
            }
        }
        if (rerun.isEmpty()) {
            return new Plan(List.of(), carried, String.valueOf(last.get("started")), "nothing to rerun: every check "
                    + "passed in the last run at " + last.get("timeScale") + " (" + mode + ")");
        }
        return new Plan(List.copyOf(rerun), carried, String.valueOf(last.get("started")), null);
    }

    private static Plan everything(List<Check> catalogue, String why) {
        List<Check> all = new ArrayList<>();
        for (Check c : catalogue) {
            if (c.kind() != Check.Kind.MANUAL) {
                all.add(c);
            }
        }
        return new Plan(List.copyOf(all), Map.of(), null, why);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> entries(Map<String, Object> report) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (report != null && report.get("checks") instanceof Map<?, ?> checks) {
            for (Map.Entry<?, ?> e : checks.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> entry) {
                    out.put(String.valueOf(e.getKey()), new LinkedHashMap<>((Map<String, Object>) entry));
                }
            }
        }
        return out;
    }

    private static String abbrev(Object commit) {
        if (commit == null) {
            return "none";
        }
        String s = String.valueOf(commit);
        return s.length() > 10 ? s.substring(0, 10) : s;
    }
}
