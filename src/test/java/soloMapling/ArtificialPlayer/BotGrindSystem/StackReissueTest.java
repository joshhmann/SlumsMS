package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** StackStrategy must use the same "only skip while still moving" rule as RoamStrategy (the driver drops stalled walks). */
class StackReissueTest {
    @Test
    void stackApproachUsesTheMovingAwareRule() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/StackStrategy.java"));
        assertTrue(src.contains("RoamStrategy.alreadyHeading(tx, b.lastMoveTargetX, GCMovement.isMoving(chr))"));
        assertFalse(src.contains("Math.abs(tx - b.lastMoveTargetX)"));
    }
}
