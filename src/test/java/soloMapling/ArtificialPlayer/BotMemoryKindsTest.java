package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotConvo.RelationshipCard;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0 slice S3, the owner's memory kinds: episodes written without the LLM, the last-topic and inside-joke slots,
 * follow-up tags, the best episodes on the card, pruning by age and use, the LRU cache, and a database without
 * migration 030.
 */
class BotMemoryKindsTest {

    static final long HOUR = TimeUnit.HOURS.toMillis(1);
    static final long DAY = TimeUnit.DAYS.toMillis(1);
    static final int SLOT = 7;
    static final int BOT = BotStore.BOT_ID_BASE + SLOT;
    static final int PLAYER = 1001;

    private FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong clock = new AtomicLong(100 * DAY);
    private final List<SocialProfile.Push> pushes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotEpisodes.useForTest(Runnable::run, map -> map == 101000000 ? "ellinia" : null, (bot, push) -> pushes.add(push));
        SocialHooks.useForTest(null, id -> "Red Craven", clock::get);
    }

    @AfterEach
    void tearDown() {
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
        BotEpisodes.useForTest(null, null, null);
        SocialHooks.useForTest(null, null, null);
    }

    private static Character chr(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private List<BotMemory.Note> notes() {
        return BotMemory.about(SLOT, PLAYER);
    }

    private void reload() {
        table.install(); // forget the cache and read the "database" back, like after a restart
    }

    @Test
    void affinityEventsLeaveEpisodesWithoutTheLlm() {
        assertFalse(BotBrains.get().isLive(), "the test config has no LLM");
        long now = clock.get();
        BotEpisodes.onAffinity(BOT, PLAYER, AffinityEvent.PARTY, 101000000, now);
        BotEpisodes.onAffinity(BOT, PLAYER, AffinityEvent.PARTY, 101000000, now + HOUR); // re-join: the same episode
        BotEpisodes.onAffinity(BOT, PLAYER, AffinityEvent.HEAL, 101000000, now);
        BotEpisodes.onAffinity(BOT, PLAYER, AffinityEvent.KS, 101000000, now); // grudges come from feeling notes
        BotEpisodes.onAffinity(BOT, BotStore.BOT_ID_BASE + 9, AffinityEvent.PARTY, 0, now); // bot-bot: slice S2's
        reload();
        List<BotMemory.Note> notes = notes();
        assertEquals(2, notes.size(), notes.toString());
        assertEquals(BotMemory.Kind.PARTY, notes.get(0).kind());
        assertEquals("partied together in ellinia", notes.get(0).text());
        assertEquals(3, notes.get(0).weight());
        assertEquals(BotMemory.Kind.HELP, notes.get(1).kind());
        assertEquals("healed me at ellinia", notes.get(1).text());
        assertTrue(BotMemory.about(SLOT, BotStore.BOT_ID_BASE + 9).isEmpty());
        // a day later the same thing is a new episode
        BotEpisodes.onAffinity(BOT, PLAYER, AffinityEvent.PARTY, 101000000, now + DAY);
        assertEquals(3, notes().size());
    }

    @Test
    void aPqClearWithARealPlayerIsAnEpisodeAndASmallSocializerPush() {
        Character bot = chr(BOT, "Nayrb");
        Character otherBot = chr(BotStore.BOT_ID_BASE + 8, "Bot8");
        Character player = chr(PLAYER, "Josh");
        SocialHooks.onPqClear("KerningPQ", List.of(player, bot, otherBot));
        assertEquals(List.of("did KPQ together"), notes().stream().map(BotMemory.Note::text).toList());
        assertEquals(BotMemory.Kind.PQ, notes().get(0).kind());
        assertEquals(6, notes().get(0).weight());
        assertTrue(BotMemory.about(SLOT, BotStore.BOT_ID_BASE + 8).isEmpty(), "bot-bot pairs are slice S2's");
        assertEquals(List.of(SocialProfile.Push.PQ_TOGETHER, SocialProfile.Push.PQ_TOGETHER), pushes,
                "one small push per bot that cleared it with a real player");
        clock.addAndGet(2 * HOUR);
        SocialHooks.onPqClear("ZakumBattle", List.of(player, bot));
        assertEquals("beat zakum together", notes().get(1).text());
        assertEquals(BotMemory.Kind.BOSS, notes().get(1).kind());
        assertEquals(2, pushes.size(), "at most one push a day");
        SocialHooks.onPqClear("WeddingChapel", List.of(player, bot));
        SocialHooks.onPqClear("3rdJob_magician", List.of(player, bot));
        assertEquals(2, notes().size(), "weddings and job quests are not PQs");
    }

    @Test
    void aChatLeavesItsTopicAJokeAndTaggedGoalsWithoutTheLlm() {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BOT);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(PLAYER);
        BotMemory.writeConversationNotes(bot, player, List.of(BotBrain.Turn.system("persona"),
                BotBrain.Turn.user("im saving for a zhelm"), BotBrain.Turn.assistant("nice, gotta do kpq first tho"),
                BotBrain.Turn.user("my pet ate my return scroll"), BotBrain.Turn.assistant("lmao"),
                BotBrain.Turn.user("also trying for 4th job this week")));
        assertEquals("4th job", BotMemory.lastTopic(SLOT, PLAYER).orElseThrow().text());
        assertEquals("my pet ate my return scroll", BotMemory.insideJoke(SLOT, PLAYER).orElseThrow().text());
        List<BotMemory.Note> goals = notes().stream().filter(n -> n.kind() == BotMemory.Kind.GOAL).toList();
        assertEquals(List.of("wants zhelm", "trying for 4th job"), goals.stream().map(BotMemory.Note::text).toList());
        assertEquals(List.of("item:zhelm", "goal:4th job"), goals.stream().map(BotMemory.Note::tag).toList());
        reload();
        assertEquals("4th job", BotMemory.lastTopic(SLOT, PLAYER).orElseThrow().text(), "the slots are stored");
        assertFalse(BotMemory.recall(SLOT, PLAYER, clock.get()).contains("4th job"), "slots are not facts");

        // the next chat replaces the topic, keeps the joke (nobody laughed), and does not repeat a pending goal
        BotMemory.writeConversationNotes(bot, player, List.of(BotBrain.Turn.user("still want that zhelm"),
                BotBrain.Turn.assistant("see u in henesys")));
        assertEquals("henesys", BotMemory.lastTopic(SLOT, PLAYER).orElseThrow().text());
        assertEquals("my pet ate my return scroll", BotMemory.insideJoke(SLOT, PLAYER).orElseThrow().text());
        assertEquals(1, notes().stream().filter(n -> n.kind() == BotMemory.Kind.TOPIC).count(), "one topic slot");
        assertEquals(2, notes().stream().filter(n -> n.kind() == BotMemory.Kind.GOAL).count());
    }

    @Test
    void taggedNotesAreDueForAFollowUpADayLaterUntilFollowedUp() {
        long now = clock.get();
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.GOAL, "wants zhelm", 4, "item:zhelm", now);
        BotMemory.remember(SLOT, PLAYER, List.of("said they're saving up for a zhelm"), BotMemory.Source.DIRECT, 0, 0, now);
        assertEquals("item:zhelm", notes().get(1).tag(), "an LLM note about an item is tagged too");
        assertTrue(BotMemory.dueFollowUps(SLOT, PLAYER, now + HOUR).isEmpty(), "not the same hour");
        assertEquals(2, BotMemory.dueFollowUps(SLOT, PLAYER, now + DAY).size());
        assertEquals(2, BotMemory.markFollowedUp(SLOT, PLAYER, "item:zhelm", now + DAY));
        assertTrue(BotMemory.dueFollowUps(SLOT, PLAYER, now + 2 * DAY).isEmpty());
        reload();
        assertTrue(BotMemory.dueFollowUps(SLOT, PLAYER, now + 2 * DAY).isEmpty(), "the follow-up is stored");
    }

    @Test
    void theCardShowsTheBestEpisodesTheTopicAndTheJoke() {
        long now = clock.get();
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.HELP, "buffed me", 2, "", now - HOUR);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PQ, "did KPQ together", 6, "", now - 2 * DAY);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.TRADE, "traded me a Red Craven", 4, "", now - DAY);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PARTY, "partied together", 3, "", now - 3 * DAY);
        BotMemory.remember(SLOT, PLAYER, List.of("likes pink capes"), BotMemory.Source.DIRECT, 0, 0, now - DAY);
        BotMemory.setSlot(SLOT, PLAYER, BotMemory.Kind.TOPIC, "zhelm", now - DAY);
        BotMemory.setSlot(SLOT, PLAYER, BotMemory.Kind.JOKE, "my pet ate my return scroll", now - DAY);

        List<BotMemory.Note> best = BotMemory.bestEpisodes(SLOT, PLAYER, now, 3);
        assertEquals(List.of("did KPQ together", "traded me a Red Craven", "partied together"),
                best.stream().map(BotMemory.Note::text).toList(), "weight first, faded by age");
        assertTrue(notes().stream().filter(n -> n.text().equals("did KPQ together")).allMatch(n -> n.lastUsedAt() == now),
                "stamped as used");

        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(BOT);
        Character player = Character.getDefault(new BotClient(0, 0));
        player.setID(PLAYER);
        player.setName("Josh");
        String card = RelationshipCard.of(bot, player, null, null, now);
        assertTrue(card.contains("Between you two: did KPQ together ("), card);
        assertTrue(card.contains("traded me a Red Craven (") && card.contains("partied together ("), card);
        assertFalse(card.contains("buffed me"), "only the best three: " + card);
        assertTrue(card.contains("Last time you talked about zhelm ("), card);
        assertTrue(card.contains("Inside joke: \"my pet ate my return scroll\""), card);
        assertTrue(card.contains("You remember: likes pink capes"), card);
        assertTrue(card.length() <= 520, card);
    }

    @Test
    void aPairKeepsAtMostTenNotesDroppingTheLeastWorthKeeping() {
        long now = clock.get();
        for (int i = 0; i < 10; i++) {
            BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PQ, "did PQ " + i + " together", 6, "", now + i);
        }
        BotMemory.setSlot(SLOT, PLAYER, BotMemory.Kind.JOKE, "my pet ate my return scroll", now);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.HELP, "buffed me", 2, "", now + 20);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.SCAM, "scammed me on a trade", 9, "", now + 30);
        assertEquals(BotMemory.MAX_PER_PAIR, notes().size());
        assertEquals(BotMemory.MAX_PER_PAIR, table.rows.size());
        assertTrue(BotMemory.insideJoke(SLOT, PLAYER).isPresent(), "slots are kept");
        assertTrue(notes().stream().anyMatch(n -> n.kind() == BotMemory.Kind.SCAM), "the heavy one stays");
        assertTrue(notes().stream().noneMatch(n -> n.text().equals("buffed me")), "the light one went");
    }

    @Test
    void notesAgeOutOnceOldAndUnused() {
        long t0 = clock.get();
        BotMemory.remember(SLOT, PLAYER, List.of("likes pink capes"), BotMemory.Source.DIRECT, 0, 0, t0);
        BotMemory.remember(SLOT, PLAYER, List.of("mains a hermit"), BotMemory.Source.DIRECT, 0, 0, t0);
        BotMemory.remember(SLOT, PLAYER, List.of("got scammed in the FM"), BotMemory.Source.HEARSAY, 9, 1, t0);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.SCAM, "scammed me on a trade", 9, "", t0);
        BotMemory.Note hermit = notes().stream().filter(n -> n.text().equals("mains a hermit")).findFirst().orElseThrow();
        BotMemory.Note pinky = notes().stream().filter(n -> n.text().equals("likes pink capes")).findFirst().orElseThrow();
        assertTrue(BotMemory.expired(pinky, t0 + 50 * DAY), "light, 50 days old, never used");
        assertFalse(BotMemory.expired(pinky, t0 + 30 * DAY), "too young");
        assertFalse(BotMemory.expired(hermit.used(t0 + 40 * DAY), t0 + 50 * DAY), "used ten days ago");
        assertTrue(BotMemory.expired(hermit.used(t0 + 40 * DAY), t0 + 60 * DAY), "and then forgotten");
        BotMemory.Note scam = notes().stream().filter(n -> n.kind() == BotMemory.Kind.SCAM).findFirst().orElseThrow();
        assertFalse(BotMemory.expired(scam, t0 + 50 * DAY), "a scam is remembered for months");
        assertTrue(BotMemory.expired(scam, t0 + 121 * DAY));

        // the sweep: the table and the cache
        BotMemory.recallFacts(SLOT, PLAYER, t0 + 40 * DAY, 5); // stamps every note it shows as used
        BotMemory.Note heard = notes().stream().filter(n -> n.source() == BotMemory.Source.HEARSAY).findFirst().orElseThrow();
        assertEquals(t0 + 40 * DAY, heard.lastUsedAt());
        assertEquals(1, BotMemory.prune(t0 + 50 * DAY), "hearsay goes after a week unused; the rest was used ten days ago");
        assertEquals(2, BotMemory.prune(t0 + 70 * DAY), "then the two light notes");
        assertEquals(List.of("scammed me on a trade"), notes().stream().map(BotMemory.Note::text).toList());
        reload();
        assertEquals(List.of("scammed me on a trade"), notes().stream().map(BotMemory.Note::text).toList());
    }

    @Test
    void theCacheIsBoundedLeastRecentlyUsedFirst() {
        BotMemory.useForTest(Runnable::run, clock::get, 3);
        BotMemory.about(1, 1);
        BotMemory.about(1, 2);
        BotMemory.about(1, 3);
        BotMemory.about(1, 1); // used again: now the freshest
        assertEquals(4 - 1, table.pairReads.get(), "three pairs read once each");
        BotMemory.about(1, 4);
        assertEquals(3, BotMemory.cachedPairs());
        BotMemory.about(1, 1);
        assertEquals(4, table.pairReads.get(), "pair 1 stayed");
        BotMemory.about(1, 2);
        assertEquals(5, table.pairReads.get(), "pair 2 was the least recently used: read again");
        assertEquals(3, BotMemory.cachedPairs());
    }

    @Test
    void withoutMigration030NotesArePlainAndSlotsLiveInMemory() {
        table = new FakeMemoryTable(true);
        table.install();
        long now = clock.get();
        assertFalse(BotMemory.hasKinds());
        assertNotNull(BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.PQ, "did KPQ together", 6, "", now));
        assertNotNull(BotMemory.setSlot(SLOT, PLAYER, BotMemory.Kind.TOPIC, "zhelm", now));
        assertEquals(1, table.rows.size(), "the slot is not stored as a plain note");
        assertEquals("zhelm", BotMemory.lastTopic(SLOT, PLAYER).orElseThrow().text());
        assertTrue(BotMemory.prune(now) >= 0, "the sweep runs without the weight column");
        assertTrue(BotMemory.kindCounts(PLAYER).isEmpty());
        table.install();
        List<BotMemory.Note> back = notes();
        assertEquals(1, back.size());
        assertEquals("did KPQ together", back.get(0).text());
        assertEquals(BotMemory.Kind.NOTE, back.get(0).kind(), "read back as a plain note");
    }

    @Test
    void reputationInputCountsFirstHandKindsAcrossBots() {
        long now = clock.get();
        BotMemory.episode(1, PLAYER, BotMemory.Kind.SCAM, "scammed me on a trade", 9, "", now);
        BotMemory.episode(2, PLAYER, BotMemory.Kind.SCAM, "scammed me on a trade", 9, "", now);
        BotMemory.episode(3, PLAYER, BotMemory.Kind.PQ, "did KPQ together", 6, "", now);
        BotMemory.remember(4, PLAYER, List.of("scammed me on a trade"), BotMemory.Source.HEARSAY, 1, 1, now);
        Map<BotMemory.Kind, Integer> counts = BotMemory.kindCounts(PLAYER);
        assertEquals(2, counts.get(BotMemory.Kind.SCAM));
        assertEquals(1, counts.get(BotMemory.Kind.PQ));
        assertNull(counts.get(BotMemory.Kind.NOTE), "hearsay is not first hand");
    }

    @Test
    void slotsAndGoalsAreNeverPassedOnAsGossip() {
        long now = clock.get();
        BotMemory.setSlot(SLOT, PLAYER, BotMemory.Kind.JOKE, "my pet ate my return scroll", now);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.GOAL, "wants zhelm", 4, "item:zhelm", now);
        BotMemory.episode(SLOT, PLAYER, BotMemory.Kind.SCAM, "scammed me on a trade", 9, "", now);
        assertEquals(List.of("scammed me on a trade"),
                BotMemory.recentPassable(SLOT, 10).stream().map(BotMemory.Note::text).toList());
    }

    @Test
    void migration030IsGuardedBackfillSafeLedgeredAndRollsBack() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "030-bot-memory-kinds";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        for (String col : new String[]{"Kind", "Weight", "Tag", "FollowedUpAt"}) {
            assertTrue(sql.contains("COLUMN_NAME = '" + col + "'") && sql.contains("ADD COLUMN " + col + " "),
                    col + " added only when missing");
            assertTrue(rollback.contains("COLUMN_NAME = '" + col + "'") && rollback.contains("DROP COLUMN " + col),
                    col + " dropped only when present");
            assertTrue(BotMemory.INSERT_KINDS.contains(col) && BotMemory.PROBE_KINDS.contains(col), col);
        }
        assertTrue(sql.contains("Kind VARCHAR(8) NOT NULL DEFAULT ''NOTE''"), "older rows become plain notes");
        assertTrue(sql.contains("Weight TINYINT UNSIGNED NOT NULL DEFAULT 1") && sql.contains("FollowedUpAt BIGINT NOT NULL DEFAULT 0"));
        for (String note : BotMemory.FEELINGS) {
            assertTrue(sql.contains("'" + note + "'"), "the backfill knows the feeling note: " + note);
        }
        assertTrue(sql.contains("WHERE Kind = 'NOTE'"), "the backfill only touches default rows (twice is a no-op)");
        for (BotMemory.Kind k : BotMemory.Kind.values()) {
            assertTrue(k.name().length() <= 8, k + " fits VARCHAR(8)");
        }
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("migration_id='" + id + "'"), "the rollback clears its ledger row");
        assertTrue(sql.contains("Tag VARCHAR(48)"));
        assertTrue(MemoryFacts.TERMS.stream().allMatch(t -> ("item:" + t.label()).length() <= 48));
    }
}
