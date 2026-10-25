package soloMapling.ArtificialPlayer.BotFlavorSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 85: gz on hunting maps. */
class LevelUpGzTest {

    @BeforeEach
    void reset() {
        LevelUpGz.resetForTest();
    }

    @Test
    void aPlayersLevelUpGetsBetweenThreeAndSixTenthsOfAGzOnAverage() {
        Random rng = new Random(5);
        for (int eligible : new int[]{2, 5}) {
            long total = 0;
            int n = 20000;
            for (int i = 0; i < n; i++) {
                total += LevelUpGz.plan(true, true, eligible, rng);
            }
            double mean = (double) total / n;
            assertTrue(mean >= 0.3 && mean <= 0.6, "eligible " + eligible + ": " + mean);
        }
    }

    @Test
    void neverMoreThanTwoAndNeverWithNobodyNearOrNobodyToHear() {
        Random rng = new Random(9);
        for (int i = 0; i < 5000; i++) {
            assertTrue(LevelUpGz.plan(true, true, 8, rng) <= 2);
            assertEquals(0, LevelUpGz.plan(true, true, 0, rng));
            assertEquals(0, LevelUpGz.plan(false, false, 4, rng), "a bot levelling with no real player to hear");
        }
    }

    @Test
    void aBotsLevelUpRarelyGetsAGzAndOnlyOne() {
        Random rng = new Random(2);
        int bursts = 0;
        for (int i = 0; i < 20000; i++) {
            int n = LevelUpGz.plan(false, true, 5, rng);
            assertTrue(n <= 1);
            bursts += n;
        }
        assertTrue(bursts < 20000 * 0.15, "bursts " + bursts);
    }

    @Test
    void aMapGetsOneBurstEveryTwentySecondsAndTheServerEightAMinute() {
        long t = 1_000_000L;
        assertTrue(LevelUpGz.claim(100000000, t));
        assertFalse(LevelUpGz.claim(100000000, t + 5_000), "same map too soon");
        int granted = 1;
        for (int map = 1; map < 30; map++) {
            if (LevelUpGz.claim(map, t + 1_000)) {
                granted++;
            }
        }
        assertEquals(LevelUpGz.MAX_PER_MINUTE, granted);
        assertTrue(LevelUpGz.claim(100000000, t + 70_000), "a minute on");
    }

    @Test
    void linesVaryAndAreShortAndNeverDiscloseWhatABotIs() {
        Random rng = new Random(1);
        Set<String> seen = new HashSet<>();
        Pattern disclosure = Pattern.compile("(?i)\\b(ai|bot|bots|robot|npc|llm|assistant|program|script)\\b");
        for (int i = 0; i < 500; i++) {
            String line = LevelUpGz.line("Josh", 42, rng);
            seen.add(line);
            assertTrue(line.length() <= 30, line);
            assertFalse(line.contains("{"), line);
            assertFalse(disclosure.matcher(line).find(), line);
        }
        assertTrue(seen.size() >= 20, "variety: " + seen.size());
    }
}
