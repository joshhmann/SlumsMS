package scripting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import scripting.field.FieldScriptMethods;
import scripting.map.MapScriptMethods;
import scripting.npc.NPCConversationManager;
import scripting.portal.PortalPlayerInteraction;
import scripting.quest.QuestActionManager;
import scripting.reactor.ReactorActionManager;
import server.Marriage;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every script is handed one scripting object ({@code cm}, {@code qm}, {@code pi}, {@code ms}, {@code eim}, {@code rm},
 * ...) and calls it by name. Graal resolves those calls at run time, so a method this server does not have only blows up
 * when a player reaches that branch - usually after the script has already taken items or mesos. {@link
 * ScriptEvaluationTest} only proves the files parse; this test proves the calls land on something.
 * <p>
 * The receiver of a call is decided the same way the server decides it, not by the name alone: the managers are injected
 * as globals by their script manager (see {@code NPCScriptManager}, {@code QuestScriptManager},
 * {@code ReactorScriptManager}, {@code EventScriptManager}) or passed in as the first parameter ({@code start(ms)} in map
 * scripts, {@code enter(pi)} in portal scripts, {@code setup(eim)} in event scripts). Locals and parameters that shadow
 * a name are skipped, and comments and string literals are stripped first, so prose like "// cm.sendNext" or a
 * {@code ms} local holding a map is not mistaken for a call.
 */
public class ScriptApiCoverageTest {
    /**
     * Calls this lane could not resolve, with the evidence that is missing. Keyed by {@code <script path>#<method>}.
     * Keep this empty of anything that can be fixed: a parked call still throws for the player who reaches it.
     */
    private static final Map<String, String> PARKED_CALLS = Map.of(
            "scripts/item/use_damage_skin2.js#getValue",
            "item scripts are started without the item that was used (ItemScriptManager.runItemScript only passes the "
                    + "WZ ScriptedItem: npc/script/runOnPickup), and nothing in this fork models the WZ spec/value the "
                    + "stock ItemScriptMethods.getValue() returned; the same script also calls client.DamageSkin."
                    + "getDamageSkinId, which does not exist here either");

    private static final Map<String, Map<String, List<Class<?>>>> GLOBAL_RECEIVERS = Map.of(
            "npc", Map.of("cm", List.of(NPCConversationManager.class), "im", List.of(NPCConversationManager.class)),
            "item", Map.of("im", List.of(NPCConversationManager.class), "cm", List.of(NPCConversationManager.class)),
            "quest", Map.of("qm", List.of(QuestActionManager.class), "cm", List.of(NPCConversationManager.class)),
            // em is injected by EventScriptManager; eim arrives as the parameter of setup(eim), or as a Marriage
            // built by em.newMarriage(...), which is what the wedding scripts call initializeGiftItems() on.
            "event", Map.of("em", List.of(EventManager.class),
                    "eim", List.of(EventInstanceManager.class, Marriage.class)),
            "reactor", Map.of("rm", List.of(ReactorActionManager.class)));

    private static final Pattern DECLARED_NAME = Pattern.compile("\\b(?:var|let|const)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern FUNCTION = Pattern.compile("function\\s*[A-Za-z_$\\w]*\\s*\\(([^)]*)\\)");
    private static final Pattern RECEIVER_PARAMETER = Pattern.compile(
            "function\\s+(?:start|enter|attach|setup)\\s*\\(\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)\\s*\\.\\s*([A-Za-z_$][\\w$]*)\\s*\\(");

    private record CallSite(String scriptPath, int line, String receiver, String method) {
        @Override
        public String toString() {
            return scriptPath + ":" + line + " calls " + receiver + "." + method + "(...)";
        }
    }

    private static List<String> allScriptFilePaths() throws IOException {
        try (Stream<Path> pathStream = Files.walk(Path.of("scripts"))) {
            return pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".js") && !path.toString().endsWith(".old.js"))
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> scriptKind(path) != null)
                    .sorted()
                    .toList();
        }
    }

    private static String scriptKind(String scriptPath) {
        if (!scriptPath.startsWith("scripts/")) {
            return null;
        }
        String[] parts = scriptPath.split("/");
        if (parts.length < 3) {
            return null;
        }
        return switch (parts[1]) {
            case "npc", "item", "quest", "event", "reactor", "portal", "map" -> parts[1];
            default -> null;
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allScriptFilePaths")
    void callsOnlyMethodsThatExist(String scriptPath) throws IOException {
        List<CallSite> dangling = danglingCalls(scriptPath).stream()
                .filter(call -> !PARKED_CALLS.containsKey(call.scriptPath() + "#" + call.method()))
                .toList();

        assertTrue(dangling.isEmpty(), () -> scriptPath + " calls methods this server does not have, which throws for "
                + "the player who reaches them:\n  " + String.join("\n  ", dangling.stream().map(Object::toString).toList()));
    }

    @Test
    void walksTheWholeScriptTree() throws IOException {
        List<String> scriptPaths = allScriptFilePaths();

        assertTrue(scriptPaths.size() > 500, "expected the shipped script tree, found " + scriptPaths.size() + " scripts");
    }

    @Test
    void parkedCallsAreStillThere() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String parked : PARKED_CALLS.keySet()) {
            String scriptPath = parked.substring(0, parked.indexOf('#'));
            String method = parked.substring(parked.indexOf('#') + 1);
            boolean stillDangling = danglingCalls(scriptPath).stream().anyMatch(call -> call.method().equals(method));
            if (!stillDangling) {
                stale.add(parked + " is no longer a dangling call; drop it from PARKED_CALLS");
            }
        }

        assertTrue(stale.isEmpty(), String.join("\n", stale));
    }

    private static List<CallSite> danglingCalls(String scriptPath) throws IOException {
        String source = stripComments(Files.readString(Path.of(scriptPath)));
        Map<String, List<Class<?>>> receivers = receivers(scriptPath, source);
        Set<String> shadowed = declaredNames(source);
        String receiverParameter = firstMatch(RECEIVER_PARAMETER, source);

        List<CallSite> dangling = new ArrayList<>();
        Matcher matcher = CALL.matcher(source);
        int scannedTo = 0;
        int line = 1;
        while (matcher.find()) {
            for (int i = scannedTo; i < matcher.start(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            scannedTo = matcher.start();

            String receiver = matcher.group(1);
            String method = matcher.group(2);
            List<Class<?>> receiverTypes = receivers.get(receiver);
            if (receiverTypes == null) {
                continue;
            }
            if (shadowed.contains(receiver) && !receiver.equals(receiverParameter)) {
                continue;
            }
            boolean exists = receiverTypes.stream().anyMatch(type -> publicMethodNames(type).contains(method));
            if (!exists) {
                dangling.add(new CallSite(scriptPath, line, receiver, method));
            }
        }

        return dangling;
    }

    /**
     * The object a name refers to in this kind of script: injected globals by script manager, plus the interaction the
     * manager passes in as the first argument.
     */
    private static Map<String, List<Class<?>>> receivers(String scriptPath, String source) {
        String kind = scriptKind(scriptPath);
        Map<String, List<Class<?>>> receivers = new HashMap<>();
        if (kind == null) {
            return receivers;
        }
        receivers.putAll(GLOBAL_RECEIVERS.getOrDefault(kind, Map.of()));

        String receiverParameter = firstMatch(RECEIVER_PARAMETER, source);
        if (receiverParameter != null) {
            switch (kind) {
                case "portal" -> receivers.put(receiverParameter, List.of(PortalPlayerInteraction.class));
                case "map" -> receivers.put(receiverParameter, List.of(
                        scriptPath.startsWith("scripts/map/field/") ? FieldScriptMethods.class : MapScriptMethods.class));
                case "event" -> receivers.put(receiverParameter, List.of(EventInstanceManager.class, Marriage.class));
                default -> {
                }
            }
        }

        return receivers;
    }

    private static Set<String> declaredNames(String source) {
        Set<String> names = new HashSet<>();
        Matcher declarations = DECLARED_NAME.matcher(source);
        while (declarations.find()) {
            names.add(declarations.group(1));
        }
        Matcher functions = FUNCTION.matcher(source);
        while (functions.find()) {
            for (String parameter : functions.group(1).split(",")) {
                String name = parameter.strip();
                if (!name.isEmpty() && name.matches("[A-Za-z_$][\\w$]*")) {
                    names.add(name);
                }
            }
        }

        return names;
    }

    private static final Map<Class<?>, Set<String>> METHOD_NAMES = new HashMap<>();

    private static Set<String> publicMethodNames(Class<?> type) {
        return METHOD_NAMES.computeIfAbsent(type, ignored -> {
            Set<String> names = new HashSet<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                        names.add(method.getName());
                    }
                }
            }
            return names;
        });
    }

    private static String firstMatch(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);

        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Blanks out {@code //} and {@code /* *}{@code /} comments while keeping every newline, so reported line numbers stay
     * the real ones. Quoted text is left alone, otherwise an apostrophe in prose would swallow the rest of the file.
     */
    private static String stripComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '"' || ch == '\'' || ch == '`') {
                stripped.append(ch);
                index++;
                while (index < source.length()) {
                    char quoted = source.charAt(index);
                    stripped.append(quoted);
                    index++;
                    if (quoted == '\\' && index < source.length()) {
                        stripped.append(source.charAt(index));
                        index++;
                    } else if (quoted == ch) {
                        break;
                    }
                }
            } else if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                end = end < 0 ? source.length() : end + 2;
                for (int i = index; i < end; i++) {
                    stripped.append(source.charAt(i) == '\n' ? '\n' : ' ');
                }
                index = end;
            } else if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    stripped.append(' ');
                    index++;
                }
            } else {
                stripped.append(ch);
                index++;
            }
        }

        return stripped.toString();
    }
}
