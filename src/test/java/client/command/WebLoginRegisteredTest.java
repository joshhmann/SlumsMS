package client.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class WebLoginRegisteredTest {
    @Test
    void notRegisteredSinceTheOwnerDroppedIt() {
        CommandsExecutor ex = CommandsExecutor.freshForTest();
        assertNull(ex.classForTest("weblogin"), "1.6.0 signs in with the game username and password (ticket 105)");
    }
}
