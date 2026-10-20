package soloMapling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ticket 26: a WZ-dependent test is skipped, not failed, when the data is missing. */
class WzTestDataTest {

    @Test
    void missingWzDataSkipsAndPresentDataRuns(@TempDir Path root) throws Exception {
        String before = System.getProperty("wz-path");
        try {
            System.setProperty("wz-path", root.resolve("wz").toString());
            assertThrows(TestAbortedException.class, () -> WzTestData.assumePresent("Map.wz"));
            Files.createDirectories(root.resolve("wz/Map.wz"));
            assertDoesNotThrow(() -> WzTestData.assumePresent("Map.wz"));
        } finally {
            if (before == null) {
                System.clearProperty("wz-path");
            } else {
                System.setProperty("wz-path", before);
            }
        }
    }
}
