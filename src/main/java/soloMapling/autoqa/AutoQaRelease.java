package soloMapling.autoqa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * b222 (owner 2026-10-30, ticket 164): a RELEASE auto-QA run ({@code tools/ops/autoqa-run --release all},
 * {@code !autoqa run all release}) is the launch check, so it FAILs overall when any check FAILs or any SKIP is not
 * on the allow-list of expected SKIPs ({@link #RESOURCE}: the client-only checks). A run's verdict line reads
 * {@code release: PASS} or {@code release: FAIL (unexpected SKIP: t-mx-1, t-bq-1)}.
 * <p>
 * A release run runs every check fresh: it refuses {@code --failed} (carried rows are never fresh evidence) and
 * {@code --fast} (the shortened staging windows are not the shipped ones), and it needs the selector {@code all}.
 */
public final class AutoQaRelease {

    /** The allow-list: one check id per line, a tab, why it may SKIP; '#' starts a comment. */
    static final String RESOURCE = "/soloMapling/autoqa-release-skips.tsv";

    private static volatile Map<String, String> allow;

    private AutoQaRelease() {
    }

    /** The release verdict of one run, kept on the run's line in the report. */
    public record Verdict(boolean pass, List<String> fails, List<String> unexpectedSkips, List<String> allowedSkips,
                          List<String> missing) {

        public Verdict {
            fails = List.copyOf(fails);
            unexpectedSkips = List.copyOf(unexpectedSkips);
            allowedSkips = List.copyOf(allowedSkips);
            missing = List.copyOf(missing);
        }

        /** {@code release: PASS} or {@code release: FAIL (FAIL: ids; unexpected SKIP: ids; not run: ids)}. */
        public String line() {
            if (pass) {
                return "release: PASS";
            }
            List<String> why = new ArrayList<>();
            if (!fails.isEmpty()) {
                why.add("FAIL: " + String.join(", ", fails));
            }
            if (!unexpectedSkips.isEmpty()) {
                why.add("unexpected SKIP: " + String.join(", ", unexpectedSkips));
            }
            if (!missing.isEmpty()) {
                why.add("not run: " + String.join(", ", missing));
            }
            return "release: FAIL (" + String.join("; ", why) + ")";
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("verdict", pass ? "PASS" : "FAIL");
            m.put("line", line());
            m.put("fails", new ArrayList<>(fails));
            m.put("unexpectedSkips", new ArrayList<>(unexpectedSkips));
            m.put("allowedSkips", new ArrayList<>(allowedSkips));
            m.put("missing", new ArrayList<>(missing));
            return m;
        }
    }

    /** The allow-list from the jar (id -> why), cached. */
    public static Map<String, String> allowList() {
        Map<String, String> a = allow;
        if (a == null) {
            a = load();
            allow = a;
        }
        return a;
    }

    private static Map<String, String> load() {
        try (InputStream in = AutoQaRelease.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                AutoQa.log.warn("Auto-QA release allow-list {} is missing: every SKIP fails a release run", RESOURCE);
                return Map.of();
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    lines.add(l);
                }
            }
            return parse(lines);
        } catch (IOException e) {
            AutoQa.log.warn("Auto-QA release allow-list unreadable: {}", e.toString());
            return Map.of();
        }
    }

    /** Parses allow-list lines: {@code <id>\t<why>}, blank and '#' lines ignored. A line without a reason is refused. */
    static Map<String, String> parse(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String l = raw.strip();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            String[] parts = l.split("\t", 2);
            if (parts.length < 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException("allow-list line without a reason: " + raw);
            }
            out.put(parts[0].strip(), parts[1].strip());
        }
        return Map.copyOf(out);
    }

    /**
     * Judges a release run: every check of {@code catalogue} must have a result here; a FAIL, a SKIP not on
     * {@code allowed}, or a missing check fails the release. Results are fresh by construction (a release run never
     * carries).
     */
    static Verdict judge(List<Check> catalogue, List<AutoQaRunner.CheckResult> results, Map<String, String> allowed) {
        Map<String, AutoQaRunner.CheckResult> byId = new LinkedHashMap<>();
        for (AutoQaRunner.CheckResult r : results) {
            byId.put(r.id(), r);
        }
        List<String> fails = new ArrayList<>();
        List<String> unexpected = new ArrayList<>();
        List<String> allowedSkips = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Check c : catalogue) {
            AutoQaRunner.CheckResult r = byId.get(c.id());
            if (r == null) {
                missing.add(c.id());
                continue;
            }
            switch (r.status()) {
                case FAIL -> fails.add(c.id());
                case SKIP -> (allowed.containsKey(c.id()) ? allowedSkips : unexpected).add(c.id());
                case PASS -> {
                    // fresh evidence
                }
            }
        }
        return new Verdict(fails.isEmpty() && unexpected.isEmpty() && missing.isEmpty(), fails, unexpected,
                allowedSkips, missing);
    }

    /** Why a release request cannot run as asked, or null when it can. */
    static String refusal(String selector, boolean fast, boolean failed) {
        if (failed) {
            return "a release run runs every check fresh: --failed carries old passes, which are never release evidence";
        }
        if (fast) {
            return "a release run uses the normal QA clock: --fast shortens the staged windows";
        }
        if (!"all".equalsIgnoreCase(selector)) {
            return "a release run covers every check: use 'all', not '" + selector + "'";
        }
        return null;
    }
}
