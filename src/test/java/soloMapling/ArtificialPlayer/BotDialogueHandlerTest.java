package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-19: a bot whose dialogue pack was never written fails quietly instead of spraying a stack trace. */
class BotDialogueHandlerTest {

    private static final String PACK_DIR = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/";

    @Test
    void missingPackReturnsNullWithoutStackTrace() {
        for (String pack : new String[]{"OPQBotDialogue.yaml", "GachaBotDialogue.yaml"}) {
            assertFalse(new File(PACK_DIR + pack).isFile(),
                    pack + " exists again, so this test no longer covers the missing-pack path");

            String captured = readCaptureErr(pack);

            assertFalse(captured.contains("FileNotFoundException"),
                    "missing pack " + pack + " printed a stack trace to stderr: " + captured);
            assertFalse(captured.contains("BotDialogueHandler.readDialogueYaml"),
                    "missing pack " + pack + " printed a BotDialogueHandler frame to stderr: " + captured);
        }
    }

    @Test
    void presentPackStillResolves() {
        Map<String, Object> node = BotDialogueHandler.readDialogueYaml("SocialBotDialogue.yaml", "SocialBot",
                "CongratsLevelUp");
        assertNotNull(node, "SocialBotDialogue.yaml must still parse");
        assertTrue(node.containsKey("text"), "node keys: " + node.keySet());
    }

    private static String readCaptureErr(String pack) {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            assertNull(BotDialogueHandler.readDialogueYaml(pack, "OPQBot", "Unused"),
                    pack + " must yield no dialogue");
        } finally {
            System.setErr(originalErr);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
