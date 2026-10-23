package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotChannels;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage (t-ch-1): the probe still wanted ch5 empty after the owner gave it a share. */
class ChannelVerdictTest {

    static final double[] SHARES = BotChannels.shares("100,55,35,20,15", 5);

    @Test
    void theRunsActualSplitPasses() {
        assertNull(GameAutoQaWorld.channelVerdict(new int[]{278, 153, 101, 57, 42}, SHARES));
    }

    @Test
    void ch5BusierThanCh4Fails() {
        assertNotNull(GameAutoQaWorld.channelVerdict(new int[]{278, 153, 101, 42, 57}, SHARES));
    }

    @Test
    void anEmptyChannelOrAMissingOneFails() {
        assertTrue(GameAutoQaWorld.channelVerdict(new int[]{300, 0, 101, 57, 42}, SHARES).contains("ch2 has no bots"));
        assertTrue(GameAutoQaWorld.channelVerdict(new int[]{300, 153, 101, 57}, SHARES).contains("want 5"));
    }

    @Test
    void farOffItsShareFails() {
        assertNotNull(GameAutoQaWorld.channelVerdict(new int[]{400, 100, 90, 30, 11}, SHARES));
    }
}
