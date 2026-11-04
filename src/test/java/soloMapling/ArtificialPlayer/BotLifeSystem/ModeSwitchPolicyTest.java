package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Inputs;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The mode policy is a pure function of needs, blend, time in mode and two rolls. */
class ModeSwitchPolicyTest {

    private static final long HOUR = 3_600_000L;
    private static final long DWELL = ModeSwitch.MIN_DWELL_MS;

    /** A bot an hour into its mode with the given needs and blend. */
    private static Inputs in(Mode mode, double social, double progress, double fatigue, double boredom,
                             double grinder, double socialite, double explorer, double socialPull) {
        return new Inputs(mode, HOUR, DWELL, 0, social, progress, fatigue, boredom, grinder, socialite, explorer,
                socialPull);
    }

    @Test
    void aLonelyTiredSocializerLeavesTrainingForASocialMode() {
        Inputs lonely = in(Mode.TRAINING, 0.9, 0.0, 0.7, 0.6, 0.1, 0.5, 0.1, 0.6);
        assertEquals(Mode.SOCIAL, ModeSwitch.decide(lonely, 0.01, 0.5));
    }

    @Test
    void aProgressionGoalHoldsALonelyBotAtItsWork() {
        Inputs driven = in(Mode.TRAINING, 0.9, 0.95, 0.7, 0.6, 0.1, 0.5, 0.1, 0.6);
        assertNull(ModeSwitch.decide(driven, 0.0, 0.0));
    }

    @Test
    void aGrindersBlendHoldsItLongerThanASocialitesDoes() {
        Inputs socialite = in(Mode.TRAINING, 0.7, 0.2, 0.4, 0.4, 0.1, 0.6, 0.1, 0.5);
        Inputs grinder = in(Mode.TRAINING, 0.7, 0.2, 0.4, 0.4, 0.8, 0.05, 0.1, 0.0);
        assertEquals(Mode.SOCIAL, ModeSwitch.decide(socialite, 0.0, 0.5));
        assertNull(ModeSwitch.decide(grinder, 0.0, 0.5));
    }

    @Test
    void anExplorerWithLittleSocialPullGoesToTheTownMode() {
        Inputs explorer = in(Mode.TRAINING, 0.9, 0.0, 0.8, 0.8, 0.0, 0.2, 0.6, 0.2);
        assertEquals(Mode.TOWN, ModeSwitch.decide(explorer, 0.01, 0.5));
    }

    @Test
    void theRollDecidesWhenNotWhether() {
        Inputs lonely = in(Mode.TRAINING, 0.9, 0.0, 0.7, 0.6, 0.1, 0.5, 0.1, 0.6);
        assertEquals(Mode.SOCIAL, ModeSwitch.decide(lonely, 0.49, 0.5));
        assertNull(ModeSwitch.decide(lonely, 0.5, 0.5), "never above the 50% cap, however strong the pull");
    }

    @Test
    void aSocialBotWithAGoalAndNoLonelinessGoesBackToTraining() {
        Inputs busy = in(Mode.SOCIAL, 0.05, 0.9, 0.2, 0.5, 0.6, 0.2, 0.1, 0.2);
        assertEquals(Mode.TRAINING, ModeSwitch.decide(busy, 0.01, 0.9));
        Inputs busyTown = in(Mode.TOWN, 0.05, 0.9, 0.2, 0.5, 0.6, 0.2, 0.1, 0.2);
        assertEquals(Mode.TRAINING, ModeSwitch.decide(busyTown, 0.01, 0.9));
    }

    @Test
    void aContentSocialBotStays() {
        Inputs content = in(Mode.SOCIAL, 0.4, 0.1, 0.2, 0.3, 0.2, 0.6, 0.1, 0.6);
        assertNull(ModeSwitch.decide(content, 0.0, 0.0));
    }

    @Test
    void aBoredExplorerMovesBetweenTheTwoLightModes() {
        Inputs boredSocial = in(Mode.SOCIAL, 0.3, 0.0, 0.2, 0.9, 0.1, 0.2, 0.6, 0.1);
        assertEquals(Mode.TOWN, ModeSwitch.decide(boredSocial, 0.9, 0.1));
        Inputs lonelyTown = in(Mode.TOWN, 0.8, 0.0, 0.2, 0.2, 0.1, 0.5, 0.1, 0.6);
        assertEquals(Mode.SOCIAL, ModeSwitch.decide(lonelyTown, 0.9, 0.1));
        assertNull(ModeSwitch.decide(lonelyTown, 0.9, 0.9), "the second roll gates the light-to-light move");
    }

    @Test
    void aBotNeverSwitchesBeforeItsMinimumStayOrPastTheHourlyCap() {
        Inputs lonely = in(Mode.TRAINING, 0.9, 0.0, 0.7, 0.6, 0.1, 0.5, 0.1, 0.6);
        Inputs justArrived = new Inputs(Mode.TRAINING, DWELL - 1, DWELL, 0, lonely.social(), lonely.progress(),
                lonely.fatigue(), lonely.boredom(), lonely.grinder(), lonely.socialite(), lonely.explorer(),
                lonely.socialPull());
        assertNull(ModeSwitch.decide(justArrived, 0.0, 0.0));
        Inputs capped = new Inputs(Mode.TRAINING, HOUR, DWELL, ModeSwitch.MAX_PER_HOUR, lonely.social(),
                lonely.progress(), lonely.fatigue(), lonely.boredom(), lonely.grinder(), lonely.socialite(),
                lonely.explorer(), lonely.socialPull());
        assertNull(ModeSwitch.decide(capped, 0.0, 0.0));
    }

    @Test
    void theMinimumStayIsStablePerBotAndSpreadAcrossBots() {
        assertEquals(ModeSwitch.dwellFor(1_000_000_123), ModeSwitch.dwellFor(1_000_000_123));
        long min = Long.MAX_VALUE, max = 0;
        for (int i = 0; i < 500; i++) {
            long d = ModeSwitch.dwellFor(1_000_000_000 + i);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        org.junit.jupiter.api.Assertions.assertTrue(min >= (long) (DWELL * 0.75), "floor 0.75x: " + min);
        org.junit.jupiter.api.Assertions.assertTrue(max <= (long) (DWELL * 1.5), "ceiling 1.5x: " + max);
        org.junit.jupiter.api.Assertions.assertTrue(max - min > DWELL / 4, "bots must not share one stay");
    }
}
