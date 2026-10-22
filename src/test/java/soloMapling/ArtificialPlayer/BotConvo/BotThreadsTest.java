package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotStore;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        BotThreads.putLiveForTest(1, 1, 1, "a", 2, "b", BotThreads.Topic.LFG, 0); // not counted: only run() counts
        assertTrue(BotThreads.budgetLeft(1_000_000));
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
        assertEquals(10_002, BotThreads.jumpIn(2, 100000000, "Josh", "i can come too", now));
        assertNull(BotThreads.jumpIn(1, 100000000, "Josh", "i can come too", now), "another channel");
        assertNull(BotThreads.jumpIn(2, 100000000, "Josh", "i can come too", now + 60_000), "the thread is over");
        String card = BotThreads.contextFor(10_002, 5);
        assertTrue(card.contains("talking with Kimchi about looking for a party"), card);
        assertTrue(BotThreads.busy(10_001));
    }

    @Test
    void topicsParseFromGmWords() {
        assertEquals(BotThreads.Topic.GUILD_BEEF, BotThreads.Topic.parse("beef"));
        assertEquals(BotThreads.Topic.LFG, BotThreads.Topic.parse("lfg"));
        assertEquals(BotThreads.Topic.TRADE, BotThreads.Topic.parse("TRADE"));
        assertNull(BotThreads.Topic.parse("nope"));
    }
}
