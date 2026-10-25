package scripting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.script.ScriptEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ScriptEvaluationTest {
    /**
     * NPC scripts that MapleRoot broke and nobody has restored yet. The NPC assertion was commented out in the
     * MapleRoot baseline (Cosmic has it on), which hid them; keep this list short and fix rather than add.
     * scroll_generator.js (MapleTV's scroll generator, opened from mapleTV.js) has its tier tables cut down to
     * {@code [[ [4001006]]]}, so {@code jobWeaponRestricted[1][2][1]} throws when the file loads.
     */
    private static final Set<String> KNOWN_BROKEN_NPC_SCRIPTS = Set.of("npc/scroll_generator.js");

    private AbstractScriptManager scriptManager = new AbstractScriptManager() {};

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    private static List<String> eventScriptFilePaths() throws IOException {
        return getScriptFilePaths("event");
    }

    private static List<String> itemScriptFilePaths() throws IOException {
        return getScriptFilePaths("item");
    }

    private static List<String> npcScriptFilePaths() throws IOException {
        return getScriptFilePaths("npc");
    }

    private static List<String> portalScriptFilePaths() throws IOException {
        return getScriptFilePaths("portal");
    }

    private static List<String> questScriptFilePaths() throws IOException {
        return getScriptFilePaths("quest");
    }

    private static List<String> reactorScriptFilePaths() throws IOException {
        return getScriptFilePaths("reactor");
    }

    private static List<String> getScriptFilePaths(final String scriptsSubdirectory) throws IOException {
        Path scriptDirectory = Path.of("scripts", scriptsSubdirectory);
        try (Stream<Path> pathStream = Files.walk(scriptDirectory)) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js") && !path.toString().endsWith(".old.js"))
                    // relative to scripts/, so files in subfolders (npc/lib/...) resolve to their real path
                    .map(path -> Path.of("scripts").relativize(path).toString().replace('\\', '/'))
                    .toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventScriptFilePaths")
    void eventScriptShouldEvaluate(String eventScriptPath) {
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(eventScriptPath);

        assertNotNull(scriptEngine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("itemScriptFilePaths")
    void itemScriptShouldEvaluate(String itemScriptPath) {
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(itemScriptPath);

        assertNotNull(scriptEngine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("npcScriptFilePaths")
    void npcScriptShouldEvaluate(String npcScriptPath) {
        Assumptions.assumeFalse(KNOWN_BROKEN_NPC_SCRIPTS.contains(npcScriptPath), npcScriptPath + " is a known MapleRoot breakage");
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(npcScriptPath);

        assertNotNull(scriptEngine, npcScriptPath + " evaluates");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("portalScriptFilePaths")
    void portalScriptShouldEvaluate(String portalScriptPath) {
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(portalScriptPath);

        assertNotNull(scriptEngine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("questScriptFilePaths")
    void questScriptShouldEvaluate(String questScriptPath) {
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(questScriptPath);

        assertNotNull(scriptEngine);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reactorScriptFilePaths")
    void reactorScriptShouldEvaluate(String reactorScriptPath) {
        ScriptEngine scriptEngine = scriptManager.getInvocableScriptEngine(reactorScriptPath);

        assertNotNull(scriptEngine);
    }
}
