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
    private volatile CompletableFuture<Optional<String>> controlledReply;
    private volatile Optional<String> brainReply = Optional.of("lol ya ant tunnel is ok");
    private static int nextPlayer = 2001; // sessions are static: every test talks to a fresh pair

    @BeforeEach
    void fakes() {
        System.setProperty("solomapling.chat.typingScale", "0.01");
        System.setProperty("solomapling.chat.logReplies", "false");
        System.setProperty("solomapling.convo.quality", "false"); // no transcript files from tests
        // ticket 59's voice and bank layers re-word lines; this test checks the ticket 36/37 layer underneath
        System.setProperty("solomapling.convo.persona", "false");
        System.setProperty("solomapling.convo.bank", "false");
        ChatStats.resetForTest();
        ChatDecide.useForTest(null);
        BotBrains.useForTest(new BotBrain() {
            @Override
            public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
                CompletableFuture<Optional<String>> answer = controlledReply;
                asked.add(conversation);
                return answer == null ? CompletableFuture.completedFuture(brainReply) : answer;
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
        System.clearProperty("solomapling.convo.quality");
        System.clearProperty("solomapling.convo.persona");
        System.clearProperty("solomapling.convo.bank");
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
    void aLaterGuildMessageCannotRedirectAPendingWhisperReply() throws Exception {
        BotSM bot = mock(TownWandererBot.class);
        when(bot.getChr()).thenReturn(character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr"));
        Character player = character(nextPlayer++, "Josh");
        List<String> whispers = new CopyOnWriteArrayList<>();
        List<String> guild = new CopyOnWriteArrayList<>();
        CompletableFuture<Optional<String>> first = new CompletableFuture<>();
        CompletableFuture<Optional<String>> second = new CompletableFuture<>();
        controlledReply = first;
        assertTrue(BotConversation.say(bot, player, "where should i train at level 35?", null, whispers::add));
        waitFor(() -> asked.size() == 1);
        assertEquals(1, asked.size(), "the whisper reply must be waiting on the brain");
        controlledReply = second;
        assertTrue(BotConversation.talk(bot, player, "where should i train at level 36?", guild::add));
        waitFor(() -> asked.size() == 2);
        assertEquals(2, asked.size());
        assertEquals(2, asked.get(1).stream().filter(t -> t.role() == BotBrain.Role.USER).count(),
                "switching channels still shares conversation history");
        first.complete(Optional.of("ant tunnel is ok"));
        waitFor(() -> !whispers.isEmpty() || !guild.isEmpty());
        assertEquals(List.of("ant tunnel is ok"), whispers, "the first reply belongs in the original whisper");
        assertTrue(guild.isEmpty(), "a later guild message must not steal the whisper's reply");
        second.complete(Optional.of("try wild boars"));
        waitFor(() -> !guild.isEmpty());
        assertEquals(List.of("try wild boars"), guild);
    }

    @Test
    void aPendingWhisperIsDroppedIfThePlayerOpensATradeBeforeDelivery() throws Exception {
        CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
        BotBrain brain = mock(BotBrain.class);
        when(brain.isLive()).thenReturn(true);
        when(brain.reply(org.mockito.ArgumentMatchers.any(), anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenCallRealMethod();
        when(brain.complete(anyInt(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            asked.add(call.getArgument(1));
            return answer;
        });
        BotBrains.useForTest(brain);
        BotSM bot = mock(TownWandererBot.class);
        when(bot.getChr()).thenReturn(character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr"));
        Character player = org.mockito.Mockito.spy(character(nextPlayer++, "Josh"));
        client.Client realPlayerClient = mock(client.Client.class);
        org.mockito.Mockito.doReturn(realPlayerClient).when(player).getClient();
        assertTrue(BotConversation.say(bot, player, "where should i train at level 35?", null, said::add));
        waitFor(() -> !asked.isEmpty());
        assertTrue(!asked.isEmpty(), "the reply is waiting on the brain");
        java.util.concurrent.CountDownLatch checked = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            checked.countDown();
            return mock(server.Trade.class);
        }).when(player).getTrade();
        answer.complete(Optional.of("ant tunnel is good at 35"));
        assertTrue(checked.await(5, java.util.concurrent.TimeUnit.SECONDS), "busy state checked at delivery");
        assertTrue(said.isEmpty(), said.toString());
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

    // ── ticket 26 ──

    @Test
    void aRememberedPlayerIsGreetedAsSomeoneTheBotKnows() throws Exception {
        int player = nextPlayer;
        BotMemory.remember(40_000 + player, player, List.of("wants to be a bishop"), BotMemory.Source.DIRECT, 0, 0,
                System.currentTimeMillis() - 86_400_000L);
        brainReply = Optional.empty();
        whisper("hey");
        waitFor(() -> !said.isEmpty());
        assertTrue(BotRecognition.HELLO_AGAIN_NO_NAME.contains(said.get(0))
                || BotRecognition.HELLO_AGAIN.stream().anyMatch(f -> said.get(0).equalsIgnoreCase(String.format(f, "Josh"))),
                said.toString());
        String system = asked.get(0).get(0).content();
        assertTrue(system.contains("You know Josh from before") && system.contains("wants to be a bishop"), system);
    }

    @Test
    void aStrangerGetsAPlainHello() throws Exception {
        brainReply = Optional.empty();
        whisper("hey");
        waitFor(() -> !said.isEmpty());
        assertTrue(ScriptedReplies.GREETING.contains(said.get(0)), said.toString());
        assertTrue(!asked.get(0).get(0).content().contains("You know Josh"));
    }

    @Test
    void aMerchantSaysWhatItSellsEvenWhenTheModelIsDown() throws Exception {
        MerchantStock.useItemNamesForTest(id -> "Scroll for Gloves for ATT 60%");
        try {
            brainReply = Optional.empty();
            soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot bot =
                    mock(soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot.class);
            when(bot.getChr()).thenReturn(character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Shopz"));
            soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory inventory =
                    new soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeInventory();
            inventory.setItemForSaleMain(new client.inventory.Item(2040804, (short) 0, (short) 1));
            soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants wants =
                    new soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeWants();
            wants.setMesoWanted(12_000_000);
            when(bot.getTradeInventory()).thenReturn(inventory);
            when(bot.getTradeWants()).thenReturn(wants);
            when(bot.getTradeMode()).thenReturn(soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM.TradeMode.SELLING);
            assertTrue(BotConversation.say(bot, character(nextPlayer++, "Josh"), "what are you selling?", null, said::add));
            waitFor(() -> !said.isEmpty());
            assertTrue(said.get(0).contains("scroll for gloves for att 60%") && said.get(0).contains("12m"), said.toString());
            String system = asked.get(0).get(0).content();
            assertTrue(system.contains("You are selling Scroll for Gloves for ATT 60% for 12m"), system);
        } finally {
            MerchantStock.useItemNamesForTest(null);
        }
    }

    @Test
    void aBotThatNoticesAKnownPlayerSaysHiFirstThroughTheBrain() throws Exception {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");
        BotMemory.remember(chr.getId() - BotStore.BOT_ID_BASE, player.getId(), List.of("wants to be a bishop"),
                BotMemory.Source.DIRECT, 0, 0, System.currentTimeMillis() - 86_400_000L);
        assertTrue(BotConversation.greet(bot, player));
        waitFor(() -> !asked.isEmpty());
        String system = asked.get(0).get(0).content();
        assertTrue(system.contains("You know Josh from before") && system.contains("Nobody has said anything yet"), system);
        assertTrue(BotConversation.isTalking(chr.getId(), player.getId()), "the player's answer reaches the bot");
        assertTrue(!BotConversation.greet(bot, player), "never twice mid-chat");
        BotConversation.end(chr.getId(), player.getId());
    }
}
