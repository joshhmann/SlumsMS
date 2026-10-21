package client.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaSuite;
import soloMapling.server.AttackCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** !qa exists only on the test server: -Dslums.qa=true or attack capture. Live's unit passes neither. */
class QaCommandGatingTest {

    @AfterEach
    void clear() {
        System.clearProperty(QaSuite.PROPERTY);
    }

    @Test
    void notRegisteredWithoutTheTestFlags() {
        System.clearProperty(QaSuite.PROPERTY);
        assertFalse(AttackCapture.isEnabled(), "attack capture is off in the test JVM");
        assertFalse(QaSuite.isEnabled());
        assertFalse(CommandsExecutor.freshForTest().hasCommand("qa"));
    }

    @Test
    void registeredWithTheQaFlag() {
        System.setProperty(QaSuite.PROPERTY, "true");
        assertTrue(QaSuite.isEnabled());
        assertTrue(CommandsExecutor.freshForTest().hasCommand("qa"));
        assertEquals(3, rankOf("qa"));
    }

    private static int rankOf(String name) {
        return CommandsExecutor.freshForTest().rankForTest(name);
    }
}
