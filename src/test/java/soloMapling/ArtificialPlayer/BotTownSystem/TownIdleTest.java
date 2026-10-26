package soloMapling.ArtificialPlayer.BotTownSystem;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST bot watch 2026-10-26 (ItsBucc, Henesys channel 1, a player there): a town bot stood on one pixel for 10+ min.
 * Its fidget was off (an AFK camp turns it off; an errand or a chat on arrival skips the settle that turns it on) and
 * nothing turned it back on. The town idle beat does, on watched maps only, and adds a stroll or an emote every few
 * minutes.
 */
class TownIdleTest {

    private static final long MIN = 60_000L;

    @Test
    void anUnwatchedTownCostsNothingAndDoesNothing() {
        TownIdle.Beat beat = new TownIdle.Beat();
        Random rng = new Random(1);
        for (long t = 0; t <= 30 * MIN; t += 3_000) {
            assertEquals(TownIdle.Act.NONE, TownIdle.decide(false, false, false, t, beat, rng));
        }
        assertEquals(0L, beat.nextAt, "no clock is even started");
    }

    @Test
    void aWatchedIdlerWithItsFidgetOffFidgetsAgain() {
        // the ItsBucc case: after the AFK camp (or an errand / a chat on arrival) fidget was off for good
        assertEquals(TownIdle.Act.FIDGET, TownIdle.decide(true, false, false, 0, new TownIdle.Beat(), new Random(2)));
    }

    @Test
    void aBusyBotIsLeftAlone() {
        // walking, talking, AFK, seated or minding a shop
        assertEquals(TownIdle.Act.NONE, TownIdle.decide(true, true, false, 0, new TownIdle.Beat(), new Random(3)));
    }

    @Test
    void everyTwoToFiveMinutesItStrollsEmotesOrJustStands() {
        TownIdle.Beat beat = new TownIdle.Beat();
        Random rng = new Random(4);
        Map<TownIdle.Act, Integer> seen = new EnumMap<>(TownIdle.Act.class);
        long lastBeat = 0;
        int beats = 0;
        for (long t = 0; t <= 600 * MIN; t += 3_000) { // a macro tick every 3 s for 10 h, fidgeting all along
            long due = beat.nextAt;
            TownIdle.Act act = TownIdle.decide(true, false, true, t, beat, rng);
            if (due != 0 && t >= due) {
                beats++;
                if (lastBeat > 0) {
                    long gap = t - lastBeat;
                    assertTrue(gap >= TownIdle.BEAT_MIN_MS && gap <= TownIdle.BEAT_MAX_MS + 3_000, "gap " + gap);
                }
                lastBeat = t;
                seen.merge(act, 1, Integer::sum);
            } else {
                assertEquals(TownIdle.Act.NONE, act, "between beats it only fidgets");
            }
        }
        assertTrue(beats > 100, "a beat every few minutes: " + beats);
        assertTrue(seen.getOrDefault(TownIdle.Act.STROLL, 0) > beats / 3, "strolls: " + seen);
        assertTrue(seen.getOrDefault(TownIdle.Act.EMOTE, 0) > beats / 6, "emotes: " + seen);
    }

    @Test
    void theFirstBeatWaitsUntilItHasStoodAWhile() {
        TownIdle.Beat beat = new TownIdle.Beat();
        assertEquals(TownIdle.Act.NONE, TownIdle.decide(true, false, true, 1_000, beat, new Random(5)));
        assertTrue(beat.nextAt >= 1_000 + TownIdle.BEAT_MIN_MS);
        beat.reset();
        assertEquals(0L, beat.nextAt, "a new town stop starts a new clock");
    }
}
