package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 159, part 1: a watched bot walks to its job instructor and advances there; every way the walk can fail ends
 * in today's instant advance; offscreen bots and the default (switch off) keep the instant path.
 */
class JobErrandTest {

    private static final int BASE_ID = 1_000_000_000 + 5100;

    /** A mover that records what the errand asked for and lets the test decide how each step ends. */
    private static final class FakeMover implements JobErrand.Mover {
        boolean reachable = true;
        boolean tripSucceeds = true;
        boolean holdTrip;   // leave the trip callback unanswered (a stuck ferry)
        boolean npcFound = true;
        final Map<Integer, Integer> map = new HashMap<>();
        final List<Integer> travelled = new ArrayList<>();
        final List<Integer> walkedToNpc = new ArrayList<>();
        final List<Integer> cancelled = new ArrayList<>();
        Consumer<Boolean> arrival;

        @Override
        public boolean canReach(Character bot, int mapId) {
            return reachable;
        }

        @Override
        public int mapOf(Character bot) {
            return map.getOrDefault(bot.getId(), 100000000);
        }

        @Override
        public void travel(Character bot, int mapId, Consumer<Boolean> done) {
            travelled.add(mapId);
            if (holdTrip) {
                return;
            }
            if (tripSucceeds) {
                map.put(bot.getId(), mapId);
            }
            done.accept(tripSucceeds);
        }

        @Override
        public void walkToNpc(Character bot, int npcId, Consumer<Boolean> done) {
            walkedToNpc.add(npcId);
            if (!npcFound) {
                done.accept(false);
            } else {
                arrival = done;   // the bot is walking; the test says when it gets there
            }
        }

        @Override
        public void cancel(Character bot) {
            cancelled.add(bot.getId());
        }
    }

    private final FakeMover mover = new FakeMover();
    private final List<Runnable> queued = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private boolean watched = true;
    private boolean on = true;
    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (var job : BotSkillPlan.all().values()) {
            for (BotSkillPlan.Entry entry : job) {
                skills.put(entry.skillId(), new Skill(entry.skillId()));
                if (entry.altSkillId() != 0) {
                    skills.put(entry.altSkillId(), new Skill(entry.altSkillId()));
                }
            }
        }
        for (int granted : BotSkillPlan.allGranted()) {
            skills.put(granted, new Skill(granted));
        }
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        previousSkills = previous;
        field.set(null, skills);
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
        JobErrand.useForTest(mover, (task, ms) -> {
            queued.add(task);
            delays.add(ms);
        }, bot -> watched, () -> on, bot -> said.add(bot.getName()));
    }

    @AfterEach
    void tearDown() throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        field.set(null, previousSkills);
        BotProgression.useCatalogForTest(null);
        JobErrand.useForTest(null, null, null, null, null);
        for (int i = 0; i < 40; i++) {
            BotProgression.forget(BASE_ID + i);
        }
    }

    private static Character bot(int id, int job, int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Errander" + id);
        chr.setJob(Job.getById(job));
        chr.setLevel(level);
        return chr;
    }

    /** Runs what the errand scheduled to run now (the trip start), not the timeout. */
    private void runStart() {
        for (int i = 0; i < queued.size(); i++) {
            if (delays.get(i) == 0L) {
                queued.get(i).run();
            }
        }
    }

    private void runTimeout() {
        for (int i = 0; i < queued.size(); i++) {
            if (delays.get(i) > 0L) {
                queued.get(i).run();
            }
        }
    }

    /** A fresh level-1 beginner settled by the build, then leveled to {@code level} the observed way. */
    private Character beginnerAt(int id, int level) {
        Character bot = bot(id, 0, 1);
        assertTrue(BotProgression.buildNew(bot));
        bot.setLevel(level);
        return bot;
    }

    @Test
    void aWatchedBeginnerWalksToTheInstructorOfItsClassAndAdvancesThere() {
        Set<Integer> classes = new HashSet<>();
        for (int i = 0; i < 20 && classes.size() < 5; i++) {
            Character bot = beginnerAt(BASE_ID + i, 1);
            int target = BotBuild.nextJob(0, BotProgression.choiceFor(bot));
            if (!classes.add(target)) {
                BotProgression.forget(bot.getId());
                continue;
            }
            bot.setLevel(10);
            mover.travelled.clear();
            mover.walkedToNpc.clear();
            queued.clear();
            delays.clear();

            BotProgression.afterLevelUp(bot, 9);

            assertEquals(0, bot.getJob().getId(), "held at the beginner job while it walks");
            assertTrue(JobErrand.holds(bot.getId()));
            runStart();
            JobErrand.Instructor where = JobErrand.instructorFor(target);
            assertNotNull(where, "an instructor for " + target);
            assertEquals(List.of(where.mapId()), mover.travelled, "it travelled to the instructor's town");
            assertEquals(List.of(where.npcId()), mover.walkedToNpc, "and walked to his NPC");
            assertEquals(0, bot.getJob().getId(), "still not advanced before it arrives");

            mover.arrival.accept(true);

            assertEquals(target, bot.getJob().getId(), "advanced at the instructor");
            assertFalse(JobErrand.holds(bot.getId()));
            assertTrue(said.contains(bot.getName()), "said its celebration line");
        }
        assertEquals(5, classes.size(), "warrior, magician, bowman, thief and pirate all reached their instructor");
    }

    @Test
    void theSecondAdvancementIsAnErrandToo() {
        Character bot = bot(BASE_ID, 100, 1);
        assertTrue(BotProgression.buildNew(bot));
        bot.setLevel(30);

        BotProgression.afterLevelUp(bot, 29);

        assertEquals(100, bot.getJob().getId());
        runStart();
        assertEquals(List.of(102000003), mover.travelled);
        assertEquals(List.of(1022000), mover.walkedToNpc);
        mover.arrival.accept(true);
        assertTrue(bot.getJob().getId() >= 110 && bot.getJob().getId() < 140);
    }

    @Test
    void anUnreachableOrUnaffordableTownMeansTheInstantAdvance() {
        mover.reachable = false;   // no route, or the fare is more than the bot holds
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterLevelUp(bot, 9);

        assertNotEquals0(bot.getJob().getId());
        assertFalse(JobErrand.holds(bot.getId()));
        assertTrue(mover.travelled.isEmpty(), "no trip was started");
        assertTrue(queued.isEmpty());
    }

    @Test
    void aTripThatFailsFallsBackToTheInstantAdvance() {
        mover.tripSucceeds = false;   // the ferry never came
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterLevelUp(bot, 9);
        assertEquals(0, bot.getJob().getId());
        runStart();

        assertNotEquals0(bot.getJob().getId());
        assertFalse(JobErrand.holds(bot.getId()));
        assertEquals(List.of(bot.getId()), mover.cancelled, "the trip was cancelled");
        assertFalse(said.contains(bot.getName()), "no celebration for a fallback");
    }

    @Test
    void aStuckTripFallsBackWhenTheTimeoutRuns() {
        mover.holdTrip = true;   // the bot waits at a dock forever
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterLevelUp(bot, 9);
        runStart();
        assertEquals(0, bot.getJob().getId(), "still waiting");
        assertTrue(JobErrand.holds(bot.getId()));
        assertTrue(delays.stream().anyMatch(d -> d >= 1_000L), "a timeout was scheduled");

        runTimeout();

        assertNotEquals0(bot.getJob().getId());
        assertFalse(JobErrand.holds(bot.getId()));
        assertEquals(List.of(bot.getId()), mover.cancelled);
    }

    @Test
    void anInstructorNpcThatIsNotThereFallsBack() {
        mover.npcFound = false;
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterLevelUp(bot, 9);
        runStart();

        assertNotEquals0(bot.getJob().getId());
    }

    @Test
    void aLateArrivalAfterTheFallbackChangesNothing() {
        mover.holdTrip = false;
        Character bot = beginnerAt(BASE_ID, 10);
        BotProgression.afterLevelUp(bot, 9);
        runStart();
        Consumer<Boolean> arrival = mover.arrival;
        runTimeout();
        int job = bot.getJob().getId();

        arrival.accept(true);

        assertEquals(job, bot.getJob().getId());
        assertFalse(said.contains(bot.getName()));
    }

    @Test
    void anOffscreenBotAdvancesAtOnce() {
        watched = false;
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterOffscreenLevels(bot, 1);

        assertNotEquals0(bot.getJob().getId());
        assertTrue(mover.travelled.isEmpty());
        assertTrue(queued.isEmpty());
    }

    @Test
    void withTheSwitchOffNothingChanges() {
        on = false;
        Character bot = beginnerAt(BASE_ID, 10);

        BotProgression.afterLevelUp(bot, 9);

        assertNotEquals0(bot.getJob().getId());
        assertTrue(queued.isEmpty());
    }

    @Test
    void thirdAndFourthJobsAndOtherLinesKeepTheInstantAdvance() {
        assertNull(JobErrand.instructorFor(111));
        assertNull(JobErrand.instructorFor(112));
        assertNull(JobErrand.instructorFor(1100));
        assertNull(JobErrand.instructorFor(2100));
        assertNull(JobErrand.instructorFor(0));
        Character bot = bot(BASE_ID, 110, 1);
        assertTrue(BotProgression.buildNew(bot));
        bot.setLevel(70);

        BotProgression.afterLevelUp(bot, 69);

        assertEquals(111, bot.getJob().getId());
        assertTrue(queued.isEmpty());
    }

    @Test
    void levelsGainedWhileWalkingDoNotRestartOrBreakTheErrand() {
        Character bot = beginnerAt(BASE_ID, 10);
        BotProgression.afterLevelUp(bot, 9);
        int scheduled = queued.size();
        bot.setLevel(11);

        BotProgression.afterLevelUp(bot, 10);

        assertEquals(scheduled, queued.size(), "one errand, not two");
        assertEquals(0, bot.getJob().getId());
        runStart();
        mover.arrival.accept(true);
        assertTrue(bot.getJob().getId() > 0);
    }

    private static void assertNotEquals0(int job) {
        assertTrue(job > 0, "the bot advanced (job " + job + ")");
    }
}
