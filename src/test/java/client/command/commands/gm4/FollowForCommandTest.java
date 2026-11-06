package client.command.commands.gm4;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 1.6.5 QA staging: {@code !followfor} makes the named PLAYER the bot's leader, not the GM who typed it. */
class FollowForCommandTest {

    private final BiPredicate<Character, Character> original = FollowForCommand.follow;
    private final List<String> calls = new ArrayList<>();

    @AfterEach
    void restore() {
        FollowForCommand.follow = original;
    }

    static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    @Test
    void theNamedPlayerBecomesTheLeader() {
        Character bot = chr(1000553099, "thunder54");
        Character leader = chr(46, "AqClient");
        FollowForCommand.follow = (b, l) -> calls.add(b.getId() + "->" + l.getId());
        try (MockedStatic<BotHelpers> h = mockStatic(BotHelpers.class)) {
            h.when(() -> BotHelpers.isBot(bot)).thenReturn(true);
            h.when(() -> BotHelpers.isBot(leader)).thenReturn(false);
            assertEquals("thunder54 now follows AqClient.", FollowForCommand.apply(bot, leader, "AqClient"));
        }
        assertEquals(List.of("1000553099->46"), calls);
    }

    @Test
    void refusesAMissingBotAnOfflineLeaderOrABotLeader() {
        Character bot = chr(1000553099, "thunder54");
        Character otherBot = chr(1000551094, "MSbeta");
        FollowForCommand.follow = (b, l) -> calls.add("called");
        try (MockedStatic<BotHelpers> h = mockStatic(BotHelpers.class)) {
            h.when(() -> BotHelpers.isBot(bot)).thenReturn(true);
            h.when(() -> BotHelpers.isBot(otherBot)).thenReturn(true);
            assertTrue(FollowForCommand.apply(null, chr(46, "AqClient"), "AqClient").startsWith("No bot"));
            assertTrue(FollowForCommand.apply(bot, null, "AqClient").endsWith("is not online."));
            assertTrue(FollowForCommand.apply(bot, otherBot, "MSbeta").contains("is a bot"));
        }
        assertEquals(List.of(), calls);
    }
}
