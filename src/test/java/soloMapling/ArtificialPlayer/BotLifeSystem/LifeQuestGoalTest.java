package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestLog;
import soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifePlanner.Step.*;

/** Ticket 158: a picked quest is a BotLife goal with a plan (take, hunt, turn in), saved with the life profile. */
class LifeQuestGoalTest {
    static final int QUEST = 1001;

    @BeforeEach
    void on() {
        System.setProperty("slums.botlife.enabled", "true");
        System.setProperty("slums.botlife.execute", "true");
        System.setProperty(BotQuests.PROP, "true");
    }

    @AfterEach
    void off() {
        System.clearProperty("slums.botlife.enabled");
        System.clearProperty("slums.botlife.execute");
        System.clearProperty(BotQuests.PROP);
    }

    static LifeGoals.Facts facts() {
        return new LifeGoals.Facts(15, 100, OptionalInt.empty(), 0, false, false, false, new SocialProfile(.8, .8));
    }

    static LifePlanner.World world(LifeGoals.Facts f, BotQuestLog log) {
        return new LifePlanner.World(f, false, false, false, Set.of(), BotQuests.facts(log));
    }

    static void pick(LifeMemory memory) {
        memory.quests(log -> {
            log.pick(new BotQuestLog.Active(QUEST, 1012100, 1012100, 100000000, 100000000, 100010000,
                    Map.of(100100, 10), 0));
            return null;
        });
    }

    @Test
    void aPickedQuestBecomesAThirdGoalWithItsOwnPlan() {
        var memory = new LifeMemory(1_000_000_777, null);
        var f = facts();
        memory.refresh(f, 1);
        int seeded = memory.goals().size();
        assertTrue(seeded >= 1 && seeded <= 2);
        pick(memory);
        assertTrue(memory.dirty(), "a pick is saved");
        memory.refresh(f, 2);
        var goals = memory.goals();
        assertEquals(seeded + 1, goals.size(), "the quest comes on top of the seeded goals");
        var quest = goals.getLast();
        assertEquals(LifeGoals.Kind.QUEST, quest.kind());
        assertEquals(QUEST, quest.targetLevel());
        assertTrue(quest.description().startsWith("finish the quest"), quest.description());

        BotQuestLog[] log = new BotQuestLog[1];
        memory.quests(l -> log[0] = l);
        memory.plan(world(f, log[0]));
        assertEquals(List.of(TAKE_QUEST, HUNT_QUEST, TURN_IN_QUEST), memory.plans().getLast().steps());
        assertEquals(LifeDecision.Activity.HANG_OUT, TAKE_QUEST.activity);
        assertEquals(LifeDecision.Activity.GRIND, HUNT_QUEST.activity);

        memory.quests(l -> { l.taken(BotQuestLog.Mode.MODELLED); return null; });
        memory.plan(world(f, log[0]));
        assertEquals(List.of(HUNT_QUEST, TURN_IN_QUEST), memory.plans().getLast().steps(), "re-planned on the fact");
        memory.quests(l -> { l.addKills(100100, 10); return null; });
        memory.plan(world(f, log[0]));
        assertEquals(List.of(TURN_IN_QUEST), memory.plans().getLast().steps());

        memory.quests(l -> { l.complete(false, 3); return null; });
        memory.refresh(f, 4);
        assertEquals(seeded, memory.goals().size(), "done: the quest goal drops out");
        assertTrue(memory.goals().stream().noneMatch(g -> g.kind() == LifeGoals.Kind.QUEST));
    }

    @Test
    void theQuestAndItsGoalSurviveASave() {
        var memory = new LifeMemory(1_000_000_778, null);
        var f = facts();
        memory.refresh(f, 1);
        pick(memory);
        memory.refresh(f, 2);
        BotQuestLog[] log = new BotQuestLog[1];
        memory.quests(l -> log[0] = l);
        memory.plan(world(f, log[0]));
        var loaded = new LifeMemory(1_000_000_778, Json.parse(Json.write(memory.save(5))));
        assertEquals(memory.goals().stream().map(LifeGoals.Goal::kind).toList(),
                loaded.goals().stream().map(LifeGoals.Goal::kind).toList());
        assertEquals(memory.plans().size(), loaded.plans().size(), "the quest plan lines up after a load");
        assertEquals(QUEST, (int) loaded.quests(l -> l.active().questId));
    }

    @Test
    void switchedOffAStoredQuestIsNoGoal() {
        var memory = new LifeMemory(1_000_000_780, null);
        pick(memory);
        System.clearProperty(BotQuests.PROP);
        memory.refresh(facts(), 1);
        assertTrue(memory.goals().stream().noneMatch(g -> g.kind() == LifeGoals.Kind.QUEST));
        assertNotNull(memory.quests(BotQuestLog::active), "the log is kept for when it is switched on again");
    }

    @Test
    void noQuestNoKeyInTheProfile() {
        var memory = new LifeMemory(1_000_000_779, null);
        memory.refresh(facts(), 1);
        assertFalse(memory.save(2).containsKey("quests"), "a bot that never quested stores nothing extra");
    }
}
