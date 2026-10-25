package scripting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Static checks over every NPC script, for two kinds of damage the MapleRoot repack did to stock Cosmic scripts
 * (1.5.1): a script emptied to 0 bytes (NPC 2007 threw on click and the dialog never closed), and a paid menu option
 * whose reward line was deleted (Spiegelmann took 40 Maple Coins and gave nothing).
 */
class NpcScriptIntegrityTest {

    private static final Path NPC = Path.of("scripts", "npc");

    /** An NPC conversation needs an entry point: NPCScriptManager calls start(), then action(). */
    private static final Pattern ENTRY_POINT = Pattern.compile("function\\s+(start|action)\\s*\\(");

    /** {@code if (... cm.canHold(...) ...)}, the "can the player take the reward" guard of a trade. */
    private static final Pattern CAN_HOLD_GUARD = Pattern.compile("if\\s*\\(([^{;]*cm\\.canHold\\([^{;]*)\\)\\s*\\{");
    private static final Pattern TAKES = Pattern.compile("gainItem\\([^,;]+,\\s*-|gainMeso\\(\\s*-");
    /** {@code gainItem(id, n)} with n not negative, {@code gainItem(id)}, or a positive {@code gainMeso}. */
    private static final Pattern GIVES = Pattern.compile("gainItem\\([^,;]+,\\s*[^-\\s]|gainItem\\(\\s*[^,()]+\\)|gainMeso\\(\\s*[^-\\s]");

    private static List<Path> npcScripts() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(NPC), "run from the repository root");
        try (Stream<Path> files = Files.list(NPC)) {   // top level only: npc/lib and npc/interactions are libraries
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".js") && !p.toString().endsWith(".old.js"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void everyNpcScriptHasAConversationEntryPoint() throws IOException {
        List<String> broken = new ArrayList<>();
        for (Path script : npcScripts()) {
            String text = Files.readString(script, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                broken.add(script.getFileName() + ": empty file");
            } else if (!ENTRY_POINT.matcher(text).find()) {
                broken.add(script.getFileName() + ": no start() or action()");
            }
        }
        assertEquals(List.of(), broken, "NPC scripts that throw when clicked:\n" + String.join("\n", broken));
    }

    @Test
    void everyCanHoldGuardedTradeThatTakesSomethingAlsoGivesSomething() throws IOException {
        List<String> broken = new ArrayList<>();
        int guards = 0;
        for (Path script : npcScripts()) {
            String text = Files.readString(script, StandardCharsets.UTF_8);
            Matcher guard = CAN_HOLD_GUARD.matcher(text);
            while (guard.find()) {
                guards++;
                String body = block(text, guard.end());
                if (TAKES.matcher(body).find() && !GIVES.matcher(body).find()) {
                    int line = (int) text.substring(0, guard.start()).chars().filter(c -> c == '\n').count() + 1;
                    broken.add(script.getFileName() + ":" + line + ": takes items/mesos but gives nothing");
                }
            }
        }
        Assumptions.assumeTrue(guards > 0, "no trades found");
        assertEquals(List.of(), broken, "paid NPC options with no reward:\n" + String.join("\n", broken));
    }

    /** The body of the block whose opening brace ends just before {@code from}. */
    private static String block(String text, int from) {
        int depth = 1;
        int i = from;
        while (depth > 0 && i < text.length()) {
            char c = text.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return text.substring(from, Math.max(from, i - 1));
    }
}
