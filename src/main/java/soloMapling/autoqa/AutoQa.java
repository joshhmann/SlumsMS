package soloMapling.autoqa;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;

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

    /** The same rule for a set of -D properties and the config value (tests). */
    static boolean enabledFor(Function<String, String> property, boolean configOn) {
        return "true".equalsIgnoreCase(property.apply(PROPERTY)) || configOn;
    }

    /** Where every auto-QA file goes: {@code data/autoqa} under the working directory ({@code -Dslums.autoqa.dir}). */
    public static Path dataDir() {
        return Path.of(System.getProperty("slums.autoqa.dir", "data/autoqa"));
    }

    /** Boots the health sweeps and the nightly run when auto-QA is on. Safe to call more than once. */
    public static synchronized void boot() {
        if (!isEnabled()) {
            return;
        }
        HealthSweeper.get().start();
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
