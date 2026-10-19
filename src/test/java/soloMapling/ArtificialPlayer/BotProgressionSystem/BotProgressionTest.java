package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real bots tickets 05/06 on live characters: a new bot is built, a stored one gets its build back (or a back-filled
 * one), levels arriving by either gain path are paid exactly once, and a bot of an unknown job is left alone.
 */
class BotProgressionTest {

    private static final int BOT_ID = 1_000_000_000 + 4242;

    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void serverSkillData() throws Exception {
        // The tests have no WZ data, so the skills the plan names are registered by hand: a Skill is keyed by
        // identity in a character's skill map, and the build looks them up the way the server does.
        Map<Integer, Skill> skills = new HashMap<>();
        for (var job : BotSkillPlan.all().values()) {
            for (BotSkillPlan.Entry entry : job) {
                skills.put(entry.skillId(), new Skill(entry.skillId()));
                if (entry.altSkillId() != 0) {
                    skills.put(entry.altSkillId(), new Skill(entry.altSkillId()));
                }
            }
        }
        for (int granted : BotSkillPlan.allGranted()) {
            skills.put(granted, new Skill(granted));
        }
        previousSkills = swapSkillFactory(skills);
        BotProgression.useCatalogForTest(BotBuild.SkillCatalog.PLAN);
    }

    @AfterEach
    void restoreSkillData() throws Exception {
        swapSkillFactory(previousSkills);
        BotProgression.useCatalogForTest(null);
        BotProgression.forget(BOT_ID);
        BotProgression.forget(BOT_ID + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(int id, int job, int level) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(id);
        chr.setName("Builder");
        chr.setJob(Job.getById(job));
        chr.setLevel(level);
        return chr;
    }

    private static BotBuild.State planFor(Character bot) {
        return BotBuild.plan(bot.getJob().getId(), bot.getLevel(),
                BotBuild.alongLineOf(bot.getJob().getId(), BotProgression.choiceFor(bot)), BotBuild.SkillCatalog.PLAN);
    }

    private static Map<Integer, Integer> skillsOf(Character bot) {
        Map<Integer, Integer> held = new TreeMap<>();
        bot.getSkills().forEach((skill, entry) -> held.put(skill.getId(), (int) entry.skillevel));
        return held;
    }

    private static void assertMatchesPlan(BotBuild.State plan, Character bot) {
        assertAll(
                () -> assertEquals(plan.job, bot.getJob().getId(), "job"),
                () -> assertEquals(plan.str, bot.getStr(), "STR"),
                () -> assertEquals(plan.dex, bot.getDex(), "DEX"),
                () -> assertEquals(plan.int_, bot.getInt(), "INT"),
                () -> assertEquals(plan.luk, bot.getLuk(), "LUK"),
                () -> assertEquals(plan.remainingAp, bot.getRemainingAp(), "AP"),
                () -> assertEquals(plan.sp, bot.getRemainingSps()[0], "SP"),
                () -> assertEquals(plan.maxHp, bot.getMaxHp(), "max HP"),
                () -> assertEquals(plan.maxMp, bot.getMaxMp(), "max MP"),
                () -> assertEquals(plan.skills, skillsOf(bot), "skills"));
    }

    @Test
    void aNewBotGetsTheWholeBuildOfItsJobAndLevel() {
        Character bot = bot(BOT_ID, 410, 55);

        assertTrue(BotProgression.buildNew(bot));

        assertMatchesPlan(BotBuild.plan(410, 55, BotBuild.Choice.of(BOT_ID), BotBuild.SkillCatalog.PLAN), bot);
        assertTrue(bot.getSkillLevel(constants.skills.Assassin.CLAW_MASTERY) > 0, "the bot really holds its skills");
        assertEquals(55, BotProgression.settledLevel(BOT_ID));
        assertEquals(bot.getCurrentMaxHp(), bot.getHp(), "a new bot starts healed");
    }

    @Test
    void aBotOfAnUnknownJobIsLeftExactlyAsItIs() {
        Character bot = bot(BOT_ID, 113, 160);   // a MapleRoot custom 5th-tier job
        int str = bot.getStr();
        int maxHp = bot.getMaxHp();
        int ap = bot.getRemainingAp();

        assertFalse(BotProgression.buildNew(bot));
        BotProgression.restore(bot, null);
        BotProgression.afterOffscreenLevels(bot, 150);

        assertAll(
                () -> assertEquals(113, bot.getJob().getId()),
                () -> assertEquals(str, bot.getStr()),
                () -> assertEquals(maxHp, bot.getMaxHp()),
                () -> assertEquals(ap, bot.getRemainingAp()),
                () -> assertFalse(BotProgression.isReal(BOT_ID)),
                () -> assertNull(BotProgression.snapshot(bot), "and nothing about it is stored"));
    }

    @Test
    void offscreenLevelsAreGrantedSpentAndAdvanceTheJob() {
        Character bot = bot(BOT_ID, 400, 25);
        BotProgression.buildNew(bot);

        bot.setLevel(36);   // what applyOffscreenExp does: the level moves, no points come with it
        BotProgression.afterOffscreenLevels(bot, 25);

        assertMatchesPlan(planFor(bot), bot);
        assertEquals(2, BotBuild.tier(bot.getJob().getId()), "the 2nd job came at 30");
    }

    @Test
    void observedLevelUpsAreSpentButNotPaidASecondTime() {
        Character bot = bot(BOT_ID, 300, 28);
        BotProgression.buildNew(bot);

        for (int level = 29; level <= 31; level++) {
            // what Character.levelUp grants for one level
            bot.setLevel(level);
            bot.gainAp(5, true);
            bot.gainSp(3, 0, true);
            BotBuild.State plan = BotBuild.plan(300, level, BotBuild.alongLineOf(300, BotProgression.choiceFor(bot)),
                    BotBuild.SkillCatalog.PLAN);
            int hpBefore = bot.getMaxHp();
            BotProgression.afterLevelUp(bot, level - 1);
            assertEquals(plan.str + plan.dex + plan.int_ + plan.luk,
                    bot.getStr() + bot.getDex() + bot.getInt() + bot.getLuk(), "AP at " + level);
            assertEquals(0, bot.getRemainingAp());
            assertEquals(plan.skills, skillsOf(bot), "skills at " + level);
            assertEquals(plan.sp, bot.getRemainingSps()[0]);
            assertTrue(bot.getMaxHp() >= hpBefore);
        }
        assertEquals(2, BotBuild.tier(bot.getJob().getId()));
    }

    @Test
    void theSameLevelIsNeverPaidTwice() {
        Character bot = bot(BOT_ID, 200, 40);
        BotProgression.buildNew(bot);
        bot.setLevel(45);
        BotProgression.afterOffscreenLevels(bot, 40);
        BotRealStats once = BotProgression.snapshot(bot);

        BotProgression.afterOffscreenLevels(bot, 40);
        BotProgression.afterLevelUp(bot, 44);
        BotRealStats again = BotProgression.snapshot(bot);

        assertAll(
                () -> assertEquals(once.str(), again.str()),
                () -> assertEquals(once.int_(), again.int_()),
                () -> assertEquals(once.luk(), again.luk()),
                () -> assertEquals(once.maxMp(), again.maxMp()),
                () -> assertArrayEquals(once.sp(), again.sp()),
                () -> assertEquals(once.skills(), again.skills()));
    }

    @Test
    void aStoredBuildComesBackExactly() {
        Character first = bot(BOT_ID, 321, 88);
        BotProgression.buildNew(first);
        first.updateHpMp(123, 45);
        BotRealStats stored = BotProgression.snapshot(first);

        Character second = bot(BOT_ID + 1, 321, 88);   // a fresh clone of the template, as on the next boot
        BotProgression.restore(second, stored);
        BotRealStats restored = BotProgression.snapshot(second);

        assertAll(
                () -> assertNotNull(stored),
                () -> assertEquals(stored.str(), restored.str()),
                () -> assertEquals(stored.dex(), restored.dex()),
                () -> assertEquals(stored.int_(), restored.int_()),
                () -> assertEquals(stored.luk(), restored.luk()),
                () -> assertEquals(stored.maxHp(), restored.maxHp()),
                () -> assertEquals(stored.maxMp(), restored.maxMp()),
                () -> assertEquals(123, restored.hp(), "current HP is kept"),
                () -> assertEquals(45, restored.mp()),
                () -> assertEquals(stored.remainingAp(), restored.remainingAp()),
                () -> assertArrayEquals(stored.sp(), restored.sp()),
                () -> assertEquals(stored.buildLevel(), restored.buildLevel()),
                () -> assertEquals(stored.skills(), restored.skills()),
                () -> assertEquals(321, second.getJob().getId()));
    }

    @Test
    void aStoredBotWithoutABuildIsBackFilledFromItsLevelAndJob() {
        Character bot = bot(BOT_ID, 230, 64);

        BotProgression.restore(bot, null);

        assertTrue(BotProgression.isReal(BOT_ID));
        assertMatchesPlan(planFor(bot), bot);
        assertEquals(64, BotProgression.snapshot(bot).buildLevel(), "the back-fill is stored with the next save");
    }

    @Test
    void levelsTheRowGainedAfterItsBuildWasSettledAreCaughtUpOnLoad() {
        Character before = bot(BOT_ID, 130, 50);
        BotProgression.buildNew(before);
        BotRealStats stored = BotProgression.snapshot(before);

        Character loaded = bot(BOT_ID, 130, 72);   // the row says 72, the build was settled at 50
        BotProgression.restore(loaded, stored);

        assertEquals(72, BotProgression.settledLevel(BOT_ID));
        assertMatchesPlan(planFor(loaded), loaded);
        assertEquals(131, loaded.getJob().getId(), "and the 3rd job came at 70");
    }

    @Test
    void theTemplatesOwnSkillsDoNotLeakIntoABot() {
        Character bot = bot(BOT_ID, 100, 20);
        Skill templateSkill = SkillFactory.getSkill(constants.skills.Bishop.GENESIS);
        bot.changeSkillLevel(templateSkill, (byte) 30, 30, -1);

        BotProgression.buildNew(bot);

        assertEquals(0, bot.getSkillLevel(templateSkill));
    }

    @Test
    void aBotReachingItsFourthJobPaysForThePackageWhenItCan() {
        Character rich = bot(BOT_ID, 111, 119);
        Character poor = bot(BOT_ID + 1, 111, 119);
        BotProgression.buildNew(rich);
        BotProgression.buildNew(poor);
        rich.gainMeso(12_000_000, false, false, false);

        for (Character c : new Character[]{rich, poor}) {
            c.setLevel(120);
            BotProgression.afterOffscreenLevels(c, 119);
        }

        assertAll(
                () -> assertEquals(112, rich.getJob().getId()),
                () -> assertEquals(2_000_000, rich.getMeso(), "10m paid at NPC 2084000's price"),
                () -> assertTrue(rich.getSkillLevel(soloMapling.ArtificialPlayer.MapleRootSkills.RISING_RAGE) > 0),
                () -> assertEquals(0, poor.getMeso(), "a bot that cannot pay is not charged"),
                () -> assertTrue(poor.getSkillLevel(soloMapling.ArtificialPlayer.MapleRootSkills.RISING_RAGE) > 0,
                        "and still gets the package (the cost is the economy ticket's)"));

        BotProgression.afterOffscreenLevels(rich, 120);
        assertEquals(2_000_000, rich.getMeso(), "the package is paid once");
    }

    @Test
    void aRealBotCastsOnlyTheBuffsItHolds() {
        Character fresh = bot(BOT_ID, 410, 30);   // a new Assassin with no SP in Haste yet
        BotProgression.buildNew(fresh);
        Character trained = bot(BOT_ID + 1, 410, 69);
        BotProgression.buildNew(trained);

        assertAll(
                () -> assertFalse(soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig.buffsFor(fresh)
                        .contains(constants.skills.Assassin.HASTE)),
                () -> assertTrue(soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig.buffsFor(trained)
                        .contains(constants.skills.Assassin.HASTE)));
    }

    @Test
    void aStoredSkillTheServerNoLongerHasGivesItsSpBack() {
        Character first = bot(BOT_ID, 321, 88);
        BotProgression.buildNew(first);
        BotRealStats stored = BotProgression.snapshot(first);
        TreeMap<Integer, BotRealStats.SkillLevel> withGone = new TreeMap<>(stored.skills());
        withGone.put(1_000_004, new BotRealStats.SkillLevel(5, 0));   // an id Skill.wz does not have
        int points = stored.sp()[0] + stored.skills().values().stream().mapToInt(BotRealStats.SkillLevel::level).sum();

        Character second = bot(BOT_ID + 1, 321, 88);
        BotProgression.restore(second, stored.withSkills(withGone));
        BotRealStats restored = BotProgression.snapshot(second);

        assertAll(
                () -> assertFalse(restored.skills().containsKey(1_000_004)),
                () -> assertEquals(points + 5, restored.sp()[0]
                        + restored.skills().values().stream().mapToInt(BotRealStats.SkillLevel::level).sum(),
                        "the 5 SP are back with the bot, spent or not"));
    }
}
