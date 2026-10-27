package soloMapling.autoqa;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.History;
import soloMapling.server.History;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.Pattern;

/**
 * Auto-QA (test server only): periodic health sweeps ({@link HealthSweeper}) and the synthetic-player check runner
 * ({@link AutoQaRunner}). Off unless the JVM has {@code -Dslums.autoqa=true} or config.yaml has
 * {@code AUTOQA_ENABLED: true}. Everything it writes goes under {@code data/autoqa/}. docs/ops/qa-suite.md "Auto-QA".
 */
public final class AutoQa {

    public static final String PROPERTY = "slums.autoqa";
    static final Logger log = LoggerFactory.getLogger("autoqa");

    private AutoQa() {
    }

    /** True when auto-QA is switched on (the property or the config field). */
    public static boolean isEnabled() {
        if (Boolean.getBoolean(PROPERTY)) {
            return true;
        }
        try {
            return YamlConfig.config != null && YamlConfig.config.server != null && YamlConfig.config.server.AUTOQA_ENABLED;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Auto-QA history is kept, never deleted by default: day files in {@code dir} older than a month are gzipped
     * ({@code .jsonl.gz} / {@code .json.gz}; {@code -Dslums.history.autoqaKeepDays=N} deletes after N days). The readers
     * ({@code ChatQuality}, {@code AutoQaReport.read}) read the compressed ones too. Returns the files gzipped.
     */
    static int compressHistory(Path dir) {
        int gz = History.gzipDays("autoqa"), keep = History.keepDays("autoqa");
        String d = "\\d{4}-\\d{2}-\\d{2}";
        int n = 0;
        for (String[] sub : new String[][]{{"social-trace", d + "\\.jsonl"}, {"health", d + "\\.jsonl"},
                {"saydo", d + "\\.jsonl"}, {"botwatch", "botwatch-" + d + "T\\d{4}Z\\.json"}, {"", "report-" + d + "\\.json"},
                {"", "chat-quality-" + d + "\\.json"}}) {
            n += History.sweep(sub[0].isEmpty() ? dir : dir.resolve(sub[0]), Pattern.compile(sub[1]), gz, keep).gzipped();
        }
        return n;
    }

    /** The same rule for a set of -D properties and the config value (tests). */
    static boolean enabledFor(Function<String, String> property, boolean configOn) {
        return "true".equalsIgnoreCase(property.apply(PROPERTY)) || configOn;
    }

    /** Where every auto-QA file goes: {@code data/autoqa} under the working directory ({@code -Dslums.autoqa.dir}). */
    public static Path dataDir() {
        return Path.of(System.getProperty("slums.autoqa.dir", "data/autoqa"));
    }

    /**
     * The commit the running server was deployed from: {@code DEPLOYED_COMMIT} beside {@code data/} (written by
     * tools/ops/deploy-test). The build banner cannot be used for this - every build of a release reports the same
     * version string. Null when the file is missing or unreadable, which makes a {@code --failed} rerun carry nothing.
     */
    public static String deployCommit() {
        Path file = dataDir().resolve("..").resolve("..").resolve("DEPLOYED_COMMIT").normalize();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String commit = Files.readString(file, StandardCharsets.UTF_8).trim();
            return commit.isEmpty() ? null : commit;
        } catch (IOException e) {
            return null;
        }
    }

    /** Boots the health sweeps and the nightly run when auto-QA is on. Safe to call more than once. */
    public static synchronized void boot() {
        if (!isEnabled()) {
            return;
        }
        SentPositions.enable(true); // rc6: the "foothold (sent)" check reads what spawn/move packets told clients
        HealthSweeper.get().start();
        if (soloMapling.qa.QaSuite.isEnabled()) {
            AutoQaService.get().scheduleNightly(); // the checks drive !qa: no suite, no nightly run
            AutoQaService.get().watchRunRequests(); // tools/ops/autoqa-run: start a run without logging in
        }
        // rc6: the daily chat-quality summary, half an hour before the nightly run (09:30 UTC by default)
        int hour = YamlConfig.config.server.AUTOQA_NIGHTLY_UTC_HOUR;
        ChatQuality.scheduleDaily(hour >= 0 && hour <= 23 ? hour - 1 : 9);
        log.info("Auto-QA on: health sweeps every {} min, results in {}", HealthSweeper.get().periodMinutes(), dataDir());
    }

    /** Writes a file whole: a temp file then a move, so a reader never sees half of it. */
    static void writeAtomic(Path file, String text) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Appends one line to a file (a JSON-lines history). */
    static void appendLine(Path file, String line) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, line.replace('\n', ' ') + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
