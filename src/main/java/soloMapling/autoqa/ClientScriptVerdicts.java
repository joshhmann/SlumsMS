package soloMapling.autoqa;

import soloMapling.server.History;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The client-script verdicts in auto-QA's report (ticket 166, b222 rules): the t-ids a real-client script proves
 * (t-cl-1..3, t-rg-1/2, t-mv-1, t-jq-1, t-ga-4, t-mr-2, t-cc-1, t-la-1) are MANUAL checks, so the headless runner
 * always SKIPs them. Each run's verdict belongs under its t-id instead, with the run folder as the evidence.
 * <p>
 * The judging is done once, on 187, by {@code tools/qa-runner/verdict} (that script owns the b222 rules: a timeout is
 * a FAIL, and nothing is a PASS without a passing machine check or a recorded review). It writes one record per
 * covered id into {@code data/autoqa/client-verdicts/<check id>.json}; this class only imports them, so the server
 * never re-judges a run and there is one place the rules live.
 * <p>
 * A verdict from another TEST build is not evidence for the build being deployed: its PASS keeps its run's
 * {@code PASS(old, <run>, <commit>)} label and a release run does not count it (b222: only fresh evidence ships).
 */
public final class ClientScriptVerdicts {

    /** {@code <data/autoqa>/client-verdicts/<check id>.json}: what {@code tools/qa-runner/verdict --out} wrote. */
    static final String DIR = "client-verdicts";

    /** A record is a page of JSON; anything larger is not ours and is skipped rather than read. */
    static final int MAX_BYTES = 64 * 1024;

    private ClientScriptVerdicts() {
    }

    /**
     * The records by check id, for the ids the catalogue knows; a file that does not parse, names another id, or is
     * oversized is skipped with a warning (one bad file never fails a report write).
     */
    static Map<String, Map<String, Object>> load(Path dataDir, List<Check> catalogue) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path dir = dataDir.resolve(DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        Map<String, Check> known = new LinkedHashMap<>();
        for (Check c : catalogue) {
            known.put(c.id(), c);
        }
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files.sorted()::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".json") || !Files.isRegularFile(f)) {
                    continue;
                }
                String id = name.substring(0, name.length() - ".json".length());
                if (!known.containsKey(id)) {
                    AutoQa.log.debug("client verdict {} names no catalogue check: ignored", name);
                    continue;
                }
                try {
                    if (Files.size(f) > MAX_BYTES) {
                        AutoQa.log.warn("client verdict {} is over {} bytes: ignored", name, MAX_BYTES);
                        continue;
                    }
                    Object parsed = Json.parse(History.readString(f));
                    if (!(parsed instanceof Map<?, ?> rec)) {
                        AutoQa.log.warn("client verdict {} is not a JSON object: ignored", name);
                        continue;
                    }
                    Map<String, Object> record = cast(rec);
                    if (!id.equals(record.get("id"))) {
                        AutoQa.log.warn("client verdict {} names '{}' inside: ignored", name, record.get("id"));
                        continue;
                    }
                    out.put(id, record);
                } catch (IOException | RuntimeException e) {
                    AutoQa.log.warn("client verdict {} unreadable: {}", name, e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            AutoQa.log.warn("client verdicts in {} unreadable: {}", dir, e.toString());
        }
        return out;
    }

    /**
     * Whether a record is evidence for the build being deployed: its own {@code fresh} flag, and an unknown commit
     * (no {@code DEPLOYED_COMMIT}, or an old record without the flag) is not held against it.
     */
    static boolean fresh(Map<String, Object> record) {
        Object f = record.get("fresh");
        return f == null || Boolean.TRUE.equals(f);
    }

    /** The status a record gives a release run: {@code PASS}/{@code FAIL} when it is fresh, else null (a SKIP). */
    static String releaseState(Map<String, Object> record) {
        if (record == null || !fresh(record)) {
            return null;
        }
        String s = String.valueOf(record.getOrDefault("status", "")).toUpperCase(Locale.ROOT);
        return s.equals("PASS") || s.equals("FAIL") ? s : null;
    }

    /** The report entry for one record, in the shape the report's mergeChecks writes. */
    static Map<String, Object> entry(Map<String, Object> record, Check c, String build) {
        Map<String, Object> run = record.get("run") instanceof Map<?, ?> r ? cast(r) : Map.of();
        boolean fresh = fresh(record);
        String status = String.valueOf(record.getOrDefault("status", "SKIP")).toUpperCase(Locale.ROOT);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("status", "PASS".equals(status) && !fresh ? AutoQaReport.oldLabel(run.get("run_id"), record.get("commit"))
                : status);
        e.put("title", c.title());
        e.put("kind", c.kind().name());
        e.put("reason", record.get("reason"));
        List<String> ev = new ArrayList<>();
        if (record.get("evidence") instanceof List<?> lines) {
            for (Object line : lines) {
                ev.add(String.valueOf(line));
            }
        }
        if (run.get("folder") != null && !ev.contains(String.valueOf(run.get("folder")))) {
            ev.add(0, String.valueOf(run.get("folder")));
        }
        e.put("evidence", ev.size() > AutoQaReport.MAX_EVIDENCE
                ? new ArrayList<>(ev.subList(0, AutoQaReport.MAX_EVIDENCE)) : ev);
        e.put("started", run.get("started_utc"));
        e.put("finished", run.get("finished_utc"));
        e.put("seconds", seconds(run.get("started_utc"), run.get("finished_utc")));
        e.put("build", build);
        e.put("mode", "client-script");
        e.put("commit", record.get("commit"));
        e.put("logs", List.of(String.valueOf(run.get("folder"))));
        e.put("fresh", fresh);
        e.put("source", "client-script");
        e.put("run", run.get("folder"));
        e.put("script", record.get("script"));
        e.put("review", record.get("review"));
        return e;
    }

    /**
     * Puts every client-script verdict under its check id, replacing the runner's SKIP for that id (never a result the
     * runner really measured), and recounts. Returns how many ids it set.
     */
    @SuppressWarnings("unchecked")
    static int apply(Map<String, Object> report, Path dataDir, List<Check> catalogue, String build) {
        Map<String, Map<String, Object>> verdicts = load(dataDir, catalogue);
        if (verdicts.isEmpty()) {
            return 0;
        }
        Map<String, Object> checks = (Map<String, Object>) report.computeIfAbsent("checks",
                k -> new LinkedHashMap<>());
        int set = 0;
        for (Check c : catalogue) {
            Map<String, Object> record = verdicts.get(c.id());
            if (record == null) {
                continue;
            }
            if (checks.get(c.id()) instanceof Map<?, ?> was && !"SKIP".equals(String.valueOf(was.get("status")))
                    && !"NOT_RUN".equals(String.valueOf(was.get("status")))) {
                continue; // a result the runner measured stands: a script is not evidence against a real run
            }
            checks.put(c.id(), entry(record, c, build));
            set++;
        }
        AutoQaReport.summarise(report);
        return set;
    }

    private static long seconds(Object from, Object to) {
        try {
            if (from == null || to == null) {
                return 0;
            }
            return Math.max(0, (java.time.Instant.parse(String.valueOf(to)).toEpochMilli()
                    - java.time.Instant.parse(String.valueOf(from)).toEpochMilli()) / 1000);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }
}
