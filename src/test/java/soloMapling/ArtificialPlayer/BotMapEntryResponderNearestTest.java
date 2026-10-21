package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.4.2 (perf): a player entering a busy map wakes only the bots nearest to it, not all ~80 at once. */
class BotMapEntryResponderNearestTest {

    private static Character at(int id, int x, boolean bot) {
        Character c = Character.getDefault(bot ? new BotClient(0, 0) : org.mockito.Mockito.mock(client.Client.class));
        c.setID(id);
        c.setPosition(new Point(x, 0));
        return c;
    }

    @Test
    void onlyTheNearestBotsAreNudged() {
        List<Character> chars = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            chars.add(at(1000 + i, (i - 40) * 50, true));
        }
        chars.add(at(5, 0, false));
        List<Character> picked = BotMapEntryResponder.nearestBots(chars, new Point(0, 0), BotMapEntryResponder.ENTRY_NUDGE_MAX_BOTS);
        assertEquals(12, picked.size());
        assertTrue(picked.stream().allMatch(BotHelpers::isBot));
        assertTrue(picked.stream().allMatch(c -> Math.abs(c.getPosition().x) <= 300));
    }

    @Test
    void aQuietMapNudgesAllItsBots() {
        List<Character> chars = List.of(at(1, 0, true), at(2, 3000, true), at(5, 0, false));
        assertEquals(2, BotMapEntryResponder.nearestBots(chars, new Point(0, 0), 12).size());
        assertEquals(2, BotMapEntryResponder.nearestBots(chars, null, 12).size());
    }
}
