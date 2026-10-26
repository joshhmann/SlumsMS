package soloMapling.qa;

import soloMapling.site.PublicSiteExport;
import soloMapling.site.SiteFileShape;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 1.6.0 Track D, the website's feed, leaderboards and live-world page: sf-1 forces one export of every file and checks
 * that each is there, parses, and has the agreed shape (schema 1, lists within their limits, feed newest first, no
 * character or account ids). It runs whether or not {@code -Dslums.site.export} is on (with it off the feed has
 * recorded nothing, so it is empty but still valid), and writes to the export's directory.
 */
final class SiteScenarios {
    private SiteScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new ForceExport());
    }

    static final class ForceExport implements QaScenario {
        /** The export to run; tests substitute one. */
        static volatile Supplier<CompletableFuture<PublicSiteExport.Report>> exporter = PublicSiteExport::forceExport;

        public String id() { return "sf-1"; }
        public String title() { return "Site export: force one export, feed/leaderboards/world written with their shape"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 2; }

        public List<String> stage(QaContext ctx) {
            QaRun run = ctx.run();
            run.note("SF1", "START export=" + (PublicSiteExport.enabled() ? "on" : "off (forced once; the feed records nothing)"));
            exporter.get().orTimeout(90, TimeUnit.SECONDS).whenComplete((report, failure) -> {
                if (failure != null) {
                    run.note("SF1", "FAIL the export did not finish: " + failure);
                    return;
                }
                check(run, report);
            });
            return List.of("One site export runs now in the background (it never touches the game thread).",
                    "The log lists each file (SF1 FILE ... ok) and ends with a PASS line, or a FAIL line that names what is wrong.");
        }

        static void check(QaRun run, PublicSiteExport.Report report) {
            run.note("SF1", "DIR " + report.dir().toAbsolutePath());
            report.failed().forEach(f -> run.note("SF1", "STEP FAIL " + f));
            List<String> bad = new ArrayList<>();
            for (String name : PublicSiteExport.FILES) {
                Path p = report.dir().resolve(name);
                Object doc;
                String text;
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                    doc = soloMapling.server.Json.parse(text);
                } catch (Exception e) {
                    bad.add(name);
                    run.note("SF1", "FILE " + name + " MISSING " + e.getClass().getSimpleName());
                    continue;
                }
                List<String> problems = SiteFileShape.shape(name, doc);
                run.note("SF1", "FILE " + name + " ok " + text.length() + " bytes" + summary(name, doc));
                for (String problem : problems) {
                    run.note("SF1", "SHAPE " + name + ": " + problem);
                }
                if (!problems.isEmpty()) {
                    bad.add(name);
                }
            }
            if (bad.isEmpty() && report.failed().isEmpty()) {
                run.note("SF1", "PASS files=" + PublicSiteExport.FILES.size());
            } else {
                run.note("SF1", "FAIL bad=" + bad + " failedSteps=" + report.failed().size());
            }
        }

        private static Map<?, ?> map(Object o) {
            return o instanceof Map<?, ?> m ? m : null;
        }

        private static String summary(String name, Object doc) {
            if (!(doc instanceof Map<?, ?> m)) {
                return "";
            }
            return switch (name) {
                case "feed.json" -> " items=" + size(m.get("items"));
                case "leaderboards.json" -> {
                    Map<?, ?> bosses = map(m.get("bosses"));
                    yield " level=" + size(m.get("level")) + " fame=" + size(m.get("fame")) + " guilds="
                            + size(m.get("guilds")) + " traders=" + size(m.get("traders")) + " zakum="
                            + (bosses == null ? 0 : size(bosses.get("Zakum")));
                }
                case "world.json" -> " online=" + m.get("online") + " channels=" + size(m.get("channels"))
                        + " maps=" + size(m.get("busiest_maps")) + " running=" + size(m.get("running"))
                        + " tonight=" + (map(m.get("tonight")) == null ? "none" : map(m.get("tonight")).get("start"));
                default -> "";
            };
        }

        private static int size(Object o) {
            return o instanceof List<?> l ? l.size() : 0;
        }
    }
}
