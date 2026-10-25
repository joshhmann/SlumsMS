package scripting;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 (ticket 102): Monster Carnival is back to stock: 2+ players, CPQ1 levels 30-50, CPQ2 levels 51-70. */
class CpqStockLimitsTest {

    private static String script(int id) throws Exception {
        return Files.readString(Path.of("scripts/npc/" + id + ".js"));
    }

    @Test
    void carnival1SpiegelmannsWantTwoPlayersAndLevels30To50() throws Exception {
        for (int id : new int[]{2042000, 2042001, 2042002}) {
            String js = script(id);
            assertTrue(js.contains("var cpqMinLvl = 30;"), id + " min level");
            assertTrue(js.contains("var cpqMaxLvl = 50;"), id + " max level");
            assertTrue(js.contains("var cpqMinAmt = 2;"), id + " min players");
            assertTrue(js.contains("cm.getLevel() < 51)"), id + " entry level gate");
            assertFalse(js.contains("party.size() < 1)"), id + " hard-coded solo");
            assertFalse(js.contains("USE_ENABLE_SOLO_EXPEDITIONS"), id + " solo flag (it is on in config.yaml) must not open the carnival to one player");
            assertFalse(js.contains("if (party >= 1)"), id + " a list compared to a number never fires");
            assertTrue(js.contains("party.size() < cpqMinAmt"), id + " enforces the minimum at the lobby");
        }
    }

    @Test
    void carnival2SpiegelmannWantsTwoPlayersAndLevels51To70() throws Exception {
        String js = script(2042005);
        assertTrue(js.contains("var cpqMinLvl = 51;"));
        assertTrue(js.contains("var cpqMaxLvl = 70;"));
        assertTrue(js.contains("var cpqMinAmt = 2;"));
        assertFalse(js.contains("party.size() < 1)"));
        assertFalse(js.contains("USE_ENABLE_SOLO_EXPEDITIONS"));
        assertFalse(js.contains("if (party >= 1)"));
        assertTrue(js.contains("party.size() < cpqMinAmt"));
    }
}
