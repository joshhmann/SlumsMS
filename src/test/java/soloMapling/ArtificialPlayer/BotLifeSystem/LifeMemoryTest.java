package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LifeMemoryTest {
    @Test void savedGoalsAndBlendsSurviveTheExistingJsonFormat() {
        var memory = new LifeMemory(1000005001, null);
        memory.refresh(45, 110, OptionalInt.of(10), 10_000_000);
        memory.event(LifeMemory.Event.MARRIAGE, 10_000_000);
        memory.talked(10_000_000);
        Object json = soloMapling.server.Json.parse(soloMapling.server.Json.write(memory.save()));
        var loaded = new LifeMemory(1000005001, json);
        assertEquals(memory.profile(), loaded.profile());
        assertEquals(memory.goals(), loaded.goals());
        assertEquals(memory.lastSocial(), loaded.lastSocial());
        assertFalse(loaded.event(LifeMemory.Event.MARRIAGE, 10_000_001));
    }
    @Test void unfinishedGearGoalsDoNotMoveEveryTimeTheBotLevels() {
        var memory = new LifeMemory(3, null);
        memory.refresh(45, 110, OptionalInt.of(10), 10_000_000);
        memory.refresh(55, 110, OptionalInt.of(10), 10_000_001);
        assertEquals(40, memory.goals().stream().filter(g -> g.kind() == LifeGoals.Kind.WEAPON_UPGRADE)
                .findFirst().orElseThrow().targetLevel());
        memory.refresh(70, 111, OptionalInt.of(40), 10_000_002);
        assertTrue(memory.goals().stream().anyMatch(g -> g.kind() == LifeGoals.Kind.JOB_ADVANCE && g.targetLevel() == 120));
        assertFalse(memory.goals().stream().anyMatch(g -> g.kind() == LifeGoals.Kind.JOB_ADVANCE && g.targetLevel() == 70));
    }
    @Test void driftIsSmallDeduplicatedAndBoundedPerDayEvenAcrossReloads() {
        var memory = new LifeMemory(7, null);
        var before = memory.profile().blend();
        long now = 10_000_000;
        assertTrue(memory.event(LifeMemory.Event.BIG_DROP, now));
        assertFalse(memory.event(LifeMemory.Event.BIG_DROP, now + 1));
        for (int i = 1; i < 4; i++) assertTrue(memory.event(LifeMemory.Event.BIG_DROP, now + i * 3_600_000L));
        assertFalse(memory.event(LifeMemory.Event.BOOM, now + 4 * 3_600_000L));
        double moved = before.entrySet().stream().mapToDouble(e -> Math.abs(e.getValue() - memory.profile().blend().get(e.getKey()))).sum();
        assertTrue(moved <= .04);
        var loaded = new LifeMemory(7, memory.save());
        assertFalse(loaded.event(LifeMemory.Event.JOB_ADVANCE, now + 4 * 3_600_000L));
        assertTrue(loaded.event(LifeMemory.Event.JOB_ADVANCE, 86_400_000));
    }
    @Test void corruptSavedDataFallsBackWithoutBreakingLogin() {
        var memory = new LifeMemory(11, Map.of("version", 1, "blend", Map.of("GRINDER", Double.NaN)));
        assertEquals(LifeProfile.forBot(11), memory.profile());
        assertTrue(memory.goals().isEmpty());
    }
    @Test void advancingToFirstJobCompletesTheBeginnerGoalEvenForEarlyMagicians() {
        var memory = new LifeMemory(11, null);
        memory.refresh(5, 0, OptionalInt.empty(), 100);
        memory.refresh(8, 200, OptionalInt.empty(), 200);
        assertEquals(30, memory.goals().getFirst().targetLevel());
    }
}
