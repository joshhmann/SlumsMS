package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMessagingSystem.BotAddressee;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatRoute;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;
import soloMapling.ArtificialPlayer.BotStore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 5: bot-to-bot threads and jumping in. */
class BotThreadsTest {

    @AfterEach
    void reset() {
        soloMapling.ArtificialPlayer.Reputation.useForTest(null, null, null);
        soloMapling.ArtificialPlayer.BotGossip.useForTest(null, null, null, null);
        NameGuard.useForTest(null, null);
        BotThreads.resetForTest();
        ChatBank.useForTest(null);
        System.clearProperty("solomapling.convo.threadsPer10Min");
    }

    private static Character chr(int slot, String name, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + slot);
        c.setName(name);
        c.setLevel(level);
        return c;
    }

    @Test
    void mostlyTownsAndTheFmRarelyGrindMapsNeverBossesOrEvents() {
        assertEquals(1, BotThreads.mapWeight(100000000, false, true, true));
        assertEquals(1, BotThreads.mapWeight(910000001, false, false, true));
        assertEquals(0.1, BotThreads.mapWeight(105050000, false, false, false));
        assertEquals(0, BotThreads.mapWeight(105050000, true, false, false));
        assertEquals(0, BotThreads.mapWeight(280030000, false, false, false), "Zakum's altar");
        // review 2026-10-22: event maps without an instance and mini-dungeons too
        assertEquals(0, BotThreads.mapWeight(109010000, true, false, true), "an event map with no instance attached");
        int dungeon = constants.id.MapId.HENESYS_PIG_FARM_BASE;
        assertEquals(0, BotThreads.mapWeight(dungeon, false, false, true), "a mini-dungeon");
    }

    @Test
    void everyFewMinutesPerMapAndCappedWorldWide() {
        Random rng = new Random(1);
        for (int i = 0; i < 200; i++) {
            long gap = BotThreads.gapMs(rng);
            assertTrue(gap >= 180_000 && gap <= 360_000, "" + gap);
        }
        System.setProperty("solomapling.convo.threadsPer10Min", "2");
        assertTrue(BotThreads.budgetLeft(1_000_000));
        BotThreads.putLiveForTest(1, 1, 1, "a", 2, "b", BotThreads.Topic.LFG, 0); // not counted: only claim() counts
        assertTrue(BotThreads.budgetLeft(1_000_000));
    }

    @Test
    void bothLimitsAreOneAtomicClaim() throws Exception {
        // review 2026-10-22: overlapping ticks could both pass the checks and start threads past the cap
        System.setProperty("solomapling.convo.threadsPer10Min", "6");
        long now = 5_000_000;
        java.util.concurrent.atomic.AtomicInteger won = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger sameMap = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 64; i++) {
            int map = i;
            fs.add(pool.submit(() -> {
                go.await();
                if (BotThreads.claim(BotThreads.key(1, map % 40), now, 200_000)) {
                    won.incrementAndGet();
                }
                if (BotThreads.claim(BotThreads.key(2, 777), now, 200_000)) {
                    sameMap.incrementAndGet();
                }
                return null;
            }));
        }
        go.countDown();
        for (java.util.concurrent.Future<?> f : fs) {
            f.get();
        }
        pool.shutdown();
        assertEquals(6, won.get() + sameMap.get(), "never past the world cap");
        assertTrue(sameMap.get() <= 1, "one map, one thread per gap");
    }

    @Test
    void aMapIsDueAgainOnlyAfterItsGap() {
        long k = BotThreads.key(1, 100000000);
        assertTrue(BotThreads.claim(k, 1_000, 180_000));
        assertTrue(!BotThreads.claim(k, 100_000, 180_000));
        assertTrue(BotThreads.claim(k, 181_000, 180_000));
    }

    @Test
    void topicsFitTheMapAndTheLevels() {
        Random rng = new Random(2);
        int trade = 0;
        for (int i = 0; i < 200; i++) {
            BotThreads.Topic t = BotThreads.topicFor(0, 0, 40, 45, 910000001, rng);
            assertTrue(t == BotThreads.Topic.TRADE || t == BotThreads.Topic.GOSSIP, t.name());
            trade += t == BotThreads.Topic.TRADE ? 1 : 0;
            assertTrue(BotThreads.topicFor(0, 0, 8, 9, 100000000, rng) != BotThreads.Topic.LFG, "no PQ talk at level 8");
        }
        assertTrue(trade > 100, "" + trade);
    }

    @Test
    void theModelsExchangeIsParsedAndChecked() {
        List<String[]> lines = BotThreads.parse("Kimchi: anyone lf kpq\nBob: me lol\n1. Kimchi: \"k inv u\"\n"
                + "Someone: hi\nBob: im an ai lol\nKimchi: ok", "Kimchi", "Bob");
        assertEquals(4, lines.size());
        assertEquals("Kimchi", lines.get(0)[0]);
        assertEquals("k inv u", lines.get(2)[1]);
        assertTrue(lines.stream().noneMatch(l -> l[1].contains("ai")), "AI talk is dropped");
        assertEquals(2, BotThreads.parse("A: yo\nB: sup", "Kimchi", "Bob").size());
        // qwen9b, probed 2026-10-22: a leet kid in overdrive
        assertEquals(1, BotThreads.parse("Kimchi: lf kpq\nBob: XxXxXxXxXxXxXxXx !!!", "Kimchi", "Bob").size());
        assertTrue(!BotThreads.gibberish("hahaha nice") && !BotThreads.gibberish("noooo way"));
    }

    @Test
    void withoutTheModelTheBankOpensAndTheOtherReacts() throws Exception {
        ChatBank.useForTest(ChatBank.read(new ByteArrayInputStream(String.join("\n",
                "1\tthread_lfg\tany\tany\tany\t-\tlf1 kpq anyone",
                "2\ttalk\tany\tany\tany\t-\tya same").getBytes(StandardCharsets.UTF_8))));
        Character a = chr(70_001, "Kimchi", 25);
        Character b = chr(70_002, "Bob", 27);
        List<String[]> lines = BotThreads.fallback(BotThreads.Topic.LFG, a, b, new Random(3));
        assertEquals(2, lines.size());
        assertEquals("Kimchi", lines.get(0)[0]);
        assertEquals("lf1 kpq anyone", lines.get(0)[1]);
        assertEquals("ya same", lines.get(1)[1]);
    }

    @Test
    void aPlayerJumpsInAndTheLastSpeakerAnswersWithTheThreadInItsCard() {
        long now = System.currentTimeMillis();
        BotThreads.putLiveForTest(2, 100000000, 10_001, "Kimchi", 10_002, "Bob", BotThreads.Topic.LFG, now + 30_000);
        int id = BotThreads.liveView(2, 100000000, now).id();
        assertEquals(10_002, BotThreads.jumpIn(2, 100000000, id, "Josh", "i can come too", now));
        assertNull(BotThreads.jumpIn(1, 100000000, id, "Josh", "i can come too", now), "another channel");
        assertNull(BotThreads.jumpIn(2, 100000000, id, "Josh", "i can come too", now + 60_000), "the thread is over");
        String card = BotThreads.contextFor(10_002, 5);
        assertTrue(card.contains("talking with Kimchi about looking for a party"), card);
        assertTrue(BotThreads.busy(10_001));
    }

    @Test
    void routingSeesTheLiveThreadItsLastSpeakerAndWhenItLastSpoke() {
        long now = System.currentTimeMillis();
        BotThreads.putLiveForTest(2, 100000000, 10_001, "Kimchi", 10_002, "Bob", BotThreads.Topic.LFG, now + 30_000);
        BotThreads.View v = BotThreads.liveView(2, 100000000, now);
        assertEquals(java.util.List.of(10_001, 10_002), v.botIds());
        assertEquals(10_002, v.lastSpeakerId());
        assertEquals(now + 30_000 - BotThreads.JOIN_MS, v.lastLineAt());
        assertNull(BotThreads.liveView(1, 100000000, now), "another channel");
        assertNull(BotThreads.liveView(2, 100000000, now + 60_000), "over");
    }

    @Test
    void topicsParseFromGmWords() {
        assertEquals(BotThreads.Topic.GUILD_BEEF, BotThreads.Topic.parse("beef"));
        assertEquals(BotThreads.Topic.LFG, BotThreads.Topic.parse("lfg"));
        assertEquals(BotThreads.Topic.TRADE, BotThreads.Topic.parse("TRADE"));
        assertNull(BotThreads.Topic.parse("nope"));
    }

    /**
     * TEST log cv-5-20261024T222812Z: two bot-to-bot LFG threads were live on one map - KimchiPika and Sair6 about
     * Kerning PQ, 129 px from the player, and ownagetauro and picklemaple about Orbis PQ, 4964 px away. Only the far
     * one was held, so "Kerning PQ? i can come too" never reached the thread it named.
     */
    private void twoLiveThreads(long now) {
        BotThreads.putLiveForTest(2, 100000000, 10_004, "Sair6", 10_001, "KimchiPika", BotThreads.Topic.LFG,
                "Kerning PQ", now + 30_000);
        BotThreads.putLiveForTest(2, 100000000, 10_002, "ownagetauro", 10_005, "picklemaple", BotThreads.Topic.LFG,
                "Orbis PQ", now + 29_000);
    }

    @Test
    void twoLiveThreadsOnOneMapTheLineJoinsTheOneItNames() {
        long now = 9_000_000;
        twoLiveThreads(now);
        String line = "Kerning PQ? i can come too";
        BotThreads.View thread = BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0), id -> true);
        assertNotNull(thread, "a live thread answers this line");
        assertTrue(thread.topic().contains("Kerning PQ"), thread.topic());
        assertEquals(10_001, BotThreads.jumpIn(2, 100000000, thread.id(), "Josh", line, now),
                "the thread's own last speaker answers");
    }

    /**
     * The cv-5 shadowing, one ranking step further: a thread 5000 px away whose subject shares more words must not
     * beat the thread beside the player, because the router then drops the far one for being unheard and the line
     * never reaches the thread the player is standing in.
     */
    @Test
    void anInaudibleThreadIsNoCandidateEvenWhenItFitsTheLineBetter() {
        long now = 9_000_000;
        BotThreads.putLiveForTest(2, 100000000, 10_001, "KimchiPika", 10_004, "Sair6", BotThreads.Topic.LFG,
                "Ludi PQ", now + 30_000);
        BotThreads.putLiveForTest(2, 100000000, 10_002, "ownagetauro", 10_005, "picklemaple", BotThreads.Topic.LFG,
                "Ludi PQ party come", now + 29_000);
        String line = "Ludi PQ? i can come too, anyone party?";
        BotThreads.View unfiltered = BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0), id -> true);
        assertEquals(10_002, unfiltered.botIds().get(0), "the far thread is the better keyword fit, so it used to win");

        BotThreads.View heard = BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0),
                List.of(10_001, 10_004)::contains);
        assertNotNull(heard);
        assertEquals(List.of(10_001, 10_004), heard.botIds(), "only the thread the player can hear is a candidate");
    }

    @Test
    void twoLiveThreadsNoOneCanHearAreNotCreditedWithTheLine() {
        long now = 9_000_000;
        twoLiveThreads(now);
        assertNull(BotThreads.liveView(2, 100000000, now, new BotThreads.Ask("Kerning PQ? i can come too", 0, 0),
                id -> false), "an unheard thread is no candidate");

        String line = "Kerning PQ? i can come too";
        java.util.Map<Integer, ChatRoute.Bot> bots = new java.util.LinkedHashMap<>();
        bots.put(10_001, new ChatRoute.Bot(10_001, "KimchiPika", 100, 0, true));
        bots.put(10_002, new ChatRoute.Bot(10_002, "ownagetauro", 5_000, 0, true));
        List<MapChatLog.Line> lines = List.of(
                new MapChatLog.Line(10_001, "KimchiPika", "yeah join plz, our leader dcd lolz!!!", now - 6_000, true),
                new MapChatLog.Line(7, "Josh", line, now, false));
        ChatRoute.Decision d = ChatRoute.decide(lines, new ChatRoute.Player(7, "Josh", line, 0, 0, null, -1, 0), bots,
                BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0), id -> false),
                BotAddressee.resolve(line, List.of(new BotAddressee.Candidate(10_001, "KimchiPika"))),
                null, now);
        assertNull(d.thread(), "no thread is credited");
        assertEquals(ChatRoute.Step.CLOSEST, d.step(), "the line routes as it would with no thread at all");
    }

    @Test
    void theRouteCreditsTheThreadTheLineIsAboutNotItsQuestion() {
        long now = 9_000_000;
        twoLiveThreads(now);
        String line = "Kerning PQ? i can come too";
        java.util.Map<Integer, ChatRoute.Bot> bots = new java.util.LinkedHashMap<>();
        bots.put(10_001, new ChatRoute.Bot(10_001, "KimchiPika", 100, 0, true));
        bots.put(10_004, new ChatRoute.Bot(10_004, "Sair6", 130, 0, true));
        bots.put(10_002, new ChatRoute.Bot(10_002, "ownagetauro", 5000, 0, true));
        bots.put(10_005, new ChatRoute.Bot(10_005, "picklemaple", 5100, 0, true));
        List<MapChatLog.Line> lines = List.of(
                new MapChatLog.Line(10_001, "KimchiPika", "yeah join plz, our leader dcd lolz!!!", now - 6_000, true),
                new MapChatLog.Line(10_004, "Sair6", "lvl 31 cleric here, got room?", now - 4_000, true),
                new MapChatLog.Line(10_002, "ownagetauro", "jaja amigo u lvl 51? q party orbis pq porfa", now - 3_000,
                        true),
                new MapChatLog.Line(7, "Josh", line, now, false));
        ChatRoute.Decision d = ChatRoute.decide(lines, new ChatRoute.Player(7, "Josh", line, 0, 0, null, -1, 0), bots,
                BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0),
                        id -> ChatRoute.inBubble(bots.get(id), 0, 0)),
                BotAddressee.resolve(line, List.of(new BotAddressee.Candidate(10_001, "KimchiPika"),
                        new BotAddressee.Candidate(10_004, "Sair6"), new BotAddressee.Candidate(10_002, "ownagetauro"),
                        new BotAddressee.Candidate(10_005, "picklemaple"))),
                null, now);
        assertEquals(ChatRoute.Step.THREAD_WORDS, d.step());
        assertEquals(10_001, d.botId(), "the last speaker of the thread the line is about");
        assertNotNull(d.thread());
        assertTrue(d.thread().topic().contains("Kerning PQ"), d.thread().topic());
    }

    // ── 1.6.0 S4: grounded gossip ──

    /** Kimchi (slot 1) and Nayrb (slot 2) are a crew; player 4242 (Joshh) KS'd Nayrb. */
    private static void crewKnowsAKs() {
        int b = BotStore.BOT_ID_BASE;
        long crew = soloMapling.ArtificialPlayer.Reputation.CREW | (b + 1);
        soloMapling.ArtificialPlayer.Reputation.useForTest(id -> id == b + 1 || id == b + 2 ? new long[]{crew}
                : new long[]{id}, null, id -> false);
        java.util.Map<Integer, String> names = java.util.Map.of(b + 1, "Kimchi", b + 2, "Nayrb", b + 3, "Bob", 4242, "Joshh");
        soloMapling.ArtificialPlayer.BotGossip.useForTest(null, id -> List.of(), names::get, (bot, player) -> true);
        soloMapling.ArtificialPlayer.Reputation.witnessed(b + 2, 4242, soloMapling.ArtificialPlayer.Reputation.Mark.KS,
                System.currentTimeMillis());
    }

    @Test
    void gossipIsAboutARealSubject() {
        crewKnowsAKs();
        assertEquals("Joshh ksed Nayrb", BotThreads.about(BotThreads.Topic.GOSSIP, chr(1, "Kimchi", 30), chr(3, "Bob", 30)));
        String training = BotThreads.about(BotThreads.Topic.TRAINING, chr(3, "Bob", 30), chr(1, "Kimchi", 31));
        assertNotNull(training, "training is grounded too");
        assertTrue(training.contains("level"), training);
        String nothing = BotThreads.about(BotThreads.Topic.GOSSIP, chr(3, "Bob", 30), chr(1, "Kimchi", 31));
        assertNotNull(nothing);
        assertTrue(!nothing.contains("Joshh"), "Bob's circle knows nothing about Joshh: " + nothing);
    }

    @Test
    void theGroundedPromptNamesOnlyRealPeople() {
        crewKnowsAKs();
        Character a = chr(1, "Kimchi", 30);
        Character b = chr(3, "Bob", 30);
        soloMapling.ArtificialPlayer.BotGossip.Fact fact = BotThreads.ground(BotThreads.Topic.GOSSIP, a, b, List.of(),
                System.currentTimeMillis(), new Random(1));
        String prompt = BotThreads.threadPrompt(a, b, BotThreads.Topic.GOSSIP, fact.about(), "Henesys", fact);
        assertTrue(prompt.contains("What really happened: Joshh ksed Nayrb"), prompt);
        assertTrue(prompt.contains("The only other players you may name: Joshh, Nayrb; never make up any other player name"),
                prompt);
        assertTrue(prompt.contains("Kimchi first"), prompt);
        String plain = BotThreads.threadPrompt(a, b, BotThreads.Topic.LFG, "Kerning PQ", "Henesys");
        assertTrue(plain.contains("name no other player"), plain);
    }

    @Test
    void withoutTheModelTheTellerOpensWithTheTrueStory() throws Exception {
        crewKnowsAKs();
        ChatBank.useForTest(ChatBank.read(new ByteArrayInputStream(String.join("\n",
                "1\tthread_gossip\tany\tany\tany\t-\tsomeone got scammed again",
                "2\ttalk\tany\tany\tany\t-\tya same").getBytes(StandardCharsets.UTF_8))));
        Character a = chr(1, "Kimchi", 30);
        Character b = chr(3, "Bob", 30);
        soloMapling.ArtificialPlayer.BotGossip.Fact fact = BotThreads.ground(BotThreads.Topic.GOSSIP, a, b, List.of(),
                System.currentTimeMillis(), new Random(1));
        List<String[]> lines = BotThreads.fallback(BotThreads.Topic.GOSSIP, a, b, fact, new Random(1));
        assertEquals("Kimchi", lines.get(0)[0]);
        assertTrue(lines.get(0)[1].contains("Joshh") && lines.get(0)[1].contains("Nayrb"), lines.get(0)[1]);
        assertEquals("Bob", lines.get(1)[0]);
    }

    @Test
    void theGuardRewritesOrDropsLinesNamingStrangers() {
        NameGuard.useForTest(name -> name.equalsIgnoreCase("Joshh"), null);
        List<String[]> out = BotThreads.guard(List.of(
                        new String[]{"Kimchi", "did u see xxslayerxx try to ks me"},
                        new String[]{"Bob", "Did you see PartyKing married RibbonQueen in Amoria?"},
                        new String[]{"Kimchi", "Joshh ksed Nayrb lol"},
                        new String[]{"Bob", "lol rip"}),
                java.util.Set.of("Kimchi", "Bob", "Nayrb"), 100000000);
        assertEquals(3, out.size(), "two strangers: dropped");
        assertEquals("did u see some guy try to ks me", out.get(0)[1]);
        assertEquals("Joshh ksed Nayrb lol", out.get(1)[1]);
        assertEquals("lol rip", out.get(2)[1]);
    }

    @Test
    void socializersGossipMore() {
        int low = 0, high = 0;
        for (int i = 0; i < 2000; i++) {
            low += BotThreads.topicFor(0, 0, 10, 10, 100000000, 0.05, new Random(i)) == BotThreads.Topic.GOSSIP ? 1 : 0;
            high += BotThreads.topicFor(0, 0, 10, 10, 100000000, 0.6, new Random(i)) == BotThreads.Topic.GOSSIP ? 1 : 0;
        }
        assertTrue(high > low * 2, "socializers " + high + " vs others " + low);
    }
}
