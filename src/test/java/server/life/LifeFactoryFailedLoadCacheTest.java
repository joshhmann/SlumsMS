package server.life;

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
import soloMapling.WzTestData;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 3c094c453): a mob id with no (or a broken) Mob.wz entry, such as a
 * stale drop_data dropper, was parsed again and logged "[SEVERE] MOB n failed to load" with a stack trace on every
 * getMonster call. It now fails once: one log line, then null straight away.
 */
class LifeFactoryFailedLoadCacheTest {

    private static final int NO_SUCH_MOB = 9_999_997;

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("lifefactory-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final Capture capture = new Capture();
    private Logger logger;
    private Level level;

    @BeforeEach
    void attach() {
        logger = (Logger) LogManager.getLogger(LifeFactory.class);
        level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.INFO);   // the test config turns the root logger off
        capture.start();
        logger.addAppender(capture);
    }

    @AfterEach
    void detach() {
        logger.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(logger.getName(), level);
    }

    @Test
    void aMissingMobIsLoggedOnceAndThenSkipped() {
        WzTestData.assumePresent("Mob.wz");
        WzTestData.assumePresent("String.wz");
        assertNull(LifeFactory.getMonster(NO_SUCH_MOB));
        assertNull(LifeFactory.getMonster(NO_SUCH_MOB));
        assertNull(LifeFactory.getMonster(NO_SUCH_MOB));

        long failures = capture.lines.stream().filter(l -> l.contains("MOB " + NO_SUCH_MOB + " failed to load")).count();
        assertEquals(1, failures);
    }
}
