package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 162 part 2: a gear drop worth chasing becomes a FARM_ITEM aspiration; without a want nothing changes.
class LifeGoalsFarmTest {

    private static final SocialProfile ACHIEVER = new SocialProfile(.8, .8);

    private static LifeGoals.Facts facts(LifeGoals.FarmWant want) {
        return new LifeGoals.Facts(45, 110, OptionalInt.empty(), 50_000_000L, true, true, true, ACHIEVER, want);
    }

    @Test
    void noWantNoFarmGoal() {
        assertTrue(LifeGoals.seed(facts(null)).stream().noneMatch(g -> g.kind() == LifeGoals.Kind.FARM_ITEM));
    }

    @Test
    void aStrongWantSeedsAFarmGoalUntilItIsGone() {
        LifeGoals.FarmWant want = new LifeGoals.FarmWant(1302010, "Sword", 0.3);
        List<LifeGoals.Goal> goals = LifeGoals.seed(facts(want));
        LifeGoals.Goal farm = goals.stream().filter(g -> g.kind() == LifeGoals.Kind.FARM_ITEM).findFirst().orElseThrow();
        assertEquals(1302010, farm.itemId());
        assertTrue(farm.kind().progression());
        assertFalse(LifeGoals.met(farm, facts(want)));
        assertTrue(LifeGoals.met(farm, facts(null)), "the want is gone: got it or outgrew it");
        assertEquals(LifePlanner.Fact.GEAR_FARMED, LifePlanner.goalFact(LifeGoals.Kind.FARM_ITEM));
        LifePlanner.Plan plan = LifePlanner.plan(farm, new LifePlanner.World(facts(want), false, false, false, null));
        assertEquals(LifePlanner.Step.FARM_GEAR, plan.next());
    }

    @Test
    void theFarmGoalKeepsItsItemThroughSaveAndLoad() {
        LifeMemory memory = new LifeMemory(1_000_000_123, null);
        memory.refresh(facts(new LifeGoals.FarmWant(1302010, "Sword", 0.3)), 1000L);
        assertTrue(memory.goals().stream().anyMatch(g -> g.itemId() == 1302010));
        LifeMemory back = new LifeMemory(1_000_000_123,
                soloMapling.server.Json.parse(soloMapling.server.Json.write(memory.save())));
        assertEquals(memory.goals(), back.goals());
        // a goal kind an older build does not know is skipped on load (rollback), the rest of the memory loads
        assertNotEquals(0, back.goals().stream().mapToInt(LifeGoals.Goal::itemId).max().orElse(0));
    }
}
