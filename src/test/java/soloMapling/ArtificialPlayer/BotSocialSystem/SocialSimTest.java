package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class SocialSimTest {

    @AfterEach
    void restore() {
        SocialSim.reset();
    }

    @Test
    void testClockIsDrivenByHandAndResetRestoresWallClock() {
        AtomicLong t = new AtomicLong(1_000);
        SocialSim.useForTest(t::get, 7);
        assertEquals(1_000, SocialSim.now());
        t.addAndGet(86_400_000L);
        assertEquals(86_401_000L, SocialSim.now());
        SocialSim.reset();
        long before = System.currentTimeMillis();
        assertTrue(SocialSim.now() >= before);
    }

    @Test
    void seededStreamIsReproducibleAndSeparatePerBotAndPurpose() {
        SocialSim.useForTest(() -> 0, 42);
        long a1 = SocialSim.random(1, "pick").nextLong();
        long a2 = SocialSim.random(1, "pick").nextLong();
        SocialSim.useForTest(() -> 0, 42);
        assertEquals(a1, SocialSim.random(1, "pick").nextLong());
        assertEquals(a2, SocialSim.random(1, "pick").nextLong());
        assertNotEquals(a1, a2);

        SocialSim.useForTest(() -> 0, 42);
        // another bot's draws, or another purpose, do not move bot 1's "pick" stream
        SocialSim.random(2, "pick").nextLong();
        SocialSim.random(1, "other").nextLong();
        assertEquals(a1, SocialSim.random(1, "pick").nextLong());

        assertNotEquals(SocialSim.random(2, "pick").nextLong(), SocialSim.random(3, "pick").nextLong());
    }

    @Test
    void differentSeedsGiveDifferentStreams() {
        SocialSim.useForTest(() -> 0, 1);
        long one = SocialSim.random(5, "x").nextLong();
        SocialSim.useForTest(() -> 0, 2);
        assertNotEquals(one, SocialSim.random(5, "x").nextLong());
    }

    @Test
    void unseededReturnsTheThreadLocalSource() {
        assertSame(java.util.concurrent.ThreadLocalRandom.current(), SocialSim.random(1, "x"));
    }
}
