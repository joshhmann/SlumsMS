package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 4: realistic human timing. */
class HumanTimingTest {

    private static final ConvoPersona CHATTY = new ConvoPersona(ConvoPersona.Voice.LOWER, 0, 1, 0, 0, List.of("lol"));
    private static final HumanTiming.Context TALK = new HumanTiming.Context(false, false, false, false);

    @Test
    void typingIsOneToSixSecondsByLength() {
        Random rng = new Random(1);
        for (int i = 0; i < 500; i++) {
            long shortLine = HumanTiming.typingMs("ya", 0, rng);
            long longLine = HumanTiming.typingMs("x".repeat(68), 0, rng);
            assertTrue(shortLine >= 1000 && shortLine <= 6000, "" + shortLine);
            assertTrue(longLine >= 4000 && longLine <= 6000, "" + longLine);
        }
    }

    @Test
    void neverFasterThanAPersonCouldType() {
        Random rng = new Random(2);
        for (int len = 1; len <= 70; len++) {
            String line = "x".repeat(len);
            for (long already : new long[]{0, 500, 2000, 5000}) {
                long wait = HumanTiming.typingMs(line, already, rng);
                assertTrue(already + wait >= HumanTiming.humanFloorMs(len), len + " chars after " + already + " ms: " + wait);
            }
        }
    }

    @Test
    void theBrainsTimeCountsTowardTheDelay() {
        Random rng = new Random(3);
        assertTrue(HumanTiming.typingMs("lol ya", 5000, rng) < 1000, "a slow model leaves only the floor to wait");
    }

    @Test
    void sometimesSplitLateOrBrbButNeverForAnActionOrAWhisper() {
        Random rng = new Random(4);
        String line = "ya i was there yesterday, the mobs were insane tho lol";
        int split = 0, late = 0, brb = 0;
        for (int i = 0; i < 4000; i++) {
            HumanTiming.Plan p = HumanTiming.plan(line, 0, CHATTY, TALK, rng);
            switch (p.kind()) {
                case "split" -> {
                    split++;
                    assertEquals(2, p.parts().size());
                }
                case "late" -> {
                    late++;
                    assertTrue(p.totalMs() >= 15_000 && p.totalMs() <= 60_000, "" + p.totalMs());
                }
                case "brb" -> {
                    brb++;
                    assertEquals("brb", p.parts().get(0).text());
                    assertTrue(p.parts().get(1).delayMs() >= 20_000);
                }
                default -> {
                }
            }
            for (HumanTiming.Context strict : new HumanTiming.Context[]{new HumanTiming.Context(true, false, false, false),
                    new HumanTiming.Context(false, true, false, false)}) {
                HumanTiming.Plan q = HumanTiming.plan(line, 0, CHATTY, strict, rng);
                assertEquals("typed", q.kind());
            }
        }
        assertTrue(split > 300 && late > 60 && brb > 20, split + " " + late + " " + brb);
    }

    @Test
    void aFightingBotSometimesIgnoresAFollowUpButNeverTheFirstLine() {
        Random rng = new Random(5);
        int ignored = 0;
        for (int i = 0; i < 2000; i++) {
            if (HumanTiming.plan("lol ya", 0, CHATTY, new HumanTiming.Context(false, false, true, false), rng).ignored()) {
                ignored++;
            }
            assertFalse(HumanTiming.plan("lol ya", 0, CHATTY, new HumanTiming.Context(false, false, true, true), rng).ignored());
        }
        assertTrue(ignored > 200 && ignored < 400, "" + ignored);
    }

    @Test
    void splitsAtANaturalBreak() {
        int at = HumanTiming.splitPoint("ya i was there yesterday, the mobs were insane");
        assertEquals("ya i was there yesterday,", "ya i was there yesterday, the mobs were insane".substring(0, at));
        assertEquals(0, HumanTiming.splitPoint("nobreakhereatallinthisline"));
    }
}
