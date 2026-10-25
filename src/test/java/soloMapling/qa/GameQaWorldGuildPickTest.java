package soloMapling.qa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** t-gu-1 staging: the guild the GM applies to must have a leader that would say yes. */
class GameQaWorldGuildPickTest {

    @Test
    void anApplicationGoesToTheBiggestGuildWhoseLeaderWouldSayYes() {
        // rc6 auto-QA: the biggest guild (Legendz) had a leader that was the GM's rival, so the officer's yes was overruled
        assertEquals("MoonLiteX", GameQaWorld.chooseForApplicant(List.of(
                new GameQaWorld.GuildCandidate("Legendz", 39, true, false),
                new GameQaWorld.GuildCandidate("MoonLiteX", 35, false, false),
                new GameQaWorld.GuildCandidate("NoobTeam", 8, false, false))));
        assertEquals("NoobTeam", GameQaWorld.chooseForApplicant(List.of(
                new GameQaWorld.GuildCandidate("Legendz", 39, true, false),
                new GameQaWorld.GuildCandidate("MoonLiteX", 35, false, true),
                new GameQaWorld.GuildCandidate("NoobTeam", 8, false, false))), "a full guild says FULL");
    }

    @Test
    void aGuildWithoutRoomForTheStagedBotAndOneMoreIsNoPlaceForAScene() {
        // auto-QA 1.5.2: Legendz at 49/50 was picked, its staged officer filled it, and gu-1 heard FULL
        assertTrue(GameQaWorld.noRoomForScene(49, 50));
        assertTrue(GameQaWorld.noRoomForScene(50, 50));
        assertTrue(GameQaWorld.noRoomForScene(44, 50), "a poach keeps 5 slots for players");
        assertFalse(GameQaWorld.noRoomForScene(43, 50));
        assertFalse(GameQaWorld.noRoomForScene(35, 45), "MoonLiteX on TEST");
    }

    @Test
    void withNoWelcomingGuildItFallsBackToTheBiggest() {
        assertEquals("Legendz", GameQaWorld.chooseForApplicant(List.of(
                new GameQaWorld.GuildCandidate("Legendz", 39, true, false),
                new GameQaWorld.GuildCandidate("NoobTeam", 8, true, false))));
        assertNull(GameQaWorld.chooseForApplicant(List.of()));
    }
}
