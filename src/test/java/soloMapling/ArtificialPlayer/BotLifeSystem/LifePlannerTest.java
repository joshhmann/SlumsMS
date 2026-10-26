package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner.Step.*;

/** 1.6.0 S8: GOAP plans for staged facts, re-planning only on a change, and the plan saved with the profile. */
class LifePlannerTest {
    static final SocialProfile SOCIALIZER = new SocialProfile(-.8, -.8), ACHIEVER = new SocialProfile(.8, .8),
            EXPLORER = new SocialProfile(.8, -.8), KILLER = new SocialProfile(-.8, .8);

    static LifeGoals.Facts facts(int level, int job, long meso, boolean guild, boolean married, boolean zhelm,
                                 SocialProfile social) {
        return new LifeGoals.Facts(level, job, OptionalInt.empty(), meso, guild, married, zhelm, social);
    }

    static LifePlanner.World world(LifeGoals.Facts facts, boolean friend, boolean crush, boolean ring,
                                   Set<LifePlanner.Fact> soft) {
        return new LifePlanner.World(facts, friend, crush, ring, soft);
    }

    @Test
    void fourthJobIsGrindThenTravelThenTheInstructor() {
        var f = facts(112, 111, 0, false, false, false, ACHIEVER);
        var goal = LifeGoals.seed(f).getFirst();
        assertEquals(LifeGoals.Kind.FOURTH_JOB, goal.kind());
        assertEquals(120, goal.targetLevel());
        var plan = LifePlanner.plan(goal, world(f, false, false, false, Set.of()));
        assertEquals(List.of(GRIND_TO_LEVEL, TRAVEL_TO_INSTRUCTOR, TALK_TO_INSTRUCTOR), plan.steps());
        assertEquals(LifeDecision.Activity.GRIND, plan.next().activity);
        assertTrue(plan.describe().contains("grind to level 120 -> travel to the job instructor's town"), plan.describe());
        // At 120 without the advance (bots normally advance on the level-up), the trip is next, to Leafre.
        var ready = facts(120, 111, 0, false, false, false, ACHIEVER);
        var trip = LifePlanner.plan(goal, world(ready, false, false, false, Set.of()));
        assertEquals(List.of(TRAVEL_TO_INSTRUCTOR, TALK_TO_INSTRUCTOR), trip.steps());
        assertEquals(240000000, LifePlanner.destination(TRAVEL_TO_INSTRUCTOR, ready, 120));
        // Arrived (a soft fact the finished trip set): talk to the instructor.
        var there = LifePlanner.plan(goal, world(ready, false, false, false, Set.of(LifePlanner.Fact.AT_INSTRUCTOR)));
        assertEquals(List.of(TALK_TO_INSTRUCTOR), there.steps());
        // Advanced: the goal is met and nothing is left.
        var done = facts(120, 112, 0, false, false, false, ACHIEVER);
        assertTrue(LifeGoals.met(goal, done));
        assertTrue(LifePlanner.plan(goal, world(done, false, false, false, Set.of())).steps().isEmpty());
    }

    @Test
    void marriageIsFriendThenCrushThenRingThenWedding() {
        var f = facts(40, 210, 0, false, false, false, SOCIALIZER);
        var goal = LifeGoals.seed(f).get(1);
        assertEquals(LifeGoals.Kind.MARRIAGE, goal.kind(), "a socializer's aspiration");
        assertEquals(List.of(MAKE_FRIENDS, FIND_A_CRUSH, GET_A_RING, WEDDING),
                LifePlanner.plan(goal, world(f, false, false, false, Set.of())).steps());
        assertEquals(List.of(FIND_A_CRUSH, GET_A_RING, WEDDING),
                LifePlanner.plan(goal, world(f, true, false, false, Set.of())).steps());
        assertEquals(List.of(GET_A_RING, WEDDING), LifePlanner.plan(goal, world(f, true, true, false, Set.of())).steps());
        assertEquals(680000000, LifePlanner.destination(WEDDING, f, goal.targetLevel()));
        var married = facts(40, 210, 0, false, true, false, SOCIALIZER);
        assertTrue(LifePlanner.plan(goal, world(married, true, true, true, Set.of())).steps().isEmpty());
    }

    @Test
    void zhelmGuildAndWealthPlans() {
        var a = facts(45, 110, 0, true, false, false, ACHIEVER);
        var zhelm = LifeGoals.seed(a).get(1);
        assertEquals(LifeGoals.Kind.ZHELM, zhelm.kind(), "an achiever's aspiration");
        assertEquals(List.of(GRIND_TO_50, ZAKUM_PREQUEST, ZAKUM_RUN),
                LifePlanner.plan(zhelm, world(a, false, false, false, Set.of())).steps());
        assertEquals(LifeDecision.Activity.PARTY_QUEST, ZAKUM_RUN.activity);
        var k = facts(30, 410, 0, false, false, false, KILLER);
        var guild = LifeGoals.seed(k).get(1);
        assertEquals(LifeGoals.Kind.GUILD, guild.kind(), "a killer wants a guild first");
        assertEquals(List.of(MEET_A_GUILD, JOIN_A_GUILD), LifePlanner.plan(guild, world(k, false, false, false, Set.of())).steps());
        var e = facts(35, 310, 1_000_000, true, false, false, EXPLORER);
        var wealth = LifeGoals.seed(e).get(1);
        assertEquals(LifeGoals.Kind.WEALTH, wealth.kind(), "an explorer wants to get rich");
        assertEquals(List.of(FARM_LOOT, SELL_LOOT), LifePlanner.plan(wealth, world(e, false, false, false, Set.of())).steps());
        assertEquals(List.of(SELL_LOOT), LifePlanner.plan(wealth, world(e, false, false, false,
                Set.of(LifePlanner.Fact.LOOT))).steps());
    }

    @Test
    void theMemoryReplansOnlyWhenAGoalOrAFactChanges() {
        int id = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 777_001;
        var memory = new LifeMemory(id, null);
        var f = facts(112, 111, 0, false, false, false, ACHIEVER);
        memory.refresh(f, 1_000);
        long before = LifePlanner.replans();
        assertTrue(memory.plan(world(f, false, false, false, memory.softFacts())));
        long first = LifePlanner.replans();
        assertTrue(first > before);
        for (int tick = 0; tick < 50; tick++) {
            memory.refresh(f, 2_000 + tick);
            assertFalse(memory.plan(world(f, false, false, false, memory.softFacts())));
        }
        assertEquals(first, LifePlanner.replans(), "same goals and facts: no search");
        // A fact changes (a level that makes the grind step done): one re-plan.
        var ready = facts(120, 111, 0, false, false, false, ACHIEVER);
        memory.refresh(ready, 9_000);
        assertTrue(memory.plan(world(ready, false, false, false, memory.softFacts())));
        assertTrue(LifePlanner.replans() > first);
        assertEquals(TRAVEL_TO_INSTRUCTOR, memory.plans().getFirst().next());
    }

    @Test
    void thePlanAndSoftFactsAreSavedWithTheProfileNotTheActivityState() {
        int id = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 777_002;
        var memory = new LifeMemory(id, null);
        var f = facts(45, 110, 0, true, false, false, ACHIEVER);
        memory.refresh(f, 1_000);
        memory.plan(world(f, false, false, false, Set.of()));
        assertTrue(memory.soften(LifePlanner.Fact.ZAKUM_READY));
        assertFalse(memory.soften(LifePlanner.Fact.MARRIED), "only soft facts can be set by an outcome");
        memory.plan(world(f, false, false, false, memory.softFacts()));
        Map<String, Object> saved = memory.save(5_000);
        assertTrue(saved.containsKey("plans"));
        var loaded = new LifeMemory(id, soloMapling.server.Json.parse(soloMapling.server.Json.write(saved)));
        assertEquals(memory.goals(), loaded.goals());
        assertEquals(memory.plans(), loaded.plans());
        assertEquals(Set.of(LifePlanner.Fact.ZAKUM_READY), loaded.softFacts());
        assertEquals(List.of(ZAKUM_RUN), loaded.plans().get(1).steps());
        // A pre-S8 save (no plans) still loads.
        saved.remove("plans");
        saved.remove("soft");
        var old = new LifeMemory(id, saved);
        assertTrue(old.restored());
        assertTrue(old.plans().isEmpty());
    }

    @Test
    void theNextStepIsTheMostPressingGoalsAndCarriesItsBonus() {
        var f = facts(45, 110, 0, true, false, false, ACHIEVER);
        var goals = LifeGoals.seed(f);
        var w = world(f, false, false, false, Set.of());
        var plans = goals.stream().map(g -> LifePlanner.plan(g, w)).toList();
        var next = LifePlanner.next(goals, plans);
        assertNotNull(next);
        assertTrue(next.bonus() > 0 && next.bonus() <= LifePlanner.STEP_BONUS);
        assertNull(LifePlanner.next(List.of(), List.of()));
    }

    @Test
    void noAspirationWithoutAPointOrBelowItsLevel() {
        assertEquals(LifeGoals.seed(45, 110, OptionalInt.empty()),
                LifeGoals.seed(LifeGoals.Facts.basic(45, 110, OptionalInt.empty())));
        assertTrue(LifeGoals.seed(facts(12, 100, 0, false, false, false, SOCIALIZER)).stream()
                .noneMatch(g -> g.kind() == LifeGoals.Kind.MARRIAGE), "too young to marry");
    }
}
