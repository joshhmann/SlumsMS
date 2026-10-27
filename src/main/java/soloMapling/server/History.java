package soloMapling.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * History is kept, not deleted (owner 2026-10-27: "the point of this live data being stored is to see the history").
 * Every day-file or table that holds history has a keep knob whose default is forever, and day files older than
 * {@link #DEFAULT_GZIP_DAYS} days are gzipped in place ({@code day.json} becomes {@code day.json.gz}) so a year of them
 * stays small. Knobs, all JVM properties:
 * <pre>
 *   -Dslums.history.gzipDays=30         default for every source; 0 or less = never compress
 *   -Dslums.history.&lt;name&gt;GzipDays=N    one source (names are the {@code name} arguments in the callers)
 *   -Dslums.history.&lt;name&gt;KeepDays=N    delete after N days; 0 or less (the default) = keep forever
 * </pre>
 * Readers go through {@link #readString}, {@link #readLines}, {@link #exists} so a compressed day reads like a plain one.
 * Scratch and caches (avatar pictures, graph caches, temp files) are not history and keep their own pruning.
 */
public final class History {

    private static final Logger log = LoggerFactory.getLogger(History.class);

    public static final int DEFAULT_GZIP_DAYS = 30;
    private static final String PREFIX = "slums.history.";
    private static final Pattern DAY = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private History() {
    }

    /** Days a source keeps its history; 0 means forever (the default). Negative values count as forever too. */
    public static int keepDays(String name) {
        return Math.max(0, Integer.getInteger(PREFIX + name + "KeepDays", 0));
    }

    /** Days after which a source's day files are gzipped; 0 means never. */
    public static int gzipDays(String name) {
        int all = Integer.getInteger(PREFIX + "gzipDays", DEFAULT_GZIP_DAYS);
        return Math.max(0, Integer.getInteger(PREFIX + name + "GzipDays", all));
    }

    /** What one sweep did. */
    public record Result(int gzipped, int deleted) {
    }

    /**
     * One pass over {@code dir}: day files ({@code namePattern} matched against the file name without a {@code .gz};
     * the first date in the name is the day) older than {@code gzipDays} are gzipped in place, and with
     * {@code keepDays > 0} the ones older than that are deleted. Today and future days are never touched. Failures are
     * logged at debug and skipped.
     */
    public static Result sweep(Path dir, Pattern namePattern, LocalDate today, int gzipDays, int keepDays) {
        int zipped = 0, deleted = 0;
        if (dir == null || !Files.isDirectory(dir) || (gzipDays <= 0 && keepDays <= 0)) {
            return new Result(0, 0);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                boolean gz = n.endsWith(".gz");
                String plain = gz ? n.substring(0, n.length() - 3) : n;
                if (!namePattern.matcher(plain).matches() || !Files.isRegularFile(p)) {
                    continue;
                }
                LocalDate day = dayOf(plain);
                if (day == null) {
                    continue;
                }
                try {
                    if (keepDays > 0 && day.isBefore(today.minusDays(keepDays))) {
                        Files.deleteIfExists(p);
                        deleted++;
                    } else if (!gz && gzipDays > 0 && day.isBefore(today.minusDays(gzipDays))) {
                        gzip(p);
                        zipped++;
                    }
                } catch (IOException e) {
                    log.debug("history: could not sweep {}", p, e);
                }
            }
        } catch (IOException e) {
            log.debug("history: could not list {}", dir, e);
        }
        if (zipped > 0 || deleted > 0) {
            log.info("[History] {}: {} day files gzipped, {} deleted", dir, zipped, deleted);
        }
        return new Result(zipped, deleted);
    }

    /** {@link #sweep} for today in UTC. */
    public static Result sweep(Path dir, Pattern namePattern, int gzipDays, int keepDays) {
        return sweep(dir, namePattern, LocalDate.now(ZoneOffset.UTC), gzipDays, keepDays);
    }

    static LocalDate dayOf(String fileName) {
        Matcher m = DAY.matcher(fileName);
        if (!m.find()) {
            return null;
        }
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Writes {@code file.gz} next to the file (through a temp name) and removes the original. */
    public static void gzip(Path file) throws IOException {
        Path gz = file.resolveSibling(file.getFileName() + ".gz");
        Path tmp = file.resolveSibling(file.getFileName() + ".gz.tmp");
        try (InputStream in = Files.newInputStream(file); OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            in.transferTo(out);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, gz, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(file);
    }

    /** True when the file or its gzipped twin exists. */
    public static boolean exists(Path file) {
        return Files.isRegularFile(file) || Files.isRegularFile(gzOf(file));
    }

    /** The plain file's text, else its gzipped twin's; IOException when neither exists. */
    public static String readString(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gzOf(file)))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The lines of the plain file, else of its gzipped twin. */
    public static List<String> readLines(Path file) throws IOException {
        return readString(file).lines().toList();
    }

    private static Path gzOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".gz");
    }
}
