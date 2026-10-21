package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Bandit;
import constants.skills.Bishop;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Buccaneer;
import constants.skills.Corsair;
import constants.skills.ChiefBandit;
import constants.skills.Cleric;
import constants.skills.Crossbowman;
import constants.skills.Crusader;
import constants.skills.DawnWarrior;
import constants.skills.DragonKnight;
import constants.skills.FPArchMage;
import constants.skills.FPMage;
import constants.skills.FPWizard;
import constants.skills.Gunslinger;
import constants.skills.Hunter;
import constants.skills.ILArchMage;
import constants.skills.ILMage;
import constants.skills.ILWizard;
import constants.skills.Magician;
import constants.skills.Marauder;
import constants.skills.Marksman;
import constants.skills.NightWalker;
import constants.skills.Outlaw;
import constants.skills.Paladin;
import constants.skills.Pirate;
import constants.skills.Priest;
import constants.skills.Ranger;
import constants.skills.Rogue;
import constants.skills.Shadower;
import constants.skills.Sniper;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import constants.skills.WindArcher;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.magic;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.magicAoe;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.melee;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.meleeAoe;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.meleeMulti;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.ranged;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.rangedAoe;
import static soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile.shotAoe;
import static soloMapling.ArtificialPlayer.MapleRootSkills.*;

/*
 * Per-job attack registry is original; the bot-combat concept is inspired by GreenCatMS. Credit: NutNNut for the idea.
 * Per-job bot attack registry, on MapleRoot's skill set (its Skill.wz; see docs/real-bots/mapleroot-skills.md).
 * Each job lists up to three attacks it introduces - a single-target one, a sustained AoE it mobs with,
 * and (for a few 3rd/4th-job classes) a throttled ultimate; a bot inherits the most advanced of each
 * across its job lineage. The split matters at runtime: while the ultimate (Dragon Roar / Genesis /
 * Blizzard / Meteor Shower, or a custom skill with a WZ cooltime) is on its long cooldown, the bot keeps
 * mobbing with the sustained AoE instead of dropping to single-target (BotAttackDriver's AUTO).
 *
 * MapleRoot has no second warrior weapon forms (axe, blunt weapon, pole-arm), Big Bang, Heaven's Hammer
 * or Assassinate, and adds custom 4th-job attacks (Raging Blow, Rising Rage, Dark Impale, Megiddo Flame,
 * ...) and a Cygnus 4th job; those are in the table. Weapon picks the 1st-job basic of thieves (claw vs
 * dagger) and pirates (knuckle vs gun). Beginners fall through to a plain skill-0 weapon swing (resolve's
 * final fallback). Damage is fixed per line, no stat math.
 *
 * Route: MAGIC for spells (their WZ level data has "mad"), RANGED for a projectile class's thrown/shot
 * skills (bow, crossbow, claw, gun), CLOSE for everything else. A ranged class's skills without a
 * projectile (Showdown, Ninja Storm, Monsoon, Dark Omen, ...) and keydown skills (Corkscrew Blow,
 * Grenade, Poison Bomb, Rapid Fire, Eight Legs Easton) are left out until checked against a client: the
 * packet type a player's client sends for them is not known here.
 */
public final class BotAttackConfig {

    /*
     * A job's introduced attacks; any field may be null (meaning "inherit from the lineage").
     * single = single-target, aoe = sustained mobbing swing, ultimate = throttled full-map nuke.
     */
    public record JobAttacks(BotAttackProfile single, BotAttackProfile aoe, BotAttackProfile ultimate,
                             List<Extra> extras) {
        public JobAttacks(BotAttackProfile single, BotAttackProfile aoe, BotAttackProfile ultimate) {
            this(single, aoe, ultimate, List.of());
        }
    }

    /*
     * A rotation skill a player weaves between the main attacks (ticket 32): used whenever it is ready - out of its
     * WZ cooltime, at least everyMs since the bot last used it and, for an Aran combo finisher, with comboCost combo
     * held (spent on use) - and a mob is in its reach. Each job's extras are tried in the order listed.
     */
    public record Extra(BotAttackProfile profile, long everyMs, int comboCost) {}

    private static final Map<Job, JobAttacks> BY_JOB = new EnumMap<>(Job.class);
    private static final Map<Job, List<Extra>> EXTRAS = new EnumMap<>(Job.class);

    // 1st-job rogue and pirate basics - chosen by weapon, since the 1st job alone is ambiguous.
    private static final BotAttackProfile ROGUE_CLAW = ranged(Rogue.LUCKY_SEVEN, 2);
    private static final BotAttackProfile ROGUE_DAGGER = melee(Rogue.DOUBLE_STAB, 2);
    private static final BotAttackProfile PIRATE_GUN = ranged(Pirate.DOUBLE_SHOT, 2);
    private static final BotAttackProfile PIRATE_KNUCKLE = melee(Pirate.FLASH_FIST, 1);

    // Dragon Knight's main attack, used as both single and mob swing.
    private static final BotAttackProfile CRUSHER = meleeMulti(DragonKnight.SPEAR_CRUSHER, 3, 3);

    static {
        // ===== Warrior (melee, sword or spear) =====
        put(Job.WARRIOR,      melee(POWER_STRIKE, 1),                 meleeAoe(SLASH_BLAST, 1));
        put(Job.CRUSADER,     melee(Crusader.SWORD_PANIC, 1),         meleeAoe(Crusader.SWORD_COMA, 1));
        put(Job.WHITEKNIGHT,  melee(WhiteKnight.CHARGE_BLOW, 1),      null); // inherits Slash Blast
        // Crusher hits 3 mobs; players main it for mobbing, so the bot uses it in both the single and aoe
        // slots; Dragon Roar is the throttled full-map ultimate.
        put(Job.DRAGONKNIGHT, CRUSHER, CRUSHER, meleeAoe(DragonKnight.DRAGON_ROAR, 1));
        put(Job.HERO,         meleeMulti(RAGING_BLOW, 3, 5),          meleeAoe(RISING_RAGE, 8));
        put(Job.PALADIN,      melee(Paladin.BLAST, 1),                meleeAoe(HOLY_FLAME_HAMMER, 5));
        put(Job.DARKKNIGHT,   null,                                   meleeAoe(DARK_IMPALE, 4),
                meleeAoe(LA_MANCHA_SPEAR, 8)); // single inherits Crusher

        // ===== Magician (magic) =====
        put(Job.MAGICIAN,     magic(Magician.MAGIC_CLAW, 2),  null);
        put(Job.FP_WIZARD,    magic(FPWizard.FIRE_ARROW, 1),  null);
        put(Job.IL_WIZARD,    magic(ILWizard.COLD_BEAM, 1),   magicAoe(ILWizard.THUNDERBOLT, 1));
        put(Job.CLERIC,       magic(Cleric.HOLY_ARROW, 1),    null);
        put(Job.FP_MAGE,      null,                           magicAoe(FPMage.EXPLOSION, 1)); // inherits Fire Arrow single
        put(Job.IL_MAGE,      magic(ILMage.THUNDER_SPEAR, 1), magicAoe(ILMage.ICE_STRIKE, 1));
        put(Job.PRIEST,       null,                           magicAoe(Priest.SHINING_RAY, 1)); // inherits Holy Arrow single
        // 4th-job mages: the full-map ultimate (Meteor Shower / Blizzard / Genesis) has its own slot. The
        // custom single-target spells (Megiddo Flame, Ice Storm) carry WZ cooltimes; while one cools the
        // bot casts its AoE instead. Bishop has no custom attack and mobs with Priest's Shining Ray.
        put(Job.FP_ARCHMAGE,  magic(MEGIDDO_FLAME, 7),        magicAoe(INFERNO_AURA, 4), magicAoe(FPArchMage.METEOR_SHOWER, 1));
        put(Job.IL_ARCHMAGE,  magic(ICE_STORM, 4),            magicAoe(LIGHTNING_BURST, 4), magicAoe(ILArchMage.BLIZZARD, 1));
        put(Job.BISHOP,       magic(Bishop.ANGEL_RAY, 1),     null, magicAoe(Bishop.GENESIS, 1));

        // ===== Bowman (ranged) - weapon picks the projectile (bow->arrow, crossbow->bolt) =====
        put(Job.BOWMAN,       ranged(Archer.DOUBLE_SHOT, 2),  null);
        put(Job.HUNTER,       null,                           rangedAoe(Hunter.ARROW_BOMB, 1));     // inherits Double Shot
        put(Job.CROSSBOWMAN,  null,                           rangedAoe(Crossbowman.IRON_ARROW, 1));
        put(Job.RANGER,       ranged(Ranger.STRAFE, 4),       rangedAoe(Ranger.ARROW_RAIN, 1));
        put(Job.SNIPER,       ranged(Sniper.STRAFE, 4),       rangedAoe(Sniper.ARROW_ERUPTION, 1));
        // Hurricane is a keydown skill: a player holds it - one prepare, a stream of shots, a release - and the bot
        // fires it the same way (BotAttackDriver's keydown burst), not as one plain shot.
        put(Job.BOWMASTER,    ranged(Bowmaster.HURRICANE, 3), rangedAoe(ARROW_STREAM, 6));
        put(Job.MARKSMAN,     ranged(Marksman.SNIPE, 1),      rangedAoe(ANCIENT_ASTRA, 6), rangedAoe(HIGH_SPEED_SHOT, 8));

        // ===== Thief - claw (stars/ranged) vs dagger (melee); 1st-job rogue seeded in resolve =====
        put(Job.HERMIT,       null,                           rangedAoe(SHADOW_SLASH, 6));          // claw: inherits Lucky Seven
        put(Job.NIGHTLORD,    ranged(QUADRUPLE_THROW, 4),     null);                                // inherits Shadow Slash
        put(Job.BANDIT,       melee(Bandit.SAVAGE_BLOW, 6),   null);
        put(Job.CHIEFBANDIT,  null,                           meleeAoe(ChiefBandit.BAND_OF_THIEVES, 1)); // single inherits Savage Blow
        put(Job.SHADOWER,     meleeMulti(PHANTOM_BLOW, 4, 6), meleeAoe(Shadower.BOOMERANG_STEP, 2), meleeAoe(SHADOW_VEIL, 5));

        // ===== Pirate - knuckle (melee) vs gun (ranged); 1st-job basic seeded in resolve =====
        put(Job.PIRATE,       null,                           meleeAoe(Pirate.SOMERSAULT_KICK, 1));
        put(Job.BRAWLER,      melee(Brawler.DOUBLE_UPPERCUT, 2), meleeAoe(Brawler.BACK_SPIN_BLOW, 1));
        put(Job.GUNSLINGER,   null,                           rangedAoe(Gunslinger.INVISIBLE_SHOT, 1)); // inherits Double Shot
        put(Job.MARAUDER,     null,                           meleeAoe(Marauder.ENERGY_BLAST, 1), meleeAoe(POWER_DRILL, 4));
        put(Job.OUTLAW,       ranged(Outlaw.BURST_FIRE, 3),   rangedAoe(Outlaw.FLAME_THROWER, 1));
        // MapleRoot's Buccaneer (no Energy Charge / Barrage / Demolition): Octopunch for one, Dragon Strike for a pack,
        // Power Unity as the cooltime nuke, Double Blast (cooltime) and Energy Orb (a shot from the knuckle) woven in.
        put(Job.BUCCANEER,    meleeMulti(OCTOPUNCH, 2, 8),    meleeAoe(Buccaneer.DRAGON_STRIKE, 4), meleeAoe(POWER_UNITY, 5));
        extra(Job.BUCCANEER,  new Extra(meleeAoe(DOUBLE_BLAST, 4), 6_000, 0),
                new Extra(shotAoe(Buccaneer.ENERGY_ORB, 6, 6), 12_000, 0));
        // Corsair: Burst Fire / Flamethrower inherited, Brain Scrambler the cooltime nuke; Rapid Fire (keydown burst),
        // Eight Legs Easton and Aerial Strike woven in.
        put(Job.CORSAIR,      null,                           null, rangedAoe(BRAIN_SCRAMBLER, 5)); // inherits Burst Fire / Flamethrower
        extra(Job.CORSAIR,    new Extra(ranged(Corsair.RAPID_FIRE, 1), 10_000, 0),
                new Extra(rangedAoe(EIGHT_LEGS_EASTON, 6), 9_000, 0),
                new Extra(meleeAoe(Corsair.AERIAL_STRIKE, 6), 11_000, 0));

        // ===== Cygnus Knights (4th job is MapleRoot's) =====
        put(Job.DAWNWARRIOR1, melee(DawnWarrior.POWER_STRIKE, 1),  meleeAoe(DawnWarrior.SLASH_BLAST, 1));
        // Soul Blade leaves the sword as a shot (the client's shoot route, no bullet): sent as a swing, no blade flew.
        put(Job.DAWNWARRIOR2, null,                                shotAoe(DawnWarrior.SOUL_BLADE, 3, 1));
        put(Job.DAWNWARRIOR3, meleeMulti(DawnWarrior.BRANDISH, 3, 2), meleeAoe(DawnWarrior.SOUL_DRIVER, 4));
        put(Job.DAWNWARRIOR4, meleeMulti(MOON_DANCER, 2, 4),       meleeAoe(IMPALING_RAYS, 6), meleeAoe(SOLAR_PIERCE, 6));
        put(Job.BLAZEWIZARD1, magic(BlazeWizard.MAGIC_CLAW, 2),    null);
        put(Job.BLAZEWIZARD2, magic(BlazeWizard.FIRE_ARROW, 1),    magicAoe(BlazeWizard.FIRE_PILLAR, 1));
        put(Job.BLAZEWIZARD3, null,                                magicAoe(BlazeWizard.FIRE_STRIKE, 4),
                magicAoe(BlazeWizard.METEOR_SHOWER, 2));
        put(Job.BLAZEWIZARD4, magic(DRAGON_FIREBALLS, 4),          magicAoe(FLAME_BARRIER, 4), magicAoe(TOWERING_INFERNO, 7));
        put(Job.WINDARCHER1,  ranged(WindArcher.DOUBLE_SHOT, 2),   null);
        put(Job.WINDARCHER3,  ranged(WindArcher.STRAFE, 4),        rangedAoe(WindArcher.ARROW_RAIN, 3));
        put(Job.WINDARCHER4,  ranged(SPIRALING_VORTEX, 6),         rangedAoe(SONG_OF_HEAVEN, 6));
        put(Job.NIGHTWALKER1, ranged(NightWalker.LUCKY_SEVEN, 2),  null);
        // MapleRoot's client DLL sends the Night Walker's Avenger on the melee route (its custom-skill table).
        put(Job.NIGHTWALKER3, ranged(NW_TRIPLE_THROW, 3),          meleeAoe(NightWalker.AVENGER, 6));
        put(Job.NIGHTWALKER4, ranged(QUINTUPLE_STAR, 5),           null);                          // inherits Avenger
        put(Job.THUNDERBREAKER1, melee(ThunderBreaker.FIRST_STRIKE, 1), meleeAoe(ThunderBreaker.SOMERSAULT_KICK, 1));
        put(Job.THUNDERBREAKER2, null,                             meleeAoe(ThunderBreaker.ENERGY_BLAST, 1));
        // Shark Wave leaves the knuckle as a shot (the client sends it on the shoot route, no bullet), so its shark is
        // drawn; sent as a swing it was a plain punch.
        put(Job.THUNDERBREAKER3, melee(ThunderBreaker.BARRAGE, 6), shotAoe(ThunderBreaker.SHARK_WAVE, 6, 3));
        put(Job.THUNDERBREAKER4, melee(ANNIHILATE, 4),             meleeAoe(THUNDERBOLT, 5), meleeAoe(TYPHOON, 5));
        extra(Job.THUNDERBREAKER4, new Extra(meleeAoe(WHALE_RISING, 8), 8_000, 0),
                new Extra(shotAoe(ThunderBreaker.SHARK_WAVE, 6, 3), 5_000, 0),
                new Extra(melee(ThunderBreaker.BARRAGE, 6), 7_000, 0));

        // ===== Aran =====
        // The double/triple swings are combo commands; with Full Swing (3rd job) or Overswing (4th) learned the client
        // sends them as the hidden 2111000x / 2112000x ids. Every mob hit adds to the combo counter (BotAranCombo);
        // the finishers spend it - Combo Smash 10, Fenrir 30, Tempest 200, as RangedAttackHandler charges a player -
        // and leave as shots from the pole-arm (the client's shoot route, no bullet).
        put(Job.ARAN1,        melee(Aran.DOUBLE_SWING, 1),    null);
        put(Job.ARAN2,        meleeMulti(Aran.TRIPLE_SWING, 3, 1), meleeAoe(Aran.FINAL_CHARGE, 1));
        extra(Job.ARAN2,      new Extra(shotAoe(Aran.COMBO_SMASH, 6, 2), 0, 10));
        put(Job.ARAN3,        meleeMulti(Aran.HIDDEN_FULL_TRIPLE, 3, 1).heldAs(Aran.FULL_SWING),
                meleeAoe(Aran.ROLLING_SPIN, 4));
        extra(Job.ARAN3,      new Extra(shotAoe(Aran.COMBO_FENRIR, 6, 6), 0, 30),
                new Extra(shotAoe(Aran.COMBO_SMASH, 6, 2), 0, 10),
                new Extra(meleeAoe(Aran.FINAL_TOSS, 1), 6_000, 0),
                new Extra(meleeMulti(Aran.HIDDEN_FULL_DOUBLE, 3, 2).heldAs(Aran.FULL_SWING), 4_000, 0));
        put(Job.ARAN4,        meleeMulti(Aran.HIDDEN_OVER_TRIPLE, 3, 5).heldAs(Aran.OVER_SWING),
                meleeAoe(Aran.FINAL_BLOW, 8));
        extra(Job.ARAN4,      new Extra(shotAoe(Aran.COMBO_TEMPEST, 15, 5), 0, 200),
                new Extra(shotAoe(Aran.COMBO_FENRIR, 6, 6), 0, 30),
                new Extra(shotAoe(Aran.COMBO_SMASH, 6, 2), 0, 10),
                new Extra(meleeMulti(Aran.HIDDEN_OVER_DOUBLE, 3, 4).heldAs(Aran.OVER_SWING), 4_000, 0),
                new Extra(meleeAoe(Aran.ROLLING_SPIN, 4), 7_000, 0));
    }

    private BotAttackConfig() {}

    // A job with no ultimate leaves that slot null; the two-profile overload keeps the many
    // single/aoe entries above unchanged.
    private static void put(Job job, BotAttackProfile single, BotAttackProfile aoe) {
        put(job, single, aoe, null);
    }

    private static void put(Job job, BotAttackProfile single, BotAttackProfile aoe, BotAttackProfile ultimate) {
        BY_JOB.put(job, new JobAttacks(single, aoe, ultimate));
    }

    private static void extra(Job job, Extra... extras) {
        EXTRAS.computeIfAbsent(job, k -> new ArrayList<>()).addAll(List.of(extras));
    }

    /*
     * The bot's rotation extras: the most advanced job of its lineage that lists any decides them (a 4th-job Aran
     * weaves the 4th-job set, not the 3rd-job one too), keeping those the bot holds (hasSkill null: all of them).
     */
    static List<Extra> extrasFor(Job job, IntPredicate hasSkill) {
        if (job == null) {
            return List.of();
        }
        Job best = null;
        for (Job j : EXTRAS.keySet()) {
            if (job.isA(j) && (best == null || j.getId() > best.getId())) {
                best = j;
            }
        }
        if (best == null) {
            return List.of();
        }
        List<Extra> out = new ArrayList<>();
        for (Extra e : EXTRAS.get(best)) {
            if (hasSkill == null || hasSkill.test(e.profile().heldSkill)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    /*
     * The bot's effective {single, aoe, ultimate} attacks: the most advanced of each across its
     * lineage (highest job id wins, which is also the highest tier within a branch). Because the
     * three roles resolve independently, a class can carry a mid-tier mob spell inherited from an
     * earlier job (Bishop's Shining Ray, the arch-mages' Ice Strike / Explosion) alongside the
     * ultimate it introduces itself. 1st-job rogues get their basic seeded from the weapon. Any
     * field may be null.
     */
    public static JobAttacks resolve(Job job, WeaponType weapon) {
        return resolve(job, weapon, null);
    }

    /*
     * Real bots (ticket 06): the same resolution, restricted to the attacks the bot actually has. hasSkill answers
     * whether the bot holds a skill. When the bot holds at least one attack of
     * its lineage, each role takes the most advanced attack it holds, so a fresh 4th job keeps mobbing with its
     * 3rd-job skill until it has put SP into the new one; a role it holds nothing for stays empty. A bot that holds
     * none of them - no stored build, a job the build does not cover - falls back to the table as before.
     */
    public static JobAttacks resolve(Job job, WeaponType weapon, IntPredicate hasSkill) {
        if (hasSkill != null && job != null) {
            JobAttacks owned = resolveOwned(job, weapon, hasSkill);
            if (owned != null) {
                return new JobAttacks(owned.single(), owned.aoe(), owned.ultimate(), extrasFor(job, hasSkill));
            }
        }
        JobAttacks table = resolveTable(job, weapon);
        return new JobAttacks(table.single(), table.aoe(), table.ultimate(), extrasFor(job, null));
    }

    private static boolean holds(BotAttackProfile p, IntPredicate hasSkill) {
        return p != null && p.skillId != 0 && hasSkill.test(p.heldSkill);
    }

    /* The owned-skill resolution, or null when the bot holds no attack of its lineage at all. */
    private static JobAttacks resolveOwned(Job job, WeaponType weapon, IntPredicate hasSkill) {
        BotAttackProfile single = null, aoe = null, ultimate = null;
        int bestSingleId = -1, bestAoeId = -1, bestUltId = -1;
        for (Map.Entry<Job, JobAttacks> entry : BY_JOB.entrySet()) {
            Job j = entry.getKey();
            if (!job.isA(j)) {
                continue;
            }
            JobAttacks a = entry.getValue();
            if (holds(a.single(), hasSkill) && j.getId() > bestSingleId) {
                single = a.single();
                bestSingleId = j.getId();
            }
            if (holds(a.aoe(), hasSkill) && j.getId() > bestAoeId) {
                aoe = a.aoe();
                bestAoeId = j.getId();
            }
            if (holds(a.ultimate(), hasSkill) && j.getId() > bestUltId) {
                ultimate = a.ultimate();
                bestUltId = j.getId();
            }
        }
        if (single == null && (job.isA(Job.THIEF) || job.isA(Job.PIRATE))) {
            BotAttackProfile preferred = firstJobBasic(job, weapon);
            BotAttackProfile other = otherFirstJobBasic(preferred);
            single = holds(preferred, hasSkill) ? preferred : holds(other, hasSkill) ? other : null;
        }
        if (single == null && aoe == null && ultimate == null) {
            return null;
        }
        return new JobAttacks(single, aoe, ultimate);
    }

    private static JobAttacks resolveTable(Job job, WeaponType weapon) {
        if (job == null) {
            return new JobAttacks(null, null, null);
        }

        BotAttackProfile single = null, aoe = null, ultimate = null;
        int bestSingleId = -1, bestAoeId = -1, bestUltId = -1;
        for (Map.Entry<Job, JobAttacks> entry : BY_JOB.entrySet()) {
            Job j = entry.getKey();
            if (!job.isA(j)) {
                continue;
            }
            JobAttacks a = entry.getValue();
            if (a.single() != null && j.getId() > bestSingleId) {
                single = a.single();
                bestSingleId = j.getId();
            }
            if (a.aoe() != null && j.getId() > bestAoeId) {
                aoe = a.aoe();
                bestAoeId = j.getId();
            }
            if (a.ultimate() != null && j.getId() > bestUltId) {
                ultimate = a.ultimate();
                bestUltId = j.getId();
            }
        }

        // 1st-job rogues and pirates have no single in the table: their weapon decides the basic attack.
        // Their branches inherit it the same way until they introduce one of their own.
        if (single == null && (job.isA(Job.THIEF) || job.isA(Job.PIRATE))) {
            single = firstJobBasic(job, weapon);
        }

        // Beginners (job 0) and any job with no registered attack fall back
        // to a plain skill-0 weapon swing. This is what lets a sub-level-10 beginner bot fight with
        // the sword the decorator gives it - the basic attack it would make before any job skills.
        if (single == null && aoe == null && ultimate == null) {
            single = BotAttackProfile.basicSwing();
        }
        return new JobAttacks(single, aoe, ultimate);
    }

    /* The 1st-job basic a thief or pirate swings with this weapon (its branch's weapon when it has none). */
    private static BotAttackProfile firstJobBasic(Job job, WeaponType weapon) {
        if (job.isA(Job.THIEF)) {
            return weapon == WeaponType.CLAW || (weapon == null && job.isA(Job.ASSASSIN)) ? ROGUE_CLAW : ROGUE_DAGGER;
        }
        return weapon == WeaponType.GUN || (weapon == null && job.isA(Job.GUNSLINGER)) ? PIRATE_GUN : PIRATE_KNUCKLE;
    }

    private static BotAttackProfile otherFirstJobBasic(BotAttackProfile basic) {
        if (basic == ROGUE_CLAW) {
            return ROGUE_DAGGER;
        }
        if (basic == ROGUE_DAGGER) {
            return ROGUE_CLAW;
        }
        return basic == PIRATE_GUN ? PIRATE_KNUCKLE : PIRATE_GUN;
    }

    /* Every attack profile in the table, for tests that check the ids against the server's skill data. */
    static java.util.List<BotAttackProfile> allProfiles() {
        java.util.List<BotAttackProfile> all = new java.util.ArrayList<>(
                java.util.List.of(ROGUE_CLAW, ROGUE_DAGGER, PIRATE_GUN, PIRATE_KNUCKLE));
        for (JobAttacks a : BY_JOB.values()) {
            for (BotAttackProfile p : new BotAttackProfile[]{a.single(), a.aoe(), a.ultimate()}) {
                if (p != null) {
                    all.add(p);
                }
            }
        }
        for (List<Extra> extras : EXTRAS.values()) {
            for (Extra e : extras) {
                all.add(e.profile());
            }
        }
        return all;
    }
}
