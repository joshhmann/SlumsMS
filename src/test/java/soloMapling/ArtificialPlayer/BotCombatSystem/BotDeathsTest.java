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
        BotDeaths.clearBot(502);
        soloMapling.worldevents.WorldEventSteer.unhold(501);
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
        BotDeaths.forceNextDeath(501, true);
        assertFalse(BotDeaths.rollsDeath(bot)); // nobody can see the map: offscreen bots never die
        BotCombatTuning.override("deaths", "false");
        assertFalse(BotDeaths.rollsDeath(bot));
    }

    private static final int MAP = 104040000;

    private static Character grinder(int id) {
        Character bot = testutil.Mocks.chr("Grinder" + id);
        when(bot.getId()).thenReturn(id);
        when(bot.getMapId()).thenReturn(MAP);
        when(bot.getLevel()).thenReturn(5);
        BotDeaths.setEligible(id, true);
        soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.markObservedNow(MAP);
        return bot;
    }

    private static net.server.world.PartyCharacter member(int id) {
        net.server.world.PartyCharacter pc = org.mockito.Mockito.mock(net.server.world.PartyCharacter.class);
        when(pc.getId()).thenReturn(id);
        return pc; // offline (no live Character): judged by its id
    }

    // Review P2: eligibility was set once at grind start; a bot that partied with a player later could still die.
    @Test
    void aBotThatPartiedWithARealPlayerSinceGrindStartNeverDies() {
        Character bot = grinder(501);
        net.server.world.Party party = org.mockito.Mockito.mock(net.server.world.Party.class);
        java.util.List<net.server.world.PartyCharacter> withPlayer = java.util.List.of(member(501), member(7));
        java.util.List<net.server.world.PartyCharacter> botsOnly = java.util.List.of(member(501),
                member(soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 77));
        when(party.getMembers()).thenReturn(withPlayer);
        when(bot.getParty()).thenReturn(party);
        BotDeaths.forceNextDeath(501, true);
        assertFalse(BotDeaths.rollsDeath(bot), "partied with a real player");
        when(party.getMembers()).thenReturn(botsOnly);
        assertTrue(BotDeaths.rollsDeath(bot), "a party of bots only: the forced death goes through");
    }

    @Test
    void aBotHeldByGmStagingNeverDies() {
        Character bot = grinder(501);
        soloMapling.worldevents.WorldEventSteer.hold(501, MAP);
        BotDeaths.forceNextDeath(501, true);
        assertFalse(BotDeaths.rollsDeath(bot));
        soloMapling.worldevents.WorldEventSteer.unhold(501); // cb-3 stages without a hold
        assertTrue(BotDeaths.rollsDeath(bot));
    }

    @SuppressWarnings("unchecked")
    private static void onCooldown(int id) {
        try {
            java.lang.reflect.Field f = BotDeaths.class.getDeclaredField("lastDeath");
            f.setAccessible(true);
            ((java.util.Map<Integer, Long>) f.get(null)).put(id, System.currentTimeMillis());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // Review P2: forceNext was one global flag, so whichever bot took a lethal hit first consumed it.
    @Test
    void aForcedDeathBelongsToItsBot() {
        Character other = grinder(502);
        Character target = grinder(501);
        BotDeaths.forceNextDeath(501, true);
        onCooldown(502); // only a force could kill it now
        assertFalse(BotDeaths.rollsDeath(other), "another bot's lethal hit does not consume the force");
        assertTrue(BotDeaths.rollsDeath(target));
        onCooldown(501);
        assertFalse(BotDeaths.rollsDeath(target), "one force, one death");
    }

    @Test
    void aDeadBotLiesThenRespawnsOnce() {
        assertFalse(BotDeaths.isDead(501));
        assertFalse(BotDeaths.readyToRespawn(501));
    }
}
