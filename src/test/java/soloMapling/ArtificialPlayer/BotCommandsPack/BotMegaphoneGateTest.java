package soloMapling.ArtificialPlayer.BotCommandsPack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotMegaphoneGateTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.botMegaphoneGapSeconds");
        BotMegaphoneGate.useClockForTest(null);
    }

    @Test
    void oneMegaphonePerGapServerWide() {
        BotMegaphoneGate.useClockForTest(now::get);
        assertTrue(BotMegaphoneGate.tryAcquire());
        assertFalse(BotMegaphoneGate.tryAcquire());
        now.addAndGet(44_000);
        assertFalse(BotMegaphoneGate.tryAcquire());
        now.addAndGet(1_000);
        assertTrue(BotMegaphoneGate.tryAcquire());
    }

    @Test
    void gapIsTunable() {
        System.setProperty("solomapling.botMegaphoneGapSeconds", "10");
        BotMegaphoneGate.useClockForTest(now::get);
        assertTrue(BotMegaphoneGate.tryAcquire());
        now.addAndGet(10_000);
        assertTrue(BotMegaphoneGate.tryAcquire());
    }
}
