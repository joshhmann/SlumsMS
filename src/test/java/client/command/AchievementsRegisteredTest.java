package client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 93 (1.6.1): @achievements is for everyone, !achievement for GM 3. */
class AchievementsRegisteredTest {
    @Test
    void theCommandsAreRegistered() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(client.command.commands.gm0.AchievementsCommand.class, ex.classForTest("achievements"));
        assertEquals(0, ex.rankForTest("achievements"));
        assertEquals(client.command.commands.gm3.AchievementCommand.class, ex.classForTest("achievement"));
        assertEquals(3, ex.rankForTest("achievement"));
    }
}
