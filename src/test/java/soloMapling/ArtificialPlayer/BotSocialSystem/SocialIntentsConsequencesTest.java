package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Persona;
import soloMapling.ArtificialPlayer.BotEpisodes;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Presence;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents.Result;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.FakeMemoryTable;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 5: what keeping or breaking a promise does, with the real consequences (not a fake): the promised-to
 * bot's own edge toward the actor on the trust, respect, resentment and obligation axes (and nothing else: the legacy
 * score does not move, the actor's own edge does not move), a BROKEN note in the promised-to bot's memory, and the
 * actor's pride, or anxiety and embarrassment.
 */
class SocialIntentsConsequencesTest {

    static final long MIN = TimeUnit.MINUTES.toMillis(1);
    static final AtomicInteger NEXT = new AtomicInteger(BotStore.BOT_ID_BASE + 60_000);

    final AtomicLong clock = new AtomicLong(3_000_000_000_000L);
    final FakeMemoryTable table = new FakeMemoryTable(false);
    final SocialIntentsTest.Fakes fakes = new SocialIntentsTest.Fakes();
    int actor, promised, control;

    @BeforeEach
    void setUp() {
        System.clearProperty(SocialIntents.SWITCH);
        SocialSim.useForTest(clock::get, 3);
        SocialBudget.resetForTest();
        BotAffinity.useForTest(new AffinityStore.Memory(), Runnable::run, clock::get, id -> true);
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get, id -> new double[]{.25, .25},
                (id, p) -> { }, id -> null);
        Persona.useForTest(null);
        table.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotEpisodes.useForTest(Runnable::run, id -> null, (id, push) -> { });
        SocialIntents.resetForTest();
        SocialIntents.useForTest(fakes, null, fakes); // the REAL consequences
        actor = NEXT.incrementAndGet();
        promised = NEXT.incrementAndGet();
        LifeMemoryStore.of(actor);
        fakes.online.put(actor, new Presence(100000000, 1));
        fakes.online.put(promised, new Presence(100000000, 1));
        // the promised-to bot already knows the actor: an edge to move
        BotBonds.record(promised, actor, BondEvent.PARTY, clock.get(), true);
        control = NEXT.incrementAndGet(); // an identical edge nothing happens to: what the score does by time alone
        BotBonds.record(control, actor, BondEvent.PARTY, clock.get(), true);
    }

    @AfterEach
    void tearDown() {
        SocialIntents.resetForTest();
        FakeMemoryTable.uninstall();
        BotMemory.useForTest(null, null, 0);
        BotEpisodes.useForTest(null, null, null);
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        Persona.useForTest(null);
        SocialSim.reset();
        SocialBudget.resetForTest();
    }

    private SocialIntent due(SocialActKind kind) {
        SocialIntent in = SocialIntents.promise(actor, promised, kind, MIN, 100000000, "talk:TEST", clock.get()).intent();
        clock.set(in.dueAt());
        return in;
    }

    private double feel(SocialMind.Emotion e) {
        LifeMemory m = LifeMemoryStore.of(actor);
        return m.mind(mind -> mind.emotion(e, clock.get()));
    }

    @Test
    void aBrokenPromiseCostsTrustOnThePromisedToBotsEdgeAndNothingOnTheActorsOwn() {
        SocialIntent in = due(SocialActKind.INVITE);
        fakes.answer = new Result(false, "no party");
        Axes before = BotBonds.axes(promised, actor);
        Axes actorsOwnBefore = BotBonds.axes(actor, promised);
        double anxious = feel(SocialMind.Emotion.ANXIETY), shy = feel(SocialMind.Emotion.EMBARRASSMENT);
        SocialIntents.tick(actor, clock.get());
        clock.set(in.expiresAt());
        assertEquals("broken", SocialIntents.tick(actor, clock.get()).get(0).what());

        Axes after = BotBonds.axes(promised, actor);
        assertTrue(after.trust() < before.trust() - 0.03, "trust fell: " + before.trust() + " -> " + after.trust());
        assertTrue(after.resentment() > before.resentment(), "a touch of resentment");
        assertTrue(after.respect() < before.respect());
        assertEquals(BotBonds.score(control, actor), BotBonds.score(promised, actor), 1e-12, "the legacy score is not touched");
        assertEquals(actorsOwnBefore, BotBonds.axes(actor, promised), "the one who broke it learns nothing about how it is seen");
        assertTrue(feel(SocialMind.Emotion.ANXIETY) > anxious + 0.1, "the actor is anxious");
        assertTrue(feel(SocialMind.Emotion.EMBARRASSMENT) > shy + 0.15, "and embarrassed");
    }

    @Test
    void aBrokenPromiseIsRememberedByThePromisedToBotWithANegativeFeeling() {
        SocialIntent in = due(SocialActKind.OFFER_HELP);
        fakes.answer = new Result(false, "busy");
        clock.set(in.expiresAt());
        SocialIntents.tick(actor, clock.get());
        List<BotMemory.Note> notes = BotMemory.about(promised - BotStore.BOT_ID_BASE, actor);
        assertEquals(1, notes.size(), notes.toString());
        BotMemory.Note n = notes.get(0);
        assertEquals(BotMemory.Kind.BROKEN, n.kind());
        assertTrue(n.valence() < 0, "it felt bad");
        assertTrue(n.text().contains("help out") && n.text().contains("never did"), n.text());
        assertEquals(BotMemory.SubjectType.BOT, n.subjectType());
        assertTrue(BotMemory.about(actor - BotStore.BOT_ID_BASE, promised).isEmpty(),
                "the actor's memory of the other is not written: it did not wrong it");
    }

    @Test
    void aKeptPromiseRaisesTrustAndObligationAndTheActorsPride() {
        SocialIntent in = due(SocialActKind.SPEND_TIME);
        fakes.answer = new Result(true, "spent time");
        Axes before = BotBonds.axes(promised, actor);
        double proud = feel(SocialMind.Emotion.PRIDE);
        assertEquals("kept", SocialIntents.tick(actor, clock.get()).get(0).what());
        Axes after = BotBonds.axes(promised, actor);
        assertTrue(after.trust() > before.trust() + 0.03, "trust grew");
        assertTrue(after.obligation() > before.obligation(), "it feels it owes a little back");
        assertEquals(BotBonds.score(control, actor), BotBonds.score(promised, actor), 1e-12);
        assertTrue(feel(SocialMind.Emotion.PRIDE) > proud + 0.08, "the actor is proud");
        assertTrue(BotMemory.about(promised - BotStore.BOT_ID_BASE, actor).isEmpty(), "a kept promise is not a grievance");
        assertEquals(in.actor(), actor);
    }

    @Test
    void aPromiseToAStrangerLeavesNoBondBehindAndAPromiseToAPlayerOnlyMovesTheActor() {
        int stranger = NEXT.incrementAndGet();
        assertFalse(BotBonds.promise(stranger, actor, false, clock.get()), "no edge, nothing to move");
        assertEquals(Axes.NONE, BotBonds.axes(stranger, actor));
        assertFalse(BotBonds.promise(actor, actor, true, clock.get()));
        assertFalse(BotBonds.promise(4_242, actor, true, clock.get()), "a real player has no bot edge");

        fakes.online.put(4_242, new Presence(100000000, 1));
        SocialIntent in = SocialIntents.promise(actor, 4_242, SocialActKind.OFFER_HELP, MIN, 0, "chat:HELP", clock.get()).intent();
        fakes.answer = new Result(false, "away");
        clock.set(in.expiresAt());
        double shy = feel(SocialMind.Emotion.EMBARRASSMENT);
        assertEquals("broken", SocialIntents.tick(actor, clock.get()).get(0).what());
        assertTrue(feel(SocialMind.Emotion.EMBARRASSMENT) >= shy, "the actor feels it, no matter who it promised");
        assertTrue(BotMemory.about(actor - BotStore.BOT_ID_BASE, 4_242).isEmpty());
    }
}
