package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 36/37's fast/slow path through {@link BotConversation}, with a fake brain, no Decide and a fake memory table. */
class BotConversationFlowTest {

    @TempDir
    java.nio.file.Path tmp;

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
        soloMapling.ArtificialPlayer.BotConvo.ChatBank.useForTest(null);
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

    /** Brief step 4: when the model says nothing, the plan's script is delivered through the same guards. */
    @Test
    void aPlannedGreetingReachesThePlayerWhenTheModelSaysNothing() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        Optional<String> was = brainReply;
        brainReply = Optional.empty();
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
            when(bot.getChr()).thenReturn(chr);
            // a real client, not a BotClient: a bot-to-bot pair plans nothing (BotTalk decides those)
            Character player = org.mockito.Mockito.spy(character(nextPlayer++, "Josh"));
            org.mockito.Mockito.doReturn(mock(client.Client.class)).when(player).getClient();
            assertTrue(BotConversation.say(bot, player, "hey", null, said::add));
            waitFor(() -> !said.isEmpty());
            assertEquals(1, said.size(), said.toString());
            assertEquals(1, asked.size(), "the model is asked first; the plan answers when it says nothing");
            soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner.View view =
                    soloMapling.ArtificialPlayer.BotConvo.BotReplyView.of(bot, player, chr.getId() - BotStore.BOT_ID_BASE,
                            null, "hey", soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage.rules("hey"), false, false);
            soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner.Plan plan =
                    soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner.plan(view);
            assertEquals(soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner.Move.HELLO, plan.move());
            java.util.Set<String> pool = new java.util.LinkedHashSet<>();
            for (int seed = 0; seed < 400; seed++) {
                String candidate = soloMapling.ArtificialPlayer.BotConvo.ReplyPlanner.line(view, plan,
                        new java.util.Random(seed));
                if (candidate != null) {
                    pool.add(candidate);
                }
            }
            assertTrue(pool.contains(said.get(0)),
                    "the player hears one of the planned scripts, unchanged by delivery: " + said + " not in " + pool);
            assertTrue(!soloMapling.ArtificialPlayer.BotConvo.SpeechGuard.hasSlot(said.get(0)),
                    "so the delivery guard has no slot to fill: " + said);
        } finally {
            brainReply = was;
            System.clearProperty("solomapling.convo.planner");
        }
    }

    /**
     * m02: the transcript row says which move worded the answer, so t-pl-1 (auto-QA) judges what the planner decided
     * instead of guessing from the words. The move rides on the cue the delivery went through, and a chat the player
     * started carries no {@code open}.
     */
    @Test
    void aPlannedAnswerPutsItsMoveInTheTranscript() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        System.setProperty("solomapling.convo.quality", "true");
        System.setProperty("solomapling.convo.transcriptDir", tmp.resolve("logs").toString());
        System.setProperty("solomapling.convo.dataDir", tmp.resolve("data").toString());
        Optional<String> was = brainReply;
        brainReply = Optional.empty(); // the plan answers, so the delivered line is the planned one
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Planb");
            when(bot.getChr()).thenReturn(chr);
            Character player = org.mockito.Mockito.spy(character(nextPlayer++, "Josh"));
            org.mockito.Mockito.doReturn(mock(client.Client.class)).when(player).getClient();
            assertTrue(BotConversation.say(bot, player, "hey", null, said::add));
            waitFor(() -> !said.isEmpty());
            java.nio.file.Path transcript =
                    soloMapling.ArtificialPlayer.BotConvo.ConvoQuality.transcriptFile(java.time.LocalDate.now());
            java.util.List<String> mine = List.of(); // another test's delivery in flight must not be read as ours
            for (int i = 0; i < 200 && mine.isEmpty(); i++) { // the row is written off the chat thread
                mine = java.nio.file.Files.exists(transcript) ? java.nio.file.Files.readAllLines(transcript).stream()
                        .filter(r -> r.contains("\"bot\":\"Planb\"")).toList() : List.of();
                Thread.sleep(10);
            }
            assertEquals(1, mine.size(), mine.toString());
            assertTrue(mine.get(0).contains("\"move\":\"hello\""), mine.get(0));
            assertFalse(mine.get(0).contains("\"open\""), "the player spoke first, so this reply is not an opening: "
                    + mine.get(0));
        } finally {
            brainReply = was;
            System.clearProperty("solomapling.convo.planner");
            System.clearProperty("solomapling.convo.transcriptDir");
            System.clearProperty("solomapling.convo.dataDir");
        }
    }

    /**
     * m02: the greet path plans its own hello and says so in the QA log with "(it started the chat)", which is how
     * t-pl-1 knows a row is a bot-started chat rather than an answer. Synchronous: the log is written before the
     * model is asked, so the test does not depend on the delivery.
     */
    @Test
    void aBotStartedChatIsPlannedAsOne() {
        System.setProperty("solomapling.convo.planner", "true");
        java.util.List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        soloMapling.qa.QaLog.SinkHandle h = soloMapling.qa.QaLog.sink((topic, mapId, chars, message) -> {
            if (message.startsWith("PLAN ")) {
                lines.add(message);
            }
        });
        h.topics(List.of(soloMapling.qa.QaLog.Topic.CONVO));
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
            when(bot.getChr()).thenReturn(chr);
            when(bot.activitySnapshot()).thenReturn(BotRoster.Activity.of("social", 0, null));
            Character player = org.mockito.Mockito.spy(character(nextPlayer++, "Josh"));
            org.mockito.Mockito.doReturn(mock(client.Client.class)).when(player).getClient();
            assertTrue(BotConversation.greet(bot, player), "a free social bot says hi on sight");
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.get(0).contains(" hello "), lines.get(0));
            assertTrue(lines.get(0).endsWith("(it started the chat)"), lines.get(0));
        } finally {
            h.close();
            System.clearProperty("solomapling.convo.planner");
            SocialGate.forgetBotChatsForTest();
        }
    }

    /**
     * Stage B: the gates answer a party ask and the planner words their verdict. This wanderer is not a social bot, so
     * {@code BotChatActions.party} declines; the prompt the model gets is the planned one and must not read as a yes.
     */
    @Test
    void aRefusedPartyAskIsPlannedFromTheGatesVerdict() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
            when(bot.getChr()).thenReturn(chr);
            Character player = org.mockito.Mockito.spy(character(nextPlayer++, "Josh"));
            org.mockito.Mockito.doReturn(mock(client.Client.class)).when(player).getClient();
            assertTrue(BotConversation.say(bot, player, "can i join ur pt", null, said::add));
            waitFor(() -> !asked.isEmpty());
            String system = asked.get(0).get(0).content();
            assertTrue(system.contains("Answer their party ask with a no"), "the planned party prompt:\n" + system);
            assertTrue(system.contains("Say no to the party"), "the gates' own words ride along:\n" + system);
            assertFalse(system.contains("Say yes"), system);
            assertTrue(system.length() < 1000, "a planned prompt is short: " + system.length() + " chars");
        } finally {
            System.clearProperty("solomapling.convo.planner");
        }
    }

    /** REVIEW-A2 item 1: with the planner off, a chat end writes nothing new - the old path stays as it was. */
    @Test
    void endingAChatWithThePlannerOffWritesNoProgressRow() throws Exception {
        FakeMemoryTable table = new FakeMemoryTable(false);
        table.install();
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");
        assertTrue(BotConversation.say(bot, player, "hey", null, said::add));
        waitFor(() -> !said.isEmpty());
        BotConversation.end(chr.getId(), player.getId());
        assertFalse(table.rows.stream().anyMatch(r -> "PROGRESS".equals(r.get("Kind"))),
                "the planner is off: no new memory row " + table.rows);
        assertEquals(0, BotMemory.progressLevel(chr.getId() - BotStore.BOT_ID_BASE, player.getId()),
                "nothing was recorded about the player");
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
    void bankModeAnswersAnActivityQuestionInsteadOfAcknowledgingIt() throws Exception {
        brainReply = Optional.empty();
        System.setProperty("solomapling.convo.bank", "true");
        var bytes = "1\ttalk\tany\tany\tany\t-\toh nice\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        soloMapling.ArtificialPlayer.BotConvo.ChatBank.useForTest(
                soloMapling.ArtificialPlayer.BotConvo.ChatBank.read(new java.io.ByteArrayInputStream(bytes)));
        for (String line : List.of("what u doing", "wyd?", "what are you up to rn?")) {
            int before = said.size();
            whisper(line);
            waitFor(() -> said.size() > before);
            assertEquals(before + 1, said.size(), "one answer to " + line);
            assertEquals("chatting with u rn", said.get(before), "answer the question without inventing an activity");
        }
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

    @Test
    void aSecondHelloToTheSamePlayerIsSmallTalkNotAnotherGreeting() throws Exception {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");

        assertTrue(BotConversation.say(bot, player, "hi", null, said::add));
        waitFor(() -> said.size() == 1);
        assertEquals(List.of("lol ya ant tunnel is ok"), said, "the first hello gets the brain");
        assertEquals(1, asked.size());

        assertTrue(BotConversation.say(bot, player, "hey", null, said::add), "the small talk answer goes out");
        waitFor(() -> said.size() == 2);
        assertEquals(2, said.size(), said.toString());
        assertEquals(1, asked.size(), "b248: the second hello is small talk, not another greeting for the model");
        assertTrue(ScriptedReplies.TALK.contains(said.get(1)), "small talk, not a greeting: " + said.get(1));
        assertTrue(!ScriptedReplies.GREETING.contains(said.get(1)), "another hello: " + said.get(1));
        BotConversation.end(chr.getId(), player.getId());
    }

    /**
     * m02 stage B: a bot starts a chat with a player too, but only when it is free - never in the middle of a grind
     * or a party quest, and never more than about once an hour per player.
     */
    @Test
    void aBusyBotNeverStartsAChatWithAPlayer() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Grindr");
            when(bot.getChr()).thenReturn(chr);
            when(bot.activitySnapshot())
                    .thenReturn(soloMapling.ArtificialPlayer.BotRoster.Activity.of("grind", 0, null));
            Character player = character(nextPlayer++, "Josh");
            assertFalse(BotConversation.greet(bot, player), "grinding: the bot does not open a chat");
            assertFalse(BotConversation.isTalking(chr.getId(), player.getId()), "and no chat was opened");
            when(bot.activitySnapshot())
                    .thenReturn(soloMapling.ArtificialPlayer.BotRoster.Activity.of("opq", 0, null));
            assertFalse(BotConversation.greet(bot, character(nextPlayer++, "Mika")), "a PQ is busy too");
        } finally {
            System.clearProperty("solomapling.convo.planner");
        }
    }

    /**
     * m02 fix round 3 (answer 4): one bot-started chat per player per hour, from any bot. Another bot's hello holds
     * this bot's turn, and the switch off keeps the legacy greeting untouched.
     */
    @Test
    void anotherBotsHelloHoldsThePlayersChatForTheHour() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
            when(bot.getChr()).thenReturn(chr);
            when(bot.activitySnapshot())
                    .thenReturn(soloMapling.ArtificialPlayer.BotRoster.Activity.of("social", 0, null));
            Character player = Character.getDefault(mock(client.Client.class));
            player.setID(nextPlayer++);
            player.setName("Josh");
            player.setLevel(40);
            SocialGate.noteBotChat(BotStore.BOT_ID_BASE + 5, player.getId(), System.currentTimeMillis());
            assertFalse(BotConversation.greet(bot, player), "another bot said hi first: this one waits its turn");
            assertFalse(BotConversation.isTalking(chr.getId(), player.getId()), "and no chat was opened");

            System.clearProperty("solomapling.convo.planner");
            assertTrue(BotConversation.greet(bot, player), "switch off: the legacy greeting has no per-player cap");
            assertTrue(SocialGate.botChatAllowed(BotStore.BOT_ID_BASE + 5, player.getId(),
                    System.currentTimeMillis()), "the legacy path records no bot-started chat");
            waitFor(() -> !asked.isEmpty());
            BotConversation.end(chr.getId(), player.getId());
        } finally {
            System.clearProperty("solomapling.convo.planner");
            SocialGate.forgetBotChatsForTest();
        }
    }

    /** m02 stage B: with the planner on, the bot's own opening line is a planned move, not the free-chat prompt. */
    @Test
    void aBotThatStartsAChatPlansItsOpeningLine() throws Exception {
        System.setProperty("solomapling.convo.planner", "true");
        try {
            BotSM bot = mock(TownWandererBot.class);
            Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
            when(bot.getChr()).thenReturn(chr);
            when(bot.activitySnapshot())
                    .thenReturn(soloMapling.ArtificialPlayer.BotRoster.Activity.of("social", 0, null));
            // a real player, not the test's BotClient stand-in: the planner leaves bot-to-bot chat alone
            Character player = Character.getDefault(mock(client.Client.class));
            player.setID(nextPlayer++);
            player.setName("Josh");
            player.setLevel(40);
            assertTrue(BotConversation.greet(bot, player));
            waitFor(() -> !asked.isEmpty());
            String system = asked.get(0).get(0).content();
            assertTrue(system.contains("You are Walkr, a real person playing this game in 2008"),
                    "the planned prompt: " + system);
            assertTrue(system.contains("Say hi"), "the move is the instruction: " + system);
            assertTrue(!system.contains("Nobody has said anything yet"),
                    "not the free-chat opening prompt: " + system);
            BotConversation.end(chr.getId(), player.getId());
        } finally {
            System.clearProperty("solomapling.convo.planner");
        }
    }

    @Test
    void aBotDoesNotGreetTheSamePlayerTwiceInTheGap() throws Exception {
        BotSM bot = mock(TownWandererBot.class);
        Character chr = character(BotStore.BOT_ID_BASE + 40_000 + nextPlayer, "Walkr");
        when(bot.getChr()).thenReturn(chr);
        Character player = character(nextPlayer++, "Josh");

        assertTrue(BotConversation.greet(bot, player));
        waitFor(() -> !said.isEmpty() || !asked.isEmpty());
        BotConversation.end(chr.getId(), player.getId());
        waitFor(() -> !BotConversation.isTalking(chr.getId(), player.getId()));

        assertTrue(!BotConversation.greet(bot, player), "b248: not again inside the greeting gap");
        BotConversation.end(chr.getId(), player.getId());
    }
}
