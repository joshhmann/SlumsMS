package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.6.5 b344d (QA, TEST 7 Nov): the ZPQ watcher's "stage N" waits matched a KPQ run's stage lines on another channel.
 * A stage line now names its run's content and leader; the text before the tag is unchanged.
 */
class RunLineTagTest {

    @Test
    void aStageLineNamesItsRunAndLeader() {
        assertEquals("stage 2 cleared in 40 s (0 wrong tries shown) [zakumpq, leader AqClient]",
                MechanicsRuns.tagged("stage 2 cleared in 40 s (0 wrong tries shown)", "zakumpq", "AqClient"));
        assertEquals("stage 2: 105 = miner [zakumpq]", MechanicsRuns.tagged("stage 2: 105 = miner", "zakumpq", null));
    }

    @Test
    void otherLinesPassThrough() {
        assertEquals("zakumpq cleared in 900 s; prizes, bonus and out",
                MechanicsRuns.tagged("zakumpq cleared in 900 s; prizes, bonus and out", "zakumpq", "AqClient"));
    }
}
