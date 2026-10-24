package soloMapling.ArtificialPlayer.BotCoupleSystem;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * 1.5.0 rc6 (QA t-cu-1 / t-cu-2: "not really seeing any behavior"). A watched pair stays together: one walks over to
 * the other, they smile, talk, and a couple sits on its chair side by side. A staged !qa pair does it within the first
 * minute: its gates are seconds, not the live 2-20 minutes, and cp-1 steps every 20 s.
 */
class CoupleTogetherTest {

    @Test
    void apartMeansMoreThanAStepOrAnotherPlatform() {
        assertFalse(BotCouples.apart(new Point(100, 300), new Point(160, 300)), "side by side");
        assertTrue(BotCouples.apart(new Point(100, 300), new Point(230, 300)), "a few steps apart");
        assertTrue(BotCouples.apart(new Point(100, 300), new Point(110, 180)), "another platform");
    }

    @Test
    void theSecondStandsBesideTheFirstOnItsOwnSide() {
        assertEquals(new Point(76, 300), BotCouples.besideSpot(new Point(100, 300), new Point(-200, 300)));
        assertEquals(new Point(124, 300), BotCouples.besideSpot(new Point(100, 300), new Point(400, 280)));
    }

    @Test
    void aStagedPairUsesShortGapsAndALiveOneKeepsItsPace() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/BotCouples.java"));
        assertTrue(src.contains("boolean qaPair = c.staged() && QaLog.on(QaLog.Topic.COUPLE);"), "only staged pairs");
        assertTrue(src.contains("together(c, a, b, now, qaPair);"));
        assertTrue(src.contains("TimeUnit.MINUTES.toMillis((long) prop(\"mentionMinutes\", 20))"), "live: as before");
        assertTrue(src.contains("apart(a.getPosition(), b.getPosition()) // side by side first"), "sit side by side");
    }
}
