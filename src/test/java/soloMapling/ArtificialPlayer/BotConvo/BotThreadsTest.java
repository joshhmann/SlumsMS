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
        BotThreads.View thread = BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0));
        assertNotNull(thread, "a live thread answers this line");
        assertTrue(thread.topic().contains("Kerning PQ"), thread.topic());
        assertEquals(10_001, BotThreads.jumpIn(2, 100000000, thread.id(), "Josh", line, now),
                "the thread's own last speaker answers");
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
                BotThreads.liveView(2, 100000000, now, new BotThreads.Ask(line, 0, 0)),
                BotAddressee.resolve(line, List.of(new BotAddressee.Candidate(10_001, "KimchiPika"),
                        new BotAddressee.Candidate(10_004, "Sair6"), new BotAddressee.Candidate(10_002, "ownagetauro"),
                        new BotAddressee.Candidate(10_005, "picklemaple"))),
                null, now);
        assertEquals(ChatRoute.Step.THREAD_WORDS, d.step());
        assertEquals(10_001, d.botId(), "the last speaker of the thread the line is about");
        assertNotNull(d.thread());
        assertTrue(d.thread().topic().contains("Kerning PQ"), d.thread().topic());
    }
}
