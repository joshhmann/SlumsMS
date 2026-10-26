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

    @Test void aSprintV1BlendIsReadOnceAsSeedPlusBoundedDelta() {
        int id = 1000005001;
        var v1Seed = LifeProfile.forBotV1(id).blend();
        Map<String, Object> blend = new LinkedHashMap<>();
        v1Seed.forEach((type, value) -> blend.put(type.name(),
                value * .99 + (type == LifeProfile.Archetype.HELPER ? .01 : 0)));
        var v1 = Map.of("version", 1, "blend", blend, "goals", List.of(), "lastJob", 110,
                "lastSocial", 7L, "driftDay", 3L, "changesToday", 2, "events", Map.of("GUILD_WAR", 99L));
        var memory = new LifeMemory(id, soloMapling.server.Json.parse(soloMapling.server.Json.write(v1)));
        assertTrue(memory.restored());
        assertTrue(memory.migrated(), "needs its first v2 write");
        assertTrue(memory.dirty());
        assertEquals(LifeProfile.forBot(id), memory.seed(), "the seed is the v2 Bartle seed");
        assertEquals(SocialProfile.forCharacter(id), memory.socialSeed());
        for (var type : LifeProfile.Archetype.values()) {
            double drift = v1Seed.get(type) * .99 + (type == LifeProfile.Archetype.HELPER ? .01 : 0) - v1Seed.get(type);
            assertEquals(drift, memory.delta().getOrDefault(type, 0.0), 1e-12, type.name());
        }
        assertTrue(memory.profile().blend().get(LifeProfile.Archetype.HELPER)
                > memory.seed().blend().get(LifeProfile.Archetype.HELPER));
        assertEquals(7L, memory.lastSocial());
        assertFalse(memory.event(LifeMemory.Event.GUILD_WAR, 100), "v1 event times are kept");

        var save = memory.takeDirty(1_000);
        assertEquals(2, save.get("version"));
        assertFalse(memory.dirty());
        assertFalse(memory.migrated());
        assertNull(memory.takeDirty(2_000), "nothing changed since");
        var v2 = new LifeMemory(id, soloMapling.server.Json.parse(soloMapling.server.Json.write(save)));
        assertFalse(v2.migrated());
        assertEquals(memory.profile(), v2.profile());
        assertEquals(memory.delta(), v2.delta());
        assertEquals(1_000, v2.savedAt());
    }

    @Test void seedAndDeltaAreStoredApartAndTheDeltaStaysBounded() {
        int id = 1000005002;
        var memory = new LifeMemory(id, null);
        assertFalse(memory.restored());
        assertTrue(memory.delta().isEmpty());
        long now = 0;
        for (int day = 0; day < 400; day++) for (int hour = 0; hour < 4; hour++) {
            memory.event(LifeMemory.Event.BOOM, now = day * 86_400_000L + hour * 3_600_000L);
        }
        assertEquals(LifeMemory.MAX_CHANNEL_DELTA, memory.delta().get(LifeProfile.Archetype.SLACKER), 1e-12,
                "1,600 nudges stop at the bound");
        memory.delta().values().forEach(d -> assertTrue(Math.abs(d) <= LifeMemory.MAX_CHANNEL_DELTA + 1e-12));
        assertEquals(LifeProfile.forBot(id), memory.seed(), "the seed never moves");
        assertEquals(SocialProfile.forCharacter(id), memory.socialSeed(), "nor the seed point");
        double[] drift = memory.drift();
        assertTrue(Math.hypot(drift[0], drift[1]) <= memory.plasticity() * SocialProfile.RADIUS_PER_PLASTICITY + 1e-12);
        assertTrue(drift[1] > 0 && drift[0] < 0, "BOOM pushes toward Killer/acting");
        @SuppressWarnings("unchecked")
        var seed = (Map<String, Map<String, Double>>) memory.save(now).get("seed");
        @SuppressWarnings("unchecked")
        var delta = (Map<String, Map<String, Double>>) memory.save(now).get("delta");
        assertEquals(LifeProfile.forBot(id).blend().get(LifeProfile.Archetype.SLACKER), seed.get("blend").get("SLACKER"));
        assertEquals(memory.socialSeed().x(), seed.get("bartle").get("x"));
        assertEquals(memory.plasticity(), seed.get("bartle").get("plasticity"));
        assertEquals(memory.pullBack(), seed.get("bartle").get("pullBack"));
        assertEquals(drift[0], delta.get("bartle").get("dx"));
        assertEquals(LifeMemory.MAX_CHANNEL_DELTA, delta.get("blend").get("SLACKER"), 1e-12);
    }

    @Test void pointPlasticityAndDriftSurviveAJsonRoundTripAndAWildDriftIsReCapped() {
        int id = 1000005003;
        var memory = new LifeMemory(id, null);
        memory.event(LifeMemory.Event.MARRIAGE, 10_000_000);
        memory.push(SocialProfile.Push.PARTY);
        var loaded = new LifeMemory(id, soloMapling.server.Json.parse(soloMapling.server.Json.write(memory.save(5))));
        assertEquals(memory.socialSeed(), loaded.socialSeed());
        assertEquals(memory.social(), loaded.social());
        assertEquals(memory.plasticity(), loaded.plasticity());
        assertEquals(memory.pullBack(), loaded.pullBack());
        assertArrayEquals(memory.drift(), loaded.drift());

        Map<String, Object> edited = new LinkedHashMap<>(memory.save(5));
        @SuppressWarnings("unchecked")
        var seed = (Map<String, Object>) edited.get("seed");
        Map<String, Double> point = new LinkedHashMap<>();
        point.put("x", -.9); point.put("y", .9); point.put("plasticity", .1); point.put("pullBack", .05);
        edited.put("seed", Map.of("bartle", point, "blend", seed.get("blend")));
        edited.put("delta", Map.of("bartle", Map.of("dx", 5.0, "dy", -5.0), "blend", Map.of("GRINDER", -9.0)));
        var wild = new LifeMemory(id, soloMapling.server.Json.parse(soloMapling.server.Json.write(edited)));
        assertEquals(SocialProfile.Type.KILLER, wild.socialSeed().leading(), "a stored seed wins over today's formula");
        assertEquals(.1, wild.plasticity());
        assertEquals(.1 * SocialProfile.RADIUS_PER_PLASTICITY, Math.hypot(wild.drift()[0], wild.drift()[1]), 1e-9);
        assertEquals(SocialProfile.Type.KILLER, wild.social().leading(), "a low-plasticity bot stays in its corner");
        assertEquals(-LifeMemory.MAX_CHANNEL_DELTA, wild.delta().get(LifeProfile.Archetype.GRINDER), 1e-12);
    }
}
