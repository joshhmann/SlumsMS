package soloMapling.autoqa;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /** Merges a run's results into a day's report (in place): the checks, then the run's line. Returns it. */
    static Map<String, Object> merge(Map<String, Object> report, String build, String trigger, long startedMs, long finishedMs,
                                     List<AutoQaRunner.CheckResult> results, Map<String, Object> health) {
        mergeChecks(report, build, results, health);
        addRun(report, trigger, startedMs, finishedMs, results);
        return report;
    }

    /** Puts each result under its check id (replacing an earlier one), the health line, and re-counts. */
    @SuppressWarnings("unchecked")
    static void mergeChecks(Map<String, Object> report, String build, List<AutoQaRunner.CheckResult> results,
                            Map<String, Object> health) {
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
            report.put("health", h);
        }
        summarise(report);
    }

    /** Adds the run's line to "runs": who started it, when, and its counts. */
    @SuppressWarnings("unchecked")
    static void addRun(Map<String, Object> report, String trigger, long startedMs, long finishedMs,
                       List<AutoQaRunner.CheckResult> results) {
        int pass = 0, fail = 0, skip = 0;
        for (AutoQaRunner.CheckResult r : results) {
            switch (r.status()) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case SKIP -> skip++;
            }
        }
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("trigger", trigger);
        run.put("started", Instant.ofEpochMilli(startedMs).toString());
        run.put("finished", Instant.ofEpochMilli(finishedMs).toString());
        run.put("checks", results.size());
        run.put("PASS", pass);
        run.put("FAIL", fail);
        run.put("SKIP", skip);
        Object runsObj = report.get("runs");
        List<Object> runs = runsObj instanceof List ? new ArrayList<>((List<Object>) runsObj) : new ArrayList<>();
        runs.add(run);
        report.put("runs", runs);
        report.put("updated", Instant.ofEpochMilli(finishedMs).toString());
    }

    @SuppressWarnings("unchecked")
    static void summarise(Map<String, Object> report) {
        Map<String, Object> checks = (Map<String, Object>) report.get("checks");
        Map<String, Object> s = new LinkedHashMap<>();
        for (String k : List.of("PASS", "FAIL", "SKIP", "NOT_RUN")) {
            s.put(k, 0);
        }
        for (Object o : checks.values()) {
            String st = String.valueOf(((Map<String, Object>) o).get("status"));
            s.merge(st, 1, (a, b) -> (Integer) a + (Integer) b);
        }
        s.put("total", checks.size());
        report.put("summary", s);
    }

    /** Reads a day's report, or null when there is none or it is unreadable. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Object o = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            return o instanceof Map ? new LinkedHashMap<>((Map<String, Object>) o) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes report-<day>.json and latest.json. */
    static void write(Path dir, String day, Map<String, Object> report) throws IOException {
        String json = Json.write(report);
        AutoQa.writeAtomic(dir.resolve("report-" + day + ".json"), json);
        AutoQa.writeAtomic(dir.resolve("latest.json"), json);
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
        for (String k : List.of("PASS", "FAIL", "SKIP", "NOT_RUN")) {
            counts.put(k, 0);
        }
        List<CheckLine> lines = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) report.get("checks")).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> c = (Map<String, Object>) e.getValue();
            String status = String.valueOf(c.get("status"));
            counts.merge(status, 1, Integer::sum);
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
        out.add("Auto-QA " + report.get("day") + " (" + report.get("build") + "): " + s.get("PASS") + " pass, "
                + s.get("FAIL") + " fail, " + s.get("SKIP") + " skip, " + s.get("NOT_RUN") + " not run of " + s.get("total")
                + ". Updated " + report.get("updated"));
        Map<String, Object> h = (Map<String, Object>) report.get("health");
        if (h != null) {
            out.add("Health " + h.get("status") + ": " + h.get("summary"));
        }
        Map<String, Object> checks = (Map<String, Object>) report.get("checks");
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
        if (out.size() >= maxLines) {
            out.add("... more in data/autoqa/latest.json");
        }
        return out;
    }
}
