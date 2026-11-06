package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMemory.Kind;
import soloMapling.ArtificialPlayer.BotMemory.Note;
import soloMapling.ArtificialPlayer.BotMemory.Provenance;
import soloMapling.ArtificialPlayer.BotMemory.Query;
import soloMapling.ArtificialPlayer.BotMemory.Source;
import soloMapling.ArtificialPlayer.BotMemory.SubjectType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 271 Stage 3: structured social memory. The new columns (migration 063) and the server staying up without
 * them, bots as the subject of a note, the writer API, scored recall, and consolidation of repeats.
 */
class BotMemorySocialTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);
    static final int SLOT = 7;
    static final int BOT_B = BotStore.BOT_ID_BASE + 9;
    static final int BOT_C = BotStore.BOT_ID_BASE + 10;
    /** A real player's id that is numerically the same as bot slot 9: the two subjects must never share a pair. */
    static final int PLAYER = 9;

    private FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong clock = new AtomicLong(100 * DAY);

    @BeforeEach
    void setUp() {
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
    }

    @AfterEach
    void tearDown() {
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
    }

    private void reload() {
        table.install();
    }

    private static Note remember(int subject, Kind kind, String text, double valence, int location, Provenance p,
                                 double confidence, int other, long now) {
        return BotMemory.remember(SLOT, subject, kind, text, valence, location, p, confidence, other, now);
    }

    // ── defaults for old rows ──

    @Test
    void aLegacyNoteReadsWithTheDefaultsOfItsSource() {
        Note direct = new Note(1, SLOT, PLAYER, "x", Source.DIRECT, 0, 0, 5, 0, Kind.NOTE, 2, "", 0);
        assertEquals(SubjectType.PLAYER, direct.subjectType());
        assertEquals(0.9, direct.confidence(), 1e-9);
        assertEquals(Provenance.WITNESSED, direct.provenance());
        assertEquals(0, direct.valence(), 1e-9);
        assertEquals(0, direct.locationMapId());
        assertEquals(0, direct.otherId());
        Note heard1 = new Note(2, SLOT, PLAYER, "y", Source.HEARSAY, 3, 1, 5, 0);
        Note heard2 = new Note(3, SLOT, PLAYER, "y", Source.HEARSAY, 3, 2, 5, 0);
        assertEquals(0.5, heard1.confidence(), 1e-9);
        assertEquals(0.25, heard2.confidence(), 1e-9, "halves with each further mouth");
        assertEquals(Provenance.REPORTED, heard1.provenance());
        assertEquals(SubjectType.BOT, new Note(4, SLOT, BOT_B, "z", Source.DIRECT, 0, 0, 5, 0).subjectType(),
                "a bot's id is a bot subject");
    }

    @Test
    void valenceAndConfidenceAreClamped() {
        Note n = new Note(1, SLOT, PLAYER, "x", Source.DIRECT, 0, 0, 5, 0, Kind.NOTE, 2, "", 0, null, -3, 7.0, -4.0, null,
                -1);
        assertEquals(1.0, n.confidence(), 1e-9);
        assertEquals(-1.0, n.valence(), 1e-9);
        assertEquals(0, n.locationMapId());
        assertEquals(0, n.otherId());
    }

    @Test
    void rowsFromBeforeMigration060ReadWithDefaultsAndNewNotesRoundTrip() {
        // a row as migration 063 leaves the existing ones: the new columns hold their defaults / NULL
        Map<String, Object> old = new HashMap<>();
        old.put("Id", 500L);
        old.put("BotId", SLOT);
        old.put("AboutCharacterId", PLAYER);
        old.put("Note", "did KPQ together");
        old.put("Source", "DIRECT");
        old.put("FromBotId", 0);
        old.put("Hops", 0);
        old.put("CreatedAt", clock.get());
        old.put("LastUsedAt", 0L);
        old.put("Kind", "PQ");
        old.put("Weight", 6);
        old.put("Tag", "");
        old.put("FollowedUpAt", 0L);
        old.put("SubjectType", "PLAYER");
        old.put("Valence", 0.0);
        table.rows.add(old); // Location, Confidence, Provenance, OtherParticipant absent = NULL
        Note back = BotMemory.about(SLOT, PLAYER).get(0);
        assertEquals(0.9, back.confidence(), 1e-9, "NULL confidence: the default of DIRECT");
        assertEquals(Provenance.WITNESSED, back.provenance());
        assertEquals(0, back.locationMapId());
        assertEquals(0, back.otherId());

        Note stored = remember(BOT_B, Kind.PARTY, "partied with me", 0.4, 101000000, Provenance.WITNESSED, 0.8, BOT_C,
                clock.get());
        assertNotNull(stored);
        reload();
        Note read = BotMemory.about(SLOT, BOT_B).get(0);
        assertEquals(SubjectType.BOT, read.subjectType());
        assertEquals(101000000, read.locationMapId());
        assertEquals(0.8, read.confidence(), 1e-6);
        assertEquals(0.4, read.valence(), 1e-6);
        assertEquals(Provenance.WITNESSED, read.provenance());
        assertEquals(BOT_C, read.otherId());
        assertEquals("BOT", table.rows.get(1).get("SubjectType"));
    }

    // ── the server without migration 063 (and without 030) ──

    @Test
    void withoutMigration060NotesAreStoredAsBeforeAndTheNewFieldsLiveInMemoryOnly() {
        table = FakeMemoryTable.without060();
        table.install();
        assertTrue(BotMemory.hasKinds());
        assertFalse(BotMemory.hasSocial(), "063 not applied");
        Note stored = remember(BOT_B, Kind.PARTY, "partied with me", 0.6, 101000000, Provenance.WITNESSED, 0.7, BOT_C,
                clock.get());
        assertNotNull(stored);
        assertEquals(1, table.rows.size());
        assertFalse(table.rows.get(0).containsKey("SubjectType"), "the 030 insert never names the 063 columns");
        assertEquals("PARTY", table.rows.get(0).get("Kind"));
        // in memory, until the cache forgets it
        Note cached = BotMemory.about(SLOT, BOT_B).get(0);
        assertEquals(0.6, cached.valence(), 1e-9);
        assertEquals(101000000, cached.locationMapId());
        reload();
        Note back = BotMemory.about(SLOT, BOT_B).get(0);
        assertEquals("partied with me", back.text());
        assertEquals(Kind.PARTY, back.kind());
        assertEquals(0, back.valence(), 1e-9, "read back with the defaults");
        assertEquals(0.9, back.confidence(), 1e-9);
        assertEquals(SubjectType.BOT, back.subjectType(), "a bot subject is still told by its id");
        // a merge, a recall and the sweep all work on the 030 schema
        remember(BOT_B, Kind.PARTY, "partied with me", 0.6, 0, Provenance.WITNESSED, Double.NaN, 0,
                clock.get() + 21 * 3600_000L);
        assertEquals("partied with me (x2)", table.rows.get(0).get("Note"));
        assertEquals(1, BotMemory.recall(SLOT, BOT_B, Query.none(), clock.get() + 22 * 3600_000L, 5).size());
        assertTrue(BotMemory.prune(clock.get()) >= 0);
    }

    @Test
    void withoutMigration030TheWriterStillStoresPlainNotes() {
        table = new FakeMemoryTable(true);
        table.install();
        assertFalse(BotMemory.hasSocial());
        assertNotNull(remember(BOT_B, Kind.CHAT, "chatted", 0.2, 0, Provenance.WITNESSED, Double.NaN, 0, clock.get()));
        assertEquals(1, table.rows.size());
        assertEquals("NOTE", table.rows.get(0).get("Kind"));
        assertEquals("chatted", BotMemory.about(SLOT, BOT_B).get(0).text());
    }

    // ── bots as subjects ──

    @Test
    void aBotRemembersAnotherBotAndNeverMixesItWithAPlayerOfTheSameNumber() {
        long now = clock.get();
        assertNotNull(remember(BOT_B, Kind.PARTY, "partied with me", 0.4, 0, Provenance.WITNESSED, Double.NaN, 0, now));
        assertNotNull(BotMemory.episode(SLOT, PLAYER, Kind.PQ, "did KPQ together", 6, "", now));
        assertEquals(List.of("partied with me"), BotMemory.about(SLOT, BOT_B).stream().map(Note::text).toList());
        assertEquals(List.of("did KPQ together"), BotMemory.about(SLOT, PLAYER).stream().map(Note::text).toList());
        assertEquals(SubjectType.BOT, BotMemory.about(SLOT, BOT_B).get(0).subjectType());
        assertEquals(SubjectType.PLAYER, BotMemory.about(SLOT, PLAYER).get(0).subjectType());
    }

    @Test
    void theWriterRefusesSlotsSelfAndEmptyNotes() {
        long now = clock.get();
        assertNull(remember(BOT_B, Kind.TOPIC, "zhelm", 0, 0, Provenance.WITNESSED, Double.NaN, 0, now), "slots have setSlot");
        assertNull(remember(BotStore.BOT_ID_BASE + SLOT, Kind.CHAT, "talked to myself", 0, 0, Provenance.WITNESSED,
                Double.NaN, 0, now), "a bot has no note about itself");
        assertNull(remember(BOT_B, Kind.CHAT, "  ", 0, 0, Provenance.WITNESSED, Double.NaN, 0, now));
        assertNull(remember(0, Kind.CHAT, "x", 0, 0, Provenance.WITNESSED, Double.NaN, 0, now));
        assertTrue(table.rows.isEmpty());
    }

    @Test
    void botEventsWriteBotSubjectNotesAndPlayerFacingEpisodesStillSkipBots() {
        BotEpisodes.useForTest(Runnable::run, map -> null, null);
        try {
            int botChar = BotStore.BOT_ID_BASE + SLOT;
            long now = clock.get();
            BotEpisodes.botEvent(botChar, BOT_B, Kind.CHAT, "chatted", 0.2, 100000000, now);
            BotEpisodes.botEvent(botChar, PLAYER, Kind.CHAT, "chatted", 0.2, 100000000, now); // a player: not this door
            BotEpisodes.botEvent(botChar, botChar, Kind.CHAT, "chatted", 0.2, 100000000, now); // itself
            BotEpisodes.botEvent(PLAYER, BOT_B, Kind.CHAT, "chatted", 0.2, 100000000, now); // a player is not a bot
            assertEquals(1, table.rows.size(), table.rows.toString());
            assertEquals(BOT_B, table.rows.get(0).get("AboutCharacterId"));
            assertEquals(100000000, table.rows.get(0).get("Location"));
            // the episodes about real players keep their guard: a bot is not "a player who did something"
            BotEpisodes.onAffinity(botChar, BOT_B, soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent.PARTY,
                    100000000, now);
            assertEquals(1, table.rows.size(), "bot-bot affinity is Bond's, not a player episode");
        } finally {
            BotEpisodes.useForTest(null, null, null);
        }
    }

    @Test
    void reportedNotesAreHearsayWithTheirTellerAndHops() {
        Note n = BotMemory.remember(SLOT, BOT_B, Kind.NOTE, "heard they killed Zakum", 0.3, 0, Provenance.REPORTED,
                Double.NaN, 0, 3, 2, clock.get());
        assertEquals(Source.HEARSAY, n.source());
        assertEquals(3, n.fromBotId());
        assertEquals(2, n.hops());
        assertEquals(0.25, n.confidence(), 1e-9, "REPORTED two mouths deep");
        assertTrue(n.weight() <= 2, "hearsay never weighs more than 2");
        assertEquals("HEARSAY", table.rows.get(0).get("Source"));
        assertNull(BotMemory.remember(SLOT, BOT_B, Kind.NOTE, "heard they killed Zakum", 0.3, 0, Provenance.REPORTED,
                Double.NaN, 0, 4, 1, clock.get() + 5 * DAY), "already heard: kept once");
    }

    @Test
    void theSameThingTwiceInTwentyHoursCountsOnce() {
        long now = clock.get();
        assertNotNull(remember(BOT_B, Kind.CHAT, "chatted", 0.2, 0, Provenance.WITNESSED, Double.NaN, 0, now));
        assertNull(remember(BOT_B, Kind.CHAT, "chatted", 0.2, 0, Provenance.WITNESSED, Double.NaN, 0, now + 3600_000L));
        assertEquals(1, table.rows.size());
    }

    @Test
    void aBotSubjectPairIsCappedAtTenAndDropsTheLeastWorthKeeping() {
        long now = clock.get();
        String[] things = {"alpha stage", "bravo trek", "charlie trial", "delta climb", "echo raid", "foxtrot hunt",
                "golf dive", "hotel crawl", "india rush", "juliet march"};
        for (int i = 0; i < 10; i++) {
            remember(BOT_B, Kind.PARTY, things[i], 0.1, 0, Provenance.WITNESSED, Double.NaN, 0, now + i);
        }
        remember(BOT_B, Kind.CHAT, "said hi", 0.1, 0, Provenance.WITNESSED, Double.NaN, 0, now + 20);
        remember(BOT_B, Kind.SCAM, "scammed me on a trade", -0.9, 0, Provenance.WITNESSED, Double.NaN, 0, now + 30);
        List<Note> notes = BotMemory.about(SLOT, BOT_B);
        assertEquals(BotMemory.MAX_PER_PAIR, notes.size());
        assertEquals(BotMemory.MAX_PER_PAIR, table.rows.size(), "the dropped rows are gone from the table too");
        assertTrue(notes.stream().anyMatch(n -> n.kind() == Kind.SCAM), "the heavy, strongly felt one stays");
        assertTrue(notes.stream().noneMatch(n -> n.text().equals("said hi")), "the light one went");
    }

    @Test
    void botSubjectNotesAgeOutLikeAnyOther() {
        long now = clock.get();
        remember(BOT_B, Kind.CHAT, "chatted", 0.1, 0, Provenance.WITNESSED, Double.NaN, 0, now);
        remember(BOT_C, Kind.SCAM, "scammed me on a trade", -0.9, 0, Provenance.WITNESSED, Double.NaN, 0, now);
        long later = now + 60 * DAY; // light: 45 days old and unused for 14; heavy: kept to 120
        assertTrue(BotMemory.prune(later) >= 1);
        assertTrue(BotMemory.about(SLOT, BOT_B).isEmpty(), "the small chat is forgotten");
        assertEquals(1, BotMemory.about(SLOT, BOT_C).size(), "the grudge is not yet");
    }

    @Test
    void anOptOutPurgeOfAPlayerLeavesBotSubjectNotesAlone() {
        long now = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.SCAM, "scammed me on a trade", 9, "", now);
        remember(BOT_B, Kind.SCAM, "scammed me on a trade", -0.9, 0, Provenance.WITNESSED, Double.NaN, 0, now);
        assertEquals(1, BotMemory.forgetGossipAbout(PLAYER, List.of()));
        assertTrue(BotMemory.about(SLOT, PLAYER).isEmpty());
        assertEquals(1, BotMemory.about(SLOT, BOT_B).size());
    }

    @Test
    void notesAboutBotsAreNotGossipAboutPlayers() {
        long now = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.SCAM, "scammed me on a trade", 9, "", now);
        remember(BOT_B, Kind.GIFT, "gave me a potion", 0.5, 0, Provenance.WITNESSED, Double.NaN, 0, now + 1);
        assertEquals(List.of("scammed me on a trade"),
                BotMemory.recentPassable(SLOT, 5).stream().map(Note::text).toList(),
                "a gift between bots is not a story that passes the player gossip step");
    }

    @Test
    void theAllBotsCountIsAnAggregateAndTheCircleCountReadsOnlyTheCircle() {
        long now = clock.get();
        for (int slot = 1; slot <= 4; slot++) {
            BotMemory.episode(slot, PLAYER, Kind.SCAM, "scammed me on a trade", 9, "", now);
        }
        BotMemory.episode(2, PLAYER, Kind.PQ, "did KPQ together", 6, "", now);
        BotMemory.remember(3, BOT_B, Kind.GIFT, "gave me a potion", 0.5, 0, Provenance.WITNESSED, Double.NaN, 0, now);
        assertEquals(4, BotMemory.kindCounts(PLAYER).get(Kind.SCAM), "every bot together");
        Map<Kind, Integer> circle = BotMemory.kindCounts(PLAYER, List.of(1, 2));
        assertEquals(2, circle.get(Kind.SCAM), "only bots 1 and 2 are read");
        assertEquals(1, circle.get(Kind.PQ));
        assertNull(circle.get(Kind.GIFT), "bot 3's note and bot 3's bot-subject note are outside the circle");
        assertTrue(BotMemory.kindCounts(PLAYER, List.of()).isEmpty(), "no circle, nothing read");
        assertTrue(BotMemory.kindCounts(PLAYER, List.of(99)).isEmpty(), "a stranger knows nothing of it");
    }

    // ── scored recall ──

    @Test
    void relevanceBeatsRecencyWhenItShould() {
        long now = clock.get();
        // a PQ 25 days ago, and small talk a second ago that has nothing to do with it
        remember(PLAYER, Kind.PQ, "did KPQ together", 0.0, 100000000, Provenance.WITNESSED, Double.NaN, 0, now - 25 * DAY);
        remember(PLAYER, Kind.CHAT, "talked about the weather", 0.1, 0, Provenance.WITNESSED, Double.NaN, 0, now - 1000);
        assertEquals("talked about the weather", BotMemory.recall(SLOT, PLAYER, Query.none(), now, 1).get(0).text(),
                "with nothing in particular in mind, the fresh note wins");
        Query pq = Query.of(Set.of(Kind.PQ), 0, 100000000, "want to do kpq again", 0);
        List<Note> asked = BotMemory.recall(SLOT, PLAYER, pq, now, 2);
        assertEquals("did KPQ together", asked.get(0).text(), "asked about the PQ in the PQ's town, the old note wins");
        assertEquals("talked about the weather", asked.get(1).text());
        // the match is the only difference: the same PQ note scores less with a query that matches nothing of it
        Note pqNote = asked.get(0);
        assertTrue(BotMemory.score(pqNote, pq, now) > 2 * BotMemory.score(pqNote, Query.none(), now));
        assertEquals(BotMemory.score(pqNote, Query.none(), now),
                BotMemory.score(pqNote, Query.of(Set.of(Kind.GIFT), 0, 101000000, "weather", 0), now), 1e-12);
    }

    @Test
    void anOldMatchOutranksAFreshNonMatchOfTheSameWeight() {
        long now = clock.get();
        remember(BOT_B, Kind.PARTY, "partied with me", 0.2, 101000000, Provenance.WITNESSED, Double.NaN, 0, now - 2 * DAY);
        remember(BOT_B, Kind.HELP, "came to help me train", 0.2, 100000000, Provenance.WITNESSED, Double.NaN, 0, now - 1000);
        // by itself the fresher note wins (same salience, fresher); the place makes the older one the better fit
        assertEquals("came to help me train", BotMemory.recall(SLOT, BOT_B, Query.none(), now, 1).get(0).text());
        Query ellinia = new Query(Set.of(), 0, 101000000, Set.of(), 0);
        assertEquals("partied with me", BotMemory.recall(SLOT, BOT_B, ellinia, now, 1).get(0).text());
        // the other participant counts too
        remember(BOT_B, Kind.TRADE, "traded with me", 0.2, 0, Provenance.WITNESSED, Double.NaN, BOT_C, now - 3 * DAY);
        Query withC = new Query(Set.of(), 0, 0, Set.of(), BOT_C);
        assertEquals("traded with me", BotMemory.recall(SLOT, BOT_B, withC, now, 1).get(0).text());
    }

    @Test
    void aStrongFeelingStaysSalientAndAMatchingMoodLiftsIt() {
        long now = clock.get();
        remember(BOT_B, Kind.CHAT, "chatted", 0.05, 0, Provenance.WITNESSED, Double.NaN, 0, now - DAY);
        remember(BOT_B, Kind.ASKED, "turned me down for a party", -0.9, 0, Provenance.WITNESSED, Double.NaN, 0, now - DAY);
        assertEquals("turned me down for a party", BotMemory.recall(SLOT, BOT_B, Query.none(), now, 1).get(0).text());
        List<Note> notes = BotMemory.about(SLOT, BOT_B);
        Note refused = notes.stream().filter(n -> n.kind() == Kind.ASKED).findFirst().orElseThrow();
        Query sour = new Query(Set.of(), -0.8, 0, Set.of(), 0);
        assertTrue(BotMemory.score(refused, sour, now) > BotMemory.score(refused, Query.none(), now),
                "a sour mood brings the bad memory to mind");
        Query sweet = new Query(Set.of(), 0.8, 0, Set.of(), 0);
        assertEquals(BotMemory.score(refused, Query.none(), now), BotMemory.score(refused, sweet, now), 1e-9);
    }

    @Test
    void ownFirstHandBeatsHearsayAtAnEqualScore() {
        long now = clock.get();
        // the same kind, weight, age and (explicit) confidence: only the source differs
        BotMemory.remember(SLOT, BOT_B, Kind.NOTE, "heard they got a rare drop", 0.2, 0, Provenance.REPORTED, 0.9, 0, 4, 1, now);
        remember(BOT_B, Kind.NOTE, "saw them get a rare drop", 0.2, 0, Provenance.WITNESSED, 0.9, 0, now);
        List<Note> out = BotMemory.recall(SLOT, BOT_B, Query.none(), now, 5);
        assertEquals(2, out.size());
        assertEquals(Source.DIRECT, out.get(0).source(), "an equal score: what the bot saw itself first");
        assertEquals(Source.HEARSAY, out.get(1).source());
    }

    @Test
    void hearsayLosesToDirectByItsLowerConfidence() {
        long now = clock.get();
        BotMemory.remember(SLOT, BOT_B, Kind.NOTE, "heard they got a rare drop", 0.2, 0, Provenance.REPORTED, Double.NaN, 0, 4, 1, now);
        remember(BOT_B, Kind.NOTE, "saw them get a rare drop", 0.2, 0, Provenance.WITNESSED, Double.NaN, 0, now - DAY);
        assertEquals("saw them get a rare drop", BotMemory.recall(SLOT, BOT_B, Query.none(), now, 1).get(0).text(),
                "0.9 against 0.5: the day-old first-hand note still leads");
    }

    @Test
    void recallStampsTheNotesItReturnsAndSlotsAreNeverRecalled() {
        long now = clock.get();
        remember(BOT_B, Kind.PARTY, "partied with me", 0.4, 0, Provenance.WITNESSED, Double.NaN, 0, now - 2 * DAY);
        BotMemory.setSlot(SLOT, BOT_B, Kind.TOPIC, "zhelm", now);
        List<Note> out = BotMemory.recall(SLOT, BOT_B, Query.none(), now, 5);
        assertEquals(1, out.size(), "the slot is not a note");
        assertEquals(now, BotMemory.about(SLOT, BOT_B).stream().filter(n -> n.kind() == Kind.PARTY).findFirst()
                .orElseThrow().lastUsedAt());
        BotMemory.flushPending(); // 1.6.5 b324: stamps are written by the flusher, in one batch
        assertEquals(now, ((Number) table.rows.get(0).get("LastUsedAt")).longValue(), "and the table");
    }

    @Test
    void theOldRecallStillWorksAndDelegatesToTheScoredOne() {
        long now = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.PQ, "did KPQ together", 6, "", now - 3 * DAY);
        BotMemory.remember(SLOT, PLAYER, List.of("saving up for a cape"), Source.DIRECT, 0, 0, now - DAY);
        BotMemory.remember(SLOT, PLAYER, List.of("heard they got scammed"), Source.HEARSAY, 4, 1, now);
        List<String> lines = BotMemory.recall(SLOT, PLAYER, now);
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).startsWith("heard from "), "hearsay last: " + lines);
        assertEquals(lines, BotMemory.recall(SLOT, PLAYER, Query.none(), now).subList(0, 3));
    }

    @Test
    void recallIfCachedNeverReadsTheDatabaseAndWarmsThePairInTheBackground() {
        long now = clock.get();
        Map<String, Object> row = new HashMap<>();
        row.put("Id", 900L);
        row.put("BotId", SLOT);
        row.put("AboutCharacterId", BOT_B);
        row.put("Note", "partied with me");
        row.put("Source", "DIRECT");
        row.put("FromBotId", 0);
        row.put("Hops", 0);
        row.put("CreatedAt", now - DAY);
        row.put("LastUsedAt", 0L);
        row.put("Kind", "PARTY");
        row.put("Weight", 3);
        row.put("Tag", "");
        row.put("FollowedUpAt", 0L);
        table.rows.add(row);
        BotMemory.useForTest(r -> { /* the load waits */ }, clock::get, 0);
        reload();
        int reads = table.pairReads.get();
        assertTrue(BotMemory.recallIfCached(SLOT, BOT_B, Query.none(), now, 3).isEmpty(), "not loaded yet: no wait");
        assertEquals(reads, table.pairReads.get(), "no database read on the caller's thread");
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        reload();
        BotMemory.recallIfCached(SLOT, BOT_B, Query.none(), now, 3); // schedules the warm-up, which now runs at once
        assertEquals("partied with me", BotMemory.recallIfCached(SLOT, BOT_B, Query.none(), now, 3).get(0).text(),
                "the next time the pair has its memories");
    }

    // ── consolidation ──

    @Test
    void aRepeatedSimilarEpisodeMergesIntoOneNoteWithACountAndMoreWeight() {
        long t = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together in ellinia", 3, "", t);
        BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together in henesys", 3, "", t + DAY);
        Note merged = BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together in ellinia", 3, "", t + 2 * DAY);
        List<Note> notes = BotMemory.about(SLOT, PLAYER);
        assertEquals(1, notes.size(), "three parties, one note: " + notes);
        assertEquals("partied together in ellinia (x3)", merged.text());
        assertEquals(5, notes.get(0).weight(), "3 and one more for each repeat");
        assertEquals(t + 2 * DAY, notes.get(0).createdAt(), "dated at the latest time");
        assertEquals(1, table.rows.size());
        assertEquals("partied together in ellinia (x3)", table.rows.get(0).get("Note"), "the table follows");
        assertEquals(5, ((Number) table.rows.get(0).get("Weight")).intValue());
    }

    @Test
    void aMergedNoteKeepsTheMeanFeelingAndTheBestConfidenceAndPersistsThem() {
        long t = clock.get();
        remember(BOT_B, Kind.CHAT, "chatted", 0.6, 0, Provenance.WITNESSED, 0.5, 0, t);
        remember(BOT_B, Kind.CHAT, "chatted", -0.2, 101000000, Provenance.WITNESSED, 0.9, BOT_C, t + DAY);
        Note n = BotMemory.about(SLOT, BOT_B).get(0);
        assertEquals("chatted (x2)", n.text());
        assertEquals(0.2, n.valence(), 1e-6, "the mean of 0.6 and -0.2");
        assertEquals(0.9, n.confidence(), 1e-6);
        assertEquals(101000000, n.locationMapId());
        assertEquals(BOT_C, n.otherId());
        reload();
        Note back = BotMemory.about(SLOT, BOT_B).get(0);
        assertEquals(0.2, back.valence(), 1e-6);
        assertEquals(101000000, back.locationMapId());
        assertEquals("chatted (x2)", back.text());
    }

    @Test
    void smallTalkNeverMergesIntoAHeavyMemory() {
        long t = clock.get();
        for (int i = 0; i < 40; i++) {
            remember(BOT_B, Kind.CHAT, "chatted", 0.2, 0, Provenance.WITNESSED, Double.NaN, 0, t + i * DAY);
        }
        List<Note> notes = BotMemory.about(SLOT, BOT_B);
        assertEquals(1, notes.size(), "forty chats, one note");
        assertEquals("chatted (x40)", notes.get(0).text());
        assertEquals(Kind.CHAT.maxWeight(), notes.get(0).weight());
        assertTrue(notes.get(0).weight() < BotMemory.HEAVY, "chatting is never heavy");
    }

    @Test
    void differentKindsAndChatNotesAndGoalsDoNotMerge() {
        long t = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together", 3, "", t);
        BotMemory.episode(SLOT, PLAYER, Kind.PQ, "partied together", 3, "", t + DAY);
        BotMemory.remember(SLOT, PLAYER, List.of("saving up for a cape"), Source.DIRECT, 0, 0, t);
        BotMemory.remember(SLOT, PLAYER, List.of("saving up for a cape!"), Source.DIRECT, 0, 0, t + DAY);
        BotMemory.episode(SLOT, PLAYER, Kind.GOAL, "wants zhelm", 4, "item:zhelm", t);
        BotMemory.episode(SLOT, PLAYER, Kind.GOAL, "wants zhelm now", 4, "item:zhelm2", t + DAY);
        assertEquals(6, BotMemory.about(SLOT, PLAYER).size(), BotMemory.about(SLOT, PLAYER).toString());
    }

    @Test
    void aMergeDoesNotCountTwiceWithinTheDedupeWindow() {
        long t = clock.get();
        BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together", 3, "", t);
        BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together", 3, "", t + DAY); // merges: (x2)
        assertNull(BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together", 3, "", t + DAY + 1000),
                "the same thing a second later is the same event, not a third time");
        assertEquals("partied together (x2)", BotMemory.about(SLOT, PLAYER).get(0).text());
    }

    @Test
    void consolidationKeepsThePairUnderTheCapWhereDistinctNotesWouldNot() {
        long t = clock.get();
        for (int i = 0; i < 30; i++) {
            BotMemory.episode(SLOT, PLAYER, Kind.PARTY, "partied together in henesys", 3, "", t + i * DAY);
            BotMemory.episode(SLOT, PLAYER, Kind.HELP, "buffed me", 2, "", t + i * DAY + 1);
        }
        assertEquals(2, BotMemory.about(SLOT, PLAYER).size());
        assertEquals(2, table.rows.size());
    }

    // ── the migration files ──

    @Test
    void migration060IsGuardedLedgeredIdempotentAndRollsBack() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "063-bot-memory-social";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        for (String col : List.of("SubjectType", "Location", "Confidence", "Valence", "Provenance", "OtherParticipant")) {
            assertTrue(sql.contains("COLUMN_NAME = '" + col + "'") && sql.contains("ADD COLUMN " + col + " "),
                    col + " is added only when missing");
            assertTrue(rollback.contains("COLUMN_NAME = '" + col + "'") && rollback.contains("DROP COLUMN " + col),
                    col + " is dropped only when present");
        }
        assertTrue(sql.contains("information_schema.COLUMNS") && sql.contains("IF(@missing,"), "the 030 guard pattern");
        assertTrue(sql.contains("DEFAULT ''PLAYER''"), "older rows are about players");
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("migration_id='" + id + "'"), "the rollback clears its ledger row");
        assertFalse(sql.contains("DROP") || sql.contains("DELETE") || sql.contains("UPDATE botmemory"),
                "nothing is removed or rewritten");
    }
}
