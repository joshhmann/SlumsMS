package soloMapling.autoqa;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 166, item 4: the release allow-list shrinks one id at a time. A client-only id leaves
 * {@code autoqa-release-skips.tsv} only in the commit that marks its script {@code ready} (i.e. the script passed on
 * TEST), because from then on a release run has its verdict to read. So: no allow-listed id may have a ready script,
 * and a ready script must cover the release id it proves (a t-id the catalogue knows) - otherwise the id could never
 * leave the list, or the release run would read nothing.
 */
class AutoQaReleaseScriptsTest {

    /** The scripts are repo files next to the server (maven runs with the project directory as the working dir). */
    static final Path SCRIPTS = Path.of("qa-scripts");

    static List<Path> scripts() throws Exception {
        assertTrue(Files.isDirectory(SCRIPTS), SCRIPTS + " is here");
        try (Stream<Path> list = Files.list(SCRIPTS)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Path script) throws Exception {
        try (var reader = new YamlReader(Files.newBufferedReader(script, StandardCharsets.UTF_8))) {
            Object o = reader.read();
            assertTrue(o instanceof Map, script + " is a YAML map");
            return (Map<String, Object>) o;
        }
    }

    static List<String> covers(Map<String, Object> doc) {
        List<String> out = new ArrayList<>();
        if (doc.get("covers") instanceof List<?> list) {
            for (Object id : list) {
                out.add(String.valueOf(id));
            }
        }
        return out;
    }

    @Test
    void noAllowListedIdHasAReadyScript() throws Exception {
        Map<String, String> allow = AutoQaRelease.allowList();
        List<String> clashes = new ArrayList<>();
        for (Path f : scripts()) {
            Map<String, Object> doc = read(f);
            if (!"ready".equals(String.valueOf(doc.get("status")))) {
                continue;
            }
            for (String id : covers(doc)) {
                if (allow.containsKey(id)) {
                    clashes.add(id + " (" + f.getFileName() + " is ready)");
                }
            }
        }
        assertEquals(List.of(), clashes, "an allow-listed id with a ready script must leave "
                + AutoQaRelease.RESOURCE + " in the same commit: the release run reads its verdict now");
    }

    @Test
    void everyReleaseIdAScriptCoversIsACatalogueCheck() throws Exception {
        List<String> unknown = new ArrayList<>();
        for (Path f : scripts()) {
            for (String id : covers(read(f))) {
                if (id.startsWith("t-") && AutoQaCatalog.find(id) == null) {
                    unknown.add(id + " (" + f.getFileName() + ")");
                }
            }
        }
        assertEquals(List.of(), unknown, "a covers t-id must name a check the report has");
    }

    @Test
    void aReadyScriptCoversTheReleaseIdItProves() throws Exception {
        List<String> silent = new ArrayList<>();
        for (Path f : scripts()) {
            Map<String, Object> doc = read(f);
            if (!"ready".equals(String.valueOf(doc.get("status")))) {
                continue;
            }
            long ids = covers(doc).stream().filter(id -> id.startsWith("t-")).count();
            if (ids == 0) {
                silent.add(f.getFileName().toString());
            }
            assertNotNull(doc.get("id"), f + " names its id");
        }
        assertEquals(List.of(), silent, "a ready script carries the t-id its verdict goes under");
    }
}
