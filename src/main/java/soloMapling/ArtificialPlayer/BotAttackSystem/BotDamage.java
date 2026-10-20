package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BuffStat;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.WeaponType;
import constants.game.GameConstants;
import constants.skills.Aran;
import constants.skills.Archer;
import constants.skills.Assassin;
import constants.skills.Bandit;
import constants.skills.BlazeWizard;
import constants.skills.Bowmaster;
import constants.skills.Brawler;
import constants.skills.Cleric;
import constants.skills.Crossbowman;
import constants.skills.DarkKnight;
import constants.skills.DawnWarrior;
import constants.skills.FPMage;
import constants.skills.Fighter;
import constants.skills.Gunslinger;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.ILMage;
import constants.skills.Marksman;
import constants.skills.NightWalker;
import constants.skills.Page;
import constants.skills.Paladin;
import constants.skills.Spearman;
import constants.skills.ThunderBreaker;
import constants.skills.WhiteKnight;
import constants.skills.WindArcher;
import net.server.PlayerBuffValueHolder;
import server.StatEffect;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;
import server.life.MonsterStats;
import client.status.MonsterStatus;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Attacker;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Crit;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Defender;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Hit;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Kind;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Line;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;

/*
 * Real bots 07: reads a bot's real numbers - its stat totals, the weapon and gear it wears, the skills it holds and
 * the buffs it shows - and the mob's, and hands them to BotCombatFormula. The attack driver rolls each swing's
 * lines through a Swing; the offscreen grind averages the same Swing (OffscreenCombat), so both agree.
 *
 * A bot's own buffs are only shown to the map, never registered on it (BotBuffDriver), so its stat totals lack
 * them: the ones it holds and casts (BotBuffConfig.buffsFor) are added here, unless the same buff really is
 * registered (a party member's Rage, say), which the totals already count.
 */
public final class BotDamage {

    private BotDamage() {}

    /* The WZ numbers of one skill level that damage depends on. */
    record SkillNumbers(int damage, int watk, int matk, int mastery, double prop, int x, int y, int fixDamage,
                        int hp, Element element) {
        static final SkillNumbers NONE = new SkillNumbers(100, 0, 0, 0, 0, 0, 0, 0, 0, Element.NEUTRAL);
    }

    private static volatile BiFunction<Integer, Integer, SkillNumbers> skillData = BotDamage::fromWz;

    /* Test hook: the tests have no WZ data. null restores the server's. */
    static void useSkillDataForTest(BiFunction<Integer, Integer, SkillNumbers> data) {
        skillData = data != null ? data : BotDamage::fromWz;
    }

    private static SkillNumbers fromWz(int skillId, int level) {
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null || level <= 0) {
            return SkillNumbers.NONE;
        }
        StatEffect e = skill.getEffect(Math.min(level, Math.max(1, skill.getMaxLevel())));
        if (e == null) {
            return SkillNumbers.NONE;
        }
        return new SkillNumbers(e.getDamage(), e.getWatk(), e.getMatk(), e.getMastery(), e.getProp(), e.getX(),
                e.getY(), e.getFixDamage(), e.getHp(), skill.getElement());
    }

    static SkillNumbers numbers(int skillId, int level) {
        if (skillId == 0 || level <= 0) {
            return SkillNumbers.NONE;
        }
        SkillNumbers n = skillData.apply(skillId, level);
        return n != null ? n : SkillNumbers.NONE;
    }

    // ---- what the bot holds ----

    // The passives a player's crits come from; a bot uses the first it holds.
    private static final int[] CRIT_PASSIVES = {
            Archer.CRITICAL_SHOT, WindArcher.CRITICAL_SHOT, Assassin.CRITICAL_THROW, NightWalker.CRITICAL_THROW,
            ThunderBreaker.CRITICAL_PUNCH,
    };

    // The buffs StatEffect gives the SHARP_EYES stat (x = crit rate %, y = crit damage %): Sharp Eyes and
    // MapleRoot's Fire Rush, Cygnus Sharp Eyes, Jolly Roger and Synth Master buff.
    private static final Set<Integer> CRIT_BUFFS = Set.of(Bowmaster.SHARP_EYES, Marksman.SHARP_EYES, 13121008,
            12111002, 2111004, 2211004, 2311005, 7101103, 5221018);

    // Maple Warrior: x % of the base stats.
    private static final Set<Integer> MAPLE_WARRIORS = Set.of(1121000, 1221000, 1321000, 2121000, 2221000, 2321000,
            3121000, 3221000, 4121000, 4221000, 5121000, 5221000, 21121000, 22171000);

    // Mastery skills whose WZ grade (mastery % = 5 x grade + 10) beats the weapon mastery when held.
    private static final int[] EXPERT_MASTERIES = {Bowmaster.BOW_EXPERT, Marksman.MARKSMAN_BOOST,
            WindArcher.BOW_EXPERT, Aran.HIGH_MASTERY};

    /* The weapon mastery skill that covers this weapon for this job, or 0. */
    static int masterySkill(Job job, WeaponType weapon) {
        if (job == null || weapon == null) {
            return 0;
        }
        return switch (weapon) {
            case SWORD1H, SWORD2H -> job.isA(Job.DAWNWARRIOR1) ? DawnWarrior.SWORD_MASTERY
                    : job.isA(Job.PAGE) ? Page.SWORD_MASTERY : Fighter.SWORD_MASTERY;
            case GENERAL1H_SWING, GENERAL1H_STAB, GENERAL2H_SWING, GENERAL2H_STAB ->
                    job.isA(Job.PAGE) ? Page.BW_MASTERY : Fighter.AXE_MASTERY;
            case SPEAR_STAB, SPEAR_SWING -> Spearman.SPEAR_MASTERY;
            case POLE_ARM_SWING, POLE_ARM_STAB -> job.isA(Job.ARAN1) ? Aran.POLEARM_MASTERY : Spearman.POLEARM_MASTERY;
            case BOW -> job.isA(Job.WINDARCHER1) ? WindArcher.BOW_MASTERY : Hunter.BOW_MASTERY;
            case CROSSBOW -> Crossbowman.CROSSBOW_MASTERY;
            case CLAW -> job.isA(Job.NIGHTWALKER1) ? NightWalker.CLAW_MASTERY : Assassin.CLAW_MASTERY;
            case DAGGER_THIEVES, DAGGER_OTHER -> Bandit.DAGGER_MASTERY;
            case KNUCKLE -> job.isA(Job.THUNDERBREAKER1) ? ThunderBreaker.KNUCKLER_MASTERY : Brawler.KNUCKLER_MASTERY;
            case GUN -> Gunslinger.GUN_MASTERY;
            default -> 0;
        };
    }

    static double weaponMastery(Character bot, WeaponType weapon) {
        int grade = 0;
        int id = masterySkill(bot.getJob(), weapon);
        if (id != 0) {
            grade = numbers(id, bot.getSkillLevel(id)).mastery();
        }
        for (int expert : EXPERT_MASTERIES) {
            int level = bot.getSkillLevel(expert);
            if (level > 0) {
                grade = Math.max(grade, numbers(expert, level).mastery());
            }
        }
        return grade > 0 ? Math.min(0.95, (5 * grade + 10) / 100.0) : BotCombatFormula.BASE_MASTERY;
    }

    private static int amplificationSkill(Job job) {
        if (job == null) {
            return 0;
        }
        if (job == Job.FP_MAGE || job == Job.FP_ARCHMAGE) {
            return FPMage.ELEMENT_AMPLIFICATION;
        }
        if (job == Job.IL_MAGE || job == Job.IL_ARCHMAGE) {
            return ILMage.ELEMENT_AMPLIFICATION;
        }
        if (job.isA(Job.BLAZEWIZARD3)) {
            return BlazeWizard.ELEMENT_AMPLIFICATION;
        }
        return 0;
    }

    /* The buffs the bot holds and shows that are not registered on it: id -> level, in cast order. */
    static List<int[]> shownBuffs(Character bot) {
        List<int[]> shown = new ArrayList<>();
        for (int id : BotBuffConfig.buffsFor(bot)) {
            int level = bot.getSkillLevel(id);
            if (level > 0 && !bot.hasBuffFromSourceid(id)) {
                shown.add(new int[]{id, level});
            }
        }
        return shown;
    }

    /* The bot as an attacker with this weapon: its totals plus what it shows. */
    public static Attacker attacker(Character bot, WeaponType weapon) {
        List<int[]> shown = shownBuffs(bot);
        int watk = bot.getTotalWatk();   // the ammo the bot carries is in it (BotAmmo.syncStats, real bots 08)
        int matk = bot.getTotalMagic();
        int mapleWarrior = 0;
        int damageBonus = 0;
        double buffCritChance = 0;
        int buffCritDamage = 0;
        for (int[] buff : shown) {
            int id = buff[0];
            SkillNumbers n = numbers(id, buff[1]);
            watk += n.watk();
            matk += n.matk();
            if (MAPLE_WARRIORS.contains(id) && bot.getBuffedValue(BuffStat.MAPLE_WARRIOR) == null) {
                mapleWarrior = Math.max(mapleWarrior, n.x());
            }
            if (CRIT_BUFFS.contains(id)) {
                buffCritChance += n.x() / 100.0;
                buffCritDamage = Math.max(buffCritDamage, n.y());
            }
            if (id != DarkKnight.BERSERK) {   // Berserk counts only under its HP line (hitFor)
                damageBonus += n.damage() - 100;
            }
        }
        for (PlayerBuffValueHolder held : bot.getAllBuffs()) {   // registered buffs, as parseDamage sums them
            damageBonus += held.effect.getDamage() - 100;
        }
        Integer sharpEyes = bot.getBuffedValue(BuffStat.SHARP_EYES);
        if (sharpEyes != null) {
            buffCritChance += ((sharpEyes >> 8) & 0xFF) / 100.0;
            buffCritDamage = Math.max(buffCritDamage, sharpEyes & 0xFF);
        }

        double passiveCritChance = 0;
        int passiveCritDamage = 0;
        for (int id : CRIT_PASSIVES) {
            int level = bot.getSkillLevel(id);
            if (level > 0) {
                SkillNumbers n = numbers(id, level);
                passiveCritChance = n.prop();
                passiveCritDamage = n.damage();
                break;
            }
        }

        int amplification = 100;
        int ampId = amplificationSkill(bot.getJob());
        if (ampId != 0 && bot.getSkillLevel(ampId) > 0) {
            amplification = Math.max(100, numbers(ampId, bot.getSkillLevel(ampId)).y());
        }

        Job job = bot.getJob();
        boolean pirate = job != null && (job.isA(Job.PIRATE) || job.isA(Job.THUNDERBREAKER1));
        return new Attacker(bot.getLevel(),
                bot.getTotalStr() + bot.getStr() * mapleWarrior / 100,
                bot.getTotalDex() + bot.getDex() * mapleWarrior / 100,
                bot.getTotalInt() + bot.getInt() * mapleWarrior / 100,
                bot.getTotalLuk() + bot.getLuk() * mapleWarrior / 100,
                watk, matk, weapon,
                job != null && job.isA(Job.THIEF), pirate, pirate && job.getId() % 100 != 0,
                weaponMastery(bot, weapon),
                passiveCritChance, passiveCritDamage, Math.min(1.0, buffCritChance), buffCritDamage,
                damageBonus, amplification);
    }

    /* The skill numbers of one swing: the skill at the level it is cast, the route it is drawn on. */
    static Hit hitFor(Character bot, int skillId, int skillLevel, BotAttackProfile.Route route, WeaponType weapon,
                      int healTargets) {
        SkillNumbers n = numbers(skillId, skillLevel);
        Hit hit;
        if (skillId != 0 && n.fixDamage() > 0) {
            hit = new Hit(Kind.FIXED, skillId, 100, 0, 0, 0, 0, n.fixDamage(), 1.0);
        } else if (route == BotAttackProfile.Route.MAGIC && skillId == Cleric.HEAL) {
            hit = new Hit(Kind.HEAL, skillId, 100, 0, 0, n.hp(), Math.max(1, healTargets), 0, 1.0);
        } else if (route == BotAttackProfile.Route.MAGIC) {
            double mastery = n.mastery() > 0 ? (5 * n.mastery() + 10) / 100.0 : BotCombatFormula.BASE_MASTERY;
            hit = Hit.magic(skillId, n.matk(), mastery);
        } else if (route == BotAttackProfile.Route.CLOSE && isRanged(weapon)) {
            hit = new Hit(Kind.SWING, skillId, skillId == 0 ? 100 : n.damage(), 0, 0, 0, 0, 0, 1.0);
        } else {
            hit = Hit.physical(skillId, skillId == 0 ? 100 : n.damage());
        }
        return hit.times(berserk(bot) * finisher(bot, skillId));
    }

    private static boolean isRanged(WeaponType weapon) {
        return weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW || weapon == WeaponType.CLAW
                || weapon == WeaponType.GUN;
    }

    // Berserk: its damage % while the Dark Knight's HP is under its x % line.
    private static double berserk(Character bot) {
        int level = bot.getSkillLevel(DarkKnight.BERSERK);
        if (level <= 0 || bot.getCurrentMaxHp() <= 0) {
            return 1.0;
        }
        SkillNumbers n = numbers(DarkKnight.BERSERK, level);
        return bot.getHp() * 100L / bot.getCurrentMaxHp() < n.x() ? Math.max(100, n.damage()) / 100.0 : 1.0;
    }

    // A combo finisher hits harder on more orbs (the client's table, from parseDamage's combo block).
    private static double finisher(Character bot, int skillId) {
        if (skillId == 0 || !GameConstants.isFinisherSkill(skillId)) {
            return 1.0;
        }
        int orbs = BotComboOrbs.orbs(bot);
        return orbs >= 5 ? 2.5 : orbs == 4 ? 2.0 : orbs == 3 ? 1.54 : orbs == 2 ? 1.2 : 1.0;
    }

    /*
     * A White Knight's or Paladin's charge (the highest it holds): the element and the multiplier parseDamage
     * allows against a mob weak to it, 1.05 (holy 1.2) plus 1.5% a level. null without one.
     */
    record Charge(Element element, double weakMultiplier) {
    }

    static Charge chargeOf(Character bot) {
        int holy = bot.getSkillLevel(Paladin.SWORD_HOLY_CHARGE);
        if (holy > 0) {
            return new Charge(Element.HOLY, 1.2 + holy * 0.015);
        }
        int fire = bot.getSkillLevel(WhiteKnight.SWORD_FIRE_CHARGE);
        return fire > 0 ? new Charge(Element.FIRE, 1.05 + fire * 0.015) : null;
    }

    static double elementMultiplier(ElementalEffectiveness effectiveness) {
        if (effectiveness == null) {
            return 1.0;
        }
        return switch (effectiveness) {
            case WEAK -> 1.5;
            case STRONG -> 0.5;
            case IMMUNE -> 0.0;
            default -> 1.0;
        };
    }

    /*
     * One swing, worked out once and rolled per mob: what the driver draws (skill, level, route) with what the
     * bot holds. healTargets is Heal's caster-plus-undead count; shadowPartner says the lines are doubled.
     */
    public static Swing swing(Character bot, int skillId, int skillLevel, BotAttackProfile.Route route,
                              WeaponType weapon, int healTargets, boolean shadowPartner) {
        Attacker attacker = attacker(bot, weapon);
        Hit hit = hitFor(bot, skillId, skillLevel, route, weapon, healTargets);
        int share = 0;
        if (shadowPartner) {
            int id = bot.getSkillLevel(NightWalker.SHADOW_PARTNER) > 0 ? NightWalker.SHADOW_PARTNER : Hermit.SHADOW_PARTNER;
            int y = numbers(id, Math.max(1, bot.getSkillLevel(id))).y();
            share = y > 0 ? y : 50;
        }
        Element element = numbers(skillId, skillLevel).element();
        return new Swing(attacker, hit, element != null ? element : Element.NEUTRAL,
                hit.kind() == Kind.PHYSICAL ? chargeOf(bot) : null, share);
    }

    /*
     * Real bots 10: the gear optimizer's yardstick - the average line of the bot's main attack (single, else mob,
     * else ultimate; a plain swing where the weapon cannot draw it, as the driver does) against a neutral mob,
     * crits included, given how much each stat total would change from what it wears now.
     */
    public interface GearValue {
        double of(int dStr, int dDex, int dInt, int dLuk, int dWatk, int dMatk);
    }

    /* The bot's GearValue while holding this weapon (0 = bare-handed): its own mastery, ammo and attacks. */
    public static GearValue gearValue(Character bot, int weaponItemId) {
        WeaponType weapon = weaponTypeOf(weaponItemId);
        Attacker now = attacker(bot, weapon);
        BotAttackConfig.JobAttacks attacks = BotAttackConfig.resolve(bot.getJob(), weapon,
                BotAttackDriver.ownedSkills(bot));
        BotAttackProfile main = attacks.single() != null ? attacks.single()
                : attacks.aoe() != null ? attacks.aoe() : attacks.ultimate();
        int skillId = 0;
        int skillLevel = 0;
        BotAttackProfile.Route route = BotAttackProfile.Route.CLOSE;
        if (main != null && BotSkillActions.weaponFits(main.skillId, weaponItemId)) {
            skillId = main.skillId;
            skillLevel = BotAttackDriver.packetLevel(bot, main);
            route = main.route;
        }
        Hit hit = hitFor(bot, skillId, skillLevel, route, weapon, 1);
        return (dStr, dDex, dInt, dLuk, dWatk, dMatk) -> {
            Attacker a = new Attacker(now.level(), now.str() + dStr, now.dex() + dDex, now.int_() + dInt,
                    now.luk() + dLuk, now.watk() + dWatk, now.matk() + dMatk, now.weapon(), now.thief(),
                    now.pirate(), now.pirateAdvanced(), now.mastery(), now.passiveCritChance(),
                    now.passiveCritDamage(), now.buffCritChance(), now.buffCritDamage(), now.damageBonusPct(),
                    now.amplificationPct());
            return BotCombatFormula.expected(BotCombatFormula.base(a, hit), BotCombatFormula.crit(a, hit));
        };
    }

    // ItemInformationProvider.getWeaponType without the item data it never reads (the id's weapon class).
    private static final WeaponType[] WEAPON_CLASSES = {WeaponType.SWORD1H, WeaponType.GENERAL1H_SWING,
            WeaponType.GENERAL1H_SWING, WeaponType.DAGGER_OTHER, WeaponType.NOT_A_WEAPON, WeaponType.NOT_A_WEAPON,
            WeaponType.NOT_A_WEAPON, WeaponType.WAND, WeaponType.STAFF, WeaponType.NOT_A_WEAPON, WeaponType.SWORD2H,
            WeaponType.GENERAL2H_SWING, WeaponType.GENERAL2H_SWING, WeaponType.SPEAR_STAB, WeaponType.POLE_ARM_SWING,
            WeaponType.BOW, WeaponType.CROSSBOW, WeaponType.CLAW, WeaponType.KNUCKLE, WeaponType.GUN};

    public static WeaponType weaponTypeOf(int itemId) {
        if (itemId <= 0) {
            return null;   // bare-handed, as BotAttack.resolveEquippedWeaponType reports it
        }
        int cat = itemId / 10000 % 100;
        return cat < 30 || cat > 49 ? WeaponType.NOT_A_WEAPON : WEAPON_CLASSES[cat - 30];
    }

    public static final class Swing {
        final Attacker attacker;
        final Hit hit;
        final Element element;
        final Charge charge;
        final int shadowShare;   // Shadow Partner's share %, 0 without it
        final Range base;
        final Crit crit;

        Swing(Attacker attacker, Hit hit, Element element, Charge charge, int shadowShare) {
            this.attacker = attacker;
            this.hit = hit;
            this.element = element;
            this.charge = charge;
            this.shadowShare = shadowShare;
            this.base = BotCombatFormula.base(attacker, hit);
            this.crit = BotCombatFormula.crit(attacker, hit);
        }

        boolean magic() {
            return hit.kind() == Kind.MAGIC || hit.kind() == Kind.HEAL;
        }

        /* The packet lines against this mob, crits negative-encoded (BotAttackData.encodeCritLine). */
        public List<Integer> roll(Monster mob, int lines) {
            List<Integer> out = new ArrayList<>(lines);
            if (mob.isBuffed(magic() ? MonsterStatus.MAGIC_IMMUNITY : MonsterStatus.WEAPON_IMMUNITY)) {
                for (int i = 0; i < lines; i++) {
                    out.add(1);   // what parseDamage makes of a hit on an immune mob
                }
                return out;
            }
            double elementMultiplier = elementMultiplier(mob::getElementalEffectiveness);
            Range range = BotCombatFormula.against(base, hit.kind(), attacker.level(),
                    new Defender(mob.getLevel(), mob.getStats().getPDDamage(), mob.getStats().getMDDamage(),
                            elementMultiplier));
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int mainLines = shadowShare > 0 ? lines - lines / 2 : lines;
            List<Line> rolled = new ArrayList<>(lines);
            for (int i = 0; i < mainLines; i++) {
                rolled.add(BotCombatFormula.roll(range, crit, rng));
            }
            for (int i = mainLines; i < lines; i++) {
                rolled.add(BotCombatFormula.shadow(rolled.get(i - mainLines), shadowShare));
            }
            for (Line line : rolled) {
                out.add(line.crit() ? BotAttackData.encodeCritLine(line.damage()) : line.damage());
            }
            return out;
        }

        /* The average damage one mob of these stats takes from this swing's lines, crits included. */
        public double expectedPerMob(MonsterStats mob, int lines) {
            double elementMultiplier = elementMultiplier(mob::getEffectiveness);
            Range range = BotCombatFormula.against(base, hit.kind(), attacker.level(),
                    new Defender(mob.getLevel(), mob.getPDDamage(), mob.getMDDamage(), elementMultiplier));
            double line = BotCombatFormula.expected(range, crit);
            if (shadowShare <= 0) {
                return line * lines;
            }
            int mainLines = lines - lines / 2;
            return line * mainLines + line * shadowShare / 100.0 * (lines - mainLines);
        }

        // The skill's element against the mob (weak 1.5, strong 0.5), then a charge the mob is weak to.
        private double elementMultiplier(java.util.function.Function<Element, ElementalEffectiveness> of) {
            double multiplier = element == Element.NEUTRAL ? 1.0 : BotDamage.elementMultiplier(of.apply(element));
            if (charge != null && of.apply(charge.element()) == ElementalEffectiveness.WEAK) {
                multiplier *= charge.weakMultiplier();
            }
            return multiplier;
        }

        public Range base() {
            return base;
        }

        public Crit crit() {
            return crit;
        }
    }
}
