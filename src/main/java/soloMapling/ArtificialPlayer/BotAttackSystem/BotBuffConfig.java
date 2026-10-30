package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.BlazeWizard;
import constants.skills.Cleric;
import constants.skills.Crossbowman;
import constants.skills.DawnWarrior;
import constants.skills.FPWizard;
import constants.skills.Fighter;
import constants.skills.Hunter;
import constants.skills.ILWizard;
import constants.skills.Magician;
import constants.skills.NightWalker;
import constants.skills.Page;
import constants.skills.Spearman;
import constants.skills.Crusader;
import constants.skills.WhiteKnight;
import constants.skills.DragonKnight;
import constants.skills.Priest;
import constants.skills.Hermit;
import constants.skills.ChiefBandit;
import constants.skills.Hero;
import constants.skills.Paladin;
import constants.skills.DarkKnight;
import constants.skills.FPArchMage;
import constants.skills.ILArchMage;
import constants.skills.Bishop;
import constants.skills.Bowmaster;
import constants.skills.Marksman;
import constants.skills.NightLord;
import constants.skills.Shadower;
import constants.skills.ThunderBreaker;
import constants.skills.WindArcher;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotProgression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static soloMapling.ArtificialPlayer.MapleRootSkills.BW_FIRE_RUSH;
import static soloMapling.ArtificialPlayer.MapleRootSkills.FP_FIRE_RUSH;
import static soloMapling.ArtificialPlayer.MapleRootSkills.IL_FIRE_RUSH;
import static soloMapling.ArtificialPlayer.MapleRootSkills.IRON_BODY;
import static soloMapling.ArtificialPlayer.MapleRootSkills.JOLLY_ROGER;
import static soloMapling.ArtificialPlayer.MapleRootSkills.PRIEST_FIRE_RUSH;
import static soloMapling.ArtificialPlayer.MapleRootSkills.WA_SHARP_EYES;

/*
 * Bot-buff concept inspired by GreenCatMS; this per-job registry and the cosmetic (no stat/MP/cooldown) approach are original. Credit: NutNNut for the idea.
 * Per-job bot buff registry, on MapleRoot's skill set. Each entry lists only the buffs gained at that
 * advancement; a bot's full buff set is the union over every job in its lineage (so a Priest inherits
 * Magician + Cleric + Priest buffs). Keyed by job rather than class branch because a Cleric
 * and an I/L Wizard are both Magicians yet keep different buffs.
 *
 * MapleRoot has no boosters (weapon or spell), so none are listed; its 3rd-job mages get Fire Rush (a
 * one-level critical-rate buff on the old Seal / Doom ids) instead. A real bot (one BotProgression
 * keeps a build for) casts only the buffs it holds - buffsFor(Character); the bare table is what a bot
 * without a build shows.
 */
public final class BotBuffConfig {

    // Each Job -> the buff skill ids it ADDS at that advancement (inherited ones
    // come from the lineage walk in buffsForJob). EnumMap iterates in enum-decl
    // order, so lower-tier buffs naturally resolve before higher-tier ones.
    private static final Map<Job, int[]> BUFFS_BY_JOB = new EnumMap<>(Job.class);

    static {
        // ---- 1st job ----
        put(Job.WARRIOR,  IRON_BODY);                                  // 1001003 - W.Def up
        put(Job.MAGICIAN, Magician.MAGIC_GUARD, Magician.MAGIC_ARMOR); // 2001002 / 2001003
        put(Job.BOWMAN,   Archer.FOCUS);                               // 3001003 - acc/avoid up
        // THIEF 1st job: only Dark Sight (hides the bot) -> intentionally none.
        // PIRATE 1st job: none yet.

        // ---- 2nd job (only what each job ADDS; 1st-job buffs come via lineage) ----
        put(Job.FIGHTER,  Fighter.RAGE, Fighter.POWER_GUARD);          // 1101006 (party atk), 1101007
        put(Job.PAGE,     Page.POWER_GUARD);                           // 1201007
        put(Job.SPEARMAN, Spearman.IRON_WILL, Spearman.HYPER_BODY);    // 1301006, 1301007 (party)
        put(Job.FP_WIZARD, FPWizard.MEDITATION);                       // 2101001 (party m.atk)
        put(Job.IL_WIZARD, ILWizard.MEDITATION);                       // 2201001 (party m.atk)
        put(Job.CLERIC,    Cleric.BLESS, Cleric.INVINCIBLE);           // 2301004 (party), 2301003
        put(Job.HUNTER,      Hunter.SOUL_ARROW);                       // 3101004
        put(Job.CROSSBOWMAN, Crossbowman.SOUL_ARROW);                  // 3201004
        put(Job.ASSASSIN, Assassin.HASTE);                             // 4101004 (party)
        put(Job.BANDIT,   Bandit.HASTE);                               // 4201003 (party)

        // ---- 3rd job ----
        put(Job.CRUSADER,     Crusader.COMBO);                             // 1111002 - combo (self)
        put(Job.WHITEKNIGHT,  WhiteKnight.SWORD_FIRE_CHARGE);              // 1211003 - elemental charge (self)
        put(Job.DRAGONKNIGHT, DragonKnight.DRAGON_BLOOD);                  // 1311008 - atk up (self)
        put(Job.FP_MAGE,      FP_FIRE_RUSH);                               // 2111004 - critical rate (self)
        put(Job.IL_MAGE,      IL_FIRE_RUSH);                               // 2211004 - critical rate (self)
        put(Job.PRIEST,       Priest.HOLY_SYMBOL, PRIEST_FIRE_RUSH);       // 2311003 - exp/drop (party), 2311005
        put(Job.HERMIT,       Hermit.SHADOW_PARTNER, Hermit.MESO_UP);      // 4111002 (self), 4111001 (party meso)
        put(Job.CHIEFBANDIT,  ChiefBandit.MESO_GUARD);                     // 4211005 - meso shield (self)
        // (Ranger/Sniper inherit Soul Arrow; F/P & I/L 3rd inherit Meditation)

        // ---- 4th job (Maple Warrior for everyone + each class's signature buff) ----
        put(Job.HERO,        Hero.MAPLE_WARRIOR, Hero.ENRAGE);             // 1121000 (party all-stat), 1121010 (self)
        put(Job.PALADIN,     Paladin.MAPLE_WARRIOR, Paladin.SWORD_HOLY_CHARGE); // 1221000 (party), 1221003 (self charge - fires after the inherited fire charge)
        put(Job.DARKKNIGHT,  DarkKnight.MAPLE_WARRIOR, DarkKnight.BERSERK);// 1321000 (party), 1320006 (self)
        put(Job.FP_ARCHMAGE, FPArchMage.MAPLE_WARRIOR, FPArchMage.INFINITY, FPArchMage.MANA_REFLECTION); // 2121000 (party), 2121004, 2121002 (self)
        put(Job.IL_ARCHMAGE, ILArchMage.MAPLE_WARRIOR, ILArchMage.INFINITY, ILArchMage.MANA_REFLECTION); // 2221000 (party), 2221004, 2221002 (self)
        put(Job.BISHOP,      Bishop.MAPLE_WARRIOR, Bishop.HOLY_SHIELD, Bishop.INFINITY, Bishop.MANA_REFLECTION); // 2321000, 2321005 (party), 2321004, 2321002 (self)
        put(Job.BOWMASTER,   Bowmaster.MAPLE_WARRIOR, Bowmaster.SHARP_EYES, Bowmaster.CONCENTRATE); // 3121000 (party), 3121002 (party crit), 3121008 (self atk)
        put(Job.MARKSMAN,    Marksman.MAPLE_WARRIOR, Marksman.SHARP_EYES);  // 3221000 (party), 3221002 (party crit)
        put(Job.NIGHTLORD,   NightLord.MAPLE_WARRIOR, NightLord.SHADOW_STARS); // 4121000 (party), 4121006 (self - infinite stars)
        put(Job.SHADOWER,    Shadower.MAPLE_WARRIOR);                      // 4221000 (party)  [Smoke Screen skipped]
        put(Job.CORSAIR,     JOLLY_ROGER);                                 // 5221018 - MapleRoot custom (self)

        // ---- Cygnus Knights ----
        put(Job.DAWNWARRIOR1,    DawnWarrior.IRON_BODY);                   // 11001001
        put(Job.DAWNWARRIOR2,    DawnWarrior.RAGE);                        // 11101003 (party atk)
        put(Job.BLAZEWIZARD1,    BlazeWizard.MAGIC_GUARD, BlazeWizard.MAGIC_ARMOR); // 12001001 / 12001002
        put(Job.BLAZEWIZARD2,    BlazeWizard.MEDITATION);                  // 12101000 (party m.atk)
        put(Job.BLAZEWIZARD3,    BW_FIRE_RUSH);                            // 12111002 - critical rate (self)
        put(Job.WINDARCHER1,     WindArcher.FOCUS);                        // 13001002
        put(Job.WINDARCHER2,     WindArcher.SOUL_ARROW);                   // 13101003
        put(Job.WINDARCHER4,     WA_SHARP_EYES);                           // 13121008 - MapleRoot's Cygnus 4th job (party crit)
        put(Job.NIGHTWALKER2,    NightWalker.HASTE);                       // 14101003 (party)
        put(Job.NIGHTWALKER3,    NightWalker.SHADOW_PARTNER);              // 14111000 (self)
        put(Job.THUNDERBREAKER3, ThunderBreaker.SPEED_INFUSION);           // 15111005 (party)

        // ---- Aran ----
        put(Job.ARAN4,           Aran.MAPLE_WARRIOR);                      // 21121000 (party)
    }

    private BotBuffConfig() {}

    private static void put(Job job, int... skillIds) {
        BUFFS_BY_JOB.put(job, skillIds);
    }

    /*
     * The buffs this bot casts: its job's list (buffsForJob), and for a real bot only the ones it holds
     * - a bot that has not put SP into Haste yet does not show it. A bot without a build (one of a job
     * the build does not cover) shows its job's table as before.
     */
    // 1.6.2: an owned alt is a real character too: only the buffs it has learned (it never showed the whole kit)
    public static List<Integer> buffsFor(Character bot) {
        if (bot == null) return Collections.emptyList();
        List<Integer> kit = buffsForJob(bot.getJob());
        if (!BotProgression.isReal(bot.getId()) && !bot.isOwnedAlt()) {
            return kit;
        }
        List<Integer> held = new ArrayList<>(kit.size());
        for (int id : kit) {
            if (bot.getSkillLevel(id) > 0) {
                held.add(id);
            }
        }
        return held;
    }

    /*
     * The full buff list for a bot of this job: the union of every configured job
     * in the bot's lineage (Job.isA(Job)), lower-tier first. Empty if the
     * job (and its ancestors) have nothing configured.
     */
    public static List<Integer> buffsForJob(Job job) {
        if (job == null) return Collections.emptyList();

        List<Integer> result = new ArrayList<>();
        for (Map.Entry<Job, int[]> entry : BUFFS_BY_JOB.entrySet()) {
            if (job.isA(entry.getKey())) {
                for (int id : entry.getValue()) {
                    result.add(id);
                }
            }
        }
        return result;
    }

    /* Every buff id in the table, for tests that check the ids against the server's skill data. */
    static List<Integer> allBuffIds() {
        List<Integer> all = new ArrayList<>();
        for (int[] ids : BUFFS_BY_JOB.values()) {
            for (int id : ids) {
                all.add(id);
            }
        }
        return all;
    }
}
