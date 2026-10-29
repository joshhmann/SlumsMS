package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 13: a per-bot target filter - an owned alt's swings (area attacks included) never touch a stranger's mob;
 * population bots have none and keep the plain botMayTarget rule.
 */
class BotAttackTargetFilterTest {

    @Test
    void aBotsFilterDecidesWhichMobsItMayFaceAndHit() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(4242);
        Character other = mock(Character.class);
        when(other.getId()).thenReturn(4243);
        Monster theirs = mock(Monster.class);
        Monster ours = mock(Monster.class);
        try {
            assertTrue(BotAttackDriver.mayTarget(alt, theirs));
            BotAttackDriver.setTargetFilter(4242, m -> m == ours);
            assertFalse(BotAttackDriver.mayTarget(alt, theirs));
            assertTrue(BotAttackDriver.mayTarget(alt, ours));
            assertTrue(BotAttackDriver.mayTarget(other, theirs), "another bot is unaffected");
            BotAttackDriver.clearBot(4242);
            assertTrue(BotAttackDriver.mayTarget(alt, theirs), "cleared with the bot");
            BotAttackDriver.setTargetFilter(4242, m -> false);
            BotAttackDriver.setTargetFilter(4242, null);
            assertTrue(BotAttackDriver.mayTarget(alt, theirs));
        } finally {
            BotAttackDriver.clearBot(4242);
        }
    }
}
