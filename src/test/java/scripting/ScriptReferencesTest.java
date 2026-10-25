package scripting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Scripts call each other by name, and the name is the file on disk: {@code cm.openNpc(9900000, "stylist")} loads
 * scripts/npc/stylist.js (NPCScriptManager.java:65) and {@code load('scripts/...')} loads that exact path. Deploys
 * run on Linux, where a name that differs in case does not match, and the miss is silent: the NPC falls back to its
 * own script (NPCScriptManager.java:141), so the hub's Stylist button reopened the hub menu forever.
 *
 * This test walks every script and asserts that each reference names a file that exists (names are compared as
 * directory entries, not through the filesystem, so a case-insensitive desktop still reports the same answer).
 */
class ScriptReferencesTest {

    /** {@code openNpc(<id or expression>, "name")} - the first argument may itself contain a call. */
    private static final Pattern OPEN_NPC = Pattern.compile("openNpc\\(\\s*(?:[^,()]|\\([^()]*\\))+?,\\s*\"([^\"]+)\"\\s*\\)");

    /** {@code load('scripts/...')} / {@code load("scripts/...")}. */
    private static final Pattern LOAD = Pattern.compile("load\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

    @Test
    void everyScriptReferenceResolvesToAScriptFile() throws IOException {
        Path root = Path.of("scripts");
        Assumptions.assumeTrue(Files.isDirectory(root), "run from the repository root");

        Set<String> files = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
        }
        Assumptions.assumeFalse(files.isEmpty(), "no scripts to scan");

        List<String> broken = new ArrayList<>();
        for (String script : files) {
            if (!script.endsWith(".js") || script.endsWith(".old.js")) {
                continue;   // *.old.js are retired copies that nothing loads
            }
            String text = Files.readString(root.resolve(script), StandardCharsets.UTF_8);

            Matcher open = OPEN_NPC.matcher(text);
            while (open.find()) {
                String target = "npc/" + open.group(1) + ".js";
                if (!files.contains(target)) {
                    broken.add(script + ": openNpc(\"" + open.group(1) + "\") - no " + target);
                }
            }

            Matcher load = LOAD.matcher(text);
            while (load.find()) {
                String target = load.group(1);
                String path = target.startsWith("scripts/") ? target.substring("scripts/".length()) : target;
                if (!files.contains(path)) {
                    broken.add(script + ": load('" + target + "') - no file");
                }
            }
        }

        assertEquals(List.of(), broken, "script references that name no file:\n" + String.join("\n", broken));
    }
}
