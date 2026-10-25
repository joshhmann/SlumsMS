package client.command;

import org.junit.jupiter.api.Test;
import tools.Pair;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 1.5.1 security (ticket 68 review): MapleRoot's @autologin set the login result to OK whenever the account last used
 * from the same IP had autologin on, so any account name with any password (even a perm-banned one) logged in, and
 * the character list skipped the PIC. The feature is removed; these keep it from coming back.
 */
class AutoLoginRemovedTest {

    @Test
    void autologinIsNotACommandAtAnyLevel() {
        for (Pair<List<String>, List<String>> tier : CommandsExecutor.getInstance().getGmCommands()) {
            assertFalse(tier.getLeft().contains("autologin"), "autologin registered: " + tier.getLeft());
        }
    }

    @Test
    void noLoginOrPicDecisionReadsTheAutologinFlag() throws Exception {
        for (String file : List.of("src/main/java/net/server/handlers/login/LoginPasswordHandler.java",
                "src/main/java/tools/PacketCreator.java")) {
            String code = Files.readString(Path.of(file));
            assertFalse(code.contains("autolog >") || code.contains("autolog>"), file + " still branches on autolog");
        }
    }
}
