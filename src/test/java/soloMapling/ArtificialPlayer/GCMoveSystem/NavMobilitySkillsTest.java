package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.FlashJumpTiers;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStyle;
import soloMapling.ArtificialPlayer.BotGrindSystem.MovementStylePolicy;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* Ticket 02: movement skills only for a bot that holds them at a level, paid in real MP, with cooldowns honoured. */
class NavMobilitySkillsTest {

    private static final AtomicInteger ids = new AtomicInteger(1_900_000_000);
    private static final int FP_TELEPORT = 2101002;
    private static final int BW_TELEPORT = 12101003;
    private static final int HERMIT_FJ = 4111006;
    private static final int NW_FJ = 14101004;
    private static final int ASSASSIN_HASTE = 4101004;

    private Map<Integer, Skill> previousSkills;
    private int cost;
    private int cooldownMs;
    private int potions;

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (int id : new int[]{FP_TELEPORT, 2201002, 2301001, BW_TELEPORT, HERMIT_FJ, NW_FJ, ASSASSIN_HASTE,
                4201003, 14101003, 4111005}) {
            skills.put(id, new Skill(id));
        }
        previousSkills = swapSkillFactory(skills);
        cost = 60;
        cooldownMs = 0;
        potions = 0;
        NavSkillCast.data = new NavSkillCast.Data() {
            @Override
            public int mpCost(Character bot, int skillId, int level) {
                return cost;
            }

            @Override
            public int cooldownMs(int skillId, int level) {
                return cooldownMs;
            }

            @Override
            public int mpPotions(Character bot) {
                return potions;
            }
        };
        NavSkillCast.resetCounters();
        NavMovementSkills.enabled = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        NavSkillCast.data = NavSkillCast.SERVER;
        NavSkillCast.resetCounters();
        NavMovementSkills.enabled = true;
        swapSkillFactory(previousSkills);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(Job job, int level, int maxMp) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(ids.incrementAndGet());
        chr.setJob(job);
        chr.setLevel(level);
        for (String f : new String[]{"maxmp", "localmaxmp", "clientmaxmp"}) {
            setInt(chr, f, maxMp);
        }
        chr.updateHpMp(chr.getHp(), maxMp);
        return chr;
    }

    private static void hold(Character bot, int skillId, int level) {
        bot.changeSkillLevel(SkillFactory.getSkill(skillId), (byte) level, level, -1);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void theJobAloneIsNotEnoughTheBotMustHoldTheSkill() throws Exception {
        Character wizard = bot(Job.FP_WIZARD, 30, 1000);
        assertEquals(0, NavMovementSkills.heldMask(wizard), "a fresh wizard who has not taken Teleport walks");
        assertFalse(GCMovement.holdsTeleport(wizard));
        hold(wizard, FP_TELEPORT, 1);
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.heldMask(wizard));
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.maskFor(wizard));

        Character hermit = bot(Job.HERMIT, 75, 1000);
        assertEquals(0, NavMovementSkills.heldMask(hermit), "a Hermit still saving SP for Flash Jump");
        hold(hermit, HERMIT_FJ, 3);
        assertEquals(BotNavigationGraph.SKILL_FLASH_JUMP, NavMovementSkills.heldMask(hermit));

        Character nightWalker = bot(Job.NIGHTWALKER2, 35, 1000);
        hold(nightWalker, NW_FJ, 1);
        assertEquals(BotNavigationGraph.SKILL_FLASH_JUMP, NavMovementSkills.heldMask(nightWalker));

        Character blaze = bot(Job.BLAZEWIZARD2, 35, 1000);
        hold(blaze, BW_TELEPORT, 1);
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.heldMask(blaze));
    }

    @Test
    void aSkillOutsideItsStockJobIsNeverUsed() throws Exception {
        Character warrior = bot(Job.FIGHTER, 40, 1000);
        hold(warrior, FP_TELEPORT, 20);
        hold(warrior, HERMIT_FJ, 20);
        assertEquals(0, NavMovementSkills.heldMask(warrior), "a warrior handed the skills still walks");
        Character shadower = bot(Job.SHADOWER, 130, 3000);
        hold(shadower, HERMIT_FJ, 20);
        assertEquals(0, NavMovementSkills.heldMask(shadower), "Shadowers have no Flash Jump in v83");
        Character aran = bot(Job.ARAN4, 130, 3000);
        assertEquals(0, NavMovementSkills.heldMask(aran));
    }

    @Test
    void theSwitchTurnsEverySkillEdgeOff() throws Exception {
        Character mage = bot(Job.FP_MAGE, 80, 2000);
        hold(mage, FP_TELEPORT, 20);
        NavMovementSkills.enabled = false;
        assertEquals(0, NavMovementSkills.maskFor(mage));
        assertFalse(GCMovement.canTeleport(mage));
    }

    @Test
    void aCastCostsItsMpAndADryBotKeepsItsReserveForFighting() throws Exception {
        Character mage = bot(Job.FP_WIZARD, 40, 1000);
        hold(mage, FP_TELEPORT, 1);
        mage.updateHpMp(mage.getHp(), 1000);
        assertTrue(NavSkillCast.pay(mage, BotNavigationGraph.EdgeType.TELEPORT));
        assertEquals(940, mage.getMp(), "Teleport level 1 costs its 60 MP");
        assertEquals(1, NavSkillCast.teleports.get());
        assertEquals(60, NavSkillCast.mpSpent.get());

        mage.updateHpMp(mage.getHp(), 340);   // under cost + 30% of 1000, and no potions
        assertFalse(NavSkillCast.affordable(mage, BotNavigationGraph.EdgeType.TELEPORT));
        assertEquals(0, NavMovementSkills.maskFor(mage), "a dry mage plans walk-only");
        assertEquals(BotNavigationGraph.SKILL_TELEPORT, NavMovementSkills.heldMask(mage),
                "...but still holds the skill, so learning does not blame the edge");
        assertFalse(NavSkillCast.pay(mage, BotNavigationGraph.EdgeType.TELEPORT));
        assertEquals(340, mage.getMp(), "a refused cast takes nothing");
        assertEquals(1, NavSkillCast.refused.get());

        potions = 5;                          // with potions to drink it blinks on, like an auto-pot player
        assertTrue(NavSkillCast.affordable(mage, BotNavigationGraph.EdgeType.TELEPORT));

        mage.updateHpMp(mage.getHp(), 30);    // counted on a potion that is not really there: it backs off
        assertFalse(NavSkillCast.pay(mage, BotNavigationGraph.EdgeType.TELEPORT));
        assertFalse(NavSkillCast.affordable(mage, BotNavigationGraph.EdgeType.TELEPORT),
                "no replanning into the same failed cast every tick");

        NavSkillCast.resetCounters();
        mage.updateHpMp(mage.getHp(), 340);
        cost = 1;                             // maxed Teleport costs 1 MP: practically free
        potions = 0;
        assertTrue(NavSkillCast.affordable(mage, BotNavigationGraph.EdgeType.TELEPORT));
    }

    @Test
    void aCooldownFromTheSkillDataIsHonoured() throws Exception {
        Character hermit = bot(Job.HERMIT, 90, 2000);
        hold(hermit, HERMIT_FJ, 20);
        hermit.updateHpMp(hermit.getHp(), 2000);
        cost = 1;
        cooldownMs = 60_000;
        assertTrue(NavSkillCast.pay(hermit, BotNavigationGraph.EdgeType.FLASH_JUMP));
        assertEquals(1, NavSkillCast.flashJumps.get());
        assertFalse(NavSkillCast.affordable(hermit, BotNavigationGraph.EdgeType.FLASH_JUMP), "cooling down");
        assertEquals(0, NavMovementSkills.maskFor(hermit));
    }

    @Test
    void learningIgnoresSkillEdgesTheBotDoesNotHold() throws Exception {
        BotNavigationGraph.Edge tele = new BotNavigationGraph.Edge(1, 2, BotNavigationGraph.EdgeType.TELEPORT,
                new Point(0, 0), new Point(150, 0), 0, 0, 0, 0, 0, 150);
        Character wizard = bot(Job.IL_WIZARD, 31, 600);
        BotMovementState entry = new BotMovementState(wizard, null);
        assertEquals(NavEdgeLearning.Cause.SKILL_UNAVAILABLE, NavEdgeLearning.classify(entry, tele));
        hold(wizard, 2201002, 1);
        wizard.updateHpMp(wizard.getHp(), 0);   // out of MP: it could not cast now, but a failure would be genuine
        assertEquals(NavEdgeLearning.Cause.GENUINE, NavEdgeLearning.classify(entry, tele));
    }

    @Test
    void grindStyleFollowsTheHeldSkill() throws Exception {
        Character wizard = bot(Job.CLERIC, 31, 600);
        assertEquals(MovementStyle.PLANTED, MovementStylePolicy.forBot(wizard));
        hold(wizard, 2301001, 1);
        assertEquals(MovementStyle.TELEPORT, MovementStylePolicy.forBot(wizard));

        Character hermit = bot(Job.HERMIT, 72, 800);
        assertEquals(MovementStyle.JUMP_ATTACK, MovementStylePolicy.forBot(hermit), "no Flash Jump yet: hops");
        hold(hermit, HERMIT_FJ, 5);
        assertEquals(MovementStyle.FLASH_JUMP, MovementStylePolicy.forBot(hermit));
        assertEquals(0.75f, FlashJumpTiers.capFor(hermit), "a Flash Jump still being raised dashes short");
        hold(hermit, HERMIT_FJ, 20);
        assertEquals(1f, FlashJumpTiers.capFor(hermit));
    }

    @Test
    void hasteSpeedComesWithTheHeldHaste() throws Exception {
        Character assassin = bot(Job.ASSASSIN, 35, 300);
        hold(assassin, 4111005, 1); // some other skill: the bot has a build, just not Haste yet
        assertFalse(BotMovementProfile.holdsHaste(assassin));
        hold(assassin, ASSASSIN_HASTE, 1);
        assertTrue(BotMovementProfile.holdsHaste(assassin));
        Character nightWalker = bot(Job.NIGHTWALKER2, 40, 300);
        hold(nightWalker, 14101003, 10);
        assertTrue(BotMovementProfile.holdsHaste(nightWalker), "Night Walkers have Haste too");
        Character noBuild = bot(Job.BANDIT, 40, 300);
        assertFalse(BotMovementProfile.holdsHaste(noBuild), "1.5.0 earned speed: no skill, no Haste");
        Character priest = bot(Job.PRIEST, 80, 300);
        assertFalse(BotMovementProfile.holdsHaste(priest));
    }

    @Test
    void earnedSpeedIsWhatAPlayerWithTheSameThingsHas() throws Exception {
        Character priest = bot(Job.PRIEST, 120, 300);
        assertEquals(BotMovementProfile.base(), BotMovementProfile.fromCharacter(priest),
                "no gear, no buff, no Haste: 100/100 at any level (no level baseline any more)");
        Character beginner = bot(Job.BEGINNER, 5, 50);
        assertEquals(BotMovementProfile.base(), BotMovementProfile.fromCharacter(beginner));
        Character assassin = bot(Job.ASSASSIN, 35, 300);
        hold(assassin, ASSASSIN_HASTE, 10);
        BotMovementProfile half = BotMovementProfile.fromCharacter(assassin);
        assertEquals(120, half.totalSpeedStat(), "Haste 10: +20 speed, from the skill's own data");
        assertEquals(110, half.totalJumpStat());
        hold(assassin, ASSASSIN_HASTE, 20);
        BotMovementProfile full = BotMovementProfile.fromCharacter(assassin);
        assertEquals(BotMovementProfile.PLAYER_MAX_SPEED, full.totalSpeedStat(), "Haste 20: +40, the player cap");
        assertEquals(120, full.totalJumpStat());
        Character bandit = bot(Job.BANDIT, 40, 300);
        assertEquals(BotMovementProfile.base(), BotMovementProfile.fromCharacter(bandit), "no Haste skill held");
    }
}
