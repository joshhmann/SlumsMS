package soloMapling.qa;

import soloMapling.Economy.market.PublicMarketExport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 1.6.0 Track D, the website Market's server side: mk-1 forces one full market export (every 15-minute file,
 * yesterday's price rollup, pruning, the index) and checks that every file is there and is valid JSON. It runs
 * whether or not {@code -Dslums.market.export} is on, and writes to the export's directory.
 */
final class MarketScenarios {
    private MarketScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new ForceExport());
    }

    static final class ForceExport implements QaScenario {
        /** The export to run; tests substitute one. */
        static volatile Supplier<CompletableFuture<PublicMarketExport.Report>> exporter =
                PublicMarketExport::forceExport;

        public String id() { return "mk-1"; }
        public String title() { return "Market export: force one export, every file is written and valid"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 2; }

        public List<String> stage(QaContext ctx) {
            QaRun run = ctx.run();
            run.note("MK1", "START export=" + (PublicMarketExport.enabled() ? "on" : "off (forced once)"));
            exporter.get().orTimeout(90, TimeUnit.SECONDS).whenComplete((report, failure) -> {
                if (failure != null) {
                    run.note("MK1", "FAIL the export did not finish: " + failure);
                    return;
                }
                check(run, report);
            });
            return List.of("One market export runs now in the background (it never touches the game thread).",
                    "The log lists each file (MK1 FILE ... ok) and ends with a PASS line, or a FAIL line that names what is missing.");
        }

        static void check(QaRun run, PublicMarketExport.Report report) {
            run.note("MK1", "DIR " + report.dir().toAbsolutePath());
            report.failed().forEach(f -> run.note("MK1", "STEP FAIL " + f));
            List<String> want = new ArrayList<>(PublicMarketExport.FILES);
            want.add("prices/" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + ".json");
            List<String> missing = new ArrayList<>();
            for (String name : want) {
                Path p = report.dir().resolve(name);
                try {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    Object doc = soloMapling.server.Json.parse(text);
                    run.note("MK1", "FILE " + name + " ok " + text.length() + " bytes" + summary(name, doc));
                } catch (Exception e) {
                    missing.add(name);
                    run.note("MK1", "FILE " + name + " MISSING " + e.getClass().getSimpleName());
                }
            }
            if (missing.isEmpty() && report.failed().isEmpty()) {
                run.note("MK1", "PASS files=" + want.size());
            } else {
                run.note("MK1", "FAIL missing=" + missing + " failedSteps=" + report.failed().size());
            }
        }

        private static String summary(String name, Object doc) {
            if (!(doc instanceof Map<?, ?> m)) {
                return "";
            }
            return switch (name) {
                case "listings.json" -> " listings=" + m.get("count") + " shops=" + m.get("shops")
                        + " botShops=" + m.get("bot_shops") + " playerShops=" + m.get("player_shops");
                case "sales.json" -> " sales=" + m.get("count");
                case "nx-rate.json" -> " days=" + (m.get("days") instanceof List<?> l ? l.size() : 0)
                        + " today=" + (m.get("today") instanceof Map<?, ?> t ? t.get("mesos_per_1000nx") : null);
                case "economy-public.json" -> " shopsOpen=" + m.get("shops_open") + " trades24h=" + m.get("trades_24h");
                case "prices/index.json" -> " dates=" + (m.get("dates") instanceof List<?> l ? l.size() : 0);
                default -> m.containsKey("item_count") ? " items=" + m.get("item_count") : "";
            };
        }
    }
}
