package soloMapling.autoqa;

import soloMapling.server.History;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The auto-QA report: {@code data/autoqa/report-<yyyy-MM-dd>.json} (UTC day) and {@code latest.json}, keyed by the
 * release-notes check ids. A day's runs merge into the day's report: a check run again replaces its entry, the rest
 * stay. Shape:
 * <pre>
 * { "build": "1.5.0-rc5", "day": "2026-10-23", "updated": "...Z",
 *   "summary": {"PASS": n, "FAIL": n, "SKIP": n, "NOT_RUN": n, "total": n},
 *   "health": {"status": "...", "summary": "...", "time": "..."},
 *   "runs": [{"trigger": "...", "started": "...Z", "finished": "...Z", "checks": n, "PASS": n, "FAIL": n, "SKIP": n}],
 *   "checks": {"t-af-1": {"status": "PASS", "title": "...", "kind": "AUTO", "reason": null,
 *                         "evidence": ["..."], "started": "...Z", "finished": "...Z", "seconds": n, "build": "..."}}}
 * </pre>
 */
public final class AutoQaReport {

    static final int MAX_EVIDENCE = 40;

    private AutoQaReport() {
    }

    /** A fresh day's report listing every check of the catalogue as NOT_RUN (MANUAL ones as SKIP with why). */
    static Map<String, Object> empty(String build, String day, List<Check> catalogue) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("build", build);
        r.put("day", day);
        r.put("updated", null);
        r.put("summary", new LinkedHashMap<>());
        r.put("health", null);
        r.put("runs", new ArrayList<>());
        Map<String, Object> checks = new LinkedHashMap<>();
        for (Check c : catalogue) {
            Map<String, Object> e = new LinkedHashMap<>();
            boolean manual = c.kind() == Check.Kind.MANUAL;
            e.put("status", manual ? "SKIP" : "NOT_RUN");
            e.put("title", c.title());
            e.put("kind", c.kind().name());
            e.put("reason", manual ? c.why() : null);
            e.put("evidence", List.of());
            checks.put(c.id(), e);
        }
        r.put("checks", checks);
        summarise(r);
        return r;
    }

    /**
     * One run as the report records it: when it ran, how (the QA clock it ran on and the commit the server was
     * deployed from), and which earlier passes it carried forward instead of rerunning them.
     */
    record Run(String trigger, long startedMs, long finishedMs, double timeScale, String commit,
               List<AutoQaRunner.CheckResult> results, Map<String, Map<String, Object>> carried, String carriedRun,
               AutoQaRelease.Verdict release) {

        Run {
            carried = carried == null ? Map.of() : carried;
        }

        /** An ordinary (not release) run. */
        Run(String trigger, long startedMs, long finishedMs, double timeScale, String commit,
            List<AutoQaRunner.CheckResult> results, Map<String, Map<String, Object>> carried, String carriedRun) {
            this(trigger, startedMs, finishedMs, timeScale, commit, results, carried, carriedRun, null);
        }
    }

    /** "fast" when the QA clock ran below 1x, else "normal": every run and check line says which. */
    static String mode(double timeScale) {
        return timeScale < 1.0 - 1e-9 ? "fast" : "normal";
    }

    /** The QA clock a run ran on, as the report shows it ("0.4x"). */
    static String scaleLabel(double timeScale) {
        return String.format(Locale.ROOT, "%.1fx", timeScale);
    }

    /** A normal run: the QA clock at 1x, nothing carried. */
    static Map<String, Object> merge(Map<String, Object> report, String build, String trigger, long startedMs, long finishedMs,
                                     List<AutoQaRunner.CheckResult> results, Map<String, Object> health) {
        return merge(report, build, new Run(trigger, startedMs, finishedMs, 1.0, null, results, Map.of(), null), health);
    }

    /** Merges a run into a day's report (in place): results, carried passes, then the run's own line. Returns it. */
    static Map<String, Object> merge(Map<String, Object> report, String build, Run run, Map<String, Object> health) {
        mergeChecks(report, build, run.timeScale(), run.commit(), run.results(), health);
        carry(report, run.carried(), run.carriedRun(), mode(run.timeScale()), run.commit());
        addRun(report, run);
        return report;
    }

    /** Puts each result under its check id (replacing an earlier one), the health line, and re-counts. */
    static void mergeChecks(Map<String, Object> report, String build, double timeScale,
                            List<AutoQaRunner.CheckResult> results, Map<String, Object> health) {
        mergeChecks(report, build, timeScale, null, results, health);
    }

    /**
     * As above; b222: every entry also cites its evidence - the commit the TEST server was deployed from
     * ({@code DEPLOYED_COMMIT}, null when unreadable), the QA log file(s) its scenarios wrote, and when it finished -
     * and is marked fresh (it ran in this run).
     */
    @SuppressWarnings("unchecked")
    static void mergeChecks(Map<String, Object> report, String build, double timeScale, String commit,
                            List<AutoQaRunner.CheckResult> results, Map<String, Object> health) {
        Map<String, Object> checks = (Map<String, Object>) report.computeIfAbsent("checks", k -> new LinkedHashMap<>());
        long last = 0;
        for (AutoQaRunner.CheckResult r : results) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("status", r.status().name());
            e.put("title", r.title());
            e.put("kind", r.kind().name());
            e.put("reason", r.reason());
            List<String> ev = r.evidence();
            e.put("evidence", ev.size() > MAX_EVIDENCE ? new ArrayList<>(ev.subList(0, MAX_EVIDENCE)) : new ArrayList<>(ev));
            e.put("started", Instant.ofEpochMilli(r.startedMs()).toString());
            e.put("finished", Instant.ofEpochMilli(r.finishedMs()).toString());
            e.put("seconds", (r.finishedMs() - r.startedMs()) / 1000);
            e.put("build", build);
            e.put("mode", mode(timeScale));
            e.put("commit", commit);
            e.put("logs", new ArrayList<>(r.logs()));
            e.put("fresh", true);
            checks.put(r.id(), e);
            last = Math.max(last, r.finishedMs());
        }
        report.put("build", build);
        if (last > 0) {
            report.put("updated", Instant.ofEpochMilli(last).toString());
        }
        if (health != null) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", health.get("status"));
            h.put("summary", health.get("summary"));
            h.put("time", health.get("time"));
            // rc6: grounding as the server holds it and as clients were told it, side by side
            if (health.get("checks") instanceof Map<?, ?> sweepChecks) {
                for (String k : List.of("foothold", HealthSweep.SENT_CHECK)) {
                    if (sweepChecks.get(k) instanceof Map<?, ?> c) {
                        h.put(k, c.get("status") + ": " + c.get("summary"));
                    }
                }
            }
            report.put("health", h);
        }
        summarise(report);
    }

    /** rc6: the chat-quality headline and scores, and the say-do counts since boot, into the report. */
    static void putInference(Map<String, Object> report, Map<String, Object> chatQuality, Map<String, Object> sayDo) {
        if (chatQuality != null) {
            report.put("chatQuality", chatQuality);
        }
        if (sayDo != null) {
            report.put("sayDo", sayDo);
        }
    }

    /** As {@link #carry(Map, Map, String, String, String)} with no commit known. */
    static void carry(Map<String, Object> report, Map<String, Map<String, Object>> carried, String sourceRun, String mode) {
        carry(report, carried, sourceRun, mode, null);
    }

    /**
     * Carries a PASS over from an earlier run: the entry keeps its started/finished, build and mode, and says so in
     * its own evidence. b222 (owner 2026-10-30): its status reads {@code PASS(old, <run started>, <commit>)} - in
     * latest.json, test-status and {@code !autoqa report} - with {@code fresh: false}, and it is counted as OLD, never
     * as a PASS: a carried row is never fresh evidence. {@code commit} is the deployed commit the rerun was guarded
     * on (the same as the carried run's; null when unknown).
     */
    @SuppressWarnings("unchecked")
    static void carry(Map<String, Object> report, Map<String, Map<String, Object>> carried, String sourceRun, String mode,
                      String commit) {
        if (carried == null || carried.isEmpty()) {
            return;
        }
        Map<String, Object> checks = (Map<String, Object>) report.computeIfAbsent("checks", k -> new LinkedHashMap<>());
        for (Map.Entry<String, Map<String, Object>> e : carried.entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>(e.getValue());
            String wasMode = String.valueOf(entry.getOrDefault("mode", mode));
            entry.put("fresh", false);
            if (entry.get("carriedFrom") instanceof Map<?, ?> already) {
                // carried twice: the entry still points at the run that actually ran it, and already says so once
                Map<String, Object> from = new LinkedHashMap<>(cast(already));
                from.putIfAbsent("commit", entry.get("commit") != null ? entry.get("commit") : commit);
                entry.put("carriedFrom", from);
                entry.put("status", oldLabel(from.get("run"), from.get("commit")));
                checks.put(e.getKey(), entry);
                continue;
            }
            List<String> ev = new ArrayList<>();
            ev.add("not rerun - passed on " + entry.get("build") + " " + entry.get("finished") + " " + wasMode);
            if (entry.get("evidence") instanceof List<?> old) {
                for (Object line : old) {
                    ev.add(String.valueOf(line));
                }
            }
            entry.put("evidence", ev.size() > MAX_EVIDENCE ? new ArrayList<>(ev.subList(0, MAX_EVIDENCE)) : ev);
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("run", sourceRun);
            from.put("build", entry.get("build"));
            from.put("mode", wasMode);
            from.put("commit", entry.get("commit") != null ? entry.get("commit") : commit);
            from.put("status", "PASS");
            entry.put("carriedFrom", from);
            entry.put("status", oldLabel(sourceRun, from.get("commit")));
            checks.put(e.getKey(), entry);
        }
        summarise(report);
    }

    /** The status a carried pass shows: {@code PASS(old, 2026-10-30T19:00:00Z, b32a14214)}. */
    static String oldLabel(Object run, Object commit) {
        String c = commit == null ? "commit unknown" : String.valueOf(commit);
        return "PASS(old, " + (run == null ? "run unknown" : run) + ", " + (c.length() > 9 ? c.substring(0, 9) : c) + ")";
    }

    /** Whether a report entry is a carried (old) row rather than a result of the run that wrote it. */
    static boolean isOld(Map<?, ?> entry) {
        return entry != null && (entry.containsKey("carriedFrom") || Boolean.FALSE.equals(entry.get("fresh"))
                || String.valueOf(entry.get("status")).startsWith("PASS(old"));
    }

    /** Whether a report entry passed, fresh or carried (what a later {@code --failed} may carry again). */
    static boolean passed(Map<?, ?> entry) {
        Object st = entry == null ? null : entry.get("status");
        return "PASS".equals(st) || String.valueOf(st).startsWith("PASS(old");
    }

    /** The bucket a report entry counts in: PASS, FAIL, SKIP, NOT_RUN, or OLD for a carried pass. */
    static String bucket(Map<?, ?> entry) {
        return isOld(entry) ? "OLD" : String.valueOf(entry.get("status"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** Adds the run's line to "runs": who started it, when, how it ran, its counts, and its results. */
    @SuppressWarnings("unchecked")
    static void addRun(Map<String, Object> report, Run run) {
        int pass = 0, fail = 0, skip = 0;
        Map<String, Object> results = new LinkedHashMap<>();
        for (AutoQaRunner.CheckResult r : run.results()) {
            switch (r.status()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case SKIP -> skip++;
            }
            results.put(r.id(), r.status().name());
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("trigger", run.trigger());
        line.put("started", Instant.ofEpochMilli(run.startedMs()).toString());
        line.put("finished", Instant.ofEpochMilli(run.finishedMs()).toString());
        line.put("mode", mode(run.timeScale()));
        line.put("timeScale", scaleLabel(run.timeScale()));
        if (run.commit() != null) {
            line.put("commit", run.commit());
        }
        line.put("checks", run.results().size());
        line.put("PASS", pass);
        line.put("FAIL", fail);
        line.put("SKIP", skip);
        if (!run.carried().isEmpty()) {
            // the ids, not just the count: a second --failed run must know these passed without being rerun
            line.put("carried", run.carried().size());
            line.put("carriedIds", new ArrayList<>(run.carried().keySet()));
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("run", run.carriedRun());
            line.put("carriedFrom", from);
        }
        line.put("results", results);
        // b222 (4): which checks this run skipped, and why, so a nightly can never hide a SKIP among the counts
        Map<String, Object> skipped = new LinkedHashMap<>();
        for (AutoQaRunner.CheckResult r : run.results()) {
            if (r.status() == Check.Status.SKIP) {
                skipped.put(r.id(), r.reason() == null ? "no reason given" : r.reason());
            }
        }
        line.put("skipped", skipped);
        if (run.release() != null) {
            line.put("release", run.release().toMap());
        }
        line.put("summary", runSummary(line));
        Object runsObj = report.get("runs");
        List<Object> runs = runsObj instanceof List ? new ArrayList<>((List<Object>) runsObj) : new ArrayList<>();
        runs.add(line);
        report.put("runs", runs);
        report.put("updated", Instant.ofEpochMilli(run.finishedMs()).toString());
    }

    @SuppressWarnings("unchecked")
    static void summarise(Map<String, Object> report) {
        Map<String, Object> checks = (Map<String, Object>) report.get("checks");
        Map<String, Object> s = new LinkedHashMap<>();
        for (String k : List.of("PASS", "FAIL", "SKIP", "NOT_RUN", "OLD")) {
            s.put(k, 0);
        }
        int carried = 0;
        for (Object o : checks.values()) {
            Map<String, Object> c = (Map<String, Object>) o;
            s.merge(bucket(c), 1, (a, b) -> (Integer) a + (Integer) b);
            if (isOld(c)) {
                carried++;
            }
        }
        s.put("total", checks.size());
        s.put("carried", carried);
        report.put("summary", s);
    }

    /** Reads a day's report, or null when there is none or it is unreadable. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) {
        try {
            if (!History.exists(file)) {
                return null;
            }
            Object o = Json.parse(History.readString(file));
            return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The last run line in a report, or null when there is none: what a "--failed" rerun reads. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> lastRun(Map<String, Object> report) {
        if (report == null || !(report.get("runs") instanceof List<?> runs) || runs.isEmpty()) {
            return null;
        }
        Object last = runs.get(runs.size() - 1);
        return last instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null;
    }

    /** Writes report-<day>.json and latest.json. */
    static void write(Path dir, String day, Map<String, Object> report) throws IOException {
        String json = Json.write(report);
        AutoQa.writeAtomic(dir.resolve("report-" + day + ".json"), json);
        AutoQa.writeAtomic(dir.resolve("latest.json"), json);
    }

    /**
     * A run's one-line summary (b222): counts, the skipped ids grouped by reason, and for a release run its verdict:
     * {@code "<trigger> at <started>: 120 pass, 2 fail, 13 skip; skipped: client-only: t-cl-1, t-cl-2 | ...;
     * release: FAIL (unexpected SKIP: t-mx-1)"}.
     */
    static String runSummary(Map<String, Object> line) {
        StringBuilder b = new StringBuilder();
        b.append(line.get("trigger")).append(" at ").append(line.get("started")).append(": ")
                .append(line.get("PASS")).append(" pass, ").append(line.get("FAIL")).append(" fail, ")
                .append(line.get("SKIP")).append(" skip");
        if (line.get("carried") instanceof Number n && n.intValue() > 0) {
            b.append(", ").append(n).append(" old (carried, not rerun)");
        }
        if (line.get("skipped") instanceof Map<?, ?> skipped && !skipped.isEmpty()) {
            Map<String, List<String>> byReason = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : skipped.entrySet()) {
                String why = String.valueOf(e.getValue());
                why = why.length() > 70 ? why.substring(0, 70) + "..." : why;
                byReason.computeIfAbsent(why, k -> new ArrayList<>()).add(String.valueOf(e.getKey()));
            }
            List<String> parts = new ArrayList<>();
            byReason.forEach((why, ids) -> parts.add(String.join(", ", ids) + " (" + why + ")"));
            b.append("; skipped: ").append(String.join(" | ", parts));
        }
        if (line.get("release") instanceof Map<?, ?> rel) {
            b.append("; ").append(rel.get("line"));
        }
        return b.toString();
    }

    /** One check's line in a report: id ({@code t-af-1}), title, status (PASS/FAIL/SKIP/NOT_RUN) and the reason. */
    public record CheckLine(String id, String title, String status, String reason) {
    }

    /** A report read for the {@code !qa} menu: day, build, counts per status, and every check in report order. */
    public record Snapshot(String day, String build, Map<String, Integer> counts, List<CheckLine> checks) {

        public int count(String status) {
            return counts.getOrDefault(status, 0);
        }
    }

    /** The report as a {@link Snapshot} (counts recounted from its checks); null for no report. */
    @SuppressWarnings("unchecked")
    public static Snapshot snapshot(Map<String, Object> report) {
        if (report == null || !(report.get("checks") instanceof Map)) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String k : List.of("PASS", "FAIL", "SKIP", "NOT_RUN", "OLD")) {
            counts.put(k, 0);
        }
        List<CheckLine> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) report.get("checks")).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> c = (Map<String, Object>) e.getValue();
            String status = String.valueOf(c.get("status"));
            counts.merge(bucket(c), 1, Integer::sum);
            Object reason = c.get("reason");
            lines.add(new CheckLine(e.getKey(), String.valueOf(c.get("title")), status,
                    reason == null ? null : String.valueOf(reason)));
        }
        return new Snapshot(String.valueOf(report.get("day")), String.valueOf(report.get("build")), counts,
                List.copyOf(lines));
    }

    /** The {@code !autoqa report} lines: totals, health, then every FAIL and a few SKIP reasons. */
    @SuppressWarnings("unchecked")
    static List<String> summaryLines(Map<String, Object> report, int maxLines) {
        List<String> out = new ArrayList<>();
        if (report == null) {
            out.add("No auto-QA report yet. !autoqa run <check-id|all> makes one.");
            return out;
        }
        Map<String, Object> s = (Map<String, Object>) report.get("summary");
        Object old = s.get("OLD");
        out.add("Auto-QA " + report.get("day") + " (" + report.get("build") + "): " + s.get("PASS") + " pass, "
                + s.get("FAIL") + " fail, " + s.get("SKIP") + " skip, " + s.get("NOT_RUN") + " not run"
                + (old instanceof Number o && o.intValue() > 0 ? ", " + o + " old (carried, not rerun)" : "")
                + " of " + s.get("total") + ". Updated " + report.get("updated"));
        Map<?, ?> last = lastRun(report);
        if (last != null && last.get("carried") instanceof Number n && n.intValue() > 0) {
            Object from = last.get("carriedFrom") instanceof Map<?, ?> c ? c.get("run") : null;
            out.add("Last run: " + last.get("PASS") + " pass / " + last.get("FAIL") + " fail rerun at "
                    + last.get("timeScale") + " (" + last.get("mode") + "); " + n + " pass carried forward, not rerun, "
                    + "from " + from + " - shown as PASS(old, ...), never fresh evidence");
        }
        Map<String, Object> h = (Map<String, Object>) report.get("health");
        if (h != null) {
            out.add("Health " + h.get("status") + ": " + h.get("summary"));
            if (h.get("foothold") != null || h.get(HealthSweep.SENT_CHECK) != null) {
                out.add("Grounding: server " + h.get("foothold") + " | sent " + h.get(HealthSweep.SENT_CHECK));
            }
        }
        if (report.get("chatQuality") instanceof Map<?, ?> cq) {
            out.add("Chat: " + cq.get("headline") + (cq.get("problems") instanceof List<?> p && !p.isEmpty()
                    ? " PROBLEMS " + p : ""));
        }
        if (report.get("sayDo") instanceof Map<?, ?> sd && sd.get("claims") instanceof Number c && c.longValue() > 0) {
            out.add("Say-do since boot: " + sd.get("backed") + " of " + c + " claims backed");
        }
        if (last != null) {
            // b222: what the last run skipped and why, and a release run's verdict
            out.add("Last run: " + (last.get("summary") != null ? last.get("summary") : runSummary(cast(last))));
        }
        Map<String, Object> checks = (Map<String, Object>) report.get("checks");
        List<String> olds = new ArrayList<>();
        for (Map.Entry<String, Object> e : checks.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> c && isOld(c)) {
                olds.add(e.getKey() + " " + c.get("status"));
            }
        }
        for (String want : List.of("FAIL", "PASS", "SKIP")) {
            for (Map.Entry<String, Object> e : checks.entrySet()) {
                Map<String, Object> c = (Map<String, Object>) e.getValue();
                if (!want.equals(c.get("status")) || out.size() >= maxLines) {
                    continue;
                }
                if ("PASS".equals(want)) {
                    continue; // passes are only counted
                }
                Object reason = c.get("reason");
                out.add(want + " " + e.getKey() + " " + c.get("title") + (reason == null ? "" : ": " + reason));
            }
        }
        if (!olds.isEmpty() && out.size() < maxLines) {
            out.add("OLD (carried, not rerun): " + String.join("; ", olds.subList(0, Math.min(olds.size(), 8)))
                    + (olds.size() > 8 ? "; ... " + (olds.size() - 8) + " more" : ""));
        }
        if (out.size() >= maxLines) {
            out.add("... more in data/autoqa/latest.json");
        }
        return out;
    }
}
