package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 161 item 3: a friendly bot may fame a player who asks, once a day per pair, under the normal fame rules. */
class BotFameRequestTest {

    private static final int BOT = 1_000_000_000 + 77;
    private static final int PLAYER = 4242;
    private static final long NOW = 1_800_000_000_000L;

    private final List<String> said = new ArrayList<>();
    private final List<Integer> gave = new ArrayList<>();
    private boolean friend = true;
    private boolean canGive = true;
    private Character.FameStatus status = Character.FameStatus.OK;

    @BeforeEach
    void seam() {
        System.setProperty("slums.bots.famerequest", "true");
        BotFameRequest.useForTest(new BotFameRequest.Effects() {
            @Override public boolean friend(int botId, int playerId) { return friend; }
            @Override public Character.FameStatus status(Character bot, Character player) { return status; }
            @Override public boolean give(Character bot, Character player) { if (canGive) gave.add(player.getId()); return canGive; }
            @Override public void say(Character bot, String line) { said.add(line); }
        });
    }

    @AfterEach
    void clean() {
        System.clearProperty("slums.bots.famerequest");
        BotFameRequest.useForTest(null);
    }

    private static Character chr(int id, int level, int mapId) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getLevel()).thenReturn(level);
        when(c.getMapId()).thenReturn(mapId);
        return c;
    }

    @Test
    void recognisesAskersAndNotDefamersOrTalkAboutFame() {
        for (String yes : new String[]{"fame me pls", "can u fame me?", "gimme a fame", "plz fame", "fame me", "could you fame me please"}) {
            assertTrue(BotFameRequest.isFameRequest(yes), yes);
        }
        for (String no : new String[]{"defame me", "dont fame me", "my fame is 12", "do u have fame", "hello", "",
                "i got famed by a friend yesterday and it was so nice of them to do that for me ok"}) {
            assertFalse(BotFameRequest.isFameRequest(no), no);
        }
    }

    @Test
    void aFriendFamesAPlayerWhoAsks() {
        Character bot = chr(BOT, 50, 100000000);
        Character player = chr(PLAYER, 40, 100000000);
        assertEquals(BotFameRequest.Outcome.FAMED, BotFameRequest.handle(bot, player, "fame me pls", NOW));
        assertEquals(List.of(PLAYER), gave);
        assertEquals(1, said.size());
    }

    @Test
    void onlyOnceADayPerPairThenAgain() {
        Character bot = chr(BOT, 50, 1);
        Character player = chr(PLAYER, 40, 1);
        assertEquals(BotFameRequest.Outcome.FAMED, BotFameRequest.handle(bot, player, "fame me pls", NOW));
        assertEquals(BotFameRequest.Outcome.ALREADY_TODAY, BotFameRequest.handle(bot, player, "fame me pls", NOW + 3_600_000));
        assertEquals(1, gave.size(), "no second fame the same day");
        assertEquals(BotFameRequest.Outcome.FAMED,
                BotFameRequest.handle(bot, player, "fame me pls", NOW + BotFameRequest.PAIR_GAP_MS + 1));
    }

    @Test
    void notAFriendIsLeftToTheNormalChat() {
        friend = false;
        Character bot = chr(BOT, 50, 1);
        Character player = chr(PLAYER, 40, 1);
        assertEquals(BotFameRequest.Outcome.IGNORED, BotFameRequest.handle(bot, player, "fame me pls", NOW));
        assertTrue(gave.isEmpty());
        assertTrue(said.isEmpty());
        assertFalse(BotFameRequest.tryHandle(bot, player, "fame me pls"));
    }

    @Test
    void normalFameRulesHold() {
        Character player = chr(PLAYER, 40, 1);
        assertEquals(BotFameRequest.Outcome.IGNORED, BotFameRequest.handle(chr(BOT, 14, 1), player, "fame me pls", NOW),
                "a famer must be level 15");
        assertEquals(BotFameRequest.Outcome.IGNORED, BotFameRequest.handle(chr(BOT, 50, 2), player, "fame me pls", NOW),
                "same map");
        status = Character.FameStatus.NOT_TODAY;
        assertEquals(BotFameRequest.Outcome.CANNOT_NOW, BotFameRequest.handle(chr(BOT, 50, 1), player, "fame me pls", NOW),
                "the server's own once-a-day rule");
        status = Character.FameStatus.NOT_THIS_MONTH;
        assertEquals(BotFameRequest.Outcome.ALREADY_TODAY, BotFameRequest.handle(chr(BOT, 50, 1), player, "fame me pls", NOW),
                "the server's once-a-month-per-target rule");
        status = Character.FameStatus.OK;
        canGive = false;
        assertEquals(BotFameRequest.Outcome.CANNOT_NOW, BotFameRequest.handle(chr(BOT, 50, 1), player, "fame me pls", NOW),
                "the fame ceiling");
        assertTrue(gave.isEmpty());
    }

    @Test
    void aBotNeverFamesABotAndTheSwitchDefaultsOff() {
        Character otherBot = chr(BOT + 1, 50, 1);
        when(otherBot.getClient()).thenReturn(new client.BotClient(0, 0));
        assertEquals(BotFameRequest.Outcome.NOT_A_REQUEST,
                BotFameRequest.handle(chr(BOT, 50, 1), otherBot, "fame me pls", NOW));
        System.clearProperty("slums.bots.famerequest");
        assertEquals(BotFameRequest.Outcome.NOT_A_REQUEST,
                BotFameRequest.handle(chr(BOT, 50, 1), chr(PLAYER, 40, 1), "fame me pls", NOW));
        assertTrue(gave.isEmpty());
    }
}
