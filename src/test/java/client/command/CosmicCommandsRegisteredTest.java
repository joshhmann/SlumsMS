package client.command;

import org.junit.jupiter.api.Test;
import tools.Pair;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stock Cosmic's GM-1 commands goto, whodrops, whatdropsfrom, mobhp and bosshp were reported missing (the 1.4.0
 * social-systems research and the 1.4.1 Cosmic diff compared file paths). They are all registered: MapleRoot moved
 * the drop lookups and the HP readouts to players (@whodrops/@wd, @whatdropsfrom/@wdf, @mobhp, @bosshp, which GMs
 * have too) and !goto to GM 2. This keeps them from going missing for real.
 */
class CosmicCommandsRegisteredTest {

    @Test
    void theDropAndHpLookupsArePlayerCommands() {
        List<String> players = CommandsExecutor.getInstance().getGmCommands().get(0).getLeft();
        for (String name : List.of("whodrops", "wd", "whatdropsfrom", "wdf", "mobhp", "bosshp")) {
            assertTrue(players.contains(name), name + " in " + players);
        }
    }

    @Test
    void gotoIsAGmLevelTwoCommand() {
        List<Pair<List<String>, List<String>>> tiers = CommandsExecutor.getInstance().getGmCommands();
        assertTrue(tiers.get(2).getLeft().contains("goto"), "GM-2 tier: " + tiers.get(2).getLeft());
    }
}
