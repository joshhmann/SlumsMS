package scripting;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owner decision 2026-11-08: the rebirth helper's "change to a previous rebirth class" swap is gone. Its
 * jobChangeChecker flag was always 0, so the branch was dead, and the method behind it swapped only the job id at the
 * character's current level - a level-10 rebirth could jump straight back to a 4th job.
 */
class RebirthPreviousClassSwapRemovedTest {

    private static final String REMOVED = "changeRebirthJobCerezeth";

    @Test
    void noScriptCallsThePreviousClassSwap() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("scripts"))) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                if (!p.toString().endsWith(".js")) continue;
                for (String line : Files.readAllLines(p)) {
                    if (line.contains(REMOVED)) offenders.add(p + ": " + line.strip());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "scripts still call " + REMOVED + ": " + offenders);
    }

    @Test
    void theScriptTypeDeclarationsNoLongerDeclareIt() throws IOException {
        Path lib = Path.of("scripts", "lib.d.ts");
        assertFalse(Files.readString(lib).contains(REMOVED), "scripts/lib.d.ts still declares " + REMOVED);
    }

    @Test
    void theServerMethodsAreRemoved() {
        assertTrue(declares(NPCConversationManager.class).isEmpty(),
                "NPCConversationManager still has " + REMOVED);
        assertTrue(declares(Character.class).isEmpty(), "Character still has " + REMOVED);
    }

    private static List<String> declares(Class<?> type) {
        List<String> found = new ArrayList<>();
        for (var m : type.getDeclaredMethods()) {
            if (m.getName().equals(REMOVED)) found.add(type.getSimpleName() + "." + m.getName());
        }
        return found;
    }
}
