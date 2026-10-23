package soloMapling.qa;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.StageSpots;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* rc6 (QA cv-1): a staged bot is named to the GM by its character name, its level and where it stands. */
class BotLabelTest {

    @Test
    void nameLevelAndWhereFromTheGm() {
        assertEquals("Aria (lv 34, 120 px to your right)", GameQaWorld.label("Aria", 34, 100000000, 100000000, 4747, 4867));
        assertEquals("Aria (lv 34, 95 px to your left)", GameQaWorld.label("Aria", 34, 100000000, 100000000, 4747, 4652));
        assertEquals("Aria (lv 34, right next to you)", GameQaWorld.label("Aria", 34, 100000000, 100000000, 4747, 4760));
        assertEquals("Aria (lv 34, on map 104040000)", GameQaWorld.label("Aria", 34, 100000000, 104040000, 0, 0));
    }

    @Test
    void aStagedSocialBotIsPinnedNextToTheGmUntilUnstaged() {
        assertFalse(StageSpots.pinned(1_000_000_555));
        StageSpots.pin(1_000_000_555);
        assertTrue(StageSpots.pinned(1_000_000_555));
        StageSpots.unpin(1_000_000_555);
        assertFalse(StageSpots.pinned(1_000_000_555));
    }
}
