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
import scripting.event.EventInstanceManager;
import soloMapling.server.BotShutdown;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Owner report 2026-10-24: a bot you are talking to walks away mid-conversation. The chat session already existed;
 * nothing held the bot's movement. These tests pin the hold (45 s, refreshed by every exchange) and the exceptions
 * that still let a bot go. Package-local so it can use the same fakes as {@link BotConversationFlowTest}.
 */
class BotConversationHoldTest {

    private final List<String> said = new CopyOnWriteArrayList<>();
    private final List<List<BotBrain.Turn>> asked = new CopyOnWriteArrayList<>();
    private volatile CompletableFuture<Optional<String>> controlledReply;
    private static int nextPlayer = 6001; // sessions are static: every test talks to a fresh pair

    @BeforeEach
    void fakes() {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        System.setProperty("solomapling.convo.quality", "false");
        System.setProperty("solomapling.convo.persona", "false");
        System.setProperty("solomapling.convo.bank", "false");
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                asked.add(conversation);
                CompletableFuture<Optional<String>> answer = controlledReply;
                return answer == null ? CompletableFuture.completedFuture(Optional.of("sure, still here")) : answer;
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
        BotConversation.holdMsForTest(BotConversation.HOLD_MS);
    }

    private PreparedStatement statement() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet empty = mock(ResultSet.class);
        when(ps.executeQuery()).thenReturn(empty);
        when(ps.getGeneratedKeys()).thenReturn(empty);
        return ps;
    }

    @AfterEach
    void reset() {
        for (String key : List.of("solomapling.chat.typingScale", "solomapling.chat.logReplies",
                "solomapling.convo.quality", "solomapling.convo.persona", "solomapling.convo.bank")) {
            System.clearProperty(key);
        }
        BotBrains.useForTest(null);
        ChatDecide.useForTest(null);
        BotMemory.useConnectionSourceForTest(null);
        BotRoster.useConnectionSourceForTest(null);
        ChatStats.resetForTest();
        BotConversation.holdMsForTest(BotConversation.HOLD_MS);
    }

    private static Character character(int id, String name) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName(name);
        chr.setLevel(35);
        return chr;
    }

    private BotSM whisperTo(String line) {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 50_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");
        assertTrue(BotConversation.say(bot, player, line, null, said::add));
        return bot;
    }

    private void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 200 && !done.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
    }

    @Test
    void oneChatLineHoldsThereForFortyFiveSeconds() {
        BotSM bot = whisperTo("hey where are you going?");
        long hold = BotConversation.holdMs(bot.getChr().getId());
        assertTrue(hold > 40_000 && hold <= BotConversation.HOLD_MS,
                "one exchange must hold the bot for the full 45 s, had " + hold + " ms");
        assertTrue(BotConversation.isHoldingChat(bot.getChr()), "the wander paths ask this");
    }

    @Test
    void theBotsOwnReplyIsAnExchangeTooAndRestartsTheHold() throws Exception {
        CompletableFuture<Optional<String>> pending = new CompletableFuture<>();
        controlledReply = pending;
        BotSM bot = whisperTo("where should i train at level 35?");
        int id = bot.getChr().getId();
        assertTrue(BotConversation.holdMs(id) > 40_000, "the player's line already holds the bot");
        waitFor(() -> asked.size() >= 1); // the reply is still being written when the hold is checked
        BotConversation.holdMsForTest(150); // a short hold, cleared, to see what the reply itself arms
        pending.complete(Optional.of("ant tunnel is ok"));
        waitFor(() -> !said.isEmpty());
        assertEquals(List.of("ant tunnel is ok"), said);
        long afterReply = BotConversation.holdMs(id);
        assertTrue(afterReply > 0 && afterReply <= 150,
                "the bot's own line is an exchange and re-arms the hold, had " + afterReply + " ms");
    }

    @Test
    void theHoldWearsOffAndTheBotIsFreeToGoAgain() throws Exception {
        BotConversation.holdMsForTest(60); // the hold length, cleared: this test's chat must arm it afresh
        BotSM bot = whisperTo("where are you going?");
        int id = bot.getChr().getId();
        assertTrue(BotConversation.isHoldingChat(bot.getChr()), "a short hold is still a hold");
        for (int i = 0; i < 100 && BotConversation.isHoldingChat(bot.getChr()); i++) {
            Thread.sleep(25); // the bot's own reply re-arms the hold once; then it lapses
        }
        assertFalse(BotConversation.isHoldingChat(bot.getChr()), "after the hold the bot wanders again");
        assertEquals(0, BotConversation.holdMs(id), "a lapsed hold reports nothing left");
    }

    @Test
    void aBotWithARealReasonToLeaveIsNotHeld() {
        BotSM bot = whisperTo("are you coming?");
        Character chr = bot.getChr();
        try (var shutdown = mockStatic(BotShutdown.class)) {
            shutdown.when(BotShutdown::isQuiesced).thenReturn(true);
            assertFalse(BotConversation.isHoldingChat(chr), "shutdown outranks the chat");
        }
        chr.setEventInstance(mock(EventInstanceManager.class));
        assertFalse(BotConversation.isHoldingChat(chr), "an active event owns the bot, not the chat");
        chr.setEventInstance(null);
        assertTrue(BotConversation.isHoldingChat(chr), "no reason to leave: the chat holds it");
    }

    @Test
    void everyAmbientMoverAsksTheHoldBeforeItLeaves() throws Exception {
        // a wiring test: the fix is only as good as its call sites, and each of these moves a bot away on its own
        List<String> movers = List.of(
                "BotWanderSystem/BotWanderSystem.java",
                "GCMoveSystem/GCFidget.java",
                "BotTownSystem/PersonalSpace.java",
                "BotTownSystem/TownLoiter.java",
                "BotTypes/TownWandererBot.java",
                "BotTypes/SocialBot.java",
                "BotTypes/HenesysBot.java",
                "BotTypes/TrainingBot.java");
        Path root = Path.of("src/main/java/soloMapling/ArtificialPlayer");
        for (String mover : movers) {
            String src = Files.readString(root.resolve(mover));
            assertTrue(src.contains("BotConversation.isHoldingChat(") || src.contains("BotConversation.heldByChat("),
                    mover + " can walk a chatting bot away: it must consult the conversation hold");
        }
    }
}
