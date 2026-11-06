package client;

import config.YamlConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b353: five wrong PINs or PICs are the limit, but the limit only dropped the account from the online list - the
 * socket stayed open and every further guess was still checked, so the limit did not stop guessing. Now the sixth
 * attempt closes the connection and is refused, right or wrong.
 */
class PinPicAttemptLimitTest {

    private static final String SECRET = "135790";

    private final boolean enablePin = YamlConfig.config.server.ENABLE_PIN;
    private final boolean enablePic = YamlConfig.config.server.ENABLE_PIC;

    @BeforeEach
    void enable() {
        YamlConfig.config.server.ENABLE_PIN = true;
        YamlConfig.config.server.ENABLE_PIC = true;
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.ENABLE_PIN = enablePin;
        YamlConfig.config.server.ENABLE_PIC = enablePic;
    }

    private static Client client(EmbeddedChannel socket, String field) throws Exception {
        Client c = Client.createLoginClient(353, "127.0.0.1", null, 0, 0);
        c.useChannelForTest(socket);
        Field f = Client.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(c, SECRET);
        return c;
    }

    private static boolean check(Client c, String kind, String guess) {
        return kind.equals("pin") ? c.checkPin(guess) : c.checkPic(guess);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pin", "pic"})
    void theSixthGuessClosesTheConnectionAndIsRefusedEvenWhenRight(String kind) throws Exception {
        EmbeddedChannel socket = new EmbeddedChannel();
        Client c = client(socket, kind);
        for (int i = 0; i < 5; i++) {
            assertFalse(check(c, kind, "000000"));
        }
        assertTrue(socket.isOpen(), "five wrong guesses are allowed");

        assertFalse(check(c, kind, SECRET), "a guess past the limit is never checked");
        assertFalse(socket.isOpen(), "the limit closes the connection");
    }
}
