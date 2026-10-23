package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage (t-gw-2): the GM's !botwar check declared war on the owner's guild instead of the GM's. */
class GuildWarCheckScopeTest {

    @Test
    void aCheckLimitedToTheGmsGuildWeighsOnlyThatGuild() {
        assertTrue(GuildWars.weighs(12, 12));
        assertFalse(GuildWars.weighs(11, 12), "another player guild online is left alone");
    }

    @Test
    void thePeriodicCheckWeighsEveryGuild() {
        assertTrue(GuildWars.weighs(11, 0));
        assertTrue(GuildWars.weighs(12, 0));
    }
}
