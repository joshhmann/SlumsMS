package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 item 4: with the death count armed ({@code -Dslums.bossfight.deathcount}, off by default) a bot's death spends
 * the party's budget. The owner's reason: "to see if they can survive" is meaningless if their deaths are free, and the
 * counter's whole point is the party's resilience.
 *
 * <p>Before this change {@code ExpeditionDeathCount.counts} refused every bot, so a wiped bot party cost the expedition
 * nothing - the first test here is the fail-before.
 */
class BotDeathCountTest {

    private static final int BOT = 801;

    @AfterEach
    void forget() {
        BotDeaths.clearBot(BOT);
        BotCombatTuning.reset();
    }

    private static Character bot(EventInstanceManager eim) {
        Character c = testutil.Mocks.chr("Spender");
        when(c.getId()).thenReturn(BOT);
        when(c.isBot()).thenReturn(true);
        when(c.getName()).thenReturn("Spender");
        when(c.getLevel()).thenReturn(120);
        when(c.getMapId()).thenReturn(280030000);
        when(c.getEventInstance()).thenReturn(eim);
        return c;
    }

    @Test
    void aBotDeathIsChargedLikeAHumans() {
        Character bot = bot(null);
        assertTrue(ExpeditionDeathCount.counts(bot), "a mortal bot's death is the party's loss now");

        Character ownedAlt = bot(null);
        when(ownedAlt.isBot()).thenReturn(false);
        when(ownedAlt.isOwnedAlt()).thenReturn(true);
        assertFalse(ExpeditionDeathCount.counts(ownedAlt), "an owned alt's death is already its player's");

        assertFalse(ExpeditionDeathCount.counts(null));
    }

    @Test
    void aBotsDeathTellsItsInstanceSoTheCounterCanSpendOne() {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character bot = bot(eim);

        try (var movement = mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            BotDeaths.die(bot);
        }

        verify(eim).botKilled(bot);
    }

    @Test
    void aBotOutsideAnInstanceHasNobodyToCharge() {
        Character bot = bot(null);
        try (var movement = mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            BotDeaths.die(bot); // must not throw: there is no instance, so no budget
        }
        assertTrue(BotDeaths.isDead(BOT));
    }

    @Test
    void theBudgetItselfIsUnchangedAndStillOffUnlessArmed() {
        System.clearProperty(ExpeditionDeathCount.FLAG);
        assertEquals(0, ExpeditionDeathCount.configuredFor(server.expeditions.ExpeditionType.ZAKUM),
                "no budget unless -Dslums.bossfight.deathcount is on");

        ExpeditionDeathCount count = new ExpeditionDeathCount();
        assertTrue(count.arm(2));
        assertEquals(ExpeditionDeathCount.Charge.SPENT, count.charge());
        assertEquals(ExpeditionDeathCount.Charge.SPENT, count.charge());
        assertEquals(ExpeditionDeathCount.Charge.EXHAUSTED, count.charge());
        assertEquals(0, count.left());
    }
}
