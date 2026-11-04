package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.SOCIAL_BOT;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.TOWN_WANDERER_BOT;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.TRAINING_BOT;

/**
 * Before 1.6.4 a roster bot with the brain on resumed as a training bot whatever it had been doing, so a bot the
 * brain had moved to a social or town mode was put back to grinding by the next restart.
 */
class BotRosterResumeChoiceTest {

    @Test
    void aSwitchedModeIsResumedWhenTheModeSwitchIsOn() {
        assertEquals(SOCIAL_BOT, BotRoster.resumeChoice(true, true, SOCIAL_BOT));
        assertEquals(TOWN_WANDERER_BOT, BotRoster.resumeChoice(true, true, TOWN_WANDERER_BOT));
    }

    @Test
    void aFreshSessionWithTheBrainOnStartsAsTraining() {
        assertEquals(TRAINING_BOT, BotRoster.resumeChoice(true, true, null));
    }

    @Test
    void withTheModeSwitchOffTheBrainStillForcesTraining() {
        assertEquals(TRAINING_BOT, BotRoster.resumeChoice(true, false, SOCIAL_BOT));
    }

    @Test
    void withoutTheBrainTheRecordedTypeStandsOrTheCallersChoiceDoes() {
        assertEquals(SOCIAL_BOT, BotRoster.resumeChoice(false, true, SOCIAL_BOT));
        assertNull(BotRoster.resumeChoice(false, true, null));
    }
}
