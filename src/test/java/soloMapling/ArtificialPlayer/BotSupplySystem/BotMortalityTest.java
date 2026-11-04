package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.7.0, owner 2026-11-03: bots "should not be immortal, but should be toggleable for testing to see if they can
 * survive". So a lethal hit inside an event instance kills a bot like it kills a player, and
 * {@code -Dslums.bots.immortal=true} buys back the 1.5.0 rule that no bot ever dies in an instance.
 *
 * <p>Before this change the instance branch of {@code BotDeaths.protectedFromDeath} returned true unconditionally, so
 * the first test here (the mortal default) is the fail-before: the same hit left the bot standing at 1 HP.
 */
class BotMortalityTest {

    private static final int BOT = 601;

    @AfterEach
    void forget() {
        BotDeaths.clearBot(BOT);
        BotCombatTuning.reset();
        System.clearProperty(BotDeaths.IMMORTAL_SWITCH);
    }

    private static Character bot(int hp, int maxHp, EventInstanceManager eim) {
        Character c = testutil.Mocks.chr("Morty");
        when(c.getId()).thenReturn(BOT);
        when(c.getHp()).thenReturn(hp);
        when(c.getMaxHp()).thenReturn(maxHp);
        when(c.getCurrentMaxHp()).thenReturn(maxHp);
        when(c.getLevel()).thenReturn(120);
        when(c.getEventInstance()).thenReturn(eim);
        when(c.getInventory(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(client.inventory.Inventory.class)); // an empty bag: no potion to drink
        return c;
    }

    @Test
    void aLethalHitInAnEventInstanceKillsAMortalBot() {
        Character bot = bot(100, 1_000, mock(EventInstanceManager.class));
        assertFalse(BotDeaths.immortal(), "-Dslums.bots.immortal is off by default: bots are mortal");
        assertTrue(BotDeaths.diesOutright(bot), "a boss room is where a bot's death has to be able to happen");

        try (var movement = mockStatic(GCMovement.class)) {
            BotVitals.hurt(bot, 500); // one hit, more than it has
            assertTrue(BotDeaths.isDead(BOT), "the hit that takes it to 0 kills it, with no roll");
            movement.verify(() -> GCMovement.stop(bot));
        }
    }

    @Test
    void withTheImmortalSwitchOnTheSameHitLeavesTheBotStandingAtOneHp() {
        System.setProperty(BotDeaths.IMMORTAL_SWITCH, "true");
        Character bot = bot(100, 1_000, mock(EventInstanceManager.class));
        assertTrue(BotDeaths.immortal());
        assertFalse(BotDeaths.diesOutright(bot), "immortality is the 1.5.0 behaviour, restored exactly");

        try (var movement = mockStatic(GCMovement.class)) {
            BotVitals.hurt(bot, 500);
            assertFalse(BotDeaths.isDead(BOT), "no bot dies inside an instance while the switch is on");
        }
    }

    @Test
    void theOpenWorldKeepsItsOwnRareDeathRules() {
        Character bot = bot(100, 1_000, null); // no instance: a grinder
        assertFalse(BotDeaths.diesOutright(bot), "the outright rule is an instance rule only");
        // and the roll still refuses: not eligible, nobody watching the map
        assertFalse(BotDeaths.rollsDeath(bot));
        assertFalse(BotDeaths.isDead(BOT));
    }

    @Test
    void aBotWhoseHpIsAlreadyGoneTakesNoMoreHits() {
        Character bot = bot(100, 1_000, mock(EventInstanceManager.class));
        try (var movement = mockStatic(GCMovement.class)) {
            BotDeaths.die(bot);
            verify(bot, times(1)).updateHp(0); // the death itself
            BotVitals.hurt(bot, 5_000);
            verify(bot, times(1)).updateHp(0); // and nothing after it: a corpse takes no hits and is not healed
            assertTrue(BotDeaths.isDead(BOT));
        }
    }

    // ---- item 5: nothing heals a corpse back to life --------------------------------------------------

    @Test
    void noHealPathBringsADeadBotBack() {
        Character bot = bot(0, 1_000, mock(EventInstanceManager.class));
        try (var movement = mockStatic(GCMovement.class)) {
            BotDeaths.die(bot);
            assertTrue(BotDeaths.isDead(BOT));

            BotVitals.heal(bot, 400);          // a party Heal's share
            BotVitals.regen(bot, 60_000);      // the 10 s recovery tick, sixty seconds of it
            BotVitals.topUp(bot);              // potions

            verify(bot, times(1)).updateHp(0); // only the death itself; nothing raised the corpse
            assertTrue(BotDeaths.isDead(BOT), "a dead bot stays dead until something actually revives it");
        }
    }

    @Test
    void aResurrectedBotIsHealedAndRegeneratedAgain() {
        Character bot = bot(0, 1_000, null);
        BotDeaths.die(bot);
        BotDeaths.revived(bot); // what StatEffect's resurrection branch calls
        assertFalse(BotDeaths.isDead(BOT));

        when(bot.getHp()).thenReturn(300);
        BotVitals.heal(bot, 400);
        var raised = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(bot, org.mockito.Mockito.atLeastOnce()).updateHp(raised.capture());
        assertTrue(raised.getAllValues().stream().anyMatch(hp -> hp > 0),
                "a revived bot is healed again: " + raised.getAllValues());
    }

    @Test
    void theEffectOfTheSwitchIsTheInstanceClauseAndNothingElse() {
        Character inInstance = bot(100, 1_000, mock(EventInstanceManager.class));
        Character outside = bot(100, 1_000, null);
        assertTrue(BotDeaths.diesOutright(inInstance));
        assertFalse(BotDeaths.diesOutright(outside));
        assertTrue(BotDeaths.diesOutright(null) == false);
        when(inInstance.getSkillLevel(anyInt())).thenReturn(0);
        System.setProperty(BotDeaths.IMMORTAL_SWITCH, "true");
        assertFalse(BotDeaths.diesOutright(inInstance));
        assertFalse(BotDeaths.diesOutright(outside));
    }
}
