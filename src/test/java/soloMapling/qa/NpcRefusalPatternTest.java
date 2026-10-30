package soloMapling.qa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 162 runfix (t-vb-8 on TEST 10-30): the NPC refusal detector the vb-* checks fail on. */
class NpcRefusalPatternTest {

    private static boolean refuses(String dialog) {
        return GameQaWorld.REFUSAL.matcher(dialog).find();
    }

    @Test
    void mrMakersLessonIsNotARefusal() {
        // Fail-before: both lines of 2110004's successful 6032 lesson were reported as NPC_REFUSAL.
        assertFalse(refuses("2110004: I will teach you the actual application of the Maker method. All you need to do"
                + " is have an item in mind to make, gather all the ingredients from the receipt and mix them in a"
                + " scientifically alchemisty way. Easy, isn't it?"));
        assertFalse(refuses("2110004: Ok, now you need to hand me a fee, 10,000 mesos that is, for that information."
                + " The collected fee shall be used for acquiring the needed materials for your learning of the fine"
                + " art of the Maker."));
    }

    @Test
    void realRefusalsStillCount() {
        assertTrue(refuses("2110004: You need to be at least level 45 to learn the Maker skill."));
        assertTrue(refuses("9000020: You need 10,000 mesos."));
        assertTrue(refuses("1012100: You don't have enough mesos."));
        assertTrue(refuses("2111000: I'm sorry, but you cannot take this class yet."));
        assertFalse(refuses("9010000: you need to choose one of these"));
    }
}
