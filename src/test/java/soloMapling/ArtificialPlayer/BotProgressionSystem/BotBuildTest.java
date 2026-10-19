package soloMapling.ArtificialPlayer.BotProgressionSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.MapleRootSkills;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real bots tickets 05/06: the build itself - which job a bot is in at which level, where its AP and SP go, and what
 * it refuses to touch. {@link BotSkillPlanMapleRootTest} checks the plan against MapleRoot's skill data.
 */
class BotBuildTest {

    private static final BotBuild.SkillCatalog PLAN = BotBuild.SkillCatalog.PLAN;

    private static BotBuild.Choice towards(int secondJob) {
        return new BotBuild.Choice(secondJob, 7);
    }

    /** Levels a fresh character one level at a time, the way a bot that plays all along would go. */
    private static BotBuild.State levelByLevel(int beginnerJob, int toLevel, BotBuild.Choice choice,
                                               List<int[]> jobChanges) {
        BotBuild.State s = new BotBuild.State(beginnerJob, 1);
        BotBuild.settle(s, choice, PLAN);
        for (int level = 2; level <= toLevel; level++) {
            BotBuild.grantLevel(s);
            s.level = level;
            int before = s.job;
            BotBuild.settle(s, choice, PLAN);
            if (s.job != before) {
                jobChanges.add(new int[]{level, s.job});
            }
        }
        return s;
    }

    /** The SP a stock bot has been granted in total by this level and job line. */
    private static int spGranted(int level, int firstAdvancement, boolean fourthJob) {
        if (level < firstAdvancement) {
            return 0;
        }
        int sp = 1 + 3 * (level - firstAdvancement);
        sp += level >= 30 ? 1 : 0;
        sp += level >= 70 ? 1 : 0;
        sp += fourthJob && level >= 120 ? 3 : 0;
        return sp;
    }

    static int skillTotal(BotBuild.State s) {
        return s.skills.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The skill levels NPCs taught the state for free: its line's advancement grants and its 4th-job package. */
    static int grantedLevels(BotBuild.State s) {
        Map<Integer, Integer> free = new java.util.HashMap<>();
        for (int job : BotBuild.lineage(s.job)) {
            for (BotSkillPlan.Grant g : BotSkillPlan.advancementGrants(job)) {
                free.merge(g.skillId(), g.level(), Math::max);
            }
        }
        for (BotSkillPlan.Grant g : BotSkillPlan.fourthJobPackage(s.job)) {
            free.merge(g.skillId(), g.level(), Math::max);
        }
        int total = 0;
        for (Map.Entry<Integer, Integer> g : free.entrySet()) {
            total += Math.min(g.getValue(), s.skillLevel(g.getKey()));
        }
        return total;
    }

    @Test
    void onlyTheRegularClassesAreBuilt() {
        assertAll(
                () -> assertTrue(BotBuild.isBuildJob(0)),
                () -> assertTrue(BotBuild.isBuildJob(412)),
                () -> assertTrue(BotBuild.isBuildJob(1511)),
                () -> assertTrue(BotBuild.isBuildJob(1512), "MapleRoot gives Cygnus Knights a 4th job"),
                () -> assertTrue(BotBuild.isBuildJob(2112)),
                () -> assertFalse(BotBuild.isBuildJob(113), "the 5th tier needs rebirths and an item"),
                () -> assertFalse(BotBuild.isBuildJob(1113)),
                () -> assertFalse(BotBuild.isBuildJob(2113)),
                () -> assertFalse(BotBuild.isBuildJob(700), "Super Beginner is not built"),
                () -> assertFalse(BotBuild.isBuildJob(710), "nor Synth Master"),
                () -> assertFalse(BotBuild.isBuildJob(2001), "Evan is not covered"),
                () -> assertFalse(BotBuild.isBuildJob(900), "GM jobs are not covered"),
                () -> assertFalse(BotBuild.isBuildJob(513)),
                () -> assertThrows(IllegalArgumentException.class, () -> BotBuild.plan(113, 150, towards(0), PLAN),
                        "an unknown job is refused rather than guessed"));
    }

    @Test
    void explorersAdvanceAtTenThirtySeventyAndOneTwenty() {
        List<int[]> changes = new ArrayList<>();
        BotBuild.State s = levelByLevel(0, 130, towards(412), changes);

        assertEquals(List.of(List.of(10, 400), List.of(30, 410), List.of(70, 411), List.of(120, 412)),
                changes.stream().map(c -> List.of(c[0], c[1])).toList());
        assertEquals(412, s.job);
    }

    @Test
    void magiciansTakeTheirFirstJobAtEight() {
        List<int[]> changes = new ArrayList<>();
        levelByLevel(0, 31, towards(230), changes);

        assertEquals(8, changes.get(0)[0]);
        assertEquals(200, changes.get(0)[1]);
        assertEquals(List.of(30, 230), List.of(changes.get(1)[0], changes.get(1)[1]));
    }

    @Test
    void cygnusKnightsAndAransGoToTheirFourthJobAtOneTwenty() {
        List<int[]> cygnus = new ArrayList<>();
        BotBuild.State knight = levelByLevel(1000, 150, towards(1410), cygnus);
        List<int[]> aran = new ArrayList<>();
        BotBuild.State legend = levelByLevel(2000, 125, towards(0), aran);

        assertAll(
                () -> assertEquals(List.of(List.of(10, 1400), List.of(30, 1410), List.of(70, 1411), List.of(120, 1412)),
                        cygnus.stream().map(c -> List.of(c[0], c[1])).toList()),
                () -> assertEquals(1412, knight.job),
                () -> assertEquals(List.of(List.of(10, 2100), List.of(30, 2110), List.of(70, 2111), List.of(120, 2112)),
                        aran.stream().map(c -> List.of(c[0], c[1])).toList()),
                () -> assertEquals(2112, legend.job));
    }

    /** Ticket 06's "done when": every stock line, levelled 1 to 70, is in the right job at every level. */
    @ParameterizedTest
    @ValueSource(ints = {110, 120, 130, 210, 220, 230, 310, 320, 410, 420, 510, 520, 1110, 1210, 1310, 1410, 1510, 2110})
    void everyLineLevelsOneToSeventyThroughTheRightJobs(int secondJob) {
        int beginner = secondJob >= 2000 ? 2000 : secondJob >= 1000 ? 1000 : 0;
        int first = secondJob / 100 * 100;
        int firstAt = first == 200 ? 8 : 10;
        BotBuild.State s = new BotBuild.State(beginner, 1);
        BotBuild.Choice choice = towards(secondJob);
        BotBuild.settle(s, choice, PLAN);
        for (int level = 2; level <= 70; level++) {
            BotBuild.grantLevel(s);
            s.level = level;
            BotBuild.settle(s, choice, PLAN);
            int expected = level < firstAt ? beginner : level < 30 ? first : level < 70 ? secondJob : secondJob + 1;
            assertEquals(expected, s.job, "job at level " + level);
            assertEquals(0, s.remainingAp, "all AP spent at level " + level);
            for (Map.Entry<Integer, Integer> skill : s.skills.entrySet()) {
                int owner = skill.getKey() / 10000;
                assertTrue(owner == first || owner == secondJob || owner == secondJob + 1,
                        "skill " + skill.getKey() + " belongs to the bot's own line");
            }
        }
    }

    @Test
    void eachClassPutsItsApIntoItsOwnStats() {
        BotBuild.State warrior = BotBuild.plan(110, 50, towards(110), PLAN);
        BotBuild.State mage = BotBuild.plan(220, 50, towards(220), PLAN);
        BotBuild.State archer = BotBuild.plan(310, 50, towards(310), PLAN);
        BotBuild.State thief = BotBuild.plan(410, 50, towards(410), PLAN);
        BotBuild.State brawler = BotBuild.plan(510, 50, towards(510), PLAN);
        BotBuild.State gunner = BotBuild.plan(520, 50, towards(520), PLAN);
        BotBuild.State aran = BotBuild.plan(2110, 50, towards(0), PLAN);
        BotBuild.State blaze = BotBuild.plan(1210, 50, towards(1210), PLAN);

        assertAll(
                () -> assertEquals(29, warrior.dex, "warrior DEX sits at 4 + level/2"),
                () -> assertEquals(4, warrior.int_),
                () -> assertEquals(4, warrior.luk),
                () -> assertTrue(warrior.str > 200, "the rest is STR: " + warrior.str),
                () -> assertEquals(53, mage.luk, "magician LUK sits at level + 3"),
                () -> assertTrue(mage.int_ > 180, "the rest is INT: " + mage.int_),
                () -> assertEquals(4, mage.str),
                () -> assertEquals(29, archer.str),
                () -> assertTrue(archer.dex > 200),
                () -> assertEquals(29, thief.dex),
                () -> assertTrue(thief.luk > 200),
                () -> assertEquals(29, brawler.dex),
                () -> assertTrue(brawler.str > 200),
                () -> assertEquals(29, gunner.str),
                () -> assertTrue(gunner.dex > 200),
                () -> assertEquals(29, aran.dex),
                () -> assertTrue(aran.str > 200),
                () -> assertEquals(53, blaze.luk),
                () -> assertTrue(blaze.int_ > 180));
    }

    @Test
    void everyApTheServerGrantsIsSpentAndNoneIsInvented() {
        // 16 base + 9 starting AP + 5 per level; the 2nd explorer advancement grants none, the 3rd and 4th 5 each.
        for (int level : new int[]{1, 9, 10, 30, 69, 70, 119, 120, 200}) {
            BotBuild.State s = BotBuild.plan(0, level, towards(420), PLAN);
            int expected = 25 + 5 * (level - 1) + (level >= 70 ? 5 : 0) + (level >= 120 ? 5 : 0);
            int stats = s.str + s.dex + s.int_ + s.luk;
            assertEquals(expected, stats + s.remainingAp, "AP at level " + level);
            assertEquals(0, s.remainingAp, "spent at level " + level);
        }
    }

    @Test
    void aThiefMeetsItsFirstJobDexRequirement() {
        BotBuild.State rogue = BotBuild.plan(400, 10, towards(410), PLAN);

        assertTrue(rogue.dex >= 25, "DEX " + rogue.dex);
        assertTrue(rogue.luk > rogue.dex);
    }

    @ParameterizedTest
    @ValueSource(ints = {112, 122, 132, 212, 222, 232, 312, 322, 412, 422, 512, 522, 1112, 1212, 1312, 1412, 1512, 2112})
    void spIsConservedAndNeverRaisesASkillPastItsMaximum(int job) {
        boolean fourth = BotBuild.tier(job) == 4;
        int firstAt = job / 100 * 100 == 200 ? 8 : 10;
        for (int level : new int[]{12, 29, 30, 45, 70, 100, 120, 150, 200}) {
            int beginner = job >= 2000 ? 2000 : job >= 1000 ? 1000 : 0;
            BotBuild.State s = BotBuild.plan(beginner, level, towards(job / 10 * 10), PLAN);
            assertEquals(spGranted(level, firstAt, fourth), skillTotal(s) - grantedLevels(s) + s.sp,
                    "every SP granted by level " + level + " is either in a skill or still unspent");
            for (Map.Entry<Integer, Integer> skill : s.skills.entrySet()) {
                int owner = skill.getKey() / 10000;
                int target = Math.max(BotBuild.targetLevel(owner, skill.getKey(), PLAN),
                        BotSkillPlan.isGranted(skill.getKey()) ? 30 : 0);
                assertTrue(skill.getValue() >= 1 && skill.getValue() <= target,
                        "skill " + skill.getKey() + " at " + skill.getValue() + " (max " + target + ")");
            }
        }
    }

    @Test
    void theAttackComesFirst() {
        BotBuild.State rogue = BotBuild.plan(400, 16, towards(410), PLAN);
        BotBuild.State bandit = BotBuild.plan(400, 16, towards(420), PLAN);
        BotBuild.State hunter = BotBuild.plan(310, 36, towards(310), PLAN);

        assertAll(
                () -> assertEquals(19, rogue.skillLevel(constants.skills.Rogue.LUCKY_SEVEN),
                        "a claw thief's first 19 SP go into Lucky Seven"),
                () -> assertEquals(0, rogue.skillLevel(constants.skills.Rogue.DOUBLE_STAB)),
                () -> assertEquals(19, bandit.skillLevel(constants.skills.Rogue.DOUBLE_STAB),
                        "a dagger thief's into Double Stab"),
                () -> assertEquals(19, hunter.skillLevel(constants.skills.Hunter.BOW_MASTERY),
                        "a fresh hunter starts with its mastery"));
    }

    @Test
    void aSkillTheServerLacksIsSkippedAndAServerMaximumIsRespected() {
        int missing = constants.skills.Hunter.BOW_MASTERY;
        int capped = constants.skills.Hunter.SOUL_ARROW;
        BotBuild.SkillCatalog server = id -> id == missing ? 0 : id == capped ? 5 : Integer.MAX_VALUE;

        BotBuild.State s = BotBuild.plan(310, 69, towards(310), server);

        assertAll(
                () -> assertEquals(0, s.skillLevel(missing), "a skill the server does not have is never taken"),
                () -> assertEquals(5, s.skillLevel(capped), "nor raised past the server's maximum"),
                () -> assertEquals(spGranted(69, 10, false), skillTotal(s) + s.sp, "and no SP is lost to either"));
    }

    @Test
    void theBuildIsTheSameWhetherLevelledAtOnceOrOneLevelAtATime() {
        BotBuild.Choice choice = towards(220);
        BotBuild.State stepped = BotBuild.plan(220, 40, choice, PLAN);
        for (int level = 41; level <= 95; level++) {
            BotBuild.grantLevel(stepped);
            stepped.level = level;
            BotBuild.settle(stepped, choice, PLAN);
        }
        BotBuild.State planned = BotBuild.plan(220, 95, choice, PLAN);
        BotBuild.State caughtUp = BotBuild.plan(220, 40, choice, PLAN);
        caughtUp.level = 95;
        BotBuild.catchUp(caughtUp, 40, choice, PLAN);

        for (BotBuild.State other : List.of(stepped, caughtUp)) {
            assertAll(
                    () -> assertEquals(planned.job, other.job),
                    () -> assertEquals(planned.str, other.str),
                    () -> assertEquals(planned.dex, other.dex),
                    () -> assertEquals(planned.int_, other.int_),
                    () -> assertEquals(planned.luk, other.luk),
                    () -> assertEquals(planned.maxHp, other.maxHp),
                    () -> assertEquals(planned.maxMp, other.maxMp),
                    () -> assertEquals(planned.sp, other.sp),
                    () -> assertEquals(planned.skills, other.skills));
        }
    }

    @Test
    void aStoredJobIsNeverTakenBackwardsAndALaggingOneCatchesUp() {
        BotBuild.State ahead = BotBuild.plan(412, 100, towards(0), PLAN);
        BotBuild.State behind = BotBuild.plan(400, 45, towards(0), PLAN);

        assertAll(
                () -> assertEquals(412, ahead.job, "a 4th job decorated onto a level-100 bot keeps it"),
                () -> assertEquals(100, ahead.level),
                () -> assertEquals(2, BotBuild.tier(behind.job), "a 1st job at 45 advances into its 2nd"),
                () -> assertTrue(behind.job == 410 || behind.job == 420, "within its own class: " + behind.job));
    }

    @Test
    void theBranchFollowsTheWeaponAndOtherwiseTheBotsOwnSeed() {
        Set<Integer> seen = new HashSet<>();
        for (int seed = 0; seed < 12; seed++) {
            seen.add(BotBuild.plan(100, 40, new BotBuild.Choice(0, seed), PLAN).job);
            assertEquals(BotBuild.plan(100, 40, new BotBuild.Choice(0, seed), PLAN).job,
                    BotBuild.plan(100, 40, new BotBuild.Choice(0, seed), PLAN).job, "same bot, same pick");
        }
        assertAll(
                () -> assertEquals(Set.of(110, 120, 130), seen, "seeds spread over the class's branches"),
                () -> assertEquals(130, BotBuild.plan(100, 40, new BotBuild.Choice(130, 0), PLAN).job,
                        "a pole-arm makes a spearman"),
                () -> assertEquals(200, BotBuild.plan(0, 12, new BotBuild.Choice(220, 0), PLAN).job,
                        "a beginner with a wand becomes a magician"),
                () -> assertEquals(110, BotBuild.plan(100, 40, new BotBuild.Choice(310, 0), PLAN).job,
                        "a weapon of another class does not change the class"));
    }

    @Test
    void warriorsTrainSwordsOrSpearsWhateverTheyCarry() {
        int axeHint = BotProgression.hintFor(client.inventory.WeaponType.GENERAL2H_SWING, 3);
        int bluntHint = BotProgression.hintFor(client.inventory.WeaponType.GENERAL1H_STAB, 4);
        BotBuild.State axeBot = BotBuild.plan(111, 80, new BotBuild.Choice(axeHint, 3), PLAN);

        assertAll(
                () -> assertTrue(axeHint == 110 || axeHint == 120, "an axe reads as a sword: " + axeHint),
                () -> assertTrue(bluntHint == 110 || bluntHint == 120, "so does a blunt weapon: " + bluntHint),
                () -> assertEquals(130, BotProgression.hintFor(client.inventory.WeaponType.POLE_ARM_SWING, 0),
                        "a pole-arm reads as a spear"),
                () -> assertTrue(axeBot.skillLevel(constants.skills.Crusader.SWORD_PANIC) > 0),
                () -> assertTrue(axeBot.skillLevel(constants.skills.Fighter.SWORD_MASTERY) > 0));
    }

    @Test
    void npcGrantsAreFreeAndTheirSkillsAreOnlyRaisedOnceHeld() {
        BotBuild.State hermit = BotBuild.plan(411, 75, towards(410), PLAN);
        BotBuild.State thirdJobHero = BotBuild.plan(111, 119, towards(110), PLAN);
        BotBuild.State hero = BotBuild.plan(111, 121, towards(110), PLAN);

        assertAll(
                () -> assertEquals(30, hermit.skillLevel(constants.skills.Hermit.SHADOW_PARTNER),
                        "the Job Master teaches Shadow Partner at 30 with the 3rd job"),
                () -> assertEquals(0, thirdJobHero.skillLevel(MapleRootSkills.RISING_RAGE),
                        "a custom 4th-job skill is not bought with SP before the package"),
                () -> assertEquals(112, hero.job),
                () -> assertEquals(1, hero.packagesBought, "the 4th job buys the package once"),
                () -> assertTrue(hero.skillLevel(MapleRootSkills.RISING_RAGE) >= 1),
                () -> assertEquals(1, hero.skillLevel(constants.skills.Beginner.ECHO_OF_HERO)),
                () -> assertEquals(spGranted(121, 10, true), skillTotal(hero) - grantedLevels(hero) + hero.sp));
    }

    @Test
    void aHermitThatBoughtShadowPartnerWithSpGetsTheGrantAndItsSpBack() {
        BotBuild.State old = new BotBuild.State(411, 80);   // built by the stock-v83 plan: 7 SP in Shadow Partner
        old.skills.put(constants.skills.Hermit.SHADOW_PARTNER, 7);

        BotBuild.grantNpcSkills(old, PLAN);
        BotBuild.grantNpcSkills(old, PLAN);

        assertAll(
                () -> assertEquals(30, old.skillLevel(constants.skills.Hermit.SHADOW_PARTNER)),
                () -> assertEquals(7, old.sp, "the 7 SP come back, once"));
    }

    @Test
    void aFourthJobBuiltBeforeThePackageGetsItOnItsNextSettleOnce() {
        BotBuild.State old = BotBuild.plan(412, 150, towards(410), PLAN);
        for (BotSkillPlan.Grant g : BotSkillPlan.fourthJobPackage(412)) {
            if (g.skillId() != constants.skills.Hermit.SHADOW_PARTNER) {
                old.skills.remove(g.skillId());
            }
        }
        old.packagesBought = 0;

        BotBuild.settle(old, towards(410), PLAN);
        BotBuild.settle(old, towards(410), PLAN);

        assertAll(
                () -> assertEquals(1, old.packagesBought, "granted once, not on every settle"),
                () -> assertTrue(old.skillLevel(MapleRootSkills.SHOWDOWN) >= 1));
    }

    @Test
    void levelsPastTheBuildCapGrantNothingMore() {
        BotBuild.State capped = BotBuild.plan(412, 200, towards(410), PLAN);
        BotBuild.State past = BotBuild.plan(412, 230, towards(410), PLAN);

        assertAll(
                () -> assertEquals(capped.str + capped.dex + capped.int_ + capped.luk,
                        past.str + past.dex + past.int_ + past.luk),
                () -> assertEquals(capped.skills, past.skills),
                () -> assertEquals(230, past.level, "the level itself is kept"));
    }

    @Test
    void everyPlannedSkillBelongsToTheJobThatListsIt() {
        for (Map.Entry<Integer, List<BotSkillPlan.Entry>> job : BotSkillPlan.all().entrySet()) {
            assertTrue(BotBuild.isBuildJob(job.getKey()), "job " + job.getKey());
            for (BotSkillPlan.Entry entry : job.getValue()) {
                assertEquals(job.getKey(), entry.skillId() / 10000, "skill " + entry.skillId() + " of job " + job.getKey());
                if (entry.altSkillId() != 0) {
                    assertEquals(job.getKey(), entry.altSkillId() / 10000, "skill " + entry.altSkillId());
                }
                assertTrue(entry.target() > 0 && entry.target() <= 40);
            }
        }
    }
}
