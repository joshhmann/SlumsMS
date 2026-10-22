package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 6: the reviewable auto-tune file. */
class ConvoTuneTest {
    @TempDir
    Path dir;

    @BeforeEach
    void file() {
        ConvoTune.useFileForTest(dir.resolve("tune.json"));
    }

    @AfterEach
    void reset() {
        ConvoTune.useFileForTest(null);
    }

    @Test
    void aBadRatingKeepsTheExampleAndAQuotedPhraseJoinsDontSay() throws Exception {
        assertEquals("", ConvoTune.promptLines());
        ConvoTune.rated(false, "Certainly! I'd be glad to party.", "too formal \"certainly\"", 1);
        assertEquals("certainly", ConvoTune.violation("ok certainly lol"));
        assertNull(ConvoTune.violation("ya sure"));
        String prompt = ConvoTune.promptLines();
        assertTrue(prompt.contains("Never write lines like: \"Certainly! I'd be glad to party.\""), prompt);
        assertTrue(prompt.contains("Never say: certainly"), prompt);
        String json = Files.readString(dir.resolve("tune.json"));
        assertTrue(json.contains("\"dontSay\"") && json.contains("too formal"), json);
    }

    @Test
    void goodExamplesShowAsVoiceAndTheFileReloads() throws Exception {
        ConvoTune.rated(true, "lol ya ant tunnel is packed", "", 1);
        ConvoTune.rated(true, "omw, 2 min", "", 2);
        ConvoTune.rated(true, "brb pots", "", 3);
        String prompt = ConvoTune.promptLines();
        assertTrue(prompt.contains("\"omw, 2 min\" / \"brb pots\""), "the newest two: " + prompt);
        assertTrue(!prompt.contains("ant tunnel is packed"), prompt);
        Files.writeString(dir.resolve("tune.json"), "{\"dontSay\": [\"As An AI\"], \"badExamples\": [], \"goodExamples\": []}");
        assertTrue(ConvoTune.reload().contains("1 don't-say"));
        assertEquals("as an ai", ConvoTune.violation("lol as an ai i cant"));
    }

    @Test
    void examplesAreCapped() {
        for (int i = 0; i < 30; i++) {
            ConvoTune.rated(false, "bad line " + i, "", i);
        }
        String prompt = ConvoTune.promptLines();
        assertTrue(prompt.contains("bad line 29") && !prompt.contains("bad line 26\""), prompt);
    }
}
