package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews;
import soloMapling.ArtificialPlayer.BotConvo.BotTalk;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decision, 1.6.4: the daily-life brain, bot talk and crews are on without any {@code -D} flag; a flag set to
 * {@code false} is the rollback. Before this, all three needed {@code =true} and a build with no flags ran none of
 * the social layer.
 */
class SocialFlagsDefaultOnTest {

    private static final String[] KEYS = {
            "slums.botlife.enabled", "slums.botlife.execute", "slums.bottalk", "slums.crews", "slums.socialacts"};
    private final String[] saved = new String[KEYS.length];

    @BeforeEach
    void clearFlags() {
        for (int i = 0; i < KEYS.length; i++) {
            saved[i] = System.getProperty(KEYS[i]);
            System.clearProperty(KEYS[i]);
        }
    }

    @AfterEach
    void restoreFlags() {
        for (int i = 0; i < KEYS.length; i++) {
            if (saved[i] == null) {
                System.clearProperty(KEYS[i]);
            } else {
                System.setProperty(KEYS[i], saved[i]);
            }
        }
    }

    @Test
    void brainTalkAndCrewsAreOnWithNoFlags() {
        assertTrue(LifeRuntime.enabled());
        assertTrue(LifeRuntime.executeOn());
        assertTrue(LifeRuntime.reachesAllBots());
        assertTrue(BotTalk.on());
        assertTrue(BotCrews.on());
        assertTrue(SocialActions.enabled());
    }

    @Test
    void falseTurnsEachOneOff() {
        System.setProperty("slums.botlife.enabled", "false");
        assertFalse(LifeRuntime.enabled());
        assertFalse(LifeRuntime.reachesAllBots());
        System.clearProperty("slums.botlife.enabled");

        System.setProperty("slums.botlife.execute", "false");
        assertFalse(LifeRuntime.enabled());
        assertFalse(LifeRuntime.executeOn());
        System.clearProperty("slums.botlife.execute");

        System.setProperty("slums.bottalk", "false");
        assertFalse(BotTalk.on());

        System.setProperty("slums.crews", "false");
        assertFalse(BotCrews.on());

        System.setProperty("slums.socialacts", "false");
        assertFalse(SocialActions.enabled());
    }
}
