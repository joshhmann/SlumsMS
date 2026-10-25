package scripting;

import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every server script must load in the server's own script engine. 1.5.2: a regex literal in the Job Master
 * (9010003.js) made it "not coded" in game, because this GraalJS build has no regex engine.
 */
class AllScriptsLoadTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    @Test
    void everyNpcQuestEventAndPortalScriptLoads() throws IOException {
        Loader loader = new Loader();
        List<String> broken = new ArrayList<>();
        int loaded = 0;
        for (String dir : new String[]{"npc", "quest", "event", "portal", "reactor", "item", "map"}) {
            Path root = Path.of("scripts", dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".js") && !p.toString().endsWith(".old.js")).sorted().toList()) {
                    String rel = Path.of("scripts").relativize(f).toString().replace('\\', '/');
                    if (loader.load(rel) == null) {
                        broken.add(rel);
                    } else {
                        loaded++;
                    }
                }
            }
        }
        assertTrue(broken.isEmpty(), broken.size() + " of " + (loaded + broken.size()) + " scripts do not load: " + broken);
    }
}
