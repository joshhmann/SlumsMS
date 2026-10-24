package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * rc6 auto-QA triage (t-af-1, 2026-10-23): a staged friend is a social bot standing in town, and the sighting hooks
 * ran only from the roaming bots' movement driver, so it never greeted, invited or buffed the player next to it.
 */
class StationedNoticeTest {

    static Character chr(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void aStationedBotReactsToTheFirstPlayerItFeelsAbout() {
        BotSM sm = mock(BotSM.class);
        Character bot = chr(BotStore.BOT_ID_BASE + 3);
        Character a = chr(40), b = chr(41), c = chr(42);
        List<Integer> asked = new ArrayList<>();
        boolean reacted = AffinityReactions.noticePlayers(sm, bot, List.of(a, b, c), (s, x, p) -> {
            asked.add(p.getId());
            return p.getId() == 41;
        });
        assertTrue(reacted);
        assertEquals(List.of(40, 41), asked, "one reaction at most: it stops at the first");
        assertFalse(AffinityReactions.noticePlayers(sm, bot, List.of(bot), (s, x, p) -> true), "never itself");
    }

    @Test
    void socialBotsCallTheHooksFromTheirOwnTick() throws Exception {
        String src = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotTypes/SocialBot.java"));
        assertTrue(src.contains("AffinityReactions.noticePlayers(") && src.contains("maybeNoticePlayers();"),
                "SocialBot.updateState must scan for players it feels about");
    }
}
