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
            assertTrue(primary >= .30 && primary <= .75, "v2 primary " + primary);
            profile.blend().values().forEach(v -> assertTrue(v >= LifeProfile.CHANNEL_FLOOR - 1e-12));
            assertTrue(profile.grindDurationMs() >= 20 * 60_000L && profile.grindDurationMs() <= 60 * 60_000L);
            assertTrue(profile.townDurationMs() >= 5 * 60_000L && profile.townDurationMs() <= 20 * 60_000L);
            // Durations are the v1 ones: the seed change does not move a bot's grind/town rhythm.
            assertEquals(LifeProfile.forBotV1(id).grindDurationMs(), profile.grindDurationMs());
            primaries.add(profile.primary());
            profiles.add(profile);
        }
        // v2 (1.6.0 S1): Bartle drives the five core channels; merchant and slacker are flavour, never the lead.
        assertTrue(primaries.containsAll(java.util.Set.of(LifeProfile.Archetype.SOCIALITE,
                LifeProfile.Archetype.GRINDER, LifeProfile.Archetype.EXPLORER)), primaries.toString());
        assertFalse(primaries.contains(LifeProfile.Archetype.MERCHANT));
        assertFalse(primaries.contains(LifeProfile.Archetype.SLACKER));
        assertEquals(3000, profiles.size());
    }

    @Test
    void theBartleLeadPicksTheMatchingChannel() {
        int checked = 0;
        for (int id = 1_000_005_000; id < 1_000_006_000; id++) {
            var social = SocialProfile.forCharacter(id);
            var primary = LifeProfile.forBot(id).primary();
            switch (social.leading()) {
                case SOCIALIZER -> assertEquals(LifeProfile.Archetype.SOCIALITE, primary);
                case EXPLORER -> assertEquals(LifeProfile.Archetype.EXPLORER, primary);
                case ACHIEVER, KILLER -> assertTrue(primary == LifeProfile.Archetype.GRINDER
                        || primary == LifeProfile.Archetype.SHOW_OFF, primary.name());
            }
            checked++;
        }
        assertEquals(1000, checked);
    }

    @Test
    void theV1SeedIsKeptForCarryingSprintDriftOver() {
        var primaries = new HashSet<LifeProfile.Archetype>();
        for (int id = 1_000_005_000; id < 1_000_008_000; id++) {
            LifeProfile v1 = LifeProfile.forBotV1(id);
            double primary = v1.blend().get(v1.primary());
            assertTrue(primary >= .55 && primary <= .70);
            primaries.add(v1.primary());
        }
        assertEquals(7, primaries.size());
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
