package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotMemory.Note;
import soloMapling.ArtificialPlayer.BotMemory.Provenance;
import soloMapling.ArtificialPlayer.BotMemory.Source;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotWorldGossip.Kind;
import soloMapling.ArtificialPlayer.BotWorldGossip.News;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 Stage 3, knowledge boundaries: the world news is global bookkeeping, but a bot only voices what it
 * witnessed or was told (by a knower, at most two mouths from a witness, after a delay, as a REPORTED note of lower
 * confidence). A bot that neither saw nor heard it says nothing about it. The matrix: witnessed, told, neither.
 */
class BotWorldGossipKnowledgeTest {

    static final int B = BotStore.BOT_ID_BASE;
    static final int PLAYER = 4242;
    static final int WITNESS = B + 1, FRIEND = B + 2, FRIENDS_FRIEND = B + 3, STRANGER = B + 4, FOURTH = B + 5;
    static final Map<Integer, String> NAMES = Map.of(WITNESS, "Wit", FRIEND, "Fri", FRIENDS_FRIEND, "Ffr", STRANGER,
            "Str", FOURTH, "Fou", PLAYER, "Joshh");

    private final FakeMemoryTable table = new FakeMemoryTable(false);
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final Map<Integer, List<Integer>> bonds = new HashMap<>();

    @BeforeEach
    void setUp() {
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotWorldGossip.resetForTest();
        BotWorldGossip.useWriterForTest(Runnable::run);
        SocialSim.useForTest(clock::get, 7L);
        Reputation.useForTest(id -> new long[]{Reputation.SOLO | (id & 0xffffffffL)}, clock::get, id -> false);
        BotGossip.useForTest(id -> new SocialProfile(0, 0), id -> bonds.getOrDefault(id, List.of()), NAMES::get,
                (bot, player) -> true);
        BotGossip.clearStoriesForTest(id -> false);
    }

    @AfterEach
    void tearDown() {
        BotMemory.useConnectionSourceForTest(null);
        BotMemory.useForTest(null, null, 0);
        BotWorldGossip.resetForTest();
        BotWorldGossip.useWriterForTest(null);
        SocialSim.reset();
        Reputation.useForTest(null, null, null);
        BotGossip.useForTest(null, null, null, null);
        BotGossip.clearStoriesForTest(null);
    }

    /** A bot standing on a map with a real, visible player. */
    private static Character bot(int id, String name) {
        Character realPlayer = mock(Character.class);
        when(realPlayer.getId()).thenReturn(PLAYER + 1);
        when(realPlayer.getName()).thenReturn("Visitor");
        when(realPlayer.isHidden()).thenReturn(false);
        MapleMap map = mock(MapleMap.class);
        when(map.getAllPlayers()).thenReturn(List.of(realPlayer));
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100000000);
        when(c.isAlive()).thenReturn(true);
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.getLevel()).thenReturn(35);
        return c;
    }

    private News zakum(long at, int... witnesses) {
        News n = BotWorldGossip.newsForTest(Kind.BOSS, "Joshh", PLAYER, "Zakum", null, at);
        for (int w : witnesses) {
            n.witnesses.add(w);
        }
        BotWorldGossip.feedForTest().add(n);
        return n;
    }

    // ── who knows ──

    @Test
    void aWitnessKnowsAToldBotKnowsOnceTheTellingArrivesAndAStrangerNeverDoes() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        assertTrue(n.knows(WITNESS, now));
        assertEquals(0, n.hopsOf(WITNESS, now));
        assertFalse(n.knows(STRANGER, now));
        assertEquals(-1, n.hopsOf(STRANGER, now));
        assertTrue(BotWorldGossip.tell(n, WITNESS, FRIEND, now));
        assertFalse(n.knows(FRIEND, now), "a telling takes a moment to arrive");
        long arrives = n.told.get(FRIEND).knownAt();
        assertTrue(arrives >= now + BotWorldGossip.TOLD_DELAY_MIN_MS && arrives <= now + BotWorldGossip.TOLD_DELAY_MAX_MS,
                "20 to 90 seconds: " + (arrives - now));
        assertTrue(n.knows(FRIEND, arrives));
        assertEquals(1, n.hopsOf(FRIEND, arrives));
        assertFalse(n.knows(STRANGER, arrives + 1_000_000), "nobody told the stranger");
    }

    @Test
    void aBotThatNeverSawOrHeardItIsNotAmongTheBotsThatMayVoiceIt() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        BotWorldGossip.tell(n, WITNESS, FRIEND, now);
        List<Character> bots = List.of(bot(WITNESS, "Wit"), bot(FRIEND, "Fri"), bot(STRANGER, "Str"));
        List<Character> early = BotWorldGossip.voicers(n, bots, now);
        assertEquals(List.of(WITNESS), early.stream().map(Character::getId).toList(), "the telling has not arrived");
        long later = n.told.get(FRIEND).knownAt();
        List<Integer> voicers = BotWorldGossip.voicers(n, bots, later).stream().map(Character::getId).toList();
        assertEquals(List.of(WITNESS, FRIEND), voicers);
        assertFalse(voicers.contains(STRANGER), "the stranger neither saw nor heard it");
    }

    @Test
    void aBotThreadsGossipNamesOnlyNewsItKnows() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        BotWorldGossip.tell(n, WITNESS, FRIEND, now);
        long later = n.told.get(FRIEND).knownAt();
        Character witness = bot(WITNESS, "Wit"), told = bot(FRIEND, "Fri"), stranger = bot(STRANGER, "Str");
        Character partner = bot(FOURTH, "Fou");
        assertEquals("news", BotGossip.factFor(witness, partner, List.of(), later, new Random(1)).kind(), "a witness");
        assertEquals("news", BotGossip.factFor(told, partner, List.of(), later, new Random(1)).kind(), "told, arrived");
        assertFalse(BotGossip.factFor(told, partner, List.of(), now, new Random(1)).kind().equals("news"),
                "told, but it has not reached it yet");
        BotGossip.Fact none = BotGossip.factFor(stranger, partner, List.of(), later, new Random(1));
        assertFalse(none.kind().equals("news") || none.about().contains("Zakum"),
                "a bot that neither saw nor heard it never brings it up: " + none);
    }

    @Test
    void theWitnessSaysDidUSeeAndTheToldBotSaysHeard() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        BotWorldGossip.tell(n, WITNESS, FRIEND, now);
        long later = n.told.get(FRIEND).knownAt();
        Character partner = bot(FOURTH, "Fou");
        String seen = BotGossip.factFor(bot(WITNESS, "Wit"), partner, List.of(), later, new Random(3)).opener();
        String heard = BotGossip.factFor(bot(FRIEND, "Fri"), partner, List.of(), later, new Random(3)).opener();
        assertEquals(BotWorldGossip.line(n, true, "", new Random(3)), seen);
        assertEquals(BotWorldGossip.line(n, false, "", new Random(3)), heard);
    }

    // ── how it spreads ──

    @Test
    void newsIsToldAtMostTwoMouthsFromAWitnessAndKeptAsReportedHearsayOfLowerConfidence() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        assertSame(n, BotWorldGossip.pass(WITNESS, FRIEND, now, new Random(1)));
        long t1 = n.told.get(FRIEND).knownAt();
        assertSame(n, BotWorldGossip.pass(FRIEND, FRIENDS_FRIEND, t1, new Random(1)), "a told bot passes it on");
        long t2 = n.told.get(FRIENDS_FRIEND).knownAt();
        assertEquals(2, n.hopsOf(FRIENDS_FRIEND, t2));
        assertNull(BotWorldGossip.pass(FRIENDS_FRIEND, FOURTH, t2, new Random(1)), "a third mouth: too far from a witness");
        assertFalse(n.told.containsKey(FOURTH));

        Note firstHand = BotMemory.about(FRIEND - B, PLAYER).get(0);
        assertEquals(Source.HEARSAY, firstHand.source());
        assertEquals(Provenance.REPORTED, firstHand.provenance());
        assertEquals(1, firstHand.hops());
        assertEquals(WITNESS - B, firstHand.fromBotId());
        assertEquals(0.5, firstHand.confidence(), 1e-9);
        assertEquals("heard they killed Zakum", firstHand.text());
        Note secondHand = BotMemory.about(FRIENDS_FRIEND - B, PLAYER).get(0);
        assertEquals(2, secondHand.hops());
        assertEquals(FRIEND - B, secondHand.fromBotId());
        assertEquals(0.25, secondHand.confidence(), 1e-9, "each mouth lowers it");
        assertTrue(BotMemory.about(STRANGER - B, PLAYER).isEmpty());
        assertTrue(BotMemory.about(FOURTH - B, PLAYER).isEmpty());
    }

    @Test
    void aBotThatKnowsNothingPassesNothingAndNoOneIsToldTwice() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        assertNull(BotWorldGossip.pass(STRANGER, FRIEND, now, new Random(1)), "it cannot pass on what it never heard");
        assertNull(BotWorldGossip.pass(WITNESS, WITNESS, now, new Random(1)));
        assertNotNull(BotWorldGossip.pass(WITNESS, FRIEND, now, new Random(1)));
        assertNull(BotWorldGossip.pass(WITNESS, FRIEND, now + 1000, new Random(1)), "already told");
        assertNull(BotWorldGossip.pass(WITNESS, PLAYER_AS_BOT(), now, new Random(1)), "the subject needs no telling");
        // a told bot cannot pass it before the telling has arrived
        assertNull(BotWorldGossip.pass(FRIEND, FRIENDS_FRIEND, now, new Random(1)));
        assertEquals(1, BotMemory.about(FRIEND - B, PLAYER).size());
    }

    @Test
    void canPassAnswersWhatPassWouldDoWithoutTellingAnyone() {
        long now = clock.get();
        zakum(now, WITNESS);
        assertFalse(BotWorldGossip.canPass(STRANGER, FRIEND, now), "it never heard it");
        assertFalse(BotWorldGossip.canPass(WITNESS, WITNESS, now));
        assertFalse(BotWorldGossip.canPass(WITNESS, PLAYER, now), "the subject needs no telling");
        assertTrue(BotWorldGossip.canPass(WITNESS, FRIEND, now));
        assertTrue(BotWorldGossip.canPass(WITNESS, FRIEND, now), "asking told nobody");
        assertEquals(0, BotMemory.about(FRIEND - B, PLAYER).size());
        assertNotNull(BotWorldGossip.pass(WITNESS, FRIEND, now, new Random(1)));
        assertFalse(BotWorldGossip.canPass(WITNESS, FRIEND, now + 1000), "already told");
    }

    private static int PLAYER_AS_BOT() {
        return PLAYER;
    }

    @Test
    void staleNewsIsNotToldAndTheTellingIsBounded() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        assertNull(BotWorldGossip.pass(WITNESS, FRIEND, now + BotWorldGossip.TTL_MS + 1, new Random(1)));
        for (int i = 0; i < BotWorldGossip.TOLD_CAP + 5; i++) {
            BotWorldGossip.pass(WITNESS, B + 100 + i, now, new Random(i));
        }
        assertEquals(BotWorldGossip.TOLD_CAP, n.told.size());
    }

    @Test
    void aGossipStepAlongABondTellsTheNewsToAFriendOnTheMap() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        bonds.put(WITNESS, List.of(FRIEND));
        Character w = bot(WITNESS, "Wit"), f = bot(FRIEND, "Fri");
        Map<Integer, List<Character>> byMap = Map.of(100000000, List.of(w, f));
        for (int seed = 0; seed < 60 && !n.told.containsKey(FRIEND); seed++) {
            BotGossip.step(byMap, now, new Random(seed));
        }
        assertTrue(n.told.containsKey(FRIEND), "the witness's bond hears it in a step");
        assertEquals(WITNESS, n.told.get(FRIEND).fromId());
        assertFalse(n.told.containsKey(STRANGER));
    }

    @Test
    void aThreadThatTellsAStoryOfTheNewsMakesTheListenerKnowIt() {
        long now = clock.get();
        News n = zakum(now, WITNESS);
        Character witness = bot(WITNESS, "Wit");
        BotGossip.Fact fact = BotGossip.factFor(witness, bot(FRIEND, "Fri"), List.of(), now, new Random(1));
        assertEquals("news", fact.kind());
        assertNotNull(BotGossip.keep(FRIEND, WITNESS, "Wit", fact, now));
        assertTrue(n.told.containsKey(FRIEND), "kept in a thread: now it knows");
        assertTrue(n.knows(FRIEND, n.told.get(FRIEND).knownAt()));
        // the one it was told it can pass one mouth further, and the stranger that nobody told still knows nothing
        assertFalse(n.knows(STRANGER, now + 10 * 3600_000L));
    }

    // ── witnessed notes about a bot ──

    @Test
    void theWitnessesOfABotsDeedKeepAFirstHandNoteAboutThatBot() {
        long now = clock.get();
        MapleMap map = mock(MapleMap.class);
        Character w1 = bot(WITNESS, "Wit");
        Character w2 = bot(FRIEND, "Fri");
        when(map.getAllPlayers()).thenReturn(List.of(w1, w2));
        News made = null;
        int actorId = 0;
        for (int i = 0; i < 400 && made == null; i++) { // a bot's deed makes the news one time in four (seeded)
            actorId = B + 500 + i;
            Character actor = mock(Character.class);
            when(actor.getId()).thenReturn(actorId);
            when(actor.getName()).thenReturn("Actor" + i);
            when(actor.getMap()).thenReturn(map);
            when(actor.getMapId()).thenReturn(280030000);
            made = BotWorldGossip.record(Kind.BOSS, actor, "Zakum", null, now);
        }
        assertNotNull(made, "one of 400 bots made the news");
        assertEquals(java.util.Set.of(WITNESS, FRIEND), made.witnesses);
        Note note = BotMemory.about(WITNESS - B, actorId).get(0);
        assertEquals(BotMemory.SubjectType.BOT, note.subjectType());
        assertEquals(Provenance.WITNESSED, note.provenance());
        assertEquals(Source.DIRECT, note.source());
        assertEquals(280030000, note.locationMapId(), "where they saw it");
        assertEquals("was there when they killed Zakum", note.text());
        assertTrue(note.valence() > 0);
    }

}
