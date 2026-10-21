package client.command;

import org.junit.jupiter.api.Test;
import tools.Pair;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2 Cosmic parity: MapleRoot emptied the GM-1 command tier (moving whatdropsfrom/whodrops to players and
 * goto to GM 2) and @buffme fell out with it. It is registered at GM level 1 again.
 */
class BuffMeRegisteredTest {

    @Test
    void buffmeIsAGmLevelOneCommandAgain() {
        List<Pair<List<String>, List<String>>> tiers = CommandsExecutor.getInstance().getGmCommands();

        assertTrue(tiers.get(1).getLeft().contains("buffme"), "GM-1 tier: " + tiers.get(1).getLeft());
        assertFalse(tiers.get(0).getLeft().contains("buffme"), "not a player command");
    }
}
