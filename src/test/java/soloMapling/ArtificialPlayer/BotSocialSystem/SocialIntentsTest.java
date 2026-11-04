package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Created;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Presence;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Result;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Step;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 5: the intent book in a bot's life memory, and the scheduler that keeps or breaks what it holds.
 * New code, so the fail-before is that none of it existed. Everything the game would supply (who is online, whether the
 * actor is free, the opt-outs, the act, the consequences) is a fake behind the seams.
 */
class SocialIntentsTest {

    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final AtomicInteger NEXT = new AtomicInteger(BotStore.BOT_ID_BASE + 40_000);
    static final int PLAYER = 4_242;

    final AtomicLong clock = new AtomicLong(2_000_000_000_000L);
    final Fakes fakes = new Fakes();
    int actor, other;

    /** The seams, scriptable. */
    static final class Fakes implements SocialIntents.World, SocialIntents.Consequences, SocialIntents.Performer {
        final Map<Integer, Presence> online = new HashMap<>();
        final Set<Integer> unreachableMaps = new HashSet<>();
        boolean free = true, aim = true, throwOnPerform;
        Result answer = new Result(true, "done");
        final List<String> performed = new ArrayList<>();
        final List<String> kept = new ArrayList<>(), broken = new ArrayList<>();
        final List<Integer> aimed = new ArrayList<>();

        public Presence presence(int id) {
            return online.get(id);
        }

        public boolean reachable(int actorId, int mapId) {
            return !unreachableMaps.contains(mapId);
        }

        public boolean free(int actorId, long now) {
            return free;
        }

        public boolean mayAim(int actorId, int targetId, SocialActKind kind) {
            aimed.add(targetId);
            return aim;
        }

        public String name(int id) {
            return id == PLAYER ? "Josh" : "Bot" + (id - BotStore.BOT_ID_BASE);
        }

        public void kept(SocialIntent in, long now) {
            kept.add(in.kind() + ":" + in.target());
        }

        public void broken(SocialIntent in, long now) {
            broken.add(in.kind() + ":" + in.target());
        }

        public Result perform(int a, int t, SocialActKind kind, long now) {
            performed.add(a + ">" + t + " " + kind);
            if (throwOnPerform) {
                throw new IllegalStateException("boom");
            }
            return answer;
        }
    }

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialIntents.SWITCH);
        SocialSim.useForTest(clock::get, 7);
        SocialBudget.resetForTest();
        SocialIntents.resetForTest();
        SocialIntents.useForTest(fakes, fakes, fakes);
        actor = NEXT.incrementAndGet();
        other = NEXT.incrementAndGet();
        LifeMemoryStore.of(actor);        // a loaded memory: what a roster bot has once its brain runs
        fakes.online.put(actor, new Presence(100000000, 1));
        fakes.online.put(other, new Presence(100000000, 1));
        fakes.online.put(PLAYER, new Presence(100000000, 1));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SocialIntents.SWITCH);
        SocialIntents.resetForTest();
        SocialBudget.resetForTest();
        SocialSim.reset();
    }

    long now() {
        return clock.get();
    }

    Created make(SocialActKind kind, int target, long dueIn) {
        return SocialIntents.promise(actor, target, kind, dueIn, 100000000, "talk:TEST", now());
    }

    // ── creating ──

    @Test
    void aPromiseIsStoredWithItsFieldsAndAnIdThatNamesItsActor() {
        Created c = make(SocialActKind.INVITE, other, 5 * MIN);
        assertTrue(c.ok() && c.fresh());
        SocialIntent in = c.intent();
        assertEquals(actor, in.actor());
        assertEquals(other, in.target());
        assertEquals(SocialActKind.INVITE, in.kind());
        assertEquals(now(), in.createdAt());
        assertEquals(now() + 5 * MIN, in.dueAt());
        assertEquals(now() + 5 * MIN + SocialIntents.WINDOW_MS, in.expiresAt());
        assertEquals(100000000, in.mapId());
        assertEquals("talk:TEST", in.origin());
        assertEquals(actor, (int) (in.id() >>> 32), "the id carries its actor, so it is unique across bots");
        assertEquals(List.of(in), SocialIntents.live(actor));
        assertEquals(1L, SocialIntents.counters().get("created"));
        assertEquals(1L, SocialIntents.counters().get("created INVITE"));
    }

    @Test
    void liveIntentsAreOrderedByDueTimeThenIdWhateverTheOrderTheyWereAdded() {
        make(SocialActKind.SPEND_TIME, other, 9 * MIN);
        make(SocialActKind.INVITE, other, 3 * MIN);
        make(SocialActKind.OFFER_HELP, other, 3 * MIN);
        List<SocialIntent> live = SocialIntents.live(actor);
        assertEquals(List.of(SocialActKind.INVITE, SocialActKind.OFFER_HELP, SocialActKind.SPEND_TIME),
                live.stream().map(SocialIntent::kind).toList());
        assertTrue(live.get(0).id() < live.get(1).id(), "same due time: the earlier id first");
    }

    @Test
    void atMostThreeAreLiveAndAFourthIsRefusedAndCounted() {
        make(SocialActKind.INVITE, other, MIN);
        make(SocialActKind.OFFER_HELP, other, MIN);
        make(SocialActKind.SPEND_TIME, other, MIN);
        Created fourth = make(SocialActKind.GREET, other, MIN);
        assertFalse(fourth.ok());
        assertEquals("book full", fourth.reason());
        assertEquals(3, SocialIntents.live(actor).size());
        assertEquals(1L, SocialIntents.counters().get("refused"));
        assertEquals(3L, SocialIntents.counters().get("created"));
    }

    @Test
    void theSameTargetAndKindIsOneIntentNotTwo() {
        Created first = make(SocialActKind.INVITE, other, MIN);
        Created again = make(SocialActKind.INVITE, other, 8 * MIN);
        assertTrue(again.ok());
        assertFalse(again.fresh());
        assertEquals(first.intent(), again.intent(), "the live one is returned as it is");
        assertEquals(1, SocialIntents.live(actor).size());
        assertEquals(1L, SocialIntents.counters().get("created"));
    }

    @Test
    void idsAreNeverReusedAfterAnIntentEnds() {
        long first = make(SocialActKind.INVITE, other, MIN).intent().id();
        assertTrue(SocialIntents.cancel(actor, first, "test", now()));
        long second = make(SocialActKind.INVITE, other, MIN).intent().id();
        assertNotEquals(first, second);
        assertTrue(second > first);
    }

    @Test
    void timesAreClampedAndTextIsBounded() {
        long far = now() + TimeUnit.DAYS.toMillis(3);
        SocialIntent in = SocialIntents.create(actor, other, SocialActKind.INVITE, now() - 5 * MIN, now() - 9 * MIN, 0,
                "x".repeat(500) + "\nline", now()).intent();
        assertEquals(now(), in.dueAt(), "never due in the past");
        assertEquals(now() + SocialIntents.WINDOW_MS, in.expiresAt(), "a deadline not after the due time gets the default window");
        assertEquals(SocialIntent.ORIGIN_MAX, in.origin().length());
        assertFalse(in.origin().contains("\n"));
        SocialIntent late = SocialIntents.create(actor, other, SocialActKind.OFFER_HELP, far, far + TimeUnit.DAYS.toMillis(5),
                0, "t", now()).intent();
        assertEquals(now() + SocialIntents.MAX_HORIZON_MS, late.dueAt());
        assertEquals(late.dueAt() + SocialIntents.MAX_WINDOW_MS, late.expiresAt());
    }

    @Test
    void badRequestsCreateNothing() {
        assertFalse(SocialIntents.promise(actor, actor, SocialActKind.INVITE, MIN, 0, "t", now()).ok());
        assertFalse(SocialIntents.promise(actor, other, null, MIN, 0, "t", now()).ok());
        assertFalse(SocialIntents.promise(PLAYER, other, SocialActKind.INVITE, MIN, 0, "t", now()).ok(),
                "only a bot can promise");
        int unloaded = NEXT.incrementAndGet();
        assertFalse(SocialIntents.promise(unloaded, other, SocialActKind.INVITE, MIN, 0, "t", now()).ok(),
                "no loaded memory: nothing is read from a table to make room for it");
        assertTrue(SocialIntents.live(actor).isEmpty());
        assertEquals(0L, SocialIntents.counters().get("created"));
    }

    @Test
    void aPlayerTargetThatOptedOutOrIsGatedGetsNoIntentButABotTargetNeedsNoGate() {
        fakes.aim = false;
        Created refused = make(SocialActKind.OFFER_HELP, PLAYER, MIN);
        assertFalse(refused.ok());
        assertEquals("opted out or gated", refused.reason());
        assertTrue(SocialIntents.live(actor).isEmpty());
        assertTrue(make(SocialActKind.INVITE, other, MIN).ok(), "a bot target is not gated");
        fakes.aim = true;
        assertTrue(make(SocialActKind.OFFER_HELP, PLAYER, MIN).ok());
    }

    @Test
    void withTheFlagOffNothingIsCreatedTickedOrCounted() {
        make(SocialActKind.INVITE, other, 0);
        System.setProperty(SocialIntents.SWITCH, "false");
        assertFalse(SocialIntents.enabled());
        Created c = make(SocialActKind.OFFER_HELP, other, MIN);
        assertFalse(c.ok());
        assertEquals("off", c.reason());
        assertEquals(1L, SocialIntents.counters().get("created"), "the one made before the flag went off");
        assertEquals(0L, SocialIntents.counters().get("refused"), "off is not a refusal, it is no behaviour at all");
        assertTrue(SocialIntents.tick(actor, now() + 10 * MIN).isEmpty());
        assertTrue(fakes.performed.isEmpty());
        assertFalse(SocialIntents.dueSoon(actor, now()));
    }

    // ── persistence ──

    @Test
    void theBookSurvivesALifeMemorySaveAndRestore() {
        LifeMemory memory = LifeMemoryStore.of(actor);
        SocialIntent a = make(SocialActKind.INVITE, other, 4 * MIN).intent();
        SocialIntent b = make(SocialActKind.OFFER_HELP, PLAYER, 9 * MIN).intent();
        SocialIntents.complete(actor, make(SocialActKind.SPEND_TIME, other, MIN).intent().id(), now());
        Map<String, Object> saved = (Map<String, Object>) Json.parse(Json.write(memory.save(now()))); // through real JSON text
        assertTrue(saved.containsKey("intents"));
        LifeMemory restored = new LifeMemory(actor, saved);
        assertEquals(List.of(a, b), restored.intents(IntentBook::live), "every field, in order");
        assertEquals(IntentBook.End.KEPT, restored.intents(book -> book.recent().get(0).end()), "how the last one ended");
        long fresh = restored.intents(book -> book.add(other, SocialActKind.GREET, now(), now(), now() + 1, 0, "x").id());
        assertTrue(fresh > b.id() && fresh > a.id(), "the sequence continues after a restart");
    }

    @Test
    void aSavedBookNobodyAskedAboutIsWrittenBackAsReadAndAReadNeverMarksItDirty() {
        LifeMemory memory = LifeMemoryStore.of(actor);
        make(SocialActKind.INVITE, other, 4 * MIN);
        Map<String, Object> saved = (Map<String, Object>) Json.parse(Json.write(memory.save(now())));
        LifeMemory restored = new LifeMemory(actor, saved);
        assertNotNull(restored.save(now()).get("intents"), "never read this run: written back as it came");
        assertEquals(saved.get("intents"), Json.parse(Json.write(restored.save(now()).get("intents"))));
        restored.takeDirty(now());
        assertEquals(1, restored.intents(IntentBook::size));
        restored.intents(book -> book.due(now()));
        restored.intents(book -> book.dueSoon(now(), SocialIntents.DUE_SOON_MS));
        restored.intents(IntentBook::live);
        assertNull(restored.takeDirty(now()), "asking about intents is not a change: no write");
        restored.intents(book -> book.add(other, SocialActKind.GREET, now(), now(), now() + 1, 0, "x"));
        assertNotNull(restored.takeDirty(now()), "a change is");
    }

    @Test
    void aBrokenOrAlienRowIsSkippedNotFatalAndTheCapHolds() {
        List<Object> live = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            live.add(Map.of("id", (long) i, "target", other, "kind", "INVITE", "due", 1000L * i, "expires", 1000L * i + 500));
        }
        live.add(Map.of("id", 9L, "target", other, "kind", "NOT_A_KIND", "due", 1L, "expires", 2L));
        live.add(Map.of("id", 10L, "target", other, "kind", "INVITE", "due", 50L, "expires", 40L));
        live.add("garbage");
        IntentBook book = new IntentBook(actor);
        book.restore(Map.of("next", 3, "live", live));
        assertEquals(3, book.size(), "over the cap: the latest due are dropped");
        assertEquals(List.of(1000L, 2000L, 3000L), book.live().stream().map(SocialIntent::dueAt).toList());
        assertTrue(book.add(other, SocialActKind.GREET, 0, 1, 2, 0, "x") == null, "full");
        book.end(book.live().get(0).id(), IntentBook.End.KEPT, 5);
        long id = book.add(other, SocialActKind.GREET, 0, 1, 2, 0, "x").id();
        assertTrue((id & 0xffffffffL) >= 6, "the sequence is past every id it has seen, whatever 'next' said");
        new IntentBook(actor).restore("not a map");
    }

    // ── the tick ──

    @Test
    void aDueIntentIsCarriedOutThroughThePerformerAndEndsKeptWithItsConsequences() {
        SocialIntent in = make(SocialActKind.INVITE, other, 2 * MIN).intent();
        assertTrue(SocialIntents.tick(actor, now() + MIN).isEmpty(), "not due yet: nothing happens");
        assertTrue(fakes.performed.isEmpty());
        clock.addAndGet(2 * MIN);
        List<Step> steps = SocialIntents.tick(actor, now());
        assertEquals(1, steps.size());
        assertEquals("kept", steps.get(0).what());
        assertEquals(List.of(actor + ">" + other + " INVITE"), fakes.performed);
        assertEquals(List.of("INVITE:" + other), fakes.kept);
        assertTrue(fakes.broken.isEmpty());
        assertTrue(SocialIntents.live(actor).isEmpty());
        assertEquals(1L, SocialIntents.counters().get("kept"));
        assertEquals(1L, SocialIntents.counters().get("kept INVITE"));
        assertEquals(IntentBook.End.KEPT, LifeMemoryStore.of(actor).intents(b -> b.recent().get(0).end()));
        assertTrue(SocialIntents.tick(actor, now()).isEmpty(), "ended: it is not carried out twice");
        assertEquals(1, fakes.performed.size());
        assertEquals(in.id(), steps.get(0).intentId());
    }

    @Test
    void anActThatDoesNotHappenIsRetriedAfterAGapAndBrokenAtTheDeadline() {
        fakes.answer = new Result(false, "not close enough");
        SocialIntent in = make(SocialActKind.OFFER_HELP, other, MIN).intent();
        clock.set(in.dueAt());
        Step first = SocialIntents.tick(actor, now()).get(0);
        assertEquals("deferred", first.what());
        assertTrue(first.reason().contains("not close enough"), first.reason());
        assertEquals(1, fakes.performed.size());
        clock.addAndGet(5_000);
        assertEquals("retry gap", SocialIntents.tick(actor, now()).get(0).reason());
        assertEquals(1, fakes.performed.size(), "no second try inside the gap");
        clock.addAndGet(SocialIntents.RETRY_MS);
        SocialIntents.tick(actor, now());
        assertEquals(2, fakes.performed.size());
        clock.set(in.expiresAt());
        Step last = SocialIntents.tick(actor, now()).get(0);
        assertEquals("broken", last.what());
        assertEquals(2, fakes.performed.size(), "at the deadline it is judged, not tried again");
        assertEquals(List.of("OFFER_HELP:" + other), fakes.broken);
        assertTrue(fakes.kept.isEmpty());
        assertTrue(SocialIntents.live(actor).isEmpty());
        assertEquals(1L, SocialIntents.counters().get("broken"));
        assertEquals(1L, SocialIntents.counters().get("broken OFFER_HELP"));
        assertEquals(IntentBook.End.BROKEN, LifeMemoryStore.of(actor).intents(b -> b.recent().get(0).end()));
    }

    @Test
    void aDeadlineLongPastIsForgottenNotBrokenAndCostsNothing() {
        SocialIntent in = make(SocialActKind.INVITE, other, MIN).intent();
        clock.set(in.expiresAt() + SocialIntents.STALE_MS + 1);
        Step s = SocialIntents.tick(actor, now()).get(0);
        assertEquals("expired", s.what());
        assertTrue(fakes.broken.isEmpty() && fakes.kept.isEmpty() && fakes.performed.isEmpty());
        assertEquals(1L, SocialIntents.counters().get("expired"));
        assertEquals(0L, SocialIntents.counters().get("broken"));
    }

    @Test
    void aTargetWhoWentOfflineIsNotAPromiseBroken() {
        SocialIntent in = make(SocialActKind.INVITE, other, MIN).intent();
        fakes.online.remove(other);
        clock.set(in.dueAt());
        assertEquals("target offline", SocialIntents.tick(actor, now()).get(0).reason());
        assertTrue(fakes.performed.isEmpty());
        clock.set(in.expiresAt());
        assertEquals("cancelled", SocialIntents.tick(actor, now()).get(0).what());
        assertTrue(fakes.broken.isEmpty(), "nobody was waiting");
        assertEquals(1L, SocialIntents.counters().get("cancelled"));
    }

    @Test
    void aBusyOrFarAwayActorWaitsAndIsNotTried() {
        SocialIntent in = make(SocialActKind.SPEND_TIME, other, MIN).intent();
        clock.set(in.dueAt());
        fakes.free = false;
        assertEquals("busy", SocialIntents.tick(actor, now()).get(0).reason());
        fakes.free = true;
        fakes.online.put(other, new Presence(101000000, 1));
        fakes.unreachableMaps.add(101000000);
        assertEquals("not reachable", SocialIntents.tick(actor, now()).get(0).reason());
        assertTrue(fakes.performed.isEmpty());
        fakes.unreachableMaps.clear();
        assertEquals("kept", SocialIntents.tick(actor, now()).get(0).what(), "a reachable map on another map is fine");
        assertEquals(2L, SocialIntents.counters().get("deferred"), "the two waits are counted");
    }

    @Test
    void aPlayerWhoOptsOutBeforeTheDueTimeCancelsItAndNothingIsSaidToThem() {
        SocialIntent in = make(SocialActKind.OFFER_HELP, PLAYER, MIN).intent();
        fakes.aim = false;
        clock.set(in.dueAt());
        assertEquals("cancelled", SocialIntents.tick(actor, now()).get(0).what());
        assertTrue(fakes.performed.isEmpty(), "the gate is checked again at the due time");
        assertEquals(1L, SocialIntents.counters().get("cancelled"));
    }

    @Test
    void theLoopBreakerHoldsBackABotToBotTryAndSeesTheOutcome() {
        SocialIntent in = make(SocialActKind.INVITE, other, MIN).intent();
        clock.set(in.dueAt());
        SocialBudget.record(new SocialBudget.Outcome(actor, other, (1L << 32) | 100000000L, "intent:INVITE", now() - 1_000,
                true)); // the same pair did the same kind of act a second ago
        Step held = SocialIntents.tick(actor, now()).get(0);
        assertEquals("deferred", held.what());
        assertTrue(held.reason().startsWith("budget "), held.reason());
        assertTrue(fakes.performed.isEmpty());
        assertTrue(held.reason().endsWith("pair-cooldown"), held.reason());
        clock.addAndGet(SocialIntents.RETRY_MS + 1);
        assertEquals("kept", SocialIntents.tick(actor, now()).get(0).what());
        assertEquals(2L, SocialBudget.counters().get("recorded"), "a bot-to-bot act is recorded with the loop breaker");
    }

    @Test
    void aFailingPerformerNeverThrowsOutOfTheTickAndTheNotWiredDefaultFailsHonestly() {
        SocialIntent in = make(SocialActKind.INVITE, other, MIN).intent();
        clock.set(in.dueAt());
        fakes.throwOnPerform = true;
        assertEquals("deferred", SocialIntents.tick(actor, now()).get(0).what());
        SocialIntents.useForTest(fakes, fakes, null); // the default performer: not wired
        clock.addAndGet(SocialIntents.RETRY_MS + 1);
        Step s = SocialIntents.tick(actor, now()).get(0);
        assertEquals("deferred", s.what());
        assertEquals("not done: no performer", s.reason());
        assertEquals(1, SocialIntents.live(actor).size(), "still live: nothing was kept or broken by a missing wire");
    }

    @Test
    void fulfilEndsTheMatchingLiveIntentAsKeptOnceAndOnlyOnce() {
        make(SocialActKind.INVITE, other, 6 * MIN);
        assertFalse(SocialIntents.fulfil(actor, other, SocialActKind.OFFER_HELP, now()), "another kind");
        assertTrue(SocialIntents.fulfil(actor, other, SocialActKind.INVITE, now()));
        assertFalse(SocialIntents.fulfil(actor, other, SocialActKind.INVITE, now()));
        assertEquals(List.of("INVITE:" + other), fakes.kept);
        assertEquals(1L, SocialIntents.counters().get("kept"));
    }

    // ── committed while due soon ──

    @Test
    void anActorWithAnIntentDueWithinTwoMinutesCountsAsCommittedUntilItEnds() {
        SocialIntent in = make(SocialActKind.INVITE, other, 10 * MIN).intent();
        assertFalse(SocialIntents.dueSoon(actor, now()), "ten minutes out: free");
        assertFalse(SocialIntents.dueSoon(actor + 1, now()), "someone else's");
        assertTrue(SocialIntents.dueSoon(actor, in.dueAt() - SocialIntents.DUE_SOON_MS));
        assertTrue(SocialIntents.dueSoon(actor, in.dueAt() + 1), "due and waiting: still a commitment");
        assertFalse(SocialIntents.dueSoon(actor, in.expiresAt()), "past the deadline it holds nobody");
        assertTrue(SocialIntents.ignoringOwnIntents(actor, () -> !SocialIntents.dueSoon(actor, in.dueAt())),
                "the free check for its own promise is not failed by that promise");
        assertTrue(SocialIntents.dueSoon(actor, in.dueAt()), "and the exemption ends with the check");
        SocialIntents.cancel(actor, in.id(), "test", now());
        assertFalse(SocialIntents.dueSoon(actor, in.dueAt()));
    }

    // ── observability ──

    @Test
    void theWhyLineListsTheLiveIntentsAndHowTheLastOnesEnded() {
        assertEquals("Intents: none live", SocialIntents.whyLine(actor, now()));
        SocialIntent in = make(SocialActKind.INVITE, other, 5 * MIN).intent();
        make(SocialActKind.OFFER_HELP, PLAYER, 12 * MIN);
        String line = SocialIntents.whyLine(actor, now());
        assertTrue(line.startsWith("Intents: 2 live: INVITE -> Bot" + (other - BotStore.BOT_ID_BASE) + " due in 5 min"), line);
        assertTrue(line.contains("OFFER_HELP -> Josh due in 12 min"), line);
        assertTrue(line.contains("(talk:TEST)"), line);
        SocialIntents.complete(actor, in.id(), now());
        assertTrue(SocialIntents.whyLine(actor, now()).endsWith("| last: kept invite"), SocialIntents.whyLine(actor, now()));
        assertTrue(SocialIntents.whyLine(NEXT.incrementAndGet(), now()).startsWith("Intents: not loaded"));
        System.setProperty(SocialIntents.SWITCH, "false");
        assertTrue(SocialIntents.whyLine(actor, now()).startsWith("Intents: off"));
    }

    @Test
    void metricsExposeTheCountersAndTheLineWithoutWritingAnything() {
        make(SocialActKind.INVITE, other, MIN);
        LifeMemory memory = LifeMemoryStore.of(actor);
        memory.takeDirty(now());
        Map<String, Long> counters = SocialMetrics.intentCounters();
        for (String key : List.of("created", "kept", "broken", "expired", "cancelled", "refused", "deferred")) {
            assertTrue(counters.containsKey(key), key);
        }
        assertEquals(1L, counters.get("created INVITE"));
        assertEquals(1, SocialMetrics.intentLines(actor, now()).size());
        assertNull(memory.takeDirty(now()), "reading the line and the counters changed nothing");
    }
}
