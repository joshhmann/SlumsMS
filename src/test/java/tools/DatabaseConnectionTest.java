package tools;

import config.ServerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseConnectionTest {

    @Test
    void thePoolWaitsLongEnoughForABotSaveToFinish() {
        // b269 hard-coded a 10 s connection timeout to make a wedged pool fail loudly. At the 3k livelock the bot
        // share's waits then turned into refusals and lost writes, so the timeout is a config choice now, and a
        // config that says nothing gets Hikari's own 30 s rather than the 10 s a server would silently inherit.
        assertEquals(30_000, new ServerConfig().DB_CONNECTION_TIMEOUT_MS,
                "a config that does not mention the key keeps 30 s");
        assertAll(
                () -> assertEquals(30_000, DatabaseConnection.connectionTimeoutMs(0), "0 means unset"),
                () -> assertEquals(30_000, DatabaseConnection.connectionTimeoutMs(-5), "and so does a negative"),
                () -> assertEquals(12_000, DatabaseConnection.connectionTimeoutMs(12_000), "a value is the value"),
                () -> assertEquals(250, DatabaseConnection.connectionTimeoutMs(1),
                        "floored: Hikari needs a positive wait, and 1 ms is not one"));
    }
}
