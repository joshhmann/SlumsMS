package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.inventory.WeaponType;

import java.util.Random;

/*
 * Real bots 07: how hard a bot hits, from the same formulas a player's damage comes from. Pure arithmetic over
 * numbers BotDamage reads off the bot (stats, gear, skills, buffs) and the mob, so the tests can check it line
 * for line against the server's own bounds without any WZ data.
 *
 * The server never computes a player's damage - the client does, and AbstractDealDamageHandler.parseDamage only
 * checks it against a ceiling. So the ceilings here are parseDamage's (the physical max is exactly
 * Character.calculateMaxBaseDamage times the skill's damage %), and the rest is the v83 client's own formula
 * set: the mastery floor, the spell formula, Lucky Seven / Triple Throw, Dragon Roar, Heal against the undead,
 * a ranged weapon swung in melee, crits, and the mob's defence and level. The formula set follows nutnnut's
 * CombatFormulaProvider (GreenCatMS, AGPL-3.0); see docs/PROVENANCE.md.
 *
 * Damage lines stay ints: the attack packet carries 32-bit lines (a crit is sent negative, see
 * BotAttackData.encodeCritLine), while mob HP is 64-bit, so every line is clamped to MAX_LINE.
 */
public final class BotCombatFormula {

    private BotCombatFormula() {}

    // The largest line a packet can carry and still be encoded as a crit.
    static final int MAX_LINE = Integer.MAX_VALUE - 1;

    // Without a mastery skill a weapon has 10% mastery; a ranged weapon swung in melee always does (x 0.9).
    static final double BASE_MASTERY = 0.10;
    private static final double SWING_MASTERY = 0.09;

    /* How a swing's damage is worked out. SWING is a bow, crossbow, claw or gun used in melee. */
    public enum Kind { PHYSICAL, SWING, MAGIC, HEAL, FIXED }

    /*
     * The attacker's totals as the client sees them: stats, weapon and magic attack with gear and buffs, the
     * weapon's mastery (0.1 .. 0.9), crits from passives (Critical Shot / Throw / Punch) and from buffs (Sharp
     * Eyes and its MapleRoot kin), the sum of the active buffs' damage bonuses (parseDamage's bonusDmgBuff - 100)
     * and the mage's Element Amplification (100 = none). A crit's damage is the skill's crit damage %:
     * 200 adds 100 points to the skill's damage %.
     */
    public record Attacker(int level, int str, int dex, int int_, int luk, int watk, int matk, WeaponType weapon,
                           boolean thief, boolean pirate, boolean pirateAdvanced, double mastery,
                           double passiveCritChance, int passiveCritDamage, double buffCritChance, int buffCritDamage,
                           int damageBonusPct, int amplificationPct) {
    }

    /*
     * One swing's skill numbers: its damage % (100 for a plain swing), a spell's magic attack and mastery,
     * Heal's rate and target count, a fixed damage, and any extra multiplier (Berserk, combo finishers).
     */
    public record Hit(Kind kind, int skillId, int damagePct, int spellAttack, double spellMastery, int healRate,
                      int healTargets, int fixedDamage, double multiplier) {

        public static Hit physical(int skillId, int damagePct) {
            return new Hit(Kind.PHYSICAL, skillId, damagePct, 0, 0, 0, 0, 0, 1.0);
        }

        public static Hit magic(int skillId, int spellAttack, double spellMastery) {
            return new Hit(Kind.MAGIC, skillId, 100, spellAttack, spellMastery, 0, 0, 0, 1.0);
        }

        Hit times(double extra) {
            return new Hit(kind, skillId, damagePct, spellAttack, spellMastery, healRate, healTargets, fixedDamage,
                    multiplier * extra);
        }
    }

    /* The mob: its level, weapon and magic defence, and how its element takes the skill's (1 = neutral). */
    public record Defender(int level, int wdef, int mdef, double elementMultiplier) {
    }

    /* A damage range, both ends inclusive. */
    public record Range(long min, long max) {
        double average() {
            return (min + max) / 2.0;
        }
    }

    /* A swing's crit: the chance per line and the factor a crit line is multiplied by. */
    public record Crit(double chance, double factor) {
        static final Crit NONE = new Crit(0, 1);
    }

    /* One rolled damage line. */
    public record Line(int damage, boolean crit) {
    }

    // Skills with their own physical formula, keyed by id as parseDamage and the client do. MapleRoot keeps
    // 4121007 (now Quadruple Throw) on the Triple Throw formula.
    private static final int LUCKY_SEVEN = 4001344;
    private static final int NW_LUCKY_SEVEN = 14001004;
    private static final int TRIPLE_THROW = 4121007;
    private static final int NW_TRIPLE_THROW = 14110005;
    private static final int DRAGON_ROAR = 1311006;

    /* The swing's damage range before the mob's defence: what the attacker could hit for. */
    public static Range base(Attacker a, Hit h) {
        Range r = switch (h.kind()) {
            case FIXED -> new Range(h.fixedDamage(), h.fixedDamage());
            case MAGIC -> magic(a, h);
            case HEAL -> heal(a, h);
            case SWING -> scale(swing(a), h.damagePct());
            case PHYSICAL -> scale(physical(a, h.skillId()), h.skillId() == 0 ? 100 : h.damagePct());
        };
        if (h.kind() == Kind.FIXED) {
            return clamp(r.min(), r.max());
        }
        double factor = (100 + a.damageBonusPct()) / 100.0 * h.multiplier();
        return clamp((long) Math.ceil(r.min() * factor), (long) Math.ceil(r.max() * factor));
    }

    /*
     * The physical base before the skill's %: parseDamage's ceiling, Character.calculateMaxBaseDamage, and the
     * client's floor, where only the main stat is reduced by mastery (x 0.9).
     */
    static Range physical(Attacker a, int skillId) {
        int watk = Math.max(0, a.watk());
        if (skillId == LUCKY_SEVEN || skillId == NW_LUCKY_SEVEN || skillId == TRIPLE_THROW || skillId == NW_TRIPLE_THROW) {
            long max = (long) Math.ceil(a.luk() * 5.0 * watk / 100.0);
            return new Range(Math.round(max * 0.5), max);
        }
        if (skillId == DRAGON_ROAR) {
            long max = (long) Math.ceil((a.str() * 4.0 + a.dex()) * watk / 100.0);
            long min = Math.round((a.str() * 4.0 * a.mastery() * 0.9 + a.dex()) * watk / 100.0);
            return new Range(min, max);
        }
        WeaponType weapon = a.weapon() == null ? WeaponType.NOT_A_WEAPON : a.weapon();
        if (weapon == WeaponType.NOT_A_WEAPON) {
            return unarmed(a);
        }
        if (a.thief() && weapon == WeaponType.DAGGER_OTHER) {
            weapon = WeaponType.DAGGER_THIEVES;
        }
        int main;
        int secondary;
        if (weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW || weapon == WeaponType.GUN) {
            main = a.dex();
            secondary = a.str();
        } else if (weapon == WeaponType.CLAW || weapon == WeaponType.DAGGER_THIEVES) {
            main = a.luk();
            secondary = a.dex() + a.str();
        } else {
            main = a.str();
            secondary = a.dex();
        }
        double multiplier = weapon.getMaxDamageMultiplier();
        // Character.calculateMaxBaseDamage, operation for operation.
        long max = (long) Math.ceil(((multiplier * main + secondary) / 100.0) * watk);
        long min = Math.round((multiplier * main * 0.9 * a.mastery() + secondary) / 100.0 * watk);
        return new Range(min, max);
    }

    // Character.calculateMaxBaseDamage without a weapon: a pirate's fists, anyone else 1.
    private static Range unarmed(Attacker a) {
        if (!a.pirate()) {
            return new Range(1, 1);
        }
        double weaponMultiplier = a.pirateAdvanced() ? 4.2 : 3;
        int attack = (int) Math.min(Math.floor((2 * a.level() + 31) / 3.0), 31);
        long max = (long) Math.ceil((a.str() * weaponMultiplier + a.dex()) * attack / 100.0);
        long min = Math.round((a.str() * weaponMultiplier * 0.9 * BASE_MASTERY + a.dex()) * attack / 100.0);
        return new Range(min, max);
    }

    // A ranged weapon swung in melee (the client's own formulas; weapon mastery never applies).
    private static Range swing(Attacker a) {
        int watk = Math.max(0, a.watk());
        double max;
        double min;
        if (a.weapon() == WeaponType.BOW || a.weapon() == WeaponType.CROSSBOW) {
            max = (a.dex() * 3.4 + a.str()) * watk / 150.0;
            min = (a.dex() * 3.4 * SWING_MASTERY + a.str()) * watk / 150.0;
        } else if (a.weapon() == WeaponType.CLAW) {
            max = (a.luk() + a.str() + a.dex()) * watk / 150.0;
            min = (a.luk() * SWING_MASTERY + a.str() + a.dex()) * watk / 150.0;
        } else if (a.weapon() == WeaponType.GUN) {
            max = (a.dex() + a.str()) * watk / 200.0;
            min = (a.dex() * SWING_MASTERY + a.str()) * watk / 200.0;
        } else {
            return physical(a, 0);
        }
        return new Range(Math.round(min), (long) Math.ceil(max));
    }

    /*
     * A spell: ((MATK^2 / 1000 + MATK) / 30 + INT / 200) x the spell's magic attack, the floor taking the
     * spell's mastery on the linear term, then Element Amplification. At or under parseDamage's magic ceiling.
     */
    static Range magic(Attacker a, Hit h) {
        double m = Math.max(0, a.matk());
        double max = (m * m / 1000.0 + m) / 30.0 + a.int_() / 200.0;
        double min = (m * m / 1000.0 + m * h.spellMastery() * 0.9) / 30.0 + a.int_() / 200.0;
        double spell = Math.max(0, h.spellAttack()) * a.amplificationPct() / 100.0;
        return new Range(Math.round(min * spell), (long) Math.ceil(max * spell));
    }

    /*
     * Heal against the undead: (INT x 1.2 + LUK) x MATK / 1000 at most, (INT x 0.3 + LUK) at least, times
     * 1.5 + 5 / (caster + targets) and the heal rate %.
     */
    static Range heal(Attacker a, Hit h) {
        double targetMultiplier = 1.5 + 5.0 / Math.max(1, h.healTargets());
        double matk = Math.max(0, a.matk()) / 1000.0;
        double max = (a.int_() * 1.2 + a.luk()) * matk * targetMultiplier;
        double min = (a.int_() * 0.3 + a.luk()) * matk * targetMultiplier;
        int rate = Math.max(0, h.healRate());
        return new Range(Math.round(min) * rate / 100, Math.round(max) * rate / 100);
    }

    private static Range scale(Range r, int pct) {
        return new Range(r.min() * pct / 100, r.max() * pct / 100);
    }

    /*
     * The range against one mob: the element (weak 1.5, strong 0.5), then its defence and the level gap. A
     * physical swing loses 1% per level the mob is above the bot and half (top) to 60% (floor) of its weapon
     * defence; a spell loses the same shares of magic defence, raised 1% per level of gap.
     */
    public static Range against(Range r, Kind kind, int attackerLevel, Defender d) {
        if (kind == Kind.FIXED || d == null) {
            return r;
        }
        double element = Math.max(0, d.elementMultiplier());
        double max = r.max() * element;
        double min = r.min() * element;
        int gap = Math.max(0, d.level() - attackerLevel);
        if (kind == Kind.MAGIC || kind == Kind.HEAL) {
            double defence = Math.max(0, d.mdef()) * (1.0 + 0.01 * gap);
            max -= defence * 0.5;
            min -= defence * 0.6;
        } else {
            double levelFactor = Math.max(0, 1.0 - 0.01 * gap);
            max = max * levelFactor - Math.max(0, d.wdef()) * 0.5;
            min = min * levelFactor - Math.max(0, d.wdef()) * 0.6;
        }
        return clamp((long) min, (long) max);
    }

    /*
     * The swing's crit. Physical attacks crit from passives and buffs, spells only from a crit buff (MapleRoot's
     * Fire Rush); a ranged weapon swung in melee, Heal and fixed damage never. A crit adds its bonus to the
     * skill's damage %, so a crit line is (skill % + bonus) / skill % of a normal one.
     */
    public static Crit crit(Attacker a, Hit h) {
        double chance;
        int bonus;
        switch (h.kind()) {
            case PHYSICAL -> {
                chance = a.passiveCritChance() + a.buffCritChance();
                bonus = bonusOf(a.passiveCritDamage()) + bonusOf(a.buffCritDamage());
            }
            case MAGIC -> {
                chance = a.buffCritChance();
                bonus = bonusOf(a.buffCritDamage());
            }
            default -> {
                return Crit.NONE;
            }
        }
        if (chance <= 0 || bonus <= 0) {
            return Crit.NONE;
        }
        int pct = h.kind() == Kind.PHYSICAL && h.skillId() != 0 ? Math.max(1, h.damagePct()) : 100;
        return new Crit(Math.min(1.0, chance), (pct + bonus) / (double) pct);
    }

    private static int bonusOf(int critDamagePct) {
        return Math.max(0, critDamagePct - 100);
    }

    /* One line: uniform in the range, then a crit roll. */
    public static Line roll(Range r, Crit c, Random rng) {
        long damage = r.min() >= r.max() ? r.max() : r.min() + (long) (rng.nextDouble() * (r.max() - r.min() + 1));
        boolean crit = c.chance() > 0 && rng.nextDouble() < c.chance();
        if (crit) {
            damage = (long) Math.floor(damage * c.factor());
        }
        return new Line((int) Math.max(1, Math.min(MAX_LINE, damage)), crit);
    }

    /* A Shadow Partner line: the shadow repeats the line it mirrors at its share (y %), crit and all. */
    public static Line shadow(Line mirrored, int sharePct) {
        long damage = (long) mirrored.damage() * Math.max(0, sharePct) / 100;
        return new Line((int) Math.max(1, Math.min(MAX_LINE, damage)), mirrored.crit());
    }

    /* The average line, crits included - for the offscreen kill rate. */
    public static double expected(Range r, Crit c) {
        return r.average() * (1.0 + c.chance() * (c.factor() - 1.0));
    }

    private static Range clamp(long min, long max) {
        long hi = Math.max(1, Math.min(MAX_LINE, max));
        long lo = Math.max(1, Math.min(hi, min));
        return new Range(lo, hi);
    }
}
