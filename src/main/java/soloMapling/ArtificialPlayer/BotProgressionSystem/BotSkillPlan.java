package soloMapling.ArtificialPlayer.BotProgressionSystem;

import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.Beginner;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.ChiefBandit;
import constants.skills.Cleric;
import constants.skills.Corsair;
import constants.skills.Crossbowman;
import constants.skills.Crusader;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.DragonKnight;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.Fighter;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hero;
import constants.skills.Hunter;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.ILWizard;
import constants.skills.Legend;
import constants.skills.Magician;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.Noblesse;
import constants.skills.Outlaw;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Priest;
import constants.skills.Ranger;
import constants.skills.Rogue;
import constants.skills.Shadower;
import constants.skills.Sniper;
import constants.skills.Spearman;
import constants.skills.ThunderBreaker;
import constants.skills.Warrior;
import constants.skills.WhiteKnight;
import constants.skills.WindArcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static soloMapling.ArtificialPlayer.MapleRootSkills.*;

/**
 * Where a bot puts its SP, per job, on MapleRoot's skill set (real-bots ticket 06): an ordered list of skills and the
 * level each is raised to, attack skills first, then mastery, then the key buffs, then the rest. Entries are walked
 * in order and each raises its skill up to its target, so a skill can appear twice (one point early for the
 * prerequisite-style "take Teleport at 1", the rest later).
 *
 * <p>The ids and targets are MapleRoot's {@code Skill.wz} - what {@code SkillFactory} loads - as catalogued in
 * {@code docs/real-bots/mapleroot-skills.md}. MapleRoot dropped every booster, Slow, Big Bang, Heaven's Hammer, Steal,
 * Taunt, Ninja Ambush, Assassinate, the pirate transformations and the second warrior weapon forms (axe, blunt
 * weapon, pole-arm), so warriors are sword (Fighter, Page) or spear (Spearman) only; none of those appear here. At
 * runtime the server's skill data still has the last word ({@link BotBuild.SkillCatalog}): a target above the
 * server's maximum is lowered to it and a skill the server lacks is skipped.
 *
 * <p>The 1st-job attack of thieves and pirates follows the planned branch ({@code alt} for the dagger thief and the
 * gunslinger), as their 2nd job decides the weapon.
 *
 * <p>Some skills are not bought with SP but taught by an NPC, and SP only raises them once they are held
 * ({@link #isGranted}):
 * <ul>
 *   <li>{@link #advancementGrants} - the Job Master (9010003) teaches Shadow Partner at 30 to a new Hermit or 3rd-job
 *       Night Walker;</li>
 *   <li>{@link #fourthJobPackage} - the 4th-job skill NPC (2084000) sells, for 10,000,000 mesos, Hero's Will and
 *       MapleRoot's custom 4th-job skills at level 1 (master 5), the Cygnus 4th-job skills likewise, Echo of Hero, and
 *       Power Drill to a Buccaneer. A bot gets it on reaching its 4th job (see {@link BotProgression}).</li>
 * </ul>
 * The skill-book master levels the package also sets (10, or 25 for the Beholder passives) are modelled only behind
 * {@code -Dsolomapling.botSkillBooks=true} (ticket 159, {@link BotSkillBooks}): then a 4th-job skill the Leafre
 * instructors give master level 10 ({@link #isBookSkill}) starts at {@link #BASE_MASTER} and its SP stops there until
 * the bot reads the skill or mastery book that raises it ({@link #booksRaise}). With the switch off a bot's 4th-job
 * master level is its plan target, as if it had read the books.
 *
 * <p>Beginner skills are not listed: the server gives no SP below 1st job. MapleRoot's 5th tier (x13), Super
 * Beginner (700) and Synth Master (710) need rebirths or a quest item and are never built (see
 * {@link BotBuild#isBuildJob(int)}).
 */
public final class BotSkillPlan {

    /** The master level the 4th-job instructors give a book-limited skill (ticket 159); books raise it from here. */
    public static final int BASE_MASTER = 10;

    private static final Set<Integer> BOOK_SKILLS = new HashSet<>();

    static {
        for (int job : new int[]{112, 122, 132, 212, 222, 232, 312, 322, 412, 422, 512, 522}) {
            for (int skill : constants.game.FourthJobMasteries.forJob(job)) {
                BOOK_SKILLS.add(skill);
            }
        }
    }

    /** True for a 4th-job skill whose master level starts at {@link #BASE_MASTER} and is raised by a book. */
    public static boolean isBookSkill(int skillId) {
        return BOOK_SKILLS.contains(skillId);
    }

    /**
     * Would a book that raises a skill's master level to {@code bookCap} help a bot whose plan raises it to
     * {@code plannedTarget} and whose master level is {@code currentMaster} now? A book is wanted even before the
     * skill is high enough to read it, so a future book is never sold off.
     */
    public static boolean booksRaise(int plannedTarget, int bookCap, int currentMaster) {
        return Math.min(plannedTarget, bookCap) > currentMaster;
    }

    /** What the 4th-job package costs at NPC 2084000. */
    public static final int FOURTH_JOB_PACKAGE_MESOS = 10_000_000;

    /** One step of a job's SP order: raise {@code skillId} (or {@code altSkillId} on the other branch) to {@code target}. */
    public record Entry(int skillId, int altSkillId, int target) {
        public int skillFor(boolean alt) {
            return alt && altSkillId != 0 ? altSkillId : skillId;
        }
    }

    /** A skill an NPC teaches at {@code level}, without SP. */
    public record Grant(int skillId, int level) {
    }

    private static final Map<Integer, List<Entry>> BY_JOB = new HashMap<>();
    private static final Map<Integer, List<Grant>> ON_ADVANCE = new HashMap<>();
    private static final Map<Integer, List<Grant>> PACKAGE = new HashMap<>();
    private static final Set<Integer> GRANTED = new HashSet<>();

    private BotSkillPlan() {
    }

    private static Entry e(int skillId, int target) {
        return new Entry(skillId, 0, target);
    }

    private static Entry v(int skillId, int altSkillId, int target) {
        return new Entry(skillId, altSkillId, target);
    }

    private static Grant g(int skillId, int level) {
        return new Grant(skillId, level);
    }

    private static void put(int job, Entry... entries) {
        BY_JOB.put(job, List.of(entries));
    }

    private static void onAdvance(int job, Grant... grants) {
        ON_ADVANCE.put(job, List.of(grants));
        for (Grant grant : grants) {
            GRANTED.add(grant.skillId());
        }
    }

    /** A 4th job's share of the package: Echo of Hero for its line, then the job's own skills. */
    private static void fourthJobPackage(int job, Grant... grants) {
        int echo = job >= 2000 ? Legend.ECHO_OF_HERO : job >= 1000 ? Noblesse.ECHO_OF_HERO : Beginner.ECHO_OF_HERO;
        List<Grant> all = new ArrayList<>();
        all.add(g(echo, 1));
        all.addAll(List.of(grants));
        PACKAGE.put(job, List.copyOf(all));
        for (Grant grant : all) {
            GRANTED.add(grant.skillId());
        }
    }

    /** The SP order of one job, or an empty list for a job that has none (beginners, unknown jobs). */
    public static List<Entry> forJob(int jobId) {
        return BY_JOB.getOrDefault(jobId, List.of());
    }

    /** What the Job Master teaches on the advancement into this job, besides the job itself. */
    public static List<Grant> advancementGrants(int jobId) {
        return ON_ADVANCE.getOrDefault(jobId, List.of());
    }

    /** The part of NPC 2084000's 4th-job package that concerns this job (empty for any other job). */
    public static List<Grant> fourthJobPackage(int jobId) {
        return PACKAGE.getOrDefault(jobId, List.of());
    }

    /** True for a skill an NPC teaches: SP only raises it once the bot holds it. */
    public static boolean isGranted(int skillId) {
        return GRANTED.contains(skillId);
    }

    /** Every job with an SP order. */
    static Map<Integer, List<Entry>> all() {
        return BY_JOB;
    }

    /** Every skill an NPC grant names, on advancement or in the package. */
    static Set<Integer> allGranted() {
        return GRANTED;
    }

    static {
        // ===== Warrior: sword (Fighter, Page) or spear (Spearman) =====
        put(100, e(POWER_STRIKE, 1), e(SLASH_BLAST, 1), e(Warrior.IMPROVED_MAXHP, 10),
                e(POWER_STRIKE, 20), e(SLASH_BLAST, 20), e(IRON_BODY, 20),
                e(Warrior.IMPROVED_HPREC, 16), e(Warrior.ENDURE, 8));
        put(110, e(Fighter.SWORD_MASTERY, 20), e(Fighter.RAGE, 20), e(Fighter.FINAL_ATTACK_SWORD, 30),
                e(Fighter.POWER_GUARD, 30));
        put(120, e(Page.SWORD_MASTERY, 20), e(Page.FINAL_ATTACK_SWORD, 30), e(Page.POWER_GUARD, 30),
                e(Page.THREATEN, 20));
        put(130, e(Spearman.SPEAR_MASTERY, 20), e(Spearman.HYPER_BODY, 30), e(Spearman.FINAL_ATTACK_SPEAR, 30),
                e(Spearman.IRON_WILL, 20));
        put(111, e(Crusader.COMBO, 30), e(Crusader.SWORD_PANIC, 30), e(Crusader.SWORD_COMA, 30),
                e(Crusader.SHOUT, 30), e(Crusader.IMPROVING_MPREC, 20), e(Crusader.ARMOR_CRASH, 20),
                e(Crusader.SHIELD_MASTERY, 20));
        put(121, e(WhiteKnight.CHARGE_BLOW, 30), e(WhiteKnight.SWORD_ICE_CHARGE, 30),
                e(WhiteKnight.SWORD_FIRE_CHARGE, 30), e(WhiteKnight.SWORD_LIT_CHARGE, 30),
                e(WhiteKnight.SHIELD_MASTERY, 20), e(WhiteKnight.IMPROVING_MP_RECOVERY, 20),
                e(WhiteKnight.MAGIC_CRASH, 20));
        put(131, e(DragonKnight.SPEAR_CRUSHER, 30), e(DragonKnight.DRAGON_ROAR, 30),
                e(DragonKnight.SPEAR_DRAGON_FURY, 30), e(DragonKnight.SACRIFICE, 30), e(DragonKnight.DRAGON_BLOOD, 20),
                e(DragonKnight.ELEMENTAL_RESISTANCE, 20), e(DragonKnight.POWER_CRASH, 20));
        put(112, e(RAGING_BLOW, 30), e(RISING_RAGE, 5), e(Hero.ADVANCED_COMBO, 30), e(Hero.MAPLE_WARRIOR, 30),
                e(Hero.STANCE, 30), e(Hero.ACHILLES, 30), e(Hero.RUSH, 30), e(Hero.ENRAGE, 30), e(Hero.GUARDIAN, 30),
                e(Hero.MONSTER_MAGNET, 30), e(Hero.HEROS_WILL, 5));
        put(122, e(Paladin.BLAST, 30), e(HOLY_FLAME_HAMMER, 5), e(Paladin.SWORD_HOLY_CHARGE, 20),
                e(Paladin.MAPLE_WARRIOR, 30), e(Paladin.STANCE, 30), e(Paladin.ACHILLES, 30),
                e(Paladin.ADVANCED_CHARGE, 10), e(Paladin.RUSH, 30), e(Paladin.GUARDIAN, 30),
                e(Paladin.MONSTER_MAGNET, 30), e(Paladin.HEROS_WILL, 5));
        put(132, e(DARK_IMPALE, 5), e(LA_MANCHA_SPEAR, 5), e(GUNGNIR_DESCEND, 5), e(DarkKnight.BERSERK, 30),
                e(DarkKnight.BEHOLDER, 10), e(DarkKnight.AURA_OF_BEHOLDER, 25), e(DarkKnight.HEX_OF_BEHOLDER, 25),
                e(DarkKnight.MAPLE_WARRIOR, 30), e(DarkKnight.STANCE, 30), e(DarkKnight.ACHILLES, 30),
                e(DARK_TIDINGS, 5), e(DarkKnight.RUSH, 30), e(DarkKnight.MONSTER_MAGNET, 30),
                e(DarkKnight.HEROS_WILL, 5));

        // ===== Magician (Seal and Doom are Fire Rush in MapleRoot: one point, a critical-rate buff) =====
        put(200, e(Magician.ENERGY_BOLT, 1), e(Magician.MAGIC_CLAW, 20), e(Magician.MAGIC_GUARD, 20),
                e(Magician.IMPROVED_MAX_MP_INCREASE, 10), e(Magician.IMPROVED_MP_RECOVERY, 16),
                e(Magician.MAGIC_ARMOR, 20), e(Magician.ENERGY_BOLT, 20));
        put(210, e(FPWizard.TELEPORT, 1), e(FPWizard.FIRE_ARROW, 30), e(FPWizard.MEDITATION, 20),
                e(FPWizard.TELEPORT, 20), e(FPWizard.MP_EATER, 20), e(FPWizard.POISON_BREATH, 30));
        put(220, e(ILWizard.TELEPORT, 1), e(ILWizard.THUNDERBOLT, 30), e(ILWizard.COLD_BEAM, 30),
                e(ILWizard.MEDITATION, 20), e(ILWizard.TELEPORT, 20), e(ILWizard.MP_EATER, 20));
        put(230, e(Cleric.TELEPORT, 1), e(Cleric.HEAL, 30), e(Cleric.BLESS, 20), e(Cleric.HOLY_ARROW, 30),
                e(Cleric.INVINCIBLE, 20), e(Cleric.TELEPORT, 20), e(Cleric.MP_EATER, 20));
        put(211, e(FPMage.ELEMENT_COMPOSITION, 30), e(FPMage.EXPLOSION, 30), e(FP_FIRE_RUSH, 1),
                e(FPMage.ELEMENT_AMPLIFICATION, 30), e(FPMage.POISON_MIST, 30), e(FPMage.PARTIAL_RESISTANCE, 20));
        put(221, e(ILMage.ELEMENT_COMPOSITION, 30), e(ILMage.ICE_STRIKE, 30), e(IL_FIRE_RUSH, 1),
                e(ILMage.ELEMENT_AMPLIFICATION, 30), e(ILMage.THUNDER_SPEAR, 30), e(ILMage.PARTIAL_RESISTANCE, 20));
        put(231, e(Priest.HOLY_SYMBOL, 30), e(Priest.SHINING_RAY, 30), e(PRIEST_FIRE_RUSH, 1),
                e(Priest.SUMMON_DRAGON, 30), e(Priest.DISPEL, 20), e(Priest.ELEMENTAL_RESISTANCE, 20),
                e(Priest.MYSTIC_DOOR, 20));
        put(212, e(FPArchMage.PARALYZE, 30), e(FPArchMage.METEOR_SHOWER, 30), e(INFERNO_AURA, 5),
                e(MEGIDDO_FLAME, 5), e(FPArchMage.INFINITY, 30), e(FPArchMage.MAPLE_WARRIOR, 30),
                e(FPArchMage.ELQUINES, 30), e(FIRE_HAZE, 30), e(FPArchMage.MANA_REFLECTION, 30),
                e(FPArchMage.HEROS_WILL, 5));
        put(222, e(ILArchMage.CHAIN_LIGHTNING, 30), e(ILArchMage.BLIZZARD, 30), e(LIGHTNING_BURST, 5),
                e(ICE_STORM, 5), e(ILArchMage.INFINITY, 30), e(ILArchMage.MAPLE_WARRIOR, 30), e(ILArchMage.IFRIT, 30),
                e(GLACIER, 30), e(ILArchMage.MANA_REFLECTION, 30), e(ILArchMage.HEROS_WILL, 5));
        put(232, e(Bishop.ANGEL_RAY, 30), e(Bishop.GENESIS, 30), e(Bishop.BAHAMUT, 30), e(Bishop.MAPLE_WARRIOR, 30),
                e(Bishop.INFINITY, 30), e(Bishop.HOLY_SHIELD, 30), e(Bishop.RESURRECTION, 10),
                e(Bishop.MANA_REFLECTION, 30), e(Bishop.HEROS_WILL, 5));

        // ===== Bowman =====
        put(300, e(Archer.DOUBLE_SHOT, 1), e(Archer.CRITICAL_SHOT, 20), e(Archer.DOUBLE_SHOT, 20),
                e(Archer.EYE_OF_AMAZON, 8), e(Archer.FOCUS, 20), e(Archer.BLESSING_OF_AMAZON, 16),
                e(Archer.ARROW_BLOW, 20));
        put(310, e(Hunter.BOW_MASTERY, 20), e(Hunter.SOUL_ARROW, 20), e(Hunter.ARROW_BOMB, 30),
                e(Hunter.FINAL_ATTACK, 30), e(Hunter.POWER_KNOCKBACK, 20));
        put(320, e(Crossbowman.CROSSBOW_MASTERY, 20), e(Crossbowman.SOUL_ARROW, 20), e(Crossbowman.IRON_ARROW, 30),
                e(Crossbowman.FINAL_ATTACK, 30), e(Crossbowman.POWER_KNOCKBACK, 20));
        put(311, e(Ranger.STRAFE, 30), e(Ranger.ARROW_RAIN, 30), e(Ranger.INFERNO, 30), e(Ranger.THRUST, 20),
                e(Ranger.PUPPET, 20), e(Ranger.MORTAL_BLOW, 20), e(Ranger.SILVER_HAWK, 30));
        put(321, e(Sniper.STRAFE, 30), e(Sniper.ARROW_ERUPTION, 30), e(Sniper.BLIZZARD, 30), e(Sniper.THRUST, 20),
                e(Sniper.PUPPET, 20), e(Sniper.MORTAL_BLOW, 20), e(Sniper.GOLDEN_EAGLE, 30));
        put(312, e(Bowmaster.HURRICANE, 30), e(ARROW_STREAM, 5), e(Bowmaster.BOW_EXPERT, 30),
                e(Bowmaster.SHARP_EYES, 30), e(Bowmaster.MAPLE_WARRIOR, 30), e(Bowmaster.CONCENTRATE, 30),
                e(Bowmaster.PHOENIX, 30), e(Bowmaster.DRAGONS_BREATH, 30), e(Bowmaster.HAMSTRING, 30),
                e(Bowmaster.HEROS_WILL, 5));
        put(322, e(Marksman.SNIPE, 30), e(Marksman.PIERCING_ARROW, 30), e(ANCIENT_ASTRA, 5), e(HIGH_SPEED_SHOT, 5),
                e(Marksman.MARKSMAN_BOOST, 30), e(Marksman.SHARP_EYES, 30), e(Marksman.MAPLE_WARRIOR, 30),
                e(Marksman.FROST_PREY, 30), e(Marksman.BLIND, 30), e(Marksman.DRAGONS_BREATH, 30),
                e(Marksman.HEROS_WILL, 5));

        // ===== Thief - the 1st-job attack follows the planned branch: Lucky Seven (claw) or Double Stab (dagger) =====
        put(400, v(Rogue.LUCKY_SEVEN, Rogue.DOUBLE_STAB, 20), e(NIMBLE_BODY, 20), e(KEEN_EYES, 8),
                e(Rogue.DARK_SIGHT, 20), e(Rogue.DISORDER, 20), v(Rogue.DOUBLE_STAB, Rogue.LUCKY_SEVEN, 20));
        put(410, e(Assassin.CLAW_MASTERY, 20), e(Assassin.CRITICAL_THROW, 30), e(Assassin.HASTE, 20),
                e(Assassin.DRAIN, 30), e(Assassin.ENDURE, 20));
        put(420, e(Bandit.DAGGER_MASTERY, 20), e(Bandit.SAVAGE_BLOW, 30), e(Bandit.HASTE, 20), e(Bandit.ENDURE, 20));
        put(411, e(SHADOW_SLASH, 30), e(Hermit.FLASH_JUMP, 20), e(Hermit.ALCHEMIST, 20), e(Hermit.MESO_UP, 20),
                e(Hermit.SHADOW_WEB, 20), e(Hermit.SHADOW_MESO, 30));
        put(421, e(ChiefBandit.BAND_OF_THIEVES, 30), e(ChiefBandit.ASSAULTER, 30), e(ChiefBandit.MESO_GUARD, 20),
                e(ChiefBandit.CHAKRA, 30), e(ChiefBandit.PICKPOCKET, 20), e(ChiefBandit.MESO_EXPLOSION, 30),
                e(ChiefBandit.SHIELD_MASTERY, 20));
        put(412, e(QUADRUPLE_THROW, 30), e(SHOWDOWN, 5), e(NightLord.SHADOW_SHIFTER, 30), e(NightLord.VENOMOUS_STAR, 30),
                e(NightLord.MAPLE_WARRIOR, 30), e(NightLord.SHADOW_STARS, 30), e(NightLord.NINJA_STORM, 30),
                e(NightLord.HEROS_WILL, 5));
        put(422, e(Shadower.BOOMERANG_STEP, 30), e(PHANTOM_BLOW, 5), e(SHADOW_VEIL, 5), e(Shadower.VENOMOUS_STAB, 30),
                e(Shadower.MAPLE_WARRIOR, 30), e(Shadower.SHADOW_SHIFTER, 30), e(Shadower.SMOKE_SCREEN, 30),
                e(Shadower.HEROS_WILL, 5));

        // ===== Pirate - the 1st-job attack follows the planned branch: Flash Fist (brawler) or Double Shot (gun) =====
        put(500, v(Pirate.FLASH_FIST, Pirate.DOUBLE_SHOT, 20), e(Pirate.SOMERSAULT_KICK, 20),
                e(Pirate.BULLET_TIME, 20), e(Pirate.DASH, 10), v(Pirate.DOUBLE_SHOT, Pirate.FLASH_FIST, 20));
        put(510, e(Brawler.KNUCKLER_MASTERY, 20), e(Brawler.CORKSCREW_BLOW, 20), e(Brawler.IMPROVE_MAX_HP, 10),
                e(Brawler.BACK_SPIN_BLOW, 20), e(Brawler.DOUBLE_UPPERCUT, 20), e(Brawler.MP_RECOVERY, 10),
                e(Brawler.OAK_BARREL, 10));
        put(520, e(Gunslinger.GUN_MASTERY, 20), e(Gunslinger.INVISIBLE_SHOT, 20), e(Gunslinger.WINGS, 10),
                e(Gunslinger.GRENADE, 20), e(Gunslinger.RECOIL_SHOT, 20), e(Gunslinger.BLANK_SHOT, 20));
        put(511, e(Marauder.ENERGY_CHARGE, 40), e(Marauder.ENERGY_BLAST, 30), e(Marauder.STUN_MASTERY, 20),
                e(POWER_DRILL, 10));
        put(521, e(Outlaw.BURST_FIRE, 20), e(Outlaw.FLAME_THROWER, 30), e(Outlaw.ICE_SPLITTER, 30),
                e(Outlaw.OCTOPUS, 30), e(Outlaw.GAVIOTA, 30), e(Outlaw.HOMING_BEACON, 30));
        put(512, e(Buccaneer.DRAGON_STRIKE, 30), e(Buccaneer.ENERGY_ORB, 30), e(DOUBLE_BLAST, 5), e(OCTOPUNCH, 5),
                e(POWER_UNITY, 5), e(Buccaneer.MAPLE_WARRIOR, 30), e(Buccaneer.SPEED_INFUSION, 20),
                e(Buccaneer.TIME_LEAP, 30), e(BUCCANEER_HEROS_WILL, 5));
        put(522, e(Corsair.RAPID_FIRE, 30), e(Corsair.BATTLE_SHIP, 10), e(Corsair.BATTLESHIP_CANNON, 30),
                e(Corsair.BATTLESHIP_TORPEDO, 30), e(BRAIN_SCRAMBLER, 5), e(EIGHT_LEGS_EASTON, 5), e(JOLLY_ROGER, 5),
                e(Corsair.ELEMENTAL_BOOST, 30), e(Corsair.MAPLE_WARRIOR, 30), e(Corsair.BULLSEYE, 20),
                e(Corsair.WRATH_OF_THE_OCTOPI, 20), e(Corsair.AERIAL_STRIKE, 30), e(Corsair.HEROS_WILL, 5));

        // ===== Cygnus Knights (MapleRoot gives them a 4th job at 120; its skills all come from the package) =====
        put(1100, e(DawnWarrior.POWER_STRIKE, 1), e(DawnWarrior.SLASH_BLAST, 20), e(DawnWarrior.POWER_STRIKE, 20),
                e(DawnWarrior.MAX_HP_INCREASE, 10), e(DawnWarrior.SOUL, 20), e(DawnWarrior.IRON_BODY, 10));
        put(1110, e(DawnWarrior.SWORD_MASTERY, 20), e(DawnWarrior.SOUL_BLADE, 30), e(DawnWarrior.FINAL_ATTACK, 30),
                e(DawnWarrior.RAGE, 20), e(DawnWarrior.SOUL_RUSH, 10));
        put(1111, e(DawnWarrior.COMBO, 20), e(DawnWarrior.BRANDISH, 30), e(DawnWarrior.ADVANCED_COMBO, 20),
                e(DawnWarrior.SOUL_CHARGE, 20), e(DawnWarrior.SOUL_DRIVER, 30), e(DawnWarrior.PANIC, 20),
                e(DawnWarrior.COMA, 20), e(DawnWarrior.INCREASED_MP_RECOVERY, 20));
        put(1112, e(SOLAR_PIERCE, 5), e(EQUINOX_SLASH, 5), e(DawnWarrior.STANCE, 30), e(IMPALING_RAYS, 5),
                e(FALLING_MOON, 5), e(MOON_DANCER, 5));
        put(1200, e(BlazeWizard.MAGIC_CLAW, 20), e(BlazeWizard.MAGIC_GUARD, 10), e(BlazeWizard.FLAME, 20),
                e(BlazeWizard.INCREASING_MAX_MP, 10), e(BlazeWizard.MAGIC_ARMOR, 10));
        put(1210, e(BlazeWizard.TELEPORT, 1), e(BlazeWizard.FIRE_ARROW, 20), e(BlazeWizard.FIRE_PILLAR, 20),
                e(BlazeWizard.MEDITATION, 20), e(BlazeWizard.TELEPORT, 20), e(BlazeWizard.ELEMENTAL_RESET, 20));
        put(1211, e(BlazeWizard.FIRE_STRIKE, 30), e(BW_FIRE_RUSH, 1), e(BlazeWizard.ELEMENT_AMPLIFICATION, 20),
                e(BlazeWizard.IFRIT, 20), e(BlazeWizard.METEOR_SHOWER, 20), e(BlazeWizard.FLAME_GEAR, 30),
                e(BlazeWizard.ELEMENTAL_RESISTANCE, 20));
        put(1212, e(TOWERING_INFERNO, 5), e(FLAME_BARRIER, 5), e(DRAGON_BLAZE, 5), e(DRAGON_FIREBALLS, 5));
        put(1300, e(WindArcher.DOUBLE_SHOT, 20), e(WindArcher.CRITICAL_SHOT, 20), e(WindArcher.EYE_OF_AMAZON, 8),
                e(WindArcher.FOCUS, 10), e(WindArcher.STORM, 20));
        put(1310, e(WindArcher.BOW_MASTERY, 20), e(WindArcher.STORM_BREAK, 20), e(WindArcher.SOUL_ARROW, 20),
                e(WindArcher.THRUST, 20), e(WindArcher.FINAL_ATTACK, 30), e(WindArcher.WIND_WALK, 10));
        put(1311, e(WindArcher.HURRICANE, 20), e(WindArcher.BOW_EXPERT, 20), e(WindArcher.STRAFE, 30),
                e(WindArcher.ARROW_RAIN, 20), e(WindArcher.PUPPET, 20));
        put(1312, e(SPIRALING_VORTEX, 5), e(SONG_OF_HEAVEN, 5), e(WA_SHARP_EYES, 5), e(MONSOON, 5), e(GRITTY_GUST, 5));
        put(1400, e(NightWalker.LUCKY_SEVEN, 20), e(NightWalker.KEEN_EYES, 8), e(NightWalker.NIMBLE_BODY, 10),
                e(NightWalker.DARKNESS, 20), e(NightWalker.DARK_SIGHT, 10), e(NW_DISORDER, 10));
        put(1410, e(NightWalker.CLAW_MASTERY, 20), e(NightWalker.CRITICAL_THROW, 30), e(NightWalker.HASTE, 20),
                e(NightWalker.FLASH_JUMP, 20), e(NightWalker.VAMPIRE, 20));
        put(1411, e(NW_TRIPLE_THROW, 20), e(NightWalker.AVENGER, 30), e(NightWalker.ALCHEMIST, 20),
                e(NightWalker.VENOM, 20), e(NightWalker.POISON_BOMB, 30), e(NightWalker.SHADOW_WEB, 20));
        put(1412, e(QUINTUPLE_STAR, 5), e(DARK_OMEN, 5), e(NW_SHOWDOWN, 5), e(SUDDEN_RAID, 5));
        put(1500, e(ThunderBreaker.SOMERSAULT_KICK, 20), e(ThunderBreaker.FIRST_STRIKE, 20),
                e(ThunderBreaker.LIGHTNING, 20), e(ThunderBreaker.QUICK_MOTION, 10), e(ThunderBreaker.DASH, 10));
        put(1510, e(ThunderBreaker.KNUCKLER_MASTERY, 20), e(ThunderBreaker.CORKSCREW_BLOW, 20),
                e(ThunderBreaker.ENERGY_CHARGE, 20), e(ThunderBreaker.LIGHTNING_CHARGE, 20),
                e(ThunderBreaker.IMPROVE_MAX_HP, 10), e(ThunderBreaker.ENERGY_BLAST, 20));
        put(1511, e(ThunderBreaker.BARRAGE, 20), e(ThunderBreaker.SHARK_WAVE, 30), e(ThunderBreaker.SPARK, 20),
                e(ThunderBreaker.SPEED_INFUSION, 20), e(ThunderBreaker.CRITICAL_PUNCH, 20),
                e(ThunderBreaker.ENERGY_DRAIN, 20));
        put(1512, e(TYPHOON, 5), e(THUNDERBOLT, 5), e(ANNIHILATE, 5), e(WHALE_RISING, 5));

        // ===== Aran (the hidden combo skills come from the client-side combo system, never from SP) =====
        put(2100, e(Aran.DOUBLE_SWING, 20), e(Aran.COMBO_ABILITY, 10), e(Aran.COMBAT_STEP, 15));
        put(2110, e(Aran.POLEARM_MASTERY, 20), e(Aran.TRIPLE_SWING, 20), e(Aran.FINAL_CHARGE, 30),
                e(Aran.COMBO_SMASH, 20), e(Aran.COMBO_DRAIN, 20), e(Aran.BODY_PRESSURE, 20));
        put(2111, e(Aran.FULL_SWING, 20), e(Aran.COMBO_CRITICAL, 20), e(Aran.SNOW_CHARGE, 20),
                e(Aran.FINAL_TOSS, 30), e(Aran.ROLLING_SPIN, 20), e(Aran.COMBO_FENRIR, 30),
                e(Aran.SMART_KNOCKBACK, 20));
        put(2112, e(Aran.OVER_SWING, 30), e(Aran.HIGH_MASTERY, 30), e(Aran.MAPLE_WARRIOR, 30),
                e(Aran.COMBO_TEMPEST, 30), e(Aran.FINAL_BLOW, 30), e(Aran.FREEZE_STANDING, 30),
                e(Aran.HIGH_DEFENSE, 30), e(Aran.COMBO_BARRIER, 30), e(Aran.HEROS_WILL, 5));

        // ===== NPC grants: scripts/npc/9010003.js on advancement, 2084000.js for the 4th-job package. The package
        // also re-teaches Shadow Partner at 30, which the Job Master already gave at the 3rd job. =====
        onAdvance(411, g(Hermit.SHADOW_PARTNER, 30));
        onAdvance(1411, g(NightWalker.SHADOW_PARTNER, 30));

        fourthJobPackage(112, g(Hero.HEROS_WILL, 1), g(RISING_RAGE, 1));
        fourthJobPackage(122, g(HOLY_FLAME_HAMMER, 1));
        fourthJobPackage(132, g(DarkKnight.HEROS_WILL, 1), g(LA_MANCHA_SPEAR, 1), g(DARK_IMPALE, 1),
                g(GUNGNIR_DESCEND, 1), g(DARK_TIDINGS, 1));
        fourthJobPackage(212, g(FPArchMage.HEROS_WILL, 1), g(MEGIDDO_FLAME, 1), g(INFERNO_AURA, 1));
        fourthJobPackage(222, g(ILArchMage.HEROS_WILL, 1), g(LIGHTNING_BURST, 1), g(ICE_STORM, 1));
        fourthJobPackage(232, g(Bishop.HEROS_WILL, 1));
        fourthJobPackage(312, g(Bowmaster.HEROS_WILL, 1), g(ARROW_STREAM, 1));
        fourthJobPackage(322, g(HIGH_SPEED_SHOT, 1), g(ANCIENT_ASTRA, 1));
        fourthJobPackage(412, g(NightLord.HEROS_WILL, 1), g(SHOWDOWN, 1));
        fourthJobPackage(422, g(Shadower.HEROS_WILL, 1), g(SHADOW_VEIL, 1), g(PHANTOM_BLOW, 1));
        fourthJobPackage(512, g(BUCCANEER_HEROS_WILL, 1), g(POWER_DRILL, 1), g(OCTOPUNCH, 1), g(DOUBLE_BLAST, 1),
                g(POWER_UNITY, 1));
        fourthJobPackage(522, g(Corsair.HEROS_WILL, 1), g(BRAIN_SCRAMBLER, 1), g(EIGHT_LEGS_EASTON, 1),
                g(JOLLY_ROGER, 1));
        fourthJobPackage(1112, g(IMPALING_RAYS, 1), g(EQUINOX_SLASH, 1), g(MOON_DANCER, 1), g(FALLING_MOON, 1),
                g(SOLAR_PIERCE, 1), g(DawnWarrior.STANCE, 1));
        fourthJobPackage(1212, g(TOWERING_INFERNO, 1), g(FLAME_BARRIER, 1), g(DRAGON_FIREBALLS, 1),
                g(DRAGON_BLAZE, 1));
        fourthJobPackage(1312, g(SONG_OF_HEAVEN, 1), g(SPIRALING_VORTEX, 1), g(WA_SHARP_EYES, 1), g(MONSOON, 1),
                g(GRITTY_GUST, 1));
        fourthJobPackage(1412, g(QUINTUPLE_STAR, 1), g(DARK_OMEN, 1), g(NW_SHOWDOWN, 1), g(SUDDEN_RAID, 1));
        fourthJobPackage(1512, g(ANNIHILATE, 1), g(THUNDERBOLT, 1), g(TYPHOON, 1), g(WHALE_RISING, 1));
        fourthJobPackage(2112);
    }
}
