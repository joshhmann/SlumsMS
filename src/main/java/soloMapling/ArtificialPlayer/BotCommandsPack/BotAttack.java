package soloMapling.ArtificialPlayer.BotCommandsPack;

import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Item;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import soloMapling.server.BotAttackPackets;
import soloMapling.server.BotAttackPackets.AttackTarget;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotClientPoses;
import tools.PacketCreator;

import java.util.Collections;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.facingLeft;

/**
 * Server-side attack animation helper for bots. 
 * Credit to NuTNNuT for attack animation values and code reference
 *
 * Bots have no real client, so they don't generate close-range-damage packets
 * the way players do. This helper synthesizes the broadcast packet directly so
 * other clients see the swing animation. We do NOT couple this to any actual
 * damage application — when the swing should also hit a reactor or monster,
 * the caller invokes the relevant logic separately (e.g. CustomReactor.hitReactor).
 *
 * Packet byte semantics in this Cosmic build (verified against AbstractDealDamageHandler.parseDamage):
 *   direction byte = body action id from Character/00002000.img (e.g. swingO1 = 5, swingP1 = 13)
 *   stance    byte = facing mask (0x80 = facing left, 0x00 = facing right)
 *   display   byte = 0 for a basic (non-skill) attack
 *   speed     byte = weapon attackSpeed (2..9; lower is faster). 4 is a safe default.
 *
 * The body-action id MUST match the equipped weapon class — a 1H swingO1 on a polearm
 * renders nothing. {@link BotAttackData#randomActionFor} picks the right variant.
 */
public final class BotAttack {

    /** Equip slot id for the main-hand weapon in v83. */
    private static final short EQUIP_SLOT_WEAPON = -11;

    private BotAttack() {}

    /**
     * Broadcast a basic Ctrl-attack swing animation. Pure visual; no damage,
     * no targets, no skill. Caller is responsible for any follow-up damage
     * application (e.g. reactor hit). The animation is selected based on the
     * bot's currently equipped weapon class.
     */
    public static void basicSwing(Character chr) {
        if (chr == null) return;

        int facingMask = facingLeft(chr) ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int weaponId = equippedWeaponId(chr);
        int bodyActionId = BotAttackData.actionFor(0, 0, weaponId, BotAttackProfile.Route.CLOSE);
        if (bodyActionId < 0) {
            return; // no weapon a viewer would draw a swing for
        }

        Map<Integer, AttackTarget> emptyTargets = Collections.emptyMap();
        chr.getMap().broadcastMessage(
                chr,
                BotAttackPackets.closeRangeAttack(
                        chr,
                        /* skill        */ 0,
                        /* skilllevel   */ 0,
                        /* stance       */ facingMask,
                        /* numAttackedAndDamage */ 0,
                        /* targets      */ emptyTargets,
                        /* speed        */ BotClientPoses.actionSpeed(weaponId),
                        /* direction    */ bodyActionId,
                        /* display      */ 0
                ),
                /* repeatToSource */ false
        );
    }

    /**
     * Broadcast a cosmetic SKILL swing at the air - the character plays the skill's attack pose
     * (Brandish, Savage Blow, etc.) with no targets and no damage. Same packet the real melee path
     * builds (BotAttackEffects.meleeStrike) but with an empty target map, so nothing is hit. The
     * body-action id comes from the skill's own keyframe (BotAttackData.actionFor), falling back to
     * the weapon default. Pure visual. For magic-class skills use {@link #magicSwing} instead.
     */
    public static void skillSwing(Character chr, int skillId) {
        if (chr == null || chr.getMap() == null) return;
        if (skillId <= 0) { basicSwing(chr); return; }

        int facingMask = facingLeft(chr) ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int weaponId = equippedWeaponId(chr);
        if (!drawable(skillId, weaponId)) {
            basicSwing(chr);
            return;
        }
        int level = resolveSkillLevel(skillId);
        int bodyActionId = BotAttackData.actionFor(skillId, level, weaponId, BotAttackProfile.Route.CLOSE);
        if (bodyActionId < 0) {
            return; // the weapon has no pose a viewer would draw
        }

        Map<Integer, AttackTarget> emptyTargets = Collections.emptyMap();
        chr.getMap().broadcastMessage(
                chr,
                BotAttackPackets.closeRangeAttack(chr, skillId, level, facingMask,
                        /* numAttackedAndDamage */ 0, emptyTargets, BotClientPoses.actionSpeed(weaponId),
                        bodyActionId, /* display */ 0),
                false
        );
    }

    /**
     * Broadcast a cosmetic MAGIC skill cast at the air - the character plays the spell cast with no
     * targets and no damage. Carries the charge int (BotAttackData.magicChargeFor); omitting it on a
     * keydown CHARGE skill (Big Bang) over-reads the packet and CRASHES viewers, so it must be sent.
     * Pure visual.
     */
    public static void magicSwing(Character chr, int skillId) {
        if (chr == null || chr.getMap() == null || skillId <= 0) return;

        int facingMask = facingLeft(chr) ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int weaponId = equippedWeaponId(chr);
        if (!drawable(skillId, weaponId)) {
            return; // a mage with no wand or staff: nothing a viewer could draw
        }
        int level = resolveSkillLevel(skillId);
        int bodyActionId = BotAttackData.actionFor(skillId, level, weaponId, BotAttackProfile.Route.MAGIC);
        if (bodyActionId < 0) {
            return;
        }

        Map<Integer, AttackTarget> emptyTargets = Collections.emptyMap();
        chr.getMap().broadcastMessage(
                chr,
                BotAttackPackets.magicAttack(chr, skillId, level, facingMask,
                        /* numAttackedAndDamage */ 0, emptyTargets, BotAttackData.magicChargeFor(skillId),
                        BotClientPoses.actionSpeed(weaponId), bodyActionId, /* display */ 0),
                false
        );
    }

    /**
     * 1.5.0 rc6 (QA t-rv-4: "saw a skill cannot be used with equipped item"): whether a viewer's client accepts this
     * skill from a character holding this weapon. The idle show-off swings (BotFlavor) sent the job's skill whatever
     * the bot held; a remote attack whose skill does not fit the weapon is dropped and the viewer's chat reads "This
     * skill cannot be used with the equipped item." Every combat swing already checked this (BotSwingPlan).
     */
    static boolean drawable(int skillId, int weaponId) {
        return soloMapling.ArtificialPlayer.BotAttackSystem.BotSkillActions.weaponFits(skillId, weaponId);
    }

    private static int equippedWeaponId(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem(EQUIP_SLOT_WEAPON);
        return weapon != null ? weapon.getItemId() : 0;
    }

    // The skill's max level, so the fanciest form of the effect renders. Bots don't learn skills, so
    // there's no real level to read; 1 is a safe fallback if the skill can't be resolved.
    private static int resolveSkillLevel(int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null ? skill.getMaxLevel() : 1;
    }

    /** The bot's currently equipped main-hand weapon class, or null if unarmed. */
    public static WeaponType resolveEquippedWeaponType(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem(EQUIP_SLOT_WEAPON);
        if (weapon == null) return null;
        return ItemInformationProvider.getInstance().getWeaponType(weapon.getItemId());
    }
}
