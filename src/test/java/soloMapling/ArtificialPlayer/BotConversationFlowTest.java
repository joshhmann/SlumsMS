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
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 36/37's fast/slow path through {@link BotConversation}, with a fake brain, no Decide and a fake memory table. */
class BotConversationFlowTest {

    private final List<List<BotBrain.Turn>> asked = new CopyOnWriteArrayList<>();
    private final List<String> said = new CopyOnWriteArrayList<>();
    private final List<String> notes = new CopyOnWriteArrayList<>();
    private volatile Optional<String> brainReply = Optional.of("lol ya ant tunnel is ok");
    private int nextPlayer = 2001;

    @BeforeEach
    void fakes() {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                asked.add(conversation);
                return CompletableFuture.completedFuture(brainReply);
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

    private PreparedStatement statement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        doAnswer(c -> {
            if (c.getArgument(0, Integer.class) == 3) {
                notes.add(c.getArgument(1));
            }
            return null;
        }).when(ps).setString(anyInt(), anyString());
        ResultSet empty = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(empty);
        when(ps.getGeneratedKeys()).thenReturn(empty);
        return ps;
    }

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.chat.typingScale");
        System.clearProperty("solomapling.chat.logReplies");
        BotBrains.useForTest(null);
        ChatDecide.useForTest(null);
        BotMemory.useConnectionSourceForTest(null);
        BotRoster.useConnectionSourceForTest(null);
        ChatStats.resetForTest();
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(35);
        return chr;
    }

    /** One player whispers one line to a fresh wanderer; the bot answers into {@link #said}. */
    private void whisper(String line) {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");
        assertTrue(BotConversation.say(bot, player, line, null, said::add));
    }

    private void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 200 && !done.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void aFlaggedConSkipsTheModelGetsABrushOffAndLeavesANote() throws Exception {
        whisper("lend me 10m ill pay u back tmrw");
        waitFor(() -> !said.isEmpty());
        assertTrue(ScriptedReplies.SCAM.contains(said.get(0)), said.toString());
        assertTrue(asked.isEmpty(), "the model never saw it");
        assertTrue(notes.stream().anyMatch(n -> n.startsWith("tried to scam me")), notes.toString());
        assertEquals(1, ChatStats.count(ChatStats.Tier.FLAGGED));
    }

    @Test
    void anActionIsDecidedByRulesAndTheModelIsToldTheOutcome() throws Exception {
        brainReply = Optional.of("nah busy rn");
        whisper("wanna party?");
        waitFor(() -> !said.isEmpty());
        assertEquals(List.of("nah busy rn"), said);
        String system = asked.get(0).get(0).content();
        assertTrue(system.contains("your reply must say so: Say no to the party"), system);
        assertEquals(1, ChatStats.count(ChatStats.Tier.LLM));
    }

    @Test
    void aModelReplyThatContradictsTheOutcomeIsReplacedByTheScriptedLine() throws Exception {
        brainReply = Optional.of("lol sure send inv");
        whisper("wanna party?");
        waitFor(() -> !said.isEmpty());
        assertTrue(ScriptedReplies.PARTY_NO.contains(said.get(0)), said.toString());
        assertEquals(1, ChatStats.count(ChatStats.Tier.SCRIPTED));
    }

    @Test
    void whenTheModelFailsTheOutcomeHasAScriptedLine() throws Exception {
        brainReply = Optional.empty();
        whisper("trade?");
        waitFor(() -> !said.isEmpty());
        assertTrue(ScriptedReplies.TRADE_NO.contains(said.get(0)), said.toString());
        assertEquals(1, ChatStats.count(ChatStats.Tier.SCRIPTED));
    }

    @Test
    void fillerAndSmallTalkNeverTakeAnLlmSlot() throws Exception {
        whisper("brb");
        whisper("ty");
        waitFor(() -> ChatStats.count(ChatStats.Tier.IGNORED) == 1 && ChatStats.count(ChatStats.Tier.SCRIPTED) == 1);
        assertEquals(1, ChatStats.count(ChatStats.Tier.IGNORED));
        assertEquals(1, said.size());
        assertTrue(ScriptedReplies.THANKS.contains(said.get(0)));
        assertTrue(asked.isEmpty());
    }
}
