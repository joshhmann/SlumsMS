package client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.1: !tp x y (same-map move for the client's NPC screenshot pass) is a GM 3 command. */
class TpRegisteredTest {
    @Test
    void itIsRegisteredForGmLevel3() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(client.command.commands.gm3.TpCommand.class, ex.classForTest("tp"));
        assertEquals(3, ex.rankForTest("tp"));
    }
}
