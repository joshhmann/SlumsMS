package soloMapling.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The QA clock: default 1.0, a clamp, and the rounding every scaled wait depends on. */
class QaTimingTest {

    @AfterEach
    void clear() {
        System.clearProperty(QaTiming.PROPERTY);
        QaTiming.endRun();
    }

    @Test
    void defaultsToOneSoNothingChangesWithoutTheProperty() {
        assertEquals(1.0, QaTiming.scale());
        assertEquals(60_000, QaTiming.ms(60_000));
        assertEquals(60, QaTiming.seconds(60));
        assertFalse(QaTiming.fast());
        assertEquals("1.0x", QaTiming.label());
        assertEquals("", QaTiming.note());
    }

    @Test
    void theFastLaneScalesAndRounds() {
        System.setProperty(QaTiming.PROPERTY, "0.4");
        assertEquals(0.4, QaTiming.scale());
        assertEquals(400, QaTiming.ms(1000));
        assertEquals(24_000, QaTiming.ms(60_000));
        assertEquals(2, QaTiming.seconds(5)); // 2.0 exact
        assertEquals(1, QaTiming.seconds(3)); // 1.2 rounds to 1, never to 0
        assertTrue(QaTiming.fast());
        assertEquals("0.4x", QaTiming.label());
        assertTrue(QaTiming.note().contains("0.4"));
    }

    @Test
    void aScaledWaitIsNeverNegative() {
        System.setProperty(QaTiming.PROPERTY, "0.1");
        assertEquals(0, QaTiming.ms(-5_000));
        assertEquals(0, QaTiming.seconds(0));
        assertEquals(1, QaTiming.seconds(1));
    }

    @Test
    void clampsToTheUsefulRange() {
        System.setProperty(QaTiming.PROPERTY, "0.01");
        assertEquals(QaTiming.MIN_SCALE, QaTiming.scale());
        System.setProperty(QaTiming.PROPERTY, "-3");
        assertEquals(QaTiming.MIN_SCALE, QaTiming.scale());
        System.setProperty(QaTiming.PROPERTY, "0");
        assertEquals(QaTiming.MIN_SCALE, QaTiming.scale());
        System.setProperty(QaTiming.PROPERTY, "7");
        assertEquals(1.0, QaTiming.scale());
        assertFalse(QaTiming.fast());
    }

    @Test
    void anUnreadableValueIsOne() {
        for (String bad : new String[]{"fast", "", "   ", "0.4x", "NaN"}) {
            System.setProperty(QaTiming.PROPERTY, bad);
            assertEquals(1.0, QaTiming.scale(), bad);
        }
    }

    @Test
    void aRunCanPinTheClockAndReleaseIt() {
        System.setProperty(QaTiming.PROPERTY, "1.0");
        QaTiming.beginRun(0.4);
        assertEquals(0.4, QaTiming.scale()); // the run wins over the property the JVM was started with
        assertEquals(400, QaTiming.ms(1000));
        QaTiming.endRun();
        assertEquals(1.0, QaTiming.scale());
    }
}
