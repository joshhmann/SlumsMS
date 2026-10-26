package client.command;

import client.command.commands.gm0.WebLoginCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebLoginRegisteredTest {
    @Test
    void registeredAtRank0() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertEquals(WebLoginCommand.class, ex.classForTest("weblogin"));
        assertEquals(0, ex.rankForTest("weblogin"));
    }
}
