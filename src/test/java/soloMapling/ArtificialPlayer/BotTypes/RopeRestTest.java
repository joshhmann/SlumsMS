package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* rc6 (QA t-sp-1): a break asked to hang on a rope does, whenever the map has a safe rope band. */
class RopeRestTest {

    @Test
    void anAskedRopeRestHangsWhenThereIsARope() {
        assertTrue(GrindBreakRoutine.hangsOnRope(true, true, 0.99), "asked: always, whatever the roll");
        assertFalse(GrindBreakRoutine.hangsOnRope(false, true, 0.0), "no safe rope: a ground rest");
        assertTrue(GrindBreakRoutine.hangsOnRope(true, false, 0.1), "unasked: the usual 35% chance");
        assertFalse(GrindBreakRoutine.hangsOnRope(true, false, 0.9));
    }
}
