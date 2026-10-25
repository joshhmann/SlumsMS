package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scripted conversations are only played by bots that can back what the script says: the {@code S> 10% glove att}
 * line of {@code item_price_haggle_2} is not spoken by a bot that holds no such scroll (it used to be, and the
 * say-do audit reported it), while a script the same bots can back still plays and a claim-free script always does.
 */
class ScriptBackingTest {

    /** The fake behaviours: nothing is held and nothing can be started unless the test says so. */
    static final class Game implements ClaimActions.Game {
        boolean holds;
        boolean idles;
        boolean trades;

        @Override
        public boolean canRestock(Character bot) {
            return false;
        }

        @Override
        public void restock(Character bot) {
        }

        @Override
        public boolean canIdle(Character bot) {
            return idles;
        }

        @Override
        public void idle(Character bot, long ms) {
        }

        @Override
        public boolean canLogOut(Character bot) {
            return false;
        }

        @Override
        public void logOutSoon(Character bot, long ms) {
        }

        @Override
        public boolean canTravel(Character bot, int mapId) {
            return false;
        }

        @Override
        public void travel(Character bot, int mapId) {
        }

        @Override
        public boolean partied(Character bot) {
            return true;
        }

        @Override
        public boolean takesTrade(Character bot, Character from) {
            return trades;
        }

        @Override
        public boolean holdsOrWants(Character bot, ClaimRules.Claim claim) {
            return holds;
        }
    }

    private Game game;

    @BeforeEach
    void setUp() {
        game = new Game();
        ClaimActions.useForTest(game);
    }

    @AfterEach
    void tearDown() {
        ClaimActions.useForTest(null);
    }

    private static Character bot(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(100000000);
        return c;
    }

    private static ConversationLines.Script script(String name) {
        return ConversationLines.scripts().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("the pack has no script " + name));
    }

    private static final String AD = "S> 10% glove att";

    @Test
    void anAdTheSpeakerCannotBackIsRefusedWhole() {
        Character seller = bot(1_000_000_001, "ZhelmRamen98");
        ConversationLines.Refusal r = ConversationLines.unbackable(script("item_price_haggle_2"),
                role -> "A".equals(role) ? seller : bot(1_000_000_002, "buyer"));
        assertNotNull(r, "a bot holding no '10% glove att' cannot play the ad");
        assertEquals(AD, r.line());
        assertEquals("ZhelmRamen98", r.bot());
        assertEquals("item_price_haggle_2", r.script());
        assertEquals(100000000, r.mapId());
    }

    @Test
    void theSameAdPlaysWhenTheSpeakerHoldsTheScroll() {
        game.holds = true;
        Character seller = bot(1_000_000_001, "ZhelmRamen98");
        assertNull(ConversationLines.unbackable(script("item_price_haggle_2"), role -> seller),
                "a bot that holds the scroll may advertise it");
    }

    @Test
    void aClaimFreeScriptAlwaysPlays() {
        game.holds = false;
        game.idles = false;
        game.trades = false;
        assertNull(ConversationLines.unbackable(script("class_debate"), role -> bot(1_000_000_001, "a")));
    }

    @Test
    void aScriptTheBotsCanBackStillPlaysAndStopsWhenTheyCannot() {
        // dying_at_boss: "afk basically" - backable only by a bot that can idle
        game.idles = true;
        assertNull(ConversationLines.unbackable(script("dying_at_boss"), role -> bot(1_000_000_001, "a")),
                "a bot that can idle can say it is afk");
        game.idles = false;
        assertNotNull(ConversationLines.unbackable(script("dying_at_boss"), role -> bot(1_000_000_001, "a")),
                "a bot that cannot idle may not say it is afk");
    }

    @Test
    void thePickerSkipsTheAdAndTakesTheNextScript() {
        Character seller = bot(1_000_000_001, "ZhelmRamen98");
        List<ConversationLines.Refusal> refused = new ArrayList<>();
        ConversationLines.Script picked = ConversationLines.firstPlayable(
                List.of(script("item_price_haggle_2"), script("class_debate")), role -> seller, refused::add);
        assertNotNull(picked);
        assertEquals("class_debate", picked.name());
        assertEquals(1, refused.size());
        assertEquals("item_price_haggle_2", refused.get(0).script());
        assertEquals(AD, refused.get(0).line());
    }

    @Test
    void noPlayableScriptMeansNoScriptAtAll() {
        Character seller = bot(1_000_000_001, "ZhelmRamen98");
        assertNull(ConversationLines.firstPlayable(List.of(script("item_price_haggle_2")), role -> seller, null));
    }

    @Test
    void theThreadFallbackSpeaksNoAdItCannotBack() {
        Character a = bot(1_000_000_001, "Kimchi");
        Character b = bot(1_000_000_002, "Bob");
        assertTrue(BotThreads.scripted(a, b, List.of(script("item_price_haggle_2")), new Random(1)).isEmpty(),
                "a bot with no scroll does not play the ad script");
        game.holds = true;
        List<String[]> lines = BotThreads.scripted(a, b, List.of(script("item_price_haggle_2")), new Random(1));
        assertTrue(lines.size() >= 2);
        assertEquals(AD, lines.get(0)[1]);
    }

    @Test
    void theThreadFallbackStillUsesAScriptBothCanBack() {
        Character a = bot(1_000_000_001, "Kimchi");
        Character b = bot(1_000_000_002, "Bob");
        List<String[]> lines = BotThreads.scripted(a, b, List.of(script("class_debate")), new Random(7));
        assertTrue(lines.size() >= 2);
        assertTrue(lines.get(0)[1].contains("class") || lines.get(0)[1].contains("best"), lines.get(0)[1]);
    }
}
