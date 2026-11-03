package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRunOutcome.Slot;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Participant;
import soloMapling.mechanics.RunOutcome.ParticipantKind;
import soloMapling.mechanics.RunOutcome.Result;

/**
 * A run's end is a recorded fact ({@link RunOutcome}), written once by {@link RunOutcomes} and read by whoever has
 * something to say about the run: the bots' win and loss lines are one of the readers ({@link RunChatter#onOutcome}),
 * a symptom of the record and never the record. Each of the run's ends is driven through the real tick or the real
 * stop, and the record is asserted whole: content, tier, result, who was in it and what each was, how long, and
 * whether the run was played or simulated.
 */
class RunOutcomeTest {

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final List<Runnable> ticks = new ArrayList<>();
    private final List<RunOutcome> heard = new CopyOnWriteArrayList<>();
    private final Consumer<RunOutcome> ear = heard::add;
    private final List<Integer> brains = new ArrayList<>();

    private java.util.function.Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void setUp() {
        RunOutcomes.clearForTest();
        RunOutcomes.listen(ear);
        wasScheduler = MechanicsRuns.scheduler;
        ScheduledExecutorService mine = mock(ScheduledExecutorService.class);
        when(mine.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenAnswer(inv -> {
            ticks.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        MechanicsRuns.scheduler = () -> mine;
    }

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        RunOutcomes.unlisten(ear);
        RunOutcomes.clearForTest();
        for (int id : brains) {
            CharacterStorage.removeActiveBot(id);
        }
        System.clearProperty(Mechanics.CHATTER_FLAG);
    }

    private static EventInstanceManager kpq(int channel, List<Character> players, int leader) {
        return MechanicsRunsInstancesTest.kpqOn(channel, players, leader);
    }

    private MechanicsRuns.Active start(EventInstanceManager eim, long budgetMs, Admission admission) {
        return MechanicsRuns.get().start(eim, KpqStageDataTest.kpq(), true, budgetMs, 1, 1, true, lines::add, null,
                admission);
    }

    private static Admission lobby() {
        return RealRunPool.get().admit(Origin.KPQ_LOBBY, KpqStageDataTest.kpq(), 0);
    }

    private RunOutcome only() {
        assertEquals(1, heard.size(), "one record per run: " + heard);
        return heard.get(0);
    }

    // ---- the record, whole -----------------------------------------------------------------------------------

    @Test
    void aClearedRunIsWrittenWonWithEveryFieldTheOwnerNamed() {
        List<Character> party = MechanicsRunsInstancesTest.bots(5_100);
        EventInstanceManager eim = kpq(2, party, 5_100);
        when(eim.getProperty("1stageclear")).thenReturn("true");
        MechanicsRuns.Active a = start(eim, 0, lobby());
        long before = System.currentTimeMillis();

        ticks.get(0).run();

        RunOutcome o = only();
        assertEquals(a.runId(), o.runId());
        assertEquals("kpq", o.contentId());
        assertEquals(KpqStageDataTest.kpq().name(), o.contentName());
        assertEquals(MechanicsModel.Kind.PQ, o.kind());
        assertEquals(Tier.NORMAL, o.tier());
        assertEquals(Result.WON, o.result());
        assertEquals("cleared", o.reason());
        assertEquals(Mode.REAL, o.mode());
        assertEquals(Origin.KPQ_LOBBY, o.origin());
        assertEquals("Kerning0 w1 ch 2", o.instanceKey());
        assertTrue(o.startedAtMs() <= o.endedAtMs() && o.endedAtMs() >= before, o.toString());
        assertEquals(o.endedAtMs() - o.startedAtMs(), o.durationMs());
        assertEquals(1, o.stageReached());
        assertEquals(1, o.stagesToPlay());
        assertEquals(5_100, o.leaderId());
        assertEquals(List.of(5_100, 5_101, 5_102, 5_103), o.participants().stream().map(Participant::characterId).toList());
        assertTrue(o.participants().stream().allMatch(p -> p.kind() == ParticipantKind.BOT));
        assertFalse(o.humanAtStart());
        assertFalse(o.humanAtEnd());
        assertTrue(o.line().startsWith("outcome kpq WON real KPQ_LOBBY in Kerning0 w1 ch 2 after "), o.line());
        assertTrue(o.line().contains("[" + a.runId() + "]"));
        assertEquals(List.of(o), RunOutcomes.recent());
    }

    @Test
    void aFailedRunIsLostAndAStoppedOneIsAbandoned() {
        MechanicsRuns.Active lost = start(kpq(1, MechanicsRunsInstancesTest.bots(5_200), 5_200), 1_000, lobby());
        lost.run().markStarted(System.currentTimeMillis() - 60_000);
        ticks.get(0).run();

        RunOutcome o = only();
        assertEquals(Result.LOST, o.result());
        assertTrue(o.reason().startsWith("time budget"), o.reason());

        heard.clear();
        ticks.clear();
        MechanicsRuns.Active stopped = start(kpq(2, MechanicsRunsInstancesTest.bots(5_300), 5_300), 0, lobby());
        MechanicsRuns.get().stop(stopped, "yielded to Zak's party");
        RunOutcome s = only();
        assertEquals(Result.ABANDONED, s.result(), "a yield is not an outcome of the content");
        assertEquals("yielded to Zak's party", s.reason());
    }

    @Test
    void everyOtherEndIsAnAbandonedRecordWithItsReason() {
        EventInstanceManager disposed = kpq(1, MechanicsRunsInstancesTest.bots(5_400), 5_400);
        MechanicsRuns.Active a = start(disposed, 0, lobby());
        when(disposed.isEventDisposed()).thenReturn(true);
        ticks.get(0).run();
        assertEquals(Result.ABANDONED, only().result());
        assertEquals("the event ended", only().reason());
        assertEquals(a.runId(), only().runId());
    }

    // ---- a run that never started is still a run -------------------------------------------------------------

    @Test
    void aRunThatEndsAtItsStartAndASecondRunOnOneInstanceAreEachRecordedAbandoned() {
        List<Character> two = new ArrayList<>(MechanicsRunsInstancesTest.bots(5_500).subList(0, 2));
        MechanicsRuns.Active tiny = start(kpq(1, two, 5_500), 0, RealRunPool.exempt(Origin.STAGED));
        assertTrue(tiny.run().ended());
        RunOutcome o = only();
        assertEquals(Result.ABANDONED, o.result());
        assertTrue(o.reason().contains("takes 3-4 members"), o.reason());
        assertEquals(Origin.STAGED, o.origin());

        heard.clear();
        EventInstanceManager eim = kpq(2, MechanicsRunsInstancesTest.bots(5_600), 5_600);
        MechanicsRuns.Active first = start(eim, 0, lobby());
        MechanicsRuns.Active second = start(eim, 0, lobby());
        assertTrue(second.run().ended());
        assertEquals(1, heard.size(), "the refused second run is recorded; the first is still playing");
        assertEquals(second.runId(), heard.get(0).runId());
        assertEquals("this event instance already has a run", heard.get(0).reason());
        assertNotEquals(first.runId(), second.runId());
    }

    @Test
    void aRunIsRecordedOnceHoweverOftenItIsStopped() {
        MechanicsRuns.Active a = start(kpq(1, MechanicsRunsInstancesTest.bots(5_700), 5_700), 0, lobby());

        MechanicsRuns.get().stop(a, "first");
        MechanicsRuns.get().stop(a, "second");
        MechanicsRuns.get().stopAll("third");

        assertEquals(1, heard.size(), heard.toString());
        assertEquals("first", heard.get(0).reason());
    }

    // ---- who was in it, and what each was --------------------------------------------------------------------

    @Test
    void theRecordSaysWhoWasARealPlayerWhoABotAndWhoAnOwnedAlt() {
        Character player = MechanicsRunsInstancesTest.member(5_800, false);
        Character alt = MechanicsRunsInstancesTest.member(5_801, false);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(player);
        Character botA = MechanicsRunsInstancesTest.member(5_802, true);
        Character botB = MechanicsRunsInstancesTest.member(5_803, true);
        EventInstanceManager eim = kpq(1, List.of(player, alt, botA, botB), 5_800);
        when(eim.getProperty("1stageclear")).thenReturn("true");
        start(eim, 0, RealRunPool.exempt(Origin.HUMAN_PARTY));

        ticks.get(0).run();

        RunOutcome o = only();
        assertEquals(List.of(new Participant(5_800, ParticipantKind.PLAYER, true),
                new Participant(5_801, ParticipantKind.OWNED_ALT, false),
                new Participant(5_802, ParticipantKind.BOT, false),
                new Participant(5_803, ParticipantKind.BOT, false)), o.participants());
        assertEquals(Origin.HUMAN_PARTY, o.origin());
        assertTrue(o.humanAtStart());
        assertTrue(o.humanAtEnd());
        assertEquals(List.of(5_800), o.ofKind(ParticipantKind.PLAYER).stream().map(Participant::characterId).toList());
        assertEquals(List.of(5_801), o.ofKind(ParticipantKind.OWNED_ALT).stream().map(Participant::characterId).toList());
        assertTrue(o.line().contains("1 player(s), 2 bot(s), 1 owned alt(s)"), o.line());
    }

    @Test
    void aHumanWhoLeftAMemberOfARunThatWentOnIsNotThereAtTheEnd() {
        Character human = MechanicsRunsInstancesTest.member(5_900, false);
        List<Character> bots = MechanicsRunsInstancesTest.bots(5_910).subList(0, 3);
        List<Character> everyone = new ArrayList<>();
        everyone.add(MechanicsRunsInstancesTest.member(5_910, true)); // a bot leads
        everyone.add(human);
        everyone.addAll(bots.subList(1, 3));
        EventInstanceManager eim = kpq(1, everyone, 5_910);
        MechanicsRuns.Active a = start(eim, 0, RealRunPool.exempt(Origin.HUMAN_PARTY));
        List<Character> without = new ArrayList<>(everyone);
        without.remove(human);
        when(eim.getPlayers()).thenReturn(without);

        ticks.get(0).run(); // the human is gone from the instance: the run loses him as a member
        assertTrue(a.run().members().stream().noneMatch(m -> m.characterId() == 5_900), "he is no longer a member");
        MechanicsRuns.get().stop(a, "stopped by a GM");

        RunOutcome o = only();
        assertTrue(o.humanAtStart(), "he was in it when it started");
        assertFalse(o.humanAtEnd(), "and was no longer a member when it ended");
        assertEquals(Result.ABANDONED, o.result());
    }

    // ---- what the record cannot be made to say ---------------------------------------------------------------

    @Test
    void theRingKeepsTheLatestAndOneListenerThrowingChangesNothing() {
        Consumer<RunOutcome> thrower = x -> {
            throw new IllegalStateException("a listener with a bug");
        };
        RunOutcomes.listen(thrower);
        try {
            MechanicsRuns.Active a = start(kpq(1, MechanicsRunsInstancesTest.bots(6_000), 6_000), 0, lobby());
            MechanicsRuns.get().stop(a, "done");
            assertEquals(1, heard.size(), "the other listener still heard it");
            assertTrue(a.run().ended(), "and the run's end was not disturbed");
        } finally {
            RunOutcomes.unlisten(thrower);
        }

        RunOutcome template = heard.get(0);
        for (int i = 0; i < RunOutcomes.KEEP + 25; i++) {
            RunOutcomes.record(new RunOutcome("r" + i, template.instanceKey(), template.contentId(), template.contentName(),
                    template.kind(), template.tier(), Result.WON, "cleared", Mode.REAL, Origin.KPQ_LOBBY, 1, 2, 1, 1,
                    template.participants(), false, false));
        }
        assertEquals(RunOutcomes.KEEP, RunOutcomes.recent().size());
        assertEquals("r" + (RunOutcomes.KEEP + 24), RunOutcomes.recent().get(RunOutcomes.KEEP - 1).runId());
        assertEquals("r25", RunOutcomes.recent().get(0).runId(), "the oldest are dropped");
    }

    @Test
    void theRecordIsImmutableAndTheMapOfParticipantsIsACopy() {
        List<Participant> mine = new ArrayList<>(List.of(new Participant(1, ParticipantKind.BOT, true)));
        RunOutcome o = new RunOutcome("r", "k", "kpq", "Kerning PQ", MechanicsModel.Kind.PQ, Tier.NORMAL, Result.WON, null,
                Mode.REAL, Origin.KPQ_LOBBY, 5, 1, 1, 1, mine, false, false);
        mine.clear();
        assertEquals(1, o.participants().size(), "the record kept its own copy");
        assertEquals("", o.reason(), "no reason is the empty reason, not null");
        assertEquals(0, o.durationMs(), "an end before its start is a zero duration, not a negative one");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> o.participants().add(new Participant(2, ParticipantKind.BOT, false)));
    }

    // ---- the chatter reads the record ------------------------------------------------------------------------

    private Slot brain(int id) {
        BotSM sm = mock(BotSM.class);
        Slot slot = new Slot();
        when(sm.runOutcome()).thenReturn(slot);
        CharacterStorage.addActiveBot(id, sm);
        brains.add(id);
        return slot;
    }

    private static RunOutcome outcome(Result result, List<Participant> who) {
        return new RunOutcome("chat-run", "Kerning0 w1 ch 1", "kpq", "Kerning Party Quest", MechanicsModel.Kind.PQ,
                Tier.NORMAL, result, result == Result.LOST ? "time budget" : "cleared", Mode.REAL, Origin.KPQ_LOBBY,
                1_000, 61_000, 5, 5, who, false, false);
    }

    @Test
    void theChatterNeedsNoRunOnlyTheRecordAndNeverUsesAnOwnedAlt() {
        Slot leader = brain(6_100);
        Slot other = brain(6_101);
        Slot alt = brain(6_102);
        List<Participant> who = List.of(new Participant(6_100, ParticipantKind.BOT, true),
                new Participant(6_101, ParticipantKind.BOT, false),
                new Participant(6_102, ParticipantKind.OWNED_ALT, false));

        RunChatter.onOutcome(outcome(Result.WON, who));

        assertNotNull(leader.get(), "the leader says it");
        assertNotNull(other.get(), "and one other bot");
        assertNull(alt.get(), "an owned alt never speaks, whatever its brain slot");
        assertNotEquals(leader.get().line(), other.get().line());
    }

    @Test
    void anAbandonedRecordLeavesNothingAndALostOneLeavesALossLine() {
        Slot a = brain(6_200);
        Slot b = brain(6_201);
        List<Participant> who = List.of(new Participant(6_200, ParticipantKind.BOT, true),
                new Participant(6_201, ParticipantKind.BOT, false));

        RunChatter.onOutcome(outcome(Result.ABANDONED, who));
        assertNull(a.get());
        assertNull(b.get());

        RunChatter.onOutcome(outcome(Result.LOST, who));
        assertNotNull(a.get());
        assertTrue(RunChatter.plan(outcome(Result.LOST, who), List.of(6_200, 6_201), 0, new Random(1)).size() == 2);
    }

    @Test
    void aRunsEndFeedsTheChatterThroughTheRecordAndTheSwitchStillTurnsItOff() {
        List<Character> party = MechanicsRunsInstancesTest.bots(6_300);
        List<Slot> slots = new ArrayList<>();
        for (Character c : party) {
            slots.add(brain(c.getId()));
        }
        EventInstanceManager eim = kpq(1, party, 6_300);
        MechanicsRuns.Active a = start(eim, 0, RealRunPool.exempt(Origin.STAGED));
        a.run().markCleared(60_000);
        MechanicsRuns.get().stop(a, "cleared");
        assertEquals(2, slots.stream().filter(s -> s.get() != null).count(), "a win: two bots say it");
        assertEquals(Result.WON, only().result());

        heard.clear();
        for (Slot s : slots) {
            s.set(null);
        }
        System.setProperty(Mechanics.CHATTER_FLAG, "false");
        EventInstanceManager again = kpq(2, party, 6_300);
        MechanicsRuns.Active b = start(again, 0, RealRunPool.exempt(Origin.STAGED));
        b.run().markCleared(60_000);
        MechanicsRuns.get().stop(b, "cleared");
        assertEquals(0, slots.stream().filter(s -> s.get() != null).count(), "switched off: nothing is left to say");
        assertEquals(Result.WON, only().result(), "but the outcome is recorded all the same");
    }
}
