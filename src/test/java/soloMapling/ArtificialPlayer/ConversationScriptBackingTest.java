package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ClaimActions;
import soloMapling.ArtificialPlayer.BotConvo.ClaimRules;
import soloMapling.ArtificialPlayer.BotConvo.ConversationLines;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ambient conversation picker only chooses a script the staged cluster can back: an {@code S>} ad for a scroll
 * nobody in the cluster holds is skipped (and named in the QA log) instead of being spoken and then reported as an
 * unbacked claim by the say-do audit.
 */
class ConversationScriptBackingTest {

    /** The fake behaviours: the cluster can neither hold an item nor idle unless the test says so. */
    static final class Game implements ClaimActions.Game {
        boolean holds;
        boolean idles;

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
            return false;
        }

        @Override
        public boolean holdsOrWants(Character bot, ClaimRules.Claim claim) {
            return holds;
        }
    }

    private Game game;
    private List<Character> cluster;

    @BeforeEach
    void setUp() {
        game = new Game();
        ClaimActions.useForTest(game);
        cluster = List.of(bot(1_000_000_001, "ZhelmRamen98"), bot(1_000_000_002, "MushroomFan"));
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

    private static ConversationScript script(String name, int participants, String... lines) {
        List<ConversationScript.ConversationLine> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            out.add(new ConversationScript.ConversationLine(i % 2 == 0 ? "A" : "B", lines[i], -1, 0));
        }
        return new ConversationScript(name, participants, out);
    }

    private static ConversationScript ad() {
        return script("item_price_haggle_2", 2, "S> 10% glove att", "how much", "60m", "LOL no thx");
    }

    private static ConversationScript plain() {
        return script("class_debate", 2, "whats the best class in the game", "night lord obviously");
    }

    @Test
    void theAdIsSkippedWhenNobodyInTheClusterHoldsTheScroll() {
        List<ConversationLines.Refusal> refused = new ArrayList<>();
        ConversationScript picked = ConversationManager.playable(cluster, List.of(ad(), plain()), refused);
        assertNotNull(picked);
        assertEquals("class_debate", picked.getId(), "the ad script is not played by bots with no scroll");
        assertEquals(1, refused.size());
        assertEquals("item_price_haggle_2", refused.get(0).script());
        assertEquals("S> 10% glove att", refused.get(0).line());
        assertEquals("ZhelmRamen98", refused.get(0).bot());
        assertEquals(100000000, refused.get(0).mapId());
    }

    @Test
    void theAdPlaysWhenTheSellerHoldsTheScroll() {
        game.holds = true;
        assertEquals("item_price_haggle_2", ConversationManager.playable(cluster, List.of(ad()), null).getId());
        assertEquals("item_price_haggle_2",
                ConversationManager.playable(cluster, List.of(ad(), plain()), null).getId());
    }

    @Test
    void aScriptTheClusterCanBackIsStillChosen() {
        ConversationScript afk = script("town_hanging_out", 2, "what are we doing", "afk basically");
        game.idles = true;
        assertEquals("town_hanging_out", ConversationManager.playable(cluster, List.of(afk), null).getId());
        game.idles = false;
        assertNull(ConversationManager.playable(cluster, List.of(afk), null),
                "a cluster that cannot idle does not say it is afk");
    }

    @Test
    void noPlayableScriptMeansNoConversation() {
        assertNull(ConversationManager.playable(cluster, List.of(ad()), null));
        assertNull(ConversationManager.playable(cluster, List.of(), null));
    }
}
