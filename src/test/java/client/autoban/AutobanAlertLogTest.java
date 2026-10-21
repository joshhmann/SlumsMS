package client.autoban;

import config.YamlConfig;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.2 Cosmic parity: an autoban alert writes its log line again when USE_AUTOBAN_LOG is on (the repack had
 * commented it out), independently of USE_AUTOBAN, which only gates the in-game GM tip.
 */
class AutobanAlertLogTest {

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("autoban-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final boolean useAutoban = YamlConfig.config.server.USE_AUTOBAN;
    private final boolean useAutobanLog = YamlConfig.config.server.USE_AUTOBAN_LOG;
    private final Capture capture = new Capture();
    private Logger logger;
    private Level level;

    @BeforeEach
    void attach() {
        YamlConfig.config.server.USE_AUTOBAN = false; // no GM broadcast (needs a running Server)
        logger = (Logger) LogManager.getLogger(AutobanFactory.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.INFO); // the test config turns the root logger off
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detach() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
        YamlConfig.config.server.USE_AUTOBAN = useAutoban;
        YamlConfig.config.server.USE_AUTOBAN_LOG = useAutobanLog;
    }

    @Test
    void anAlertIsLoggedWhenAutobanLoggingIsOn() {
        YamlConfig.config.server.USE_AUTOBAN_LOG = true;
        AutobanFactory.DAMAGE_HACK.alert(null, "DMG: 999999 MaxDMG: 10");

        assertEquals(List.of("Autoban alert - chr  caused DAMAGE_HACK-DMG: 999999 MaxDMG: 10"), capture.lines);
    }

    @Test
    void nothingIsLoggedWhenAutobanLoggingIsOff() {
        YamlConfig.config.server.USE_AUTOBAN_LOG = false;
        AutobanFactory.DAMAGE_HACK.alert(null, "DMG: 999999 MaxDMG: 10");

        assertTrue(capture.lines.isEmpty());
    }
}
