package soloMapling.ArtificialPlayer.BotCombatSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 1.5.0 rc3, owner decision 5: deaths are rare, only on harder maps, never offscreen. */
class BotDeathsTest {

    @AfterEach
    void reset() {
        BotDeaths.clearBot(501);
        BotDeaths.forceNextDeath(false);
        BotCombatTuning.reset();
    }

    @Test
    void onlyAMapWellAboveTheBotIsHarder() {
        assertTrue(BotDeaths.harder(40, 37));
        assertFalse(BotDeaths.harder(39, 37));
        assertFalse(BotDeaths.harder(20, 37));
        assertFalse(BotDeaths.harder(-1, 37)); // no mobs known
    }

    @Test
    void aBotThatIsNotGrindingOrIsOffscreenNeverDies() {
        Character bot = testutil.Mocks.chr("Grinder");
        when(bot.getId()).thenReturn(501);
        when(bot.getMapId()).thenReturn(104040000);
        when(bot.getLevel()).thenReturn(5);
        assertFalse(BotDeaths.rollsDeath(bot)); // not eligible
        BotDeaths.setEligible(501, true);
        BotDeaths.forceNextDeath(true);
        assertFalse(BotDeaths.rollsDeath(bot)); // nobody can see the map: offscreen bots never die
        BotCombatTuning.override("deaths", "false");
        assertFalse(BotDeaths.rollsDeath(bot));
    }

    @Test
    void aDeadBotLiesThenRespawnsOnce() {
        assertFalse(BotDeaths.isDead(501));
        assertFalse(BotDeaths.readyToRespawn(501));
    }
}
