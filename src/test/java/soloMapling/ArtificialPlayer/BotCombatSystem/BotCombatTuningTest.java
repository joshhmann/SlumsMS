package soloMapling.ArtificialPlayer.BotCombatSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotCombatTuningTest {

    @AfterEach
    void reset() {
        BotCombatTuning.reset();
    }

    @Test
    void defaultsAreTheOwnersDecisions() {
        assertTrue(BotCombatTuning.human());
        assertEquals(3, BotCombatTuning.aoeMinMobs());
        assertTrue(BotCombatTuning.ksManners());
        assertTrue(BotCombatTuning.spread());
    }

    @Test
    void overridesApplyAndClear() {
        assertTrue(BotCombatTuning.override("aoe", "4"));
        assertEquals(4, BotCombatTuning.aoeMinMobs());
        assertTrue(BotCombatTuning.override("human", "false"));
        assertFalse(BotCombatTuning.human());
        assertFalse(BotCombatTuning.deaths()); // deaths ride on the human layer
        assertTrue(BotCombatTuning.override("aoe", null));
        assertEquals(3, BotCombatTuning.aoeMinMobs());
        assertFalse(BotCombatTuning.override("nonsense", "1"));
        assertFalse(BotCombatTuning.override("aoe", "x"));
    }
}
