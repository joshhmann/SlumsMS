package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.skills.Bishop;
import constants.skills.FPArchMage;
import constants.skills.ILArchMage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Body-action ids extracted verbatim from GreenCatMS (BotAttackDataProvider.BODY_ACTION_ID_OVERRIDES). Credit: NutNNut.
 * Body-action ids for bot attacks - the attack packet's "direction" byte (the pose other
 * clients render). Gotcha: an id is the client's fixed internal enum value, not the action's
 * ordinal in the WZ file; the two agree only up to idx 23, so anything above that must use the
 * canonical enum value (copied from the proven GreenCatMS reference). A skill's own pose comes from its
 * Skill.wz "action" (BotSkillActions); a skill without one uses the weapon default.
 */
public final class BotAttackData {

    private BotAttackData() {}

    /* Facing mask for the close-range-attack stance byte. */
    public static final int FACING_RIGHT_MASK = 0x00;
    public static final int FACING_LEFT_MASK  = 0x80;

    /* Default attack speed when the weapon profile is unknown. v83 range is 2-9 (lower = faster). */
    public static final int DEFAULT_ATTACK_SPEED = 4;

    private static final int SWING_O1 = 5, SWING_O2 = 6, SWING_O3 = 7;   // 1H swing
    private static final int SWING_T1 = 9, SWING_T2 = 10, SWING_T3 = 11; // 2H swing
    private static final int SWING_P1 = 13, STAB_T1 = 19;                // polearm
    private static final int STAB_O1 = 16, STAB_O2 = 17;                 // 1H stab
    private static final int SHOOT_1 = 22, SHOOT_2 = 23;                 // bow/crossbow draw + fire
    private static final int CLAW_1 = 24, CLAW_2 = 25, CLAW_3 = 26;      // claw throw
    private static final int WAND_1 = 28, WAND_2 = 29;                   // wand/staff cast
    // The client maps the direction byte through a FIXED internal enum (BotSkillActions.CLIENT_ACTIONS), NOT the
    // order actions appear in Character/00002000.img. The two agree up to shoot2 (idx 23) and diverge above it (the
    // enum repeats the swings for claws at 24-26 and wands at 28-30), so a skill's own pose is looked up by name.

    private static final int[] DEFAULT_1H_VARIANTS = {STAB_O1, STAB_O2, SWING_O1, SWING_O2, SWING_O3};
    private static final int[] HEAVY_2H_VARIANTS   = {STAB_O1, STAB_O2, SWING_T1, SWING_T2, SWING_T3};
    private static final int[] POLEARM_VARIANTS    = {SWING_P1, STAB_T1};
    private static final int[] WAND_VARIANTS       = {WAND_1, WAND_2};
    private static final int[] CLAW_VARIANTS       = {CLAW_1, CLAW_2, CLAW_3};
    // Bow and crossbow are NOT interchangeable: shoot1 is the bow draw, shoot2 the crossbow.
    // A shared {shoot1, shoot2} pool made each weapon fire the wrong pose ~half the time.
    private static final int[] BOW_VARIANTS        = {SHOOT_1};
    private static final int[] CROSSBOW_VARIANTS   = {SHOOT_2};

    /* The body action for a skill on a weapon at the skill's first level; see actionFor(skillId, level, weapon). */
    public static int actionFor(int skillId, WeaponType weaponType) {
        return actionFor(skillId, 1, weaponType);
    }

    /*
     * The body action a viewer's client accepts for this skill at this level: one of the skill's own Skill.wz poses
     * (the level's own for Spear Crusher, burster1 below level 16 and burster2 from it), else the weapon default. A
     * pose the skill does not allow makes the client drop the whole attack, so it is never guessed.
     */
    public static int actionFor(int skillId, int skillLevel, WeaponType weaponType) {
        List<Integer> poses = BotSkillActions.poseCodes(skillId, skillLevel);
        if (!poses.isEmpty()) {
            return poses.get(ThreadLocalRandom.current().nextInt(poses.size()));
        }
        return randomActionFor(weaponType);
    }

    // Big Bang (F/P, I/L, Bishop) is a keydown CHARGE skill: its MAGIC_ATTACK packet carries a
    // trailing charge int that the viewer's client reads back (see MagicDamageHandler / the
    // parser's BIG_BANG branch). Sending charge = -1 omits those 4 bytes, the client over-reads,
    // and CRASHES - this was the 4th-job crash. Full keydown is ~1080 ms; send that for a full ball.
    private static final int BIG_BANG_CHARGE = 1080;
    private static final Set<Integer> CHARGE_MAGIC_SKILLS =
            Set.of(FPArchMage.BIG_BANG, ILArchMage.BIG_BANG, Bishop.BIG_BANG);

    /* The charge int a magic attack must carry (only Big Bang needs one), or -1 for every other skill. */
    public static int magicChargeFor(int skillId) {
        return CHARGE_MAGIC_SKILLS.contains(skillId) ? BIG_BANG_CHARGE : -1;
    }

    // Critical-hit display encoding. v83 has no crit flag in the attack packet: a damage line is
    // marked crit by NEGATING it, and the viewer's client renders a negative line as a yellow crit
    // (and shoves the mob back harder, since knockback keys off the displayed hit). The engine does
    // exactly this in AbstractDealDamageHandler.parseDamage and decodes it back before applying HP -
    // we mirror both sides so bot crits look identical and HP still subtracts the real amount.
    public static int encodeCritLine(int rawDamage) {
        return -Integer.MAX_VALUE + rawDamage - 1;
    }

    /* Real magnitude of a packet damage line: crit lines arrive negative-encoded, normal lines pass through. */
    public static int decodeDamageLine(int line) {
        return line < 0 ? line + Integer.MAX_VALUE : line;
    }

    /* Random body-action id appropriate for the given weapon class. */
    public static int randomActionFor(WeaponType weaponType) {
        int[] variants = variantsFor(weaponType);
        return variants[ThreadLocalRandom.current().nextInt(variants.length)];
    }

    // Standard v83 ammo ids (no named ItemId constant for arrows).
    private static final int ARROW_FOR_BOW = 2060000;
    private static final int ARROW_FOR_CROSSBOW = 2061000;

    /* The flying projectile a RANGED route renders for this weapon, or 0 if it needs none. */
    public static int projectileFor(WeaponType weaponType) {
        if (weaponType == null) {
            return 0;
        }
        return switch (weaponType) {
            case BOW      -> ARROW_FOR_BOW;
            case CROSSBOW -> ARROW_FOR_CROSSBOW;
            case CLAW     -> ItemId.SUBI_THROWING_STARS;
            case GUN      -> ItemId.BULLET;
            default       -> 0;
        };
    }

    /* Like projectileFor(weapon) but, for a claw bot, returns the level-appropriate star the bot chose
     * at creation (ThrowingStarSelector), falling back to subi when there is none / the attacker isn't
     * a registered bot. Bow/gun/dagger are unaffected. */
    public static int projectileFor(WeaponType weaponType, Character bot) {
        if (weaponType == WeaponType.CLAW) {
            int chosen = ThrowingStarSelector.chosenStar(bot);
            if (chosen > 0) {
                return chosen;
            }
        }
        return projectileFor(weaponType);
    }

    /*
     * True when a warrior's skill should use its alternate weapon form: axe/blunt (the
     * GENERAL* classes) and pole-arms take the alt id; swords and spears take the default.
     */
    public static boolean usesAltWarriorVariant(WeaponType weaponType) {
        if (weaponType == null) {
            return false;
        }
        return switch (weaponType) {
            case GENERAL1H_SWING, GENERAL1H_STAB, GENERAL2H_SWING, GENERAL2H_STAB,
                 POLE_ARM_SWING, POLE_ARM_STAB -> true;
            default -> false;
        };
    }

    /* The weapon's plain attack poses, one of which a skill without its own pose is sent in. */
    static int[] variantsFor(WeaponType weaponType) {
        if (weaponType == null) {
            return DEFAULT_1H_VARIANTS;
        }
        return switch (weaponType) {
            case SWORD2H, GENERAL2H_SWING, GENERAL2H_STAB               -> HEAVY_2H_VARIANTS;
            case SPEAR_SWING, SPEAR_STAB, POLE_ARM_SWING, POLE_ARM_STAB -> POLEARM_VARIANTS;
            case WAND, STAFF                                            -> WAND_VARIANTS;
            case CLAW                                                   -> CLAW_VARIANTS;
            case BOW                                                    -> BOW_VARIANTS;      // shoot1 (bow draw)
            case CROSSBOW                                               -> CROSSBOW_VARIANTS; // shoot2 (crossbow)
            default                                                     -> DEFAULT_1H_VARIANTS; // 1H/dagger/knuckle/gun/unarmed
        };
    }
}
