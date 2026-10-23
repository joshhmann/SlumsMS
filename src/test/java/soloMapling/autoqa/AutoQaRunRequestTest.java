package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** tools/ops/autoqa-run drops a request file; the service takes the selector and deletes the file. */
class AutoQaRunRequestTest {

    @TempDir
    Path dir;

    @Test
    void theFirstLineIsTheSelectorAndTheFileIsConsumed() throws Exception {
        Path f = dir.resolve(AutoQaService.REQUEST_FILE);
        Files.writeString(f, "\n  smoke  \nall\n");
        assertEquals("smoke", AutoQaService.takeRequest(f));
        assertFalse(Files.exists(f));
        assertNull(AutoQaService.takeRequest(f), "nothing left to run");
    }

    @Test
    void anEmptyRequestStartsNothing() throws Exception {
        Path f = dir.resolve(AutoQaService.REQUEST_FILE);
        Files.writeString(f, "   \n");
        assertNull(AutoQaService.takeRequest(f));
        assertFalse(Files.exists(f));
    }
}
