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
        assertEquals(0, BotMapGates.requiredRebirths(211060100)); // Lion King's Castle walls: open since 1.6.1 b040
        assertEquals(1, BotMapGates.requiredRebirths(211070100)); // Von Leon's wing
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
        assertTrue(BotMapGates.mayEnter(211060100, 0), "Lion King's Castle is open training (1.6.1 b040)");
        assertFalse(BotMapGates.mayEnter(211070000, 0), "Von Leon's wing keeps rebirth 1");
    }

    // Ticket 81 round 4: bots get the players' level-120 rule for the Future and lower Grandis.
    @Test
    void levelOneTwentyOpensTheFutureAndLowerGrandisForBotsToo() {
        assertFalse(BotMapGates.mayEnter(273020300, 0, 119));
        assertTrue(BotMapGates.mayEnter(273020300, 0, 120));
        assertTrue(BotMapGates.mayEnter(273060300, 0, 120)); // Warrior Grounds goes with the rest
        assertFalse(BotMapGates.mayEnter(410000010, 0, 119)); // Fox Ridge, lower Grandis
        assertTrue(BotMapGates.mayEnter(410000010, 0, 120));
        assertFalse(BotMapGates.mayEnter(401010000, 2, 200), "upper Grandis stays rebirth 3");
        assertFalse(BotMapGates.mayEnter(450001010, 0, 200), "Arcane River stays rebirth 3");
        assertTrue(BotMapGates.mayEnter(450001010, 3, 1));
        assertFalse(BotMapGates.mayEnter(271030100, 3, 200), "the Knight Stronghold stays closed");
        assertFalse(BotMapGates.mayEnter(211070000, 0, 200), "Von Leon's wing stays rebirth 1");
        assertFalse(BotMapGates.mayEnter(410000002, 0, 200), "the Fox Tree is deeper Grandis (1.6.1 b039)");
        assertFalse(BotMapGates.mayEnter(410000010, 0), "no level known: the rebirth rule");
    }
}
