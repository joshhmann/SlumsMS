package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class LifeProfileGoalsTest {
    @Test
    void threeThousandBotsKeepStableVariedBlendsAndHumanDurations() {
        var primaries = new HashSet<LifeProfile.Archetype>();
        var profiles = new HashSet<LifeProfile>();
        for (int id = 1_000_005_000; id < 1_000_008_000; id++) {
            LifeProfile profile = LifeProfile.forBot(id);
            assertEquals(profile, LifeProfile.forBot(id));
            assertEquals(1, profile.blend().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
            double primary = profile.blend().get(profile.primary());
            assertTrue(primary >= .55 && primary <= .70);
            assertTrue(profile.grindDurationMs() >= 20 * 60_000L && profile.grindDurationMs() <= 60 * 60_000L);
            assertTrue(profile.townDurationMs() >= 5 * 60_000L && profile.townDurationMs() <= 20 * 60_000L);
            primaries.add(profile.primary());
            profiles.add(profile);
        }
        assertEquals(7, primaries.size());
        assertEquals(3000, profiles.size());
    }

    @Test
    void goalsFollowActualJobStageIncludingOverdueAdvancesAndKnownWeaponGap() {
        var goals = LifeGoals.seed(45, 110, OptionalInt.of(10));
        assertEquals(2, goals.size());
        assertEquals(LifeGoals.Kind.JOB_ADVANCE, goals.getFirst().kind());
        assertEquals(70, goals.getFirst().targetLevel());
        assertEquals(LifeGoals.Kind.WEAPON_UPGRADE, goals.get(1).kind());
        assertEquals(40, goals.get(1).targetLevel());
        var overdue = LifeGoals.seed(80, 100, OptionalInt.empty()).getFirst();
        assertEquals(30, overdue.targetLevel(), "do not pretend a first-job bot already advanced");
        assertEquals(1, overdue.pressure());
    }

    @Test
    void cygnusAndAranBeginnersDoNotGetMistakenForFirstJobClasses() {
        assertEquals(10, LifeGoals.seed(5, 1000, OptionalInt.empty()).getFirst().targetLevel());
        assertEquals(10, LifeGoals.seed(5, 2000, OptionalInt.empty()).getFirst().targetLevel());
        assertEquals(30, LifeGoals.seed(20, 2100, OptionalInt.empty()).getFirst().targetLevel());
        assertEquals(120, LifeGoals.seed(85, 1111, OptionalInt.empty()).getFirst().targetLevel());
    }

    @Test
    void unknownGearAndCustomJobBranchesDoNotInventUpgradeOrAdvanceFacts() {
        var goals = LifeGoals.seed(150, 113, OptionalInt.empty());
        assertEquals(1, goals.size());
        assertEquals(LifeGoals.Kind.LEVEL, goals.getFirst().kind());
        assertTrue(LifeGoals.seed(200, 112, OptionalInt.of(200)).isEmpty());
        assertTrue(LifeGoals.seed(120, 1112, OptionalInt.of(120)).isEmpty());
    }

    @Test
    void rejectsInvalidProfileAndGoalInputs() {
        assertThrows(IllegalArgumentException.class, () -> new LifeProfile(java.util.Map.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> LifeGoals.seed(0, 100, OptionalInt.empty()));
        assertThrows(IllegalArgumentException.class, () -> LifeGoals.seed(45, 110, OptionalInt.of(-1)));
        assertThrows(UnsupportedOperationException.class,
                () -> LifeProfile.forBot(1).blend().clear());
    }
}
