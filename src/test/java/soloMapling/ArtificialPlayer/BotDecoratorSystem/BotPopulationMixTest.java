package soloMapling.ArtificialPlayer.BotDecoratorSystem;

import client.Job;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotSkillPlan;
import soloMapling.ArtificialPlayer.BotTier;
import soloMapling.Environment.EnvironmentManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.6.0 population (player-likeness audit, gap 6): the roster rolled a "for demo" mix - 30% S tier, 16% of the
 * roster under Lv30, ~35 Cygnus/Aran out of 9,010, exactly 50/50 gender. A 2009 GMS server looked nothing like it,
 * so the mix now lives in BotPopulationMix: bottom-heavy levels, every class, ~65% male. These tests pin the shares
 * the audit asked for, so a nudge to a weight that turns the population back into a demo fails the build. */
class BotPopulationMixTest {

    private static final int ROLLS = 20_000;

    // rc6 (QA t-rm-2): staging asked for a lv 60 partner and got lv 27, 48, 78 - a level range has to hold.
    @Test
    void oneLevelIsExactlyThatLevel() {
        assertEquals(60, BotPopulationMix.rollLevel(60, 60));
    }

    @Test
    void aRangeStaysInsideIt() {
        for (int i = 0; i < 200; i++) {
            int level = BotPopulationMix.rollLevel(4, 16);
            assertTrue(level >= 4 && level <= 16, "gave " + level);
        }
        for (int i = 0; i < 200; i++) {
            int level = BotPopulationMix.rollLevel(10, 30);
            assertTrue(level >= 10 && level <= 30, "gave " + level);
        }
    }

    @Test
    void anInvertedRangeIsItsLowEnd() {
        assertEquals(45, BotPopulationMix.rollLevel(45, 20));
    }

    /** Every level in a band can happen: a Victoria cohort must not be all 10s or all 29s. */
    @Test
    void aBandReachesBothEnds() {
        boolean sawLow = false;
        boolean sawHigh = false;
        for (int i = 0; i < ROLLS; i++) {
            int level = BotPopulationMix.rollLevel(10, 30);
            sawLow |= level <= 12;
            sawHigh |= level >= 28;
        }
        assertTrue(sawLow && sawHigh);
    }

    /** The curve on its own: about half of everything is under Lv30, and 71+ is a real but small share. */
    @Test
    void theLevelCurveIsBottomHeavy() {
        double underThirty = BotPopulationMix.shareUpTo(29);
        double upToSeventy = BotPopulationMix.shareUpTo(70);
        assertEquals(100.0, BotPopulationMix.shareUpTo(200), 0.001);
        assertTrue(underThirty >= 44 && underThirty <= 52, "under Lv30: " + underThirty);
        assertTrue(upToSeventy - underThirty >= 28 && upToSeventy - underThirty <= 38,
                "Lv30-70: " + (upToSeventy - underThirty));
        assertTrue(100 - upToSeventy >= 12 && 100 - upToSeventy <= 22, "Lv71+: " + (100 - upToSeventy));
    }

    /**
     * The population as it is actually spawned: every training cohort drawn at its own band, then the shares over
     * all of them. This is the audit's scorecard (share under Lv30), against the real cohort bands - a band edit
     * that flattens the bottom of the population fails here.
     */
    @Test
    void theSpawnedPopulationMatchesTheAudit() {
        int total = 0;
        int underThirty = 0;
        int mid = 0;
        int high = 0;
        List<EnvironmentManager.TrainingCohort> cohorts = EnvironmentManager.TRAINING_COHORTS;
        for (int round = 0; round < 4; round++) {
            for (EnvironmentManager.TrainingCohort cohort : cohorts) {
                for (int k = 0; k < cohort.count(); k++) {
                    int level = BotPopulationMix.rollLevel(cohort.loLevel(), cohort.hiLevel());
                    assertTrue(level >= cohort.loLevel() && level <= cohort.hiLevel(),
                            cohort.townMapId() + " gave " + level);
                    total++;
                    if (level < 30) {
                        underThirty++;
                    } else if (level <= 70) {
                        mid++;
                    } else {
                        high++;
                    }
                }
            }
        }
        double underPct = 100.0 * underThirty / total;
        double midPct = 100.0 * mid / total;
        double highPct = 100.0 * high / total;
        // 1.6.0 target bands: 40-50% under Lv30, ~45% at 31-70, 8-20% at 71+ (was 12-20; Ludi/El Nath were re-banded to their maps 2026-10-26, which trims the 71+ share to about 10%).
        // The 31-70 band was 34-45 with its target at the top edge, so an unseeded roll failed it now and then (45.04%).
        assertTrue(underPct >= 40 && underPct <= 50, "under Lv30: " + underPct + "%");
        assertTrue(midPct >= 38 && midPct <= 52, "Lv31-70: " + midPct + "%");
        assertTrue(highPct >= 8 && highPct <= 20, "Lv71+: " + highPct + "%");
    }

    /**
     * 1.6.0 area levels (owner 2026-10-26): with Victoria's dungeons given their own Lv30+ cohorts, the population new
     * veterans are drawn from (every cohort weighted by its count, each level by the curve inside its band - the
     * exact expectation, no sampling) is still bottom-heavy: 40%+ under Lv30 overall.
     */
    @Test
    void theAreaBandsKeepThePopulationBottomHeavy() {
        double[] shares = expectedShares(EnvironmentManager.TRAINING_COHORTS);
        assertTrue(shares[0] >= 40, "under Lv30: " + shares[0] + "%");
        assertTrue(shares[2] >= 8 && shares[2] <= 20, "Lv71+: " + shares[2] + "%");
    }

    /** Each Victoria town is home to its fields' low bots and, but for Lith Harbour, to bots for its Lv30+ zones. */
    @Test
    void everyVictoriaTownHasItsHigherZonesToo() {
        for (int town : List.of(constants.id.MapId.HENESYS, constants.id.MapId.KERNING_CITY, constants.id.MapId.PERION,
                constants.id.MapId.ELLINIA)) {
            boolean low = false;
            boolean high = false;
            for (EnvironmentManager.TrainingCohort cohort : EnvironmentManager.TRAINING_COHORTS) {
                if (cohort.townMapId() == town) {
                    low |= cohort.loLevel() >= 10 && cohort.hiLevel() <= 30;
                    high |= cohort.loLevel() >= 30;
                }
            }
            assertTrue(low && high, "town " + town + " low " + low + " high " + high);
        }
        // the town cohorts themselves stay "30ish": no Victoria town field cohort runs past Lv30
        for (int index : new int[]{EnvironmentManager.COHORT_HENESYS, EnvironmentManager.COHORT_KERNING,
                EnvironmentManager.COHORT_PERION, EnvironmentManager.COHORT_ELLINIA, EnvironmentManager.COHORT_SLEEPYWOOD}) {
            assertTrue(EnvironmentManager.TRAINING_COHORTS.get(index).hiLevel() <= 30, "cohort " + index);
        }
    }

    /**
     * A cohort's position is its stored bots' slots (wave 8 task index): the rows that existed before the area bands
     * keep their town and headcount, so no stored bot changes cohort or is retired. New rows only ever go on the end.
     */
    @Test
    void theExistingCohortsKeepTheirSlots() {
        int[][] before = {
                {constants.id.MapId.LITH_HARBOUR, 20}, {constants.id.MapId.HENESYS, 225},
                {constants.id.MapId.KERNING_CITY, 225}, {constants.id.MapId.PERION, 225},
                {constants.id.MapId.ELLINIA, 225}, {constants.id.MapId.SLEEPYWOOD, 225},
                {constants.id.MapId.ANT_TUNNEL_PARK, 180}, {constants.id.MapId.ORBIS, 220},
                {constants.id.MapId.LUDIBRIUM, 200}, {constants.id.MapId.PATH_OF_TIME_HUB, 160},
                {constants.id.MapId.EL_NATH, 200}, {constants.id.MapId.SHARP_CLIFF_I, 200},
                {constants.id.MapId.HENESYS, 25}, {constants.id.MapId.KERNING_CITY, 20},
                {constants.id.MapId.PERION, 20}, {constants.id.MapId.ELLINIA, 20}};
        for (int i = 0; i < before.length; i++) {
            EnvironmentManager.TrainingCohort cohort = EnvironmentManager.TRAINING_COHORTS.get(i);
            assertEquals(before[i][0], cohort.townMapId(), "cohort " + i + " town");
            assertEquals(before[i][1], cohort.count(), "cohort " + i + " count");
        }
        for (EnvironmentManager.TrainingCohort cohort : EnvironmentManager.TRAINING_COHORTS) {
            assertTrue(cohort.count() <= soloMapling.ArtificialPlayer.BotStore.SLOTS_PER_TASK, "fits its task: " + cohort);
        }
    }

    /** Expected shares (percent) under Lv30, Lv30-70 and Lv71+ of a population drawn from these cohorts. */
    private static double[] expectedShares(List<EnvironmentManager.TrainingCohort> cohorts) {
        double total = 0;
        double under = 0;
        double mid = 0;
        double high = 0;
        for (EnvironmentManager.TrainingCohort cohort : cohorts) {
            double bandWeight = 0;
            for (int lv = cohort.loLevel(); lv <= cohort.hiLevel(); lv++) {
                bandWeight += BotPopulationMix.weightOf(lv);
            }
            for (int lv = cohort.loLevel(); lv <= cohort.hiLevel(); lv++) {
                double bots = cohort.count() * BotPopulationMix.weightOf(lv) / bandWeight;
                total += bots;
                if (lv < 30) {
                    under += bots;
                } else if (lv <= 70) {
                    mid += bots;
                } else {
                    high += bots;
                }
            }
        }
        return new double[]{100 * under / total, 100 * mid / total, 100 * high / total};
    }

    /** Every class is present at a believable share; pirates, Cygnus Knights and Aran are no longer curiosities. */
    @Test
    void classSharesMatchTheAuditTargets() {
        java.util.Map<Integer, Integer> byClass = new java.util.HashMap<>();
        for (int i = 0; i < ROLLS; i++) {
            byClass.merge(BotPopulationMix.rollBaseClass(), 1, Integer::sum);
        }
        int cygnus = byClass.getOrDefault(BotPopulationMix.CYGNUS, 0);
        int aran = byClass.getOrDefault(BotPopulationMix.ARAN, 0);
        int explorers = ROLLS - cygnus - aran;
        double cygnusPct = 100.0 * cygnus / ROLLS;
        double aranPct = 100.0 * aran / ROLLS;
        assertTrue(cygnusPct >= 8 && cygnusPct <= 12, "Cygnus: " + cygnusPct + "%");
        assertTrue(aranPct >= 3 && aranPct <= 7, "Aran: " + aranPct + "%");

        int[] expected = {22, 20, 20, 22, 16};
        for (int i = 0; i < expected.length; i++) {
            double pct = 100.0 * byClass.getOrDefault(i + BotPopulationMix.WARRIOR, 0) / explorers;
            assertEquals(expected[i], pct, 3.0, "explorer class " + (i + 1));
        }
        assertTrue(100.0 * byClass.getOrDefault(BotPopulationMix.PIRATE, 0) / explorers >= 12,
                "pirates of the explorers");
    }

    @Test
    void genderIsAboutTwoThirdsMale() {
        int male = 0;
        for (int i = 0; i < ROLLS; i++) {
            int gender = BotPopulationMix.rollGender();
            assertTrue(gender == 0 || gender == 1);
            male += gender == 0 ? 1 : 0;
        }
        double malePct = 100.0 * male / ROLLS;
        assertTrue(malePct >= 62 && malePct <= 68, "male: " + malePct + "%");
    }

    @Test
    void tierIsMostlyOrdinary() {
        Map<BotTier, Integer> counts = new EnumMap<>(BotTier.class);
        for (int i = 0; i < ROLLS; i++) {
            counts.merge(BotPopulationMix.rollTier(), 1, Integer::sum);
        }
        assertEquals(5, 100.0 * counts.getOrDefault(BotTier.S, 0) / ROLLS, 2.0, "S");
        assertEquals(15, 100.0 * counts.getOrDefault(BotTier.A, 0) / ROLLS, 3.0, "A");
        assertEquals(40, 100.0 * counts.getOrDefault(BotTier.B, 0) / ROLLS, 4.0, "B");
        assertEquals(30, 100.0 * counts.getOrDefault(BotTier.C, 0) / ROLLS, 4.0, "C");
        assertEquals(10, 100.0 * counts.getOrDefault(BotTier.D, 0) / ROLLS, 3.0, "D");
    }

    @Test
    void theSecondJobHintIsAlwaysOnItsBranch() {
        assertTrue(Set.of(110, 120, 130).containsAll(rollHints(BotPopulationMix.WARRIOR, 400)));
        assertTrue(Set.of(210, 220, 230).containsAll(rollHints(BotPopulationMix.MAGICIAN, 400)));
        assertTrue(Set.of(310, 320).containsAll(rollHints(BotPopulationMix.BOWMAN, 400)));
        assertTrue(Set.of(410, 420).containsAll(rollHints(BotPopulationMix.THIEF, 400)));
        assertTrue(Set.of(510, 520).containsAll(rollHints(BotPopulationMix.PIRATE, 400)));
        assertTrue(Set.of(1100, 1200, 1300, 1400, 1500).containsAll(rollHints(BotPopulationMix.CYGNUS, 400)));
        assertEquals(Set.of(0), rollHints(BotPopulationMix.ARAN, 50));
        // every branch of every class gets picked, not just the first
        for (int baseClass = BotPopulationMix.WARRIOR; baseClass <= BotPopulationMix.CYGNUS; baseClass++) {
            assertTrue(rollHints(baseClass, 400).size() >= 2, "class " + baseClass);
        }
    }

    private static Set<Integer> rollHints(int baseClass, int rolls) {
        Set<Integer> hints = new java.util.HashSet<>();
        for (int i = 0; i < rolls; i++) {
            hints.add(BotPopulationMix.secondJobHint(baseClass));
        }
        return hints;
    }

    /**
     * A spawned bot sits where a player of its class would be: beginner below the first advancement, then 1st job to
     * 29, 2nd to 69, 3rd to 119, 4th from 120 - Cygnus Knights (4th job at 120 as MapleRoot gives them) and Aran
     * included, and every job the build has a skill order for.
     */
    @Test
    void everyClassGetsALevelAppropriateJob() {
        List<Integer> classes = List.of(BotPopulationMix.WARRIOR, BotPopulationMix.MAGICIAN, BotPopulationMix.BOWMAN,
                BotPopulationMix.THIEF, BotPopulationMix.PIRATE, BotPopulationMix.CYGNUS, BotPopulationMix.ARAN);
        for (int baseClass : classes) {
            for (int level = 1; level <= 200; level++) {
                int job = BotDecorate.selectJobForClass(baseClass, level);
                assertTrue(BotBuild.isBuildJob(job), "class " + baseClass + " lv " + level + " job " + job);
                int expectedTier = level >= 120 ? 4 : level >= 70 ? 3 : level >= 30 ? 2 : level >= 10 ? 1 : 0;
                int actualTier = BotBuild.tier(job);
                if (expectedTier == 0 && baseClass == BotPopulationMix.MAGICIAN && level >= 8) {
                    // a magician advances at 8 (the server's rule), so Lv8-9 is already 1st job
                    assertEquals(1, actualTier, "magician lv " + level);
                    continue;
                }
                assertEquals(expectedTier, actualTier, "class " + baseClass + " lv " + level + " job " + job);
            }
        }
    }

    /** The class the bot was rolled as is the class it fights as, for Cygnus Knights and Aran too. */
    @Test
    void theJobBelongsToTheRolledClass() {
        for (int i = 0; i < 200; i++) {
            int cygnus = BotDecorate.selectJobForClass(BotPopulationMix.CYGNUS, 40);
            assertTrue(cygnus >= 1110 && cygnus <= 1510 && cygnus % 100 == 10, "Cygnus 2nd job: " + cygnus);
            int aran = BotDecorate.selectJobForClass(BotPopulationMix.ARAN, 40);
            assertEquals(2110, aran);
            int pirate = BotDecorate.selectJobForClass(BotPopulationMix.PIRATE, 40);
            assertTrue(pirate == 510 || pirate == 520, "pirates 2nd job: " + pirate);
        }
        // the fourth jobs of the two new lines, as MapleRoot gives them
        assertTrue(BotDecorate.selectJobForClass(BotPopulationMix.CYGNUS, 130) >= 1112);
        assertEquals(2112, BotDecorate.selectJobForClass(BotPopulationMix.ARAN, 130));
    }

    /**
     * Every class gets a weapon it can actually fight with and a skill build: the two things a new class can silently
     * lose (a pirate with a sword, an Aran with no polearm, a Cygnus Knight with no SP plan).
     */
    @Test
    void everyClassGetsAWeaponAndASkillBuild() {
        List<Integer> classes = List.of(BotPopulationMix.WARRIOR, BotPopulationMix.MAGICIAN, BotPopulationMix.BOWMAN,
                BotPopulationMix.THIEF, BotPopulationMix.PIRATE, BotPopulationMix.CYGNUS, BotPopulationMix.ARAN);
        List<Integer> levels = List.of(10, 20, 30, 45, 70, 90, 120, 150, 200);
        for (int baseClass : classes) {
            for (int level : levels) {
                int jobId = BotDecorate.selectJobForClass(baseClass, level);
                Job job = Job.getById(jobId);
                List<constants.inventory.EquipType> kinds = BotWeapons.weaponsFor(job);
                assertFalse(kinds.isEmpty(), "no weapon kind for job " + jobId + " (" + job + ")");
                assertFalse(BotSkillPlan.forJob(jobId).isEmpty(), "no SP plan for job " + jobId);

                BotBuild.State state = BotBuild.plan(jobId, level, BotBuild.Choice.of(jobId), BotBuild.SkillCatalog.PLAN);
                assertEquals(jobId, state.job, "planned job");
                assertEquals(level, state.level, "planned level");
                assertFalse(state.skills.isEmpty(), "no skills for job " + jobId + " at lv " + level);
                assertTrue(state.skills.values().stream().allMatch(v -> v > 0), "a zero skill level in " + state.skills);
                // SP left over at 200 is fine (the plan is complete); negative SP would not be.
                assertTrue(state.sp >= 0, "SP for job " + jobId + " at lv " + level);
            }
        }
    }

    /** The weapon kinds the new lines must keep: the audit's pirates and Aran were unplayable with a sword. */
    @Test
    void theNewClassesGetTheirOwnWeapons() {
        assertTrue(BotWeapons.weaponsFor(Job.getById(2110)).contains(constants.inventory.EquipType.POLEARM), "Aran");
        assertTrue(BotWeapons.weaponsFor(Job.getById(510)).contains(constants.inventory.EquipType.KNUCKLER), "Brawler");
        assertTrue(BotWeapons.weaponsFor(Job.getById(520)).contains(constants.inventory.EquipType.PISTOL), "Gunslinger");
        assertTrue(BotWeapons.weaponsFor(Job.getById(1110)).contains(constants.inventory.EquipType.SWORD), "Dawn Warrior");
        assertTrue(BotWeapons.weaponsFor(Job.getById(1210)).contains(constants.inventory.EquipType.WAND), "Blaze Wizard");
        assertTrue(BotWeapons.weaponsFor(Job.getById(1310)).contains(constants.inventory.EquipType.BOW), "Wind Archer");
        assertTrue(BotWeapons.weaponsFor(Job.getById(1410)).contains(constants.inventory.EquipType.CLAW), "Night Walker");
        assertTrue(BotWeapons.weaponsFor(Job.getById(1510)).contains(constants.inventory.EquipType.KNUCKLER), "Thunder Breaker");
    }

    /** A levelled bot of the new lines is not just decorated: it has the skills to fight (MapleRoot's own set). */
    @Test
    void theNewClassesGetASkillBuildAtEveryTier() {
        for (int jobId : List.of(1100, 1110, 1111, 1112, 1200, 1210, 1211, 1212, 1300, 1310, 1311, 1312,
                1400, 1410, 1411, 1412, 1500, 1510, 1511, 1512, 2100, 2110, 2111, 2112)) {
            assertFalse(BotSkillPlan.forJob(jobId).isEmpty(), "SP plan " + jobId);
            BotBuild.State state = BotBuild.plan(jobId, 130, BotBuild.Choice.of(jobId), BotBuild.SkillCatalog.PLAN);
            assertFalse(state.skills.isEmpty(), "skills " + jobId);
        }
    }

    /** No class is rolled so rarely that the spawn path effectively never sees it. */
    @Test
    void everyExplorerClassIsCommonEnoughToExist() {
        Map<Integer, Integer> byClass = new java.util.HashMap<>();
        for (int i = 0; i < ROLLS; i++) {
            byClass.merge(BotPopulationMix.rollBaseClass(), 1, Integer::sum);
        }
        for (int baseClass = BotPopulationMix.WARRIOR; baseClass <= BotPopulationMix.ARAN; baseClass++) {
            assertTrue(byClass.getOrDefault(baseClass, 0) > ROLLS / 50, "class " + baseClass + " too rare");
        }
        List<Integer> distinct = new ArrayList<>(byClass.keySet());
        distinct.sort(null);
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), distinct);
    }
}
