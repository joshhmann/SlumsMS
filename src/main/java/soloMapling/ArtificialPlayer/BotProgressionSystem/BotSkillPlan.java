package soloMapling.ArtificialPlayer.BotProgressionSystem;

import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bandit;
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
import constants.skills.Magician;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.NightWalker;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a bot puts its SP, per stock job (real-bots ticket 06): an ordered list of skills and the level each is
 * raised to, attack skills first, then mastery and booster, then the key buffs, then the rest. Entries are walked
 * in order and each raises its skill up to its target, so a skill can appear twice (one point early for the
 * prerequisite-style "take Teleport at 1", the rest later).
 *
 * <p>The targets are the stock v83 maximum levels. At runtime the server's own skill data has the last word
 * ({@link BotBuild.SkillCatalog}): a target above the server's maximum is lowered to it, and a skill the server does
 * not have is skipped, so a MapleRoot change to a stock skill never makes a bot hold a level that cannot exist.
 *
 * <p>Warrior skills that come in a weapon pair (sword/axe, sword/blunt, spear/pole-arm) name both forms; the bot
 * takes the one matching the weapon it carries ({@code alt} when it carries the second form).
 *
 * <p>Beginner skills are not listed: the server gives no SP below 1st job. MapleRoot custom jobs have no entry and
 * are never built (see {@link BotBuild#isStockJob(int)}).
 */
public final class BotSkillPlan {

    /** One step of a job's SP order: raise {@code skillId} (or {@code altSkillId} for the alternate weapon) to {@code target}. */
    public record Entry(int skillId, int altSkillId, int target) {
        public int skillFor(boolean alt) {
            return alt && altSkillId != 0 ? altSkillId : skillId;
        }
    }

    private static final Map<Integer, List<Entry>> BY_JOB = new HashMap<>();

    private BotSkillPlan() {
    }

    private static Entry e(int skillId, int target) {
        return new Entry(skillId, 0, target);
    }

    private static Entry v(int skillId, int altSkillId, int target) {
        return new Entry(skillId, altSkillId, target);
    }

    private static void put(int job, Entry... entries) {
        BY_JOB.put(job, List.of(entries));
    }

    /** The SP order of one job, or an empty list for a job that has none (beginners, unknown jobs). */
    public static List<Entry> forJob(int jobId) {
        return BY_JOB.getOrDefault(jobId, List.of());
    }

    /** Every job with an SP order. */
    static Map<Integer, List<Entry>> all() {
        return BY_JOB;
    }

    static {
        // ===== Warrior =====
        put(100, e(Warrior.POWER_STRIKE, 1), e(Warrior.SLASH_BLAST, 1), e(Warrior.IMPROVED_MAXHP, 10),
                e(Warrior.POWER_STRIKE, 20), e(Warrior.SLASH_BLAST, 20), e(Warrior.IRON_BODY, 20),
                e(Warrior.IMPROVED_HPREC, 16), e(Warrior.ENDURE, 8));
        put(110, v(Fighter.SWORD_MASTERY, Fighter.AXE_MASTERY, 20), v(Fighter.SWORD_BOOSTER, Fighter.AXE_BOOSTER, 20),
                e(Fighter.RAGE, 30), v(Fighter.FINAL_ATTACK_SWORD, Fighter.FINAL_ATTACK_AXE, 30),
                e(Fighter.POWER_GUARD, 30));
        put(120, v(Page.SWORD_MASTERY, Page.BW_MASTERY, 20), v(Page.SWORD_BOOSTER, Page.BW_BOOSTER, 20),
                v(Page.FINAL_ATTACK_SWORD, Page.FINAL_ATTACK_BW, 30), e(Page.POWER_GUARD, 30), e(Page.THREATEN, 20));
        put(130, v(Spearman.SPEAR_MASTERY, Spearman.POLEARM_MASTERY, 20),
                v(Spearman.SPEAR_BOOSTER, Spearman.POLEARM_BOOSTER, 20), e(Spearman.HYPER_BODY, 30),
                v(Spearman.FINAL_ATTACK_SPEAR, Spearman.FINAL_ATTACK_POLEARM, 30), e(Spearman.IRON_WILL, 20));
        put(111, e(Crusader.COMBO, 30), v(Crusader.SWORD_PANIC, Crusader.AXE_PANIC, 30),
                v(Crusader.SWORD_COMA, Crusader.AXE_COMA, 30), e(Crusader.SHOUT, 30), e(Crusader.IMPROVING_MPREC, 20),
                e(Crusader.ARMOR_CRASH, 20), e(Crusader.SHIELD_MASTERY, 20));
        put(121, e(WhiteKnight.CHARGE_BLOW, 30), v(WhiteKnight.SWORD_ICE_CHARGE, WhiteKnight.BW_ICE_CHARGE, 30),
                v(WhiteKnight.SWORD_FIRE_CHARGE, WhiteKnight.BW_FIRE_CHARGE, 30),
                v(WhiteKnight.SWORD_LIT_CHARGE, WhiteKnight.BW_LIT_CHARGE, 30), e(WhiteKnight.SHIELD_MASTERY, 20),
                e(WhiteKnight.IMPROVING_MP_RECOVERY, 20), e(WhiteKnight.MAGIC_CRASH, 20));
        put(131, v(DragonKnight.SPEAR_CRUSHER, DragonKnight.POLE_ARM_CRUSHER, 30), e(DragonKnight.DRAGON_ROAR, 30),
                v(DragonKnight.SPEAR_DRAGON_FURY, DragonKnight.POLE_ARM_DRAGON_FURY, 30), e(DragonKnight.SACRIFICE, 30),
                e(DragonKnight.DRAGON_BLOOD, 20), e(DragonKnight.ELEMENTAL_RESISTANCE, 20),
                e(DragonKnight.POWER_CRASH, 20));
        put(112, e(Hero.BRANDISH, 30), e(Hero.ADVANCED_COMBO, 30), e(Hero.MAPLE_WARRIOR, 30), e(Hero.STANCE, 30),
                e(Hero.ACHILLES, 30), e(Hero.RUSH, 30), e(Hero.ENRAGE, 30), e(Hero.GUARDIAN, 30),
                e(Hero.MONSTER_MAGNET, 30), e(Hero.HEROS_WILL, 5));
        put(122, e(Paladin.BLAST, 30), v(Paladin.SWORD_HOLY_CHARGE, Paladin.BW_HOLY_CHARGE, 30),
                e(Paladin.HEAVENS_HAMMER, 30), e(Paladin.MAPLE_WARRIOR, 30), e(Paladin.STANCE, 30),
                e(Paladin.ACHILLES, 30), e(Paladin.ADVANCED_CHARGE, 20), e(Paladin.RUSH, 30),
                e(Paladin.GUARDIAN, 30), e(Paladin.MONSTER_MAGNET, 30), e(Paladin.HEROS_WILL, 5));
        put(132, e(DarkKnight.BERSERK, 30), e(DarkKnight.BEHOLDER, 10), e(DarkKnight.AURA_OF_BEHOLDER, 20),
                e(DarkKnight.HEX_OF_BEHOLDER, 20), e(DarkKnight.MAPLE_WARRIOR, 30), e(DarkKnight.STANCE, 30),
                e(DarkKnight.ACHILLES, 30), e(DarkKnight.RUSH, 30), e(DarkKnight.MONSTER_MAGNET, 30),
                e(DarkKnight.HEROS_WILL, 5));

        // ===== Magician =====
        put(200, e(Magician.ENERGY_BOLT, 1), e(Magician.MAGIC_CLAW, 20), e(Magician.MAGIC_GUARD, 20),
                e(Magician.IMPROVED_MAX_MP_INCREASE, 10), e(Magician.IMPROVED_MP_RECOVERY, 16),
                e(Magician.MAGIC_ARMOR, 20), e(Magician.ENERGY_BOLT, 20));
        put(210, e(FPWizard.TELEPORT, 1), e(FPWizard.FIRE_ARROW, 30), e(FPWizard.MEDITATION, 20),
                e(FPWizard.TELEPORT, 20), e(FPWizard.MP_EATER, 20), e(FPWizard.POISON_BREATH, 30),
                e(FPWizard.SLOW, 20));
        put(220, e(ILWizard.TELEPORT, 1), e(ILWizard.THUNDERBOLT, 30), e(ILWizard.COLD_BEAM, 30),
                e(ILWizard.MEDITATION, 20), e(ILWizard.TELEPORT, 20), e(ILWizard.MP_EATER, 20), e(ILWizard.SLOW, 20));
        put(230, e(Cleric.TELEPORT, 1), e(Cleric.HEAL, 30), e(Cleric.BLESS, 20), e(Cleric.HOLY_ARROW, 30),
                e(Cleric.INVINCIBLE, 20), e(Cleric.TELEPORT, 20), e(Cleric.MP_EATER, 20));
        put(211, e(FPMage.ELEMENT_COMPOSITION, 30), e(FPMage.ELEMENT_AMPLIFICATION, 30), e(FPMage.EXPLOSION, 30),
                e(FPMage.SPELL_BOOSTER, 20), e(FPMage.POISON_MIST, 30), e(FPMage.SEAL, 20),
                e(FPMage.PARTIAL_RESISTANCE, 20));
        put(221, e(ILMage.ELEMENT_COMPOSITION, 30), e(ILMage.ICE_STRIKE, 30), e(ILMage.ELEMENT_AMPLIFICATION, 30),
                e(ILMage.SPELL_BOOSTER, 20), e(ILMage.THUNDER_SPEAR, 30), e(ILMage.SEAL, 20),
                e(ILMage.PARTIAL_RESISTANCE, 20));
        put(231, e(Priest.HOLY_SYMBOL, 30), e(Priest.SHINING_RAY, 30), e(Priest.SUMMON_DRAGON, 30),
                e(Priest.DISPEL, 20), e(Priest.ELEMENTAL_RESISTANCE, 20), e(Priest.MYSTIC_DOOR, 20), e(Priest.DOOM, 30));
        put(212, e(FPArchMage.PARALYZE, 30), e(FPArchMage.METEOR_SHOWER, 30), e(FPArchMage.INFINITY, 30),
                e(FPArchMage.MAPLE_WARRIOR, 30), e(FPArchMage.ELQUINES, 30), e(FPArchMage.BIG_BANG, 30),
                e(FPArchMage.FIRE_DEMON, 30), e(FPArchMage.MANA_REFLECTION, 30), e(FPArchMage.HEROS_WILL, 5));
        put(222, e(ILArchMage.CHAIN_LIGHTNING, 30), e(ILArchMage.BLIZZARD, 30), e(ILArchMage.INFINITY, 30),
                e(ILArchMage.MAPLE_WARRIOR, 30), e(ILArchMage.IFRIT, 30), e(ILArchMage.BIG_BANG, 30),
                e(ILArchMage.ICE_DEMON, 30), e(ILArchMage.MANA_REFLECTION, 30), e(ILArchMage.HEROS_WILL, 5));
        put(232, e(Bishop.ANGEL_RAY, 30), e(Bishop.GENESIS, 30), e(Bishop.BAHAMUT, 30), e(Bishop.MAPLE_WARRIOR, 30),
                e(Bishop.INFINITY, 30), e(Bishop.HOLY_SHIELD, 30), e(Bishop.RESURRECTION, 30), e(Bishop.BIG_BANG, 30),
                e(Bishop.MANA_REFLECTION, 30), e(Bishop.HEROS_WILL, 5));

        // ===== Bowman =====
        put(300, e(Archer.DOUBLE_SHOT, 1), e(Archer.CRITICAL_SHOT, 20), e(Archer.DOUBLE_SHOT, 20),
                e(Archer.EYE_OF_AMAZON, 8), e(Archer.FOCUS, 20), e(Archer.BLESSING_OF_AMAZON, 16),
                e(Archer.ARROW_BLOW, 20));
        put(310, e(Hunter.BOW_MASTERY, 20), e(Hunter.BOW_BOOSTER, 20), e(Hunter.SOUL_ARROW, 20),
                e(Hunter.ARROW_BOMB, 30), e(Hunter.FINAL_ATTACK, 30), e(Hunter.POWER_KNOCKBACK, 20));
        put(320, e(Crossbowman.CROSSBOW_MASTERY, 20), e(Crossbowman.CROSSBOW_BOOSTER, 20),
                e(Crossbowman.SOUL_ARROW, 20), e(Crossbowman.IRON_ARROW, 30), e(Crossbowman.FINAL_ATTACK, 30),
                e(Crossbowman.POWER_KNOCKBACK, 20));
        put(311, e(Ranger.STRAFE, 30), e(Ranger.ARROW_RAIN, 30), e(Ranger.INFERNO, 30), e(Ranger.THRUST, 20),
                e(Ranger.PUPPET, 20), e(Ranger.MORTAL_BLOW, 20), e(Ranger.SILVER_HAWK, 30));
        put(321, e(Sniper.STRAFE, 30), e(Sniper.ARROW_ERUPTION, 30), e(Sniper.BLIZZARD, 30), e(Sniper.THRUST, 20),
                e(Sniper.PUPPET, 20), e(Sniper.MORTAL_BLOW, 20), e(Sniper.GOLDEN_EAGLE, 30));
        put(312, e(Bowmaster.HURRICANE, 30), e(Bowmaster.BOW_EXPERT, 30), e(Bowmaster.SHARP_EYES, 30),
                e(Bowmaster.MAPLE_WARRIOR, 30), e(Bowmaster.CONCENTRATE, 30), e(Bowmaster.PHOENIX, 30),
                e(Bowmaster.DRAGONS_BREATH, 30), e(Bowmaster.HAMSTRING, 30), e(Bowmaster.HEROS_WILL, 5));
        put(322, e(Marksman.SNIPE, 30), e(Marksman.PIERCING_ARROW, 30), e(Marksman.MARKSMAN_BOOST, 30),
                e(Marksman.SHARP_EYES, 30), e(Marksman.MAPLE_WARRIOR, 30), e(Marksman.FROST_PREY, 30),
                e(Marksman.BLIND, 30), e(Marksman.DRAGONS_BREATH, 30), e(Marksman.HEROS_WILL, 5));

        // ===== Thief - the 1st-job attack follows the planned branch: Lucky Seven (claw) or Double Stab (dagger) =====
        put(400, v(Rogue.LUCKY_SEVEN, Rogue.DOUBLE_STAB, 20), e(Rogue.NIMBLE_BODY, 20), e(Rogue.KEEN_EYES, 8),
                e(Rogue.DARK_SIGHT, 20), e(Rogue.DISORDER, 20), v(Rogue.DOUBLE_STAB, Rogue.LUCKY_SEVEN, 20));
        put(410, e(Assassin.CLAW_MASTERY, 20), e(Assassin.CRITICAL_THROW, 30), e(Assassin.CLAW_BOOSTER, 20),
                e(Assassin.HASTE, 20), e(Assassin.DRAIN, 30), e(Assassin.ENDURE, 20));
        put(420, e(Bandit.DAGGER_MASTERY, 20), e(Bandit.DAGGER_BOOSTER, 20), e(Bandit.SAVAGE_BLOW, 30),
                e(Bandit.HASTE, 20), e(Bandit.ENDURE, 20), e(Bandit.STEAL, 30));
        put(411, e(Hermit.AVENGER, 30), e(Hermit.SHADOW_PARTNER, 30), e(Hermit.FLASH_JUMP, 20),
                e(Hermit.ALCHEMIST, 20), e(Hermit.MESO_UP, 20), e(Hermit.SHADOW_WEB, 20), e(Hermit.SHADOW_MESO, 30));
        put(421, e(ChiefBandit.BAND_OF_THIEVES, 30), e(ChiefBandit.ASSAULTER, 30), e(ChiefBandit.MESO_GUARD, 20),
                e(ChiefBandit.CHAKRA, 30), e(ChiefBandit.PICKPOCKET, 30), e(ChiefBandit.MESO_EXPLOSION, 30),
                e(ChiefBandit.SHIELD_MASTERY, 20));
        put(412, e(NightLord.TRIPLE_THROW, 30), e(NightLord.SHADOW_SHIFTER, 30), e(NightLord.VENOMOUS_STAR, 30),
                e(NightLord.MAPLE_WARRIOR, 30), e(NightLord.SHADOW_STARS, 30), e(NightLord.NINJA_AMBUSH, 30),
                e(NightLord.NINJA_STORM, 30), e(NightLord.TAUNT, 30), e(NightLord.HEROS_WILL, 5));
        put(422, e(Shadower.BOOMERANG_STEP, 30), e(Shadower.ASSASSINATE, 30), e(Shadower.VENOMOUS_STAB, 30),
                e(Shadower.MAPLE_WARRIOR, 30), e(Shadower.SHADOW_SHIFTER, 30), e(Shadower.SMOKE_SCREEN, 30),
                e(Shadower.NINJA_AMBUSH, 30), e(Shadower.TAUNT, 30), e(Shadower.HEROS_WILL, 5));

        // ===== Pirate - the 1st-job attack follows the planned branch: Flash Fist (brawler) or Double Shot (gun) =====
        put(500, v(Pirate.FLASH_FIST, Pirate.DOUBLE_SHOT, 20), e(Pirate.SOMERSAULT_KICK, 20),
                e(Pirate.BULLET_TIME, 20), e(Pirate.DASH, 10), v(Pirate.DOUBLE_SHOT, Pirate.FLASH_FIST, 20));
        put(510, e(Brawler.KNUCKLER_MASTERY, 20), e(Brawler.CORKSCREW_BLOW, 20), e(Brawler.KNUCKLER_BOOSTER, 20),
                e(Brawler.IMPROVE_MAX_HP, 10), e(Brawler.BACK_SPIN_BLOW, 20), e(Brawler.DOUBLE_UPPERCUT, 20),
                e(Brawler.MP_RECOVERY, 10), e(Brawler.OAK_BARREL, 10));
        put(520, e(Gunslinger.GUN_MASTERY, 20), e(Gunslinger.INVISIBLE_SHOT, 20), e(Gunslinger.GUN_BOOSTER, 20),
                e(Gunslinger.WINGS, 10), e(Gunslinger.GRENADE, 20), e(Gunslinger.RECOIL_SHOT, 20),
                e(Gunslinger.BLANK_SHOT, 20));
        put(511, e(Marauder.ENERGY_CHARGE, 40), e(Marauder.TRANSFORMATION, 20), e(Marauder.SHOCKWAVE, 30),
                e(Marauder.ENERGY_BLAST, 30), e(Marauder.STUN_MASTERY, 20), e(Marauder.ENERGY_DRAIN, 20));
        put(521, e(Outlaw.BURST_FIRE, 20), e(Outlaw.FLAME_THROWER, 30), e(Outlaw.ICE_SPLITTER, 30),
                e(Outlaw.OCTOPUS, 30), e(Outlaw.GAVIOTA, 30), e(Outlaw.HOMING_BEACON, 30));
        put(512, e(Buccaneer.BARRAGE, 30), e(Buccaneer.DRAGON_STRIKE, 30), e(Buccaneer.ENERGY_ORB, 30),
                e(Buccaneer.SUPER_TRANSFORMATION, 20), e(Buccaneer.MAPLE_WARRIOR, 30), e(Buccaneer.SPEED_INFUSION, 20),
                e(Buccaneer.DEMOLITION, 30), e(Buccaneer.SNATCH, 30), e(Buccaneer.TIME_LEAP, 30),
                e(Buccaneer.PIRATES_RAGE, 20));
        put(522, e(Corsair.RAPID_FIRE, 30), e(Corsair.BATTLE_SHIP, 30), e(Corsair.BATTLESHIP_CANNON, 30),
                e(Corsair.BATTLESHIP_TORPEDO, 30), e(Corsair.ELEMENTAL_BOOST, 30), e(Corsair.MAPLE_WARRIOR, 30),
                e(Corsair.BULLSEYE, 30), e(Corsair.WRATH_OF_THE_OCTOPI, 30), e(Corsair.AERIAL_STRIKE, 30),
                e(Corsair.HYPNOTIZE, 20), e(Corsair.HEROS_WILL, 5));

        // ===== Cygnus Knights (stock: three advancements, no 4th job) =====
        put(1100, e(DawnWarrior.POWER_STRIKE, 1), e(DawnWarrior.SLASH_BLAST, 20), e(DawnWarrior.POWER_STRIKE, 20),
                e(DawnWarrior.MAX_HP_INCREASE, 10), e(DawnWarrior.SOUL, 10), e(DawnWarrior.IRON_BODY, 10));
        put(1110, e(DawnWarrior.SWORD_MASTERY, 20), e(DawnWarrior.SWORD_BOOSTER, 20), e(DawnWarrior.SOUL_BLADE, 20),
                e(DawnWarrior.FINAL_ATTACK, 30), e(DawnWarrior.RAGE, 20), e(DawnWarrior.SOUL_RUSH, 10));
        put(1111, e(DawnWarrior.COMBO, 20), e(DawnWarrior.BRANDISH, 20), e(DawnWarrior.ADVANCED_COMBO, 20),
                e(DawnWarrior.SOUL_CHARGE, 20), e(DawnWarrior.SOUL_DRIVER, 20), e(DawnWarrior.PANIC, 20),
                e(DawnWarrior.COMA, 20), e(DawnWarrior.INCREASED_MP_RECOVERY, 20));
        put(1200, e(BlazeWizard.MAGIC_CLAW, 20), e(BlazeWizard.MAGIC_GUARD, 10), e(BlazeWizard.FLAME, 10),
                e(BlazeWizard.INCREASING_MAX_MP, 10), e(BlazeWizard.MAGIC_ARMOR, 10));
        put(1210, e(BlazeWizard.TELEPORT, 1), e(BlazeWizard.FIRE_ARROW, 20), e(BlazeWizard.FIRE_PILLAR, 20),
                e(BlazeWizard.MEDITATION, 20), e(BlazeWizard.SPELL_BOOSTER, 20), e(BlazeWizard.TELEPORT, 15),
                e(BlazeWizard.SLOW, 20), e(BlazeWizard.ELEMENTAL_RESET, 20));
        put(1211, e(BlazeWizard.FIRE_STRIKE, 20), e(BlazeWizard.ELEMENT_AMPLIFICATION, 20), e(BlazeWizard.IFRIT, 20),
                e(BlazeWizard.METEOR_SHOWER, 20), e(BlazeWizard.FLAME_GEAR, 20), e(BlazeWizard.SEAL, 20),
                e(BlazeWizard.ELEMENTAL_RESISTANCE, 20));
        put(1300, e(WindArcher.DOUBLE_SHOT, 20), e(WindArcher.CRITICAL_SHOT, 20), e(WindArcher.EYE_OF_AMAZON, 8),
                e(WindArcher.FOCUS, 10), e(WindArcher.STORM, 10));
        put(1310, e(WindArcher.BOW_MASTERY, 20), e(WindArcher.STORM_BREAK, 20), e(WindArcher.BOW_BOOSTER, 20),
                e(WindArcher.SOUL_ARROW, 20), e(WindArcher.THRUST, 20), e(WindArcher.FINAL_ATTACK, 20),
                e(WindArcher.WIND_WALK, 20));
        put(1311, e(WindArcher.HURRICANE, 20), e(WindArcher.BOW_EXPERT, 20), e(WindArcher.STRAFE, 20),
                e(WindArcher.ARROW_RAIN, 20), e(WindArcher.EAGLE_EYE, 20), e(WindArcher.PUPPET, 20),
                e(WindArcher.WIND_PIERCING, 20), e(WindArcher.WIND_SHOT, 20));
        put(1400, e(NightWalker.LUCKY_SEVEN, 20), e(NightWalker.KEEN_EYES, 8), e(NightWalker.NIMBLE_BODY, 10),
                e(NightWalker.DARKNESS, 10), e(NightWalker.DARK_SIGHT, 10), e(NightWalker.DISORDER, 10));
        put(1410, e(NightWalker.CLAW_MASTERY, 20), e(NightWalker.CRITICAL_THROW, 20), e(NightWalker.CLAW_BOOSTER, 20),
                e(NightWalker.HASTE, 20), e(NightWalker.FLASH_JUMP, 20), e(NightWalker.VAMPIRE, 20),
                e(NightWalker.VANISH, 20));
        put(1411, e(NightWalker.TRIPLE_THROW, 20), e(NightWalker.AVENGER, 20), e(NightWalker.SHADOW_PARTNER, 20),
                e(NightWalker.ALCHEMIST, 20), e(NightWalker.VENOM, 20), e(NightWalker.POISON_BOMB, 20),
                e(NightWalker.SHADOW_WEB, 20));
        put(1500, e(ThunderBreaker.SOMERSAULT_KICK, 20), e(ThunderBreaker.FIRST_STRIKE, 20),
                e(ThunderBreaker.LIGHTNING, 20), e(ThunderBreaker.QUICK_MOTION, 20), e(ThunderBreaker.DASH, 10));
        put(1510, e(ThunderBreaker.KNUCKLER_MASTERY, 20), e(ThunderBreaker.CORKSCREW_BLOW, 20),
                e(ThunderBreaker.ENERGY_CHARGE, 40), e(ThunderBreaker.KNUCKLER_BOOSTER, 20),
                e(ThunderBreaker.LIGHTNING_CHARGE, 20), e(ThunderBreaker.IMPROVE_MAX_HP, 10),
                e(ThunderBreaker.ENERGY_BLAST, 20));
        put(1511, e(ThunderBreaker.BARRAGE, 20), e(ThunderBreaker.SHARK_WAVE, 20),
                e(ThunderBreaker.TRANSFORMATION, 20), e(ThunderBreaker.SPARK, 20), e(ThunderBreaker.SPEED_INFUSION, 20),
                e(ThunderBreaker.CRITICAL_PUNCH, 20), e(ThunderBreaker.SHOCK_WAVE, 20),
                e(ThunderBreaker.ENERGY_DRAIN, 20));

        // ===== Aran (the hidden combo skills are granted by the client-side combo system, never by SP) =====
        put(2100, e(Aran.DOUBLE_SWING, 20), e(Aran.COMBO_ABILITY, 10), e(Aran.POLEARM_BOOSTER, 20),
                e(Aran.COMBAT_STEP, 15));
        put(2110, e(Aran.POLEARM_MASTERY, 20), e(Aran.TRIPLE_SWING, 20), e(Aran.FINAL_CHARGE, 20),
                e(Aran.COMBO_SMASH, 20), e(Aran.COMBO_DRAIN, 20), e(Aran.BODY_PRESSURE, 20));
        put(2111, e(Aran.FULL_SWING, 30), e(Aran.COMBO_CRITICAL, 30), e(Aran.SNOW_CHARGE, 30),
                e(Aran.FINAL_TOSS, 30), e(Aran.ROLLING_SPIN, 20), e(Aran.COMBO_FENRIR, 30),
                e(Aran.SMART_KNOCKBACK, 20));
        put(2112, e(Aran.OVER_SWING, 30), e(Aran.HIGH_MASTERY, 30), e(Aran.MAPLE_WARRIOR, 30),
                e(Aran.COMBO_TEMPEST, 30), e(Aran.FINAL_BLOW, 30), e(Aran.FREEZE_STANDING, 30),
                e(Aran.HIGH_DEFENSE, 30), e(Aran.COMBO_BARRIER, 30), e(Aran.HEROS_WILL, 5));
    }
}
