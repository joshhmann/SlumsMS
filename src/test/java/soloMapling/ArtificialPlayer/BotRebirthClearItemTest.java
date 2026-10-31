package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST 10-31 07:23 (t-rb-2, run 4d3de9837): the Von Leon run handed the bot item 4001693 at 07:23:00.999 and the bot's
 * own rebirth decision crafted key 4033442 out of it at 07:23:02.308; the probe polled the bag on its own 2 s cycle and
 * saw "0 held", failing a run that had done everything right. The claim is "earned and accounted for": the item in the
 * bag, or the next key it was spent on. A key the bot already carried is not evidence of this run.
 */
class BotRebirthClearItemTest {

    @Test
    void theItemInTheBagIsEarned() {
        assertTrue(BotRebirthQa.clearItemEarned(1, 0, 0));
    }

    @Test
    void theItemSpentOnTheNextKeyIsEarned() {
        assertTrue(BotRebirthQa.clearItemEarned(0, 0, 1), "the bot crafted its key out of the hand-out: that is the item");
    }

    @Test
    void aKeyItAlreadyCarriedDoesNotExcuseAnEmptyBag() {
        assertFalse(BotRebirthQa.clearItemEarned(0, 1, 1), "nothing was earned: the key was there before the run");
    }

    @Test
    void anEmptyBagAndNoKeyIsTheTestFailure() {
        assertFalse(BotRebirthQa.clearItemEarned(0, 0, 0), "TEST 07:23 saw exactly this and has to keep failing on it");
    }
}
