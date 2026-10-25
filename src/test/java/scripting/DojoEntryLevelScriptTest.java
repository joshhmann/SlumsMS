package scripting;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.2: Dojo Instructor (2091005) let any level in (the gate read reborns >= 0, always true); stock needs level 25. */
class DojoEntryLevelScriptTest {
    @Test
    void entryNeedsLevelTwentyFive() throws Exception {
        String src = Files.readString(Path.of("scripts/npc/2091005.js"));
        assertTrue(src.contains("} else if (cm.getPlayer().getLevel() >= 25) {"));
        assertFalse(src.contains("getReborns() >= 0"));
    }
}
