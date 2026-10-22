package soloMapling.ArtificialPlayer.BotCombatSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3, owner decision 5: each bot's habits are its own, stable, and varied across bots. */
class BotHabitsTest {

    @AfterEach
    void reset() {
        BotCombatTuning.reset();
    }

    @Test
    void habitsAreStableForABot() {
        assertEquals(BotHabits.of(123456), BotHabits.of(123456));
        assertEquals(BotHabits.of(7).describe(), BotHabits.of(7).describe());
    }

    @Test
    void habitsStayInsideTheirRanges() {
        for (int id = 1; id <= 5000; id++) {
            BotHabits h = BotHabits.of(id);
            assertTrue(h.hpPotAt() >= 0.40 && h.hpPotAt() <= 0.60, "pot line " + h.hpPotAt());
            assertTrue(h.latePotChance() >= 0.03 && h.latePotChance() <= 0.23);
            assertTrue(h.restockBelow() == 0 || (h.restockBelow() >= 3 && h.restockBelow() <= 20));
            assertTrue(h.rebuffLateMaxMs() >= 0 && h.rebuffLateMaxMs() <= 25_000);
            assertTrue(h.sloppyCast() >= 0.02 && h.sloppyCast() <= 0.12);
            assertTrue(h.paceChance() >= 0.25 && h.paceChance() <= 0.85);
            assertTrue(h.carelessness() >= 0.3 && h.carelessness() <= 1.7);
        }
    }

    @Test
    void botsDifferFromEachOther() {
        Map<BotHabits.Loot, Integer> loot = new EnumMap<>(BotHabits.Loot.class);
        int runDry = 0;
        double minPot = 1, maxPot = 0;
        for (int id = 1; id <= 2000; id++) {
            BotHabits h = BotHabits.of(id);
            loot.merge(h.loot(), 1, Integer::sum);
            runDry += h.restockBelow() == 0 ? 1 : 0;
            minPot = Math.min(minPot, h.hpPotAt());
            maxPot = Math.max(maxPot, h.hpPotAt());
        }
        // every style shows up, batching the most common (about 45 / 27 / 18 / 10 %)
        for (BotHabits.Loot l : BotHabits.Loot.values()) {
            assertTrue(loot.getOrDefault(l, 0) > 100, l + " " + loot);
        }
        assertTrue(loot.get(BotHabits.Loot.BATCH) > loot.get(BotHabits.Loot.INSTANT));
        assertTrue(runDry > 500 && runDry < 900, "run dry " + runDry);
        assertTrue(minPot < 0.42 && maxPot > 0.58);
    }

    @Test
    void aPetLooterWithoutAPetBatches() {
        assertFalse(BotHabits.hasLootingPet(null));
        int petId = 1;
        while (BotHabits.of(petId).loot() != BotHabits.Loot.PET) {
            petId++;
        }
        client.Character bot = testutil.Mocks.chr("NoPet");
        org.mockito.Mockito.when(bot.getId()).thenReturn(petId);
        assertEquals(BotHabits.Loot.BATCH, BotHabits.lootNow(bot));
    }

    @Test
    void sloppinessScalesWithTheConfig() {
        BotHabits h = BotHabits.of(42);
        BotCombatTuning.override("sloppy", "0");
        assertEquals(0.0, h.sloppyNow());
        assertEquals(0L, h.rebuffLateNowMs());
        BotCombatTuning.override("sloppy", "2");
        assertEquals(Math.min(0.5, h.sloppyCast() * 2), h.sloppyNow(), 1e-9);
    }
}
