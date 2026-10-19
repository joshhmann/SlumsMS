package soloMapling.ArtificialPlayer.BotProgressionSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import soloMapling.ArtificialPlayer.MapleRootSkillData;
import soloMapling.ArtificialPlayer.MapleRootSkillData.SkillInfo;
import soloMapling.ArtificialPlayer.MapleRootSkills;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real bots ticket 06 on MapleRoot: every skill the plan names exists in MapleRoot's Skill.wz (the
 * {@link MapleRootSkillData} fixture) at a level it has, and a build on that data spends exactly the SP the server
 * grants.
 */
class BotSkillPlanMapleRootTest {

    /** The server's catalog as the fixture describes it. */
    private static final BotBuild.SkillCatalog MAPLEROOT = id -> {
        SkillInfo info = MapleRootSkillData.get(id);
        return info == null ? 0 : info.maxLevel();
    };

    @Test
    void everyPlannedSkillIsInMapleRootsSkillWzAtALevelItHas() {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<Integer, List<BotSkillPlan.Entry>> job : BotSkillPlan.all().entrySet()) {
            for (BotSkillPlan.Entry entry : job.getValue()) {
                for (int id : new int[]{entry.skillId(), entry.altSkillId()}) {
                    if (id == 0) {
                        continue;
                    }
                    SkillInfo info = MapleRootSkillData.get(id);
                    if (info == null) {
                        wrong.add(job.getKey() + ": " + id + " is not in Skill.wz");
                    } else if (entry.target() > info.maxLevel()) {
                        wrong.add(job.getKey() + ": " + id + " " + info.name() + " to " + entry.target()
                                + " (max " + info.maxLevel() + ")");
                    } else if (info.job() != job.getKey()) {
                        wrong.add(job.getKey() + ": " + id + " belongs to job " + info.job());
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void everyNpcGrantIsInMapleRootsSkillWzAtALevelItHas() {
        List<String> wrong = new ArrayList<>();
        for (int job : BotSkillPlan.all().keySet()) {
            List<BotSkillPlan.Grant> grants = new ArrayList<>(BotSkillPlan.advancementGrants(job));
            grants.addAll(BotSkillPlan.fourthJobPackage(job));
            for (BotSkillPlan.Grant g : grants) {
                SkillInfo info = MapleRootSkillData.get(g.skillId());
                if (info == null || g.level() > info.maxLevel()) {
                    wrong.add(job + ": " + g.skillId() + " at " + g.level());
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void everyBuiltJobPastTheBeginnerHasAnSpOrder() {
        for (int job : new int[]{100, 110, 111, 112, 120, 121, 122, 130, 131, 132, 200, 210, 211, 212, 220, 221,
                222, 230, 231, 232, 300, 310, 311, 312, 320, 321, 322, 400, 410, 411, 412, 420, 421, 422, 500, 510,
                511, 512, 520, 521, 522, 1100, 1110, 1111, 1112, 1200, 1210, 1211, 1212, 1300, 1310, 1311, 1312,
                1400, 1410, 1411, 1412, 1500, 1510, 1511, 1512, 2100, 2110, 2111, 2112}) {
            assertTrue(BotBuild.isBuildJob(job), "job " + job);
            assertFalse(BotSkillPlan.forJob(job).isEmpty(), "job " + job + " has an SP order");
        }
    }

    @Test
    void theFourthJobPackageCarriesEachJobsCustomSkills() {
        assertAll(
                () -> assertTrue(hasGrant(112, MapleRootSkills.RISING_RAGE)),
                () -> assertTrue(hasGrant(132, MapleRootSkills.GUNGNIR_DESCEND)),
                () -> assertTrue(hasGrant(212, MapleRootSkills.MEGIDDO_FLAME)),
                () -> assertTrue(hasGrant(512, MapleRootSkills.POWER_DRILL), "a Marauder skill the package teaches"),
                () -> assertTrue(hasGrant(1112, constants.skills.DawnWarrior.STANCE)),
                () -> assertTrue(hasGrant(1512, MapleRootSkills.TYPHOON)),
                () -> assertFalse(hasGrant(112, MapleRootSkills.SHOWDOWN), "only the job's own skills"),
                () -> assertTrue(BotSkillPlan.fourthJobPackage(411).isEmpty(), "a 3rd job has no package"));
    }

    private static boolean hasGrant(int job, int skillId) {
        return BotSkillPlan.fourthJobPackage(job).stream().anyMatch(g -> g.skillId() == skillId);
    }

    /** On MapleRoot's own data: SP is conserved, nothing exceeds Skill.wz, and every skill held is in it. */
    @ParameterizedTest
    @ValueSource(ints = {112, 122, 132, 212, 222, 232, 312, 322, 412, 422, 512, 522, 1112, 1212, 1312, 1412, 1512, 2112})
    void aMapleRootBuildSpendsExactlyTheSpTheServerGrants(int job) {
        int beginner = job >= 2000 ? 2000 : job >= 1000 ? 1000 : 0;
        int firstAt = job / 100 * 100 == 200 ? 8 : 10;
        for (int level : new int[]{15, 50, 90, 120, 121, 160, 200}) {
            BotBuild.State s = BotBuild.plan(beginner, level, new BotBuild.Choice(job / 10 * 10, 5), MAPLEROOT);
            int granted = 1 + 3 * (level - firstAt) + (level >= 30 ? 1 : 0) + (level >= 70 ? 1 : 0)
                    + (level >= 120 ? 3 : 0);
            assertEquals(granted, BotBuildTest.skillTotal(s) - BotBuildTest.grantedLevels(s) + s.sp,
                    "job " + job + " level " + level);
            for (Map.Entry<Integer, Integer> held : s.skills.entrySet()) {
                SkillInfo info = MapleRootSkillData.get(held.getKey());
                assertNotNull(info, "skill " + held.getKey());
                assertTrue(held.getValue() <= info.maxLevel(), info.name() + " at " + held.getValue());
            }
        }
    }

    @Test
    void aCygnusKnightAtFourthJobHoldsItsCustomSkills() {
        BotBuild.State nightWalker = BotBuild.plan(1000, 150, new BotBuild.Choice(1410, 5), MAPLEROOT);

        assertAll(
                () -> assertEquals(1412, nightWalker.job),
                () -> assertEquals(5, nightWalker.skillLevel(MapleRootSkills.QUINTUPLE_STAR),
                        "raised to the package's master level"),
                () -> assertEquals(30, nightWalker.skillLevel(constants.skills.NightWalker.SHADOW_PARTNER)),
                () -> assertEquals(1, nightWalker.packagesBought));
    }
}
