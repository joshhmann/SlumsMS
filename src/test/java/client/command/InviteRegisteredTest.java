package client.command;

import client.command.commands.gm3.InviteCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 105 (1.6.1): @invite is a player command (rank 0); the GM branch is chosen inside by GM level. */
class InviteRegisteredTest {
    @Test
    void inviteIsRegisteredForEveryone() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(InviteCommand.class, ex.classForTest("invite"));
        assertEquals(0, ex.rankForTest("invite"));
    }
}
