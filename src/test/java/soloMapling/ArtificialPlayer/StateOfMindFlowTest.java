package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 156 through {@link BotConversation}: the prompt carries the snapshot, a reply that fails the check is asked
 * for once more, a second miss falls back to a safe line, and the switches turn both parts off.
 */
class StateOfMindFlowTest {

    private final List<List<BotBrain.Turn>> asked = new CopyOnWriteArrayList<>();
    private final List<String> said = new CopyOnWriteArrayList<>();
    private volatile List<String> replies = List.of("hey whats up");
    private final AtomicInteger calls = new AtomicInteger();
    private static int nextPlayer = 61_001;

    @BeforeEach
    void fakes() {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        System.setProperty("solomapling.convo.quality", "false");
        System.setProperty("solomapling.convo.persona", "false");
        System.setProperty("solomapling.convo.bank", "false");
        System.setProperty("solomapling.convo.timing", "false");
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                asked.add(conversation);
                List<String> r = replies;
                return CompletableFuture.completedFuture(Optional.of(r.get(Math.min(calls.getAndIncrement(),
                        r.size() - 1))));
            }

            @Override
            public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public boolean isLive() {
                return true;
            }

            @Override
            public String describe() {
                return "fake";
            }
        });
        BotStore.ConnectionSource fake = () -> {
            Connection con = mock(Connection.class);
            when(con.prepareStatement(anyString())).thenAnswer(c -> statement());
            when(con.prepareStatement(anyString(), anyInt())).thenAnswer(c -> statement());
            return con;
        };
        BotMemory.useConnectionSourceForTest(fake);
        BotRoster.useConnectionSourceForTest(fake);
    }

    private static PreparedStatement statement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(empty);
        when(ps.getGeneratedKeys()).thenReturn(empty);
        return ps;
    }

    @AfterEach
    void reset() {
        for (String p : List.of("solomapling.chat.typingScale", "solomapling.chat.logReplies",
                "solomapling.convo.quality", "solomapling.convo.persona", "solomapling.convo.bank",
                "solomapling.convo.timing", "solomapling.convo.grounding", "solomapling.convo.stateOfMind")) {
            System.clearProperty(p);
        }
        BotBrains.useForTest(null);
        ChatDecide.useForTest(null);
        BotMemory.useConnectionSourceForTest(null);
        BotRoster.useConnectionSourceForTest(null);
        ChatStats.resetForTest();
    }

    private static Character character(int id, String name, int level) {
        return character(id, name, level, new BotClient(0, 0));
    }

    /** A real player: a client that is not a bot's (BotHelpers.isBot is false). */
    private static Character player(int id, String name, int level) {
        return character(id, name, level, mock(client.Client.class));
    }

    private static Character character(int id, String name, int level, client.Client client) {
        Character chr = Character.getDefault(client);
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(level);
        return chr;
    }

    private void whisper(String line) throws InterruptedException {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 61_000 + nextPlayer, "NLord99", 35);
        when(bot.getChr()).thenReturn(chr);
        Character player = player(nextPlayer++, "Josh", 200);
        assertTrue(BotConversation.say(bot, player, line, null, said::add));
        for (int i = 0; i < 200 && said.isEmpty(); i++) {
            Thread.sleep(25);
        }
    }

    private static String system(List<BotBrain.Turn> prompt) {
        return prompt.get(0).content();
    }

    @Test
    void thePromptCarriesTheSnapshot() throws Exception {
        whisper("hey hows it going");
        assertFalse(asked.isEmpty());
        String system = system(asked.get(0));
        assertTrue(system.contains("YOUR STATE"), system);
        assertTrue(system.contains("Only claim places, monsters, levels, history and prices that are in here"), system);
        assertTrue(system.contains("Josh: level 200"), system);
        assertTrue(system.contains("NEVER met"), system);
        assertTrue(system.contains("Never: say you are an AI"), "the never-an-AI rule stays: " + system);
        assertEquals(List.of("hey whats up"), said);
        System.out.println("StateOfMind stats after one reply: " + StateOfMind.stats());
    }

    @Test
    void b100AFamiliarReplyIsAskedForAgainOnce() throws Exception {
        replies = List.of("yo again ^^ just chillin, hows ur lvl 100 goal going", "hey not much, u?");
        whisper("hey hows it going");
        assertEquals(List.of("hey not much, u?"), said);
        assertEquals(2, asked.size(), "one retry");
        String retry = asked.get(1).get(asked.get(1).size() - 1).content();
        assertTrue(retry.contains("Not that") && retry.contains("never met"), retry);
        Map<String, Object> st = StateOfMind.stats();
        assertTrue(((Number) st.get("retryPassed")).longValue() >= 1, st.toString());
    }

    @Test
    void aSecondMissFallsBackToASafeLine() throws Exception {
        replies = List.of("yo again lol u still in for kpq later or what");
        whisper("hi");
        assertEquals(1, said.size());
        assertFalse(said.get(0).contains("again"), said.toString());
        assertEquals(2, asked.size(), "asked twice, then the script");
        assertTrue(List.of("hey", "yo", "hii", "sup", "hey hey").contains(said.get(0)), said.toString());
    }

    @Test
    void onlyAPriceOfferedAnswersAQuote() {
        assertTrue(BotStateOfMind.haggleWords("would u do 300k"));
        assertTrue(BotStateOfMind.haggleWords("300k?"));
        assertTrue(BotStateOfMind.haggleWords("9k for it"));
        assertFalse(BotStateOfMind.haggleWords("lol im like 300k exp away from 50 tho"));
    }

    @Test
    void theSwitchesTurnTheCheckAndThePromptBlockOff() throws Exception {
        System.setProperty("solomapling.convo.grounding", "false");
        System.setProperty("solomapling.convo.stateOfMind", "false");
        replies = List.of("yo again lol");
        whisper("hi");
        assertEquals(List.of("yo again lol"), said);
        assertEquals(1, asked.size());
        assertFalse(system(asked.get(0)).contains("YOUR STATE"));
    }
}
