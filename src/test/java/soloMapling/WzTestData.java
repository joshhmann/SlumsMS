package soloMapling;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests that read the real WZ data (ticket 26): the data is not in the repo, so a checkout without {@code wz/}
 * skips them - reported as skipped, never failed. The directory is {@code -Dwz-path}, else {@code wz}.
 */
public final class WzTestData {

    private WzTestData() {
    }

    public static Path dir() {
        return Path.of(System.getProperty("wz-path", "wz"));
    }

    /** Skips the calling test unless {@code wz/<wzFile>} (e.g. "Map.wz") is there. */
    public static void assumePresent(String wzFile) {
        Assumptions.assumeTrue(Files.isDirectory(dir().resolve(wzFile)), "needs wz/" + wzFile + ", not in this checkout");
    }
}
