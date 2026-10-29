package soloMapling.achievements;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Progress, thresholds, exactly-once awards, full bags, restarts and a crash between the item and the receipt. */
class AchievementServiceTest {
    static final int PLAYER = 4001;
    static final int BOT = 1_000_000_007;

    MemoryStore store;
    FakeHands hands;
    AtomicLong clock;
    GoalCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryStore();
        hands = new FakeHands();
        clock = new AtomicLong(1_800_000_000_000L);
        catalog = GoalCatalogTest.shipped();
    }

    AchievementService service() {
        return new AchievementService(catalog, store, hands, clock::incrementAndGet, Runnable::run);
    }

    static Subject player(int id) {
        return new Subject(id, false, null);
    }

    // chr is null in these tests: the fake hands ignore it, and a null character means "offline" only for settle().
    Subject here(int id, boolean bot) {
        return new Subject(id, bot, FAKE_CHR);
    }

    static final client.Character FAKE_CHR = org.mockito.Mockito.mock(client.Character.class);

    @Test
    void aCountReachesItsTargetOnceAndTheMedalComesOnce() {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        for (int i = 0; i < 9; i++) {
            assertTrue(s.handle(me, Event.of(Trigger.FISH), false).isEmpty());
        }
        assertEquals(9, store.of(PLAYER).get("fish-10").progress());
        assertEquals(0, hands.gives);
        List<Goal> got = s.handle(me, Event.of(Trigger.FISH), false);
        assertEquals(List.of("fish-10"), got.stream().map(Goal::id).toList());
        assertEquals(1, hands.gives);
        assertTrue(hands.bag.contains(1142196));
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("fish-10").state());
        assertEquals(List.of("fish-10"), hands.announced);
        for (int i = 0; i < 20; i++) {
            s.handle(me, Event.of(Trigger.FISH), false);
        }
        assertEquals(1, hands.gives, "a finished goal never pays twice");
        assertEquals(1, store.markEarnedCalls, "one claim for fish-10");
    }

    @Test
    void levelsAndJobsCompleteSeveralGoalsAtOnce() {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        s.handle(me, new Event(Trigger.LEVEL, 75), false);
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("level-30").state());
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("level-70").state());
        assertNull(store.of(PLAYER).get("level-120"));
        s.handle(me, new Event(Trigger.JOB, 3), false);
        assertEquals(5, hands.gives, "level 30 and 70, then job 1, 2 and 3 in one advance (a seeded character)");
        assertNull(store.of(PLAYER).get("job-4"));
    }

    @Test
    void invalidAndUnrelatedEventsChangeNothing() {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        s.handle(me, new Event(Trigger.MOB_KILL, 100100), false);
        s.handle(me, new Event(Trigger.CLEAR, 1, "SomeOtherPQ"), false);
        s.handle(me, new Event(Trigger.VISIT, 1), false);
        s.handle(me, new Event(Trigger.LEVEL, 0), false);
        s.handle(me, new Event(Trigger.CHECKIN, 6), false);
        assertTrue(store.of(PLAYER).isEmpty());
        assertEquals(0, hands.gives);
    }

    @Test
    void theNamedKillAndClearCount() {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        s.handle(me, new Event(Trigger.MOB_KILL, 2220000), false);
        s.handle(me, new Event(Trigger.CLEAR, 1, "ZakumBattle"), false);
        s.handle(me, new Event(Trigger.CLEAR, 1, "KerningPQ_1"), false);
        s.handle(me, new Event(Trigger.CHECKIN, 7), false);
        assertEquals(Set4.of("kill-mano", "clear-zakum", "pq-kerning", "checkin-7"), Set4.granted(store, PLAYER));
        s.handle(me, new Event(Trigger.CHECKIN, 28), false);
        assertTrue(Set4.granted(store, PLAYER).contains("checkin-28"));
    }

    @Test
    void progressSurvivesARestart() {
        Subject me = here(PLAYER, false);
        AchievementService first = service();
        for (int i = 0; i < 6; i++) {
            first.handle(me, Event.of(Trigger.QUEST), false);
        }
        AchievementService second = service(); // a new process: empty cache, same database
        for (int i = 0; i < 44; i++) {
            second.handle(me, Event.of(Trigger.QUEST), false);
        }
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("quests-50").state(), "6 + 44 quests");
        assertEquals(1, hands.gives);
    }

    @Test
    void aFullBagLeavesTheMedalOwedUntilThereIsRoom() {
        hands.room = 0;
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        s.handle(me, Event.of(Trigger.WEDDING), false);
        assertEquals(AchievementStore.EARNED, store.of(PLAYER).get("wedding").state());
        assertTrue(hands.told.stream().anyMatch(t -> t.contains("Make room")), hands.told.toString());
        s.settle(me);
        assertEquals(AchievementStore.EARNED, store.of(PLAYER).get("wedding").state(), "still no room");
        hands.room = 1;
        s.handle(me, Event.of(Trigger.GUILD), false); // any later event retries the owed medal
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("wedding").state());
        assertEquals(AchievementStore.EARNED, store.of(PLAYER).get("guild").state(), "one slot, two medals: the second waits");
    }

    @Test
    void anOfflineCharacterGetsTheMedalAtLogin() {
        AchievementService s = service();
        s.handle(player(PLAYER), Event.of(Trigger.WEDDING), false); // no Character: nothing can be handed over
        assertEquals(AchievementStore.EARNED, store.of(PLAYER).get("wedding").state());
        assertEquals(0, hands.gives);
        s.settle(here(PLAYER, false));
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("wedding").state());
        assertEquals(1, hands.gives);
    }

    @Test
    void aCrashBetweenTheItemAndTheReceiptNeverGivesTwo() throws Exception {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        store.failMarkGranted = true;
        s.handle(me, Event.of(Trigger.WEDDING), false); // item in the bag, the receipt write fails
        assertEquals(1, hands.gives);
        assertEquals(AchievementStore.EARNED, store.of(PLAYER).get("wedding").state());
        store.failMarkGranted = false;
        AchievementService afterRestart = service();
        afterRestart.settle(me);
        assertEquals(1, hands.gives, "the medal is already in the bag: only the receipt is written");
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("wedding").state());
    }

    @Test
    void aSecondProcessCannotClaimTheSameGoalTwice() throws Exception {
        assertTrue(store.markEarned(PLAYER, "wedding", 1, 5));
        assertFalse(store.markEarned(PLAYER, "wedding", 1, 6), "already earned");
        assertTrue(store.markGranted(PLAYER, "wedding", 7));
        assertFalse(store.markGranted(PLAYER, "wedding", 8), "the receipt is written once");
        assertFalse(store.markEarned(PLAYER, "wedding", 1, 9), "and a granted goal is never earned again");
    }

    @Test
    void botsEarnTheSameWayAndAreRecordedEvenWithAFullBag() {
        hands.room = 0;
        AchievementService s = service();
        Subject bot = here(BOT, true);
        s.handle(bot, new Event(Trigger.LEVEL, 35), false);
        assertEquals(AchievementStore.GRANTED, store.of(BOT).get("level-30").state());
        assertTrue(hands.told.isEmpty(), "bots are told nothing");
        assertEquals(List.of("level-30"), hands.announced, "but their goals reach the feed");
        hands.room = 1;
        s.handle(bot, new Event(Trigger.FISH, 1), false);
        for (int i = 0; i < 9; i++) {
            s.handle(bot, Event.of(Trigger.FISH), false);
        }
        assertTrue(hands.bag.contains(1142196));
        // a bot not in the world just now is still recorded
        s.handle(new Subject(BOT + 1, true, null), Event.of(Trigger.WEDDING), false);
        assertEquals(AchievementStore.GRANTED, store.of(BOT + 1).get("wedding").state());
    }

    @Test
    void seededFactsAreQuietAndGmGrantsAndResetsWork() throws Exception {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        s.handle(me, new Event(Trigger.LEVEL, 40), true);
        assertTrue(hands.announced.isEmpty(), "a login seed is not news");
        assertEquals(AchievementService.Grant.GRANTED, s.forceEarn(me, catalog.get("rebirth-1")));
        assertTrue(hands.announced.isEmpty(), "a GM grant is not news either");
        assertEquals(AchievementService.Grant.ALREADY, s.forceEarn(me, catalog.get("rebirth-1")));
        assertTrue(s.reset(PLAYER, catalog.get("rebirth-1")));
        assertNull(store.of(PLAYER).get("rebirth-1"));
        assertEquals(AchievementService.Grant.GRANTED, s.forceEarn(me, catalog.get("rebirth-1")));
        assertEquals(2, hands.gives, "level 30 and the first rebirth medal; the bag already held it after the reset");
    }

    @Test
    void submitQueuesOnTheExecutorAndSkipsFinishedGoals() {
        List<Runnable> queue = new java.util.ArrayList<>();
        AchievementService s = new AchievementService(catalog, store, hands, clock::incrementAndGet, queue::add);
        Subject me = here(PLAYER, false);
        s.submit(me, new Event(Trigger.MOB_KILL, 100100));
        assertTrue(queue.isEmpty(), "an unrelated mob never reaches the thread");
        s.submit(me, new Event(Trigger.MOB_KILL, 2220000));
        assertEquals(1, queue.size());
        queue.forEach(Runnable::run);
        assertEquals(AchievementStore.GRANTED, store.of(PLAYER).get("kill-mano").state());
        queue.clear();
        s.submit(me, new Event(Trigger.MOB_KILL, 2220000));
        assertTrue(queue.isEmpty(), "Mano is done: no more work for this character");
    }

    /** Tiny helper: the goal ids a character has been granted. */
    static final class Set4 {
        static java.util.Set<String> of(String... ids) {
            return new java.util.TreeSet<>(List.of(ids));
        }

        static java.util.Set<String> granted(MemoryStore st, int id) {
            java.util.Set<String> out = new java.util.TreeSet<>();
            st.of(id).forEach((k, v) -> {
                if (v.state() == AchievementStore.GRANTED) out.add(k);
            });
            return out;
        }
    }

    /** TEST 2026-10-29 t-ac-1: fish-100 kept the check's catches between runs and awarded inside the nine-catch step. */
    @Test
    void theQaCheckResetsEveryGoalItsCatchesCount() throws Exception {
        AchievementService s = service();
        Subject me = here(PLAYER, false);
        for (int i = 0; i < 95; i++) {
            s.handle(me, Event.of(Trigger.FISH), true);
        }
        assertEquals(95, store.of(PLAYER).get("fish-100").progress());
        assertTrue(java.util.Arrays.asList(AchievementsQa.touchedGoals(catalog)).contains("fish-100"));
        client.Character chr = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(chr.getId()).thenReturn(PLAYER);
        AchievementsQa.clear(s, chr);
        assertNull(store.of(PLAYER).get("fish-100"), "the leftover count is gone");
        assertTrue(s.handle(me, Event.of(Trigger.FISH), true).isEmpty());
        assertEquals(1, store.of(PLAYER).get("fish-100").progress());
    }
}
