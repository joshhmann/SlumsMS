package soloMapling.ArtificialPlayer.BotTravelSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 03: bots take HyperTPHandler's rebirth gates as a hard rule, plus the Warrior Grounds hole.
class BotMapGatesTest {

    @Test
    void theGatesFollowHyperTeleportRockRanges() {
        assertEquals(0, BotMapGates.requiredRebirths(100000000));
        assertEquals(1, BotMapGates.requiredRebirths(211060100)); // Lion King's Castle
        assertEquals(2, BotMapGates.requiredRebirths(273020300)); // Twilight Perion
        assertEquals(3, BotMapGates.requiredRebirths(450001010)); // Arcane River
        assertEquals(BotMapGates.NEVER, BotMapGates.requiredRebirths(271030100)); // Knight Stronghold
    }

    @Test
    void warriorGroundsIsGatedWithTheRestOfTwilightPerion() {
        assertEquals(2, BotMapGates.requiredRebirths(273060300));
        assertFalse(BotMapGates.mayEnter(273060300, 1));
        assertTrue(BotMapGates.mayEnter(273060300, 2));
    }

    @Test
    void elNathItselfStaysOpen() {
        assertTrue(BotMapGates.mayEnter(211000000, 0));
        assertTrue(BotMapGates.mayEnter(211040100, 0));
        assertFalse(BotMapGates.mayEnter(211060100, 0));
    }
}
