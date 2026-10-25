package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A carried pass is never in the rerun list, so a --failed run that narrowed its carries by the rerun list
     * reported "carried 0" and left the carried entries unlabelled: TEST 9d5a16def, run all --failed, 63 checks,
     * two passes carried silently. The selector decides what is carried, not the rerun subset.
     */
    @Test
    void everyPassTheSelectorCoversIsCarriedEvenThoughItIsNotRerun() {
        List<Check> catalogue = AutoQaCatalog.all();
        Map<String, Map<String, Object>> reported = new LinkedHashMap<>();
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("status", "PASS");
        pass.put("build", "9d5a16def");
        reported.put("t-bg-1", pass);
        reported.put("t-af-1", new LinkedHashMap<>(pass));

        Map<String, Map<String, Object>> all = AutoQaService.carriedFor(catalogue, "all", reported);
        assertEquals(2, all.size(), "both passes are carried when the whole suite is asked for");
        Map<String, Map<String, Object>> af = AutoQaService.carriedFor(catalogue, "af", reported);
        assertEquals(1, af.size(), "a selector narrows the carries to its own checks");
        assertTrue(af.containsKey("t-af-1"));
        assertTrue(AutoQaService.carriedFor(catalogue, "bg", reported).containsKey("t-bg-1"));
        assertTrue(AutoQaService.carriedFor(catalogue, "sm", reported).isEmpty(), "no pass reported: nothing carried");
    }
}
