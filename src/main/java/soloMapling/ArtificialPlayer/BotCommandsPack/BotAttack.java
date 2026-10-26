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
import net.packet.Packet;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackEffects;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotClientPoses;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSwingPlan;
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
        Packet packet = BotAttackPackets.closeRangeAttack(
                        chr,
                        /* weapon       */ weaponId,
                        /* skill        */ 0,
                        /* skilllevel   */ 0,
                        /* stance       */ facingMask,
                        /* numAttackedAndDamage */ 0,
                        /* targets      */ emptyTargets,
                        /* speed        */ BotClientPoses.actionSpeed(weaponId),
                        /* direction    */ bodyActionId,
                        /* display      */ 0
                );
        if (packet != null) {
            chr.getMap().broadcastMessage(chr, packet, /* repeatToSource */ false);
        }
    }

    /*
     * Broadcast a show-off attack at the air (BotFlavor's idle swing): the character plays one of its attacks - a
     * Brandish, an AoE, a shot, a spell - with no targets and no damage. Pure visual.
     *
     * 1.6.0: it goes out exactly as the bot's real attack would (BotSwingPlan: the route a player's client sends the
     * skill on, its pose, its speed; the weapon's plain attack when the skill does not fit the weapon). It used to send
     * every non-spell skill as a melee swing in the skill's own pose. Once 1.6.0 gave gunslinger bots guns, that sent
     * Double Shot (doublefire, 86) as a melee attack with a gun, a pose a gun's afterimage has no range for, and the
     * viewer's client closed (CActionMan::GetMeleeAttackRange 0x414440; QA rm-2's partner and the Mu Lung Dojo
     * entrance, twice on TEST).
     */
    public static void skillSwing(Character chr, BotAttackProfile profile) {
        if (chr == null || chr.getMap() == null || profile == null) {
            return;
        }
        int weaponId = equippedWeaponId(chr);
        Packet packet = showOffPacket(chr, profile, resolveSkillLevel(chr, profile.skillId), weaponId,
                resolveEquippedWeaponType(chr), facingLeft(chr));
        if (packet != null) {
            chr.getMap().broadcastMessage(chr, packet, false);
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ATTACK)) { // !qa bk-1
                soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ATTACK, chr.getMapId(), "SHOWOFF " + chr.getName()
                        + " " + chr.getJob() + " skill " + profile.skillId + " route " + profile.route + " weapon "
                        + weaponId);
            }
        }
    }

    /*
     * The packet of a show-off attack at the air, or null when the weapon has nothing a viewer would draw. A keydown
     * or prepared skill (Hurricane, Rapid Fire, Explosion) needs its keydown packet first, so it shows the weapon's
     * plain attack instead. A skill the weapon cannot use shows the weapon's plain attack too (1.5.0 rc6, QA t-rv-4:
     * a viewer's chat read "This skill cannot be used with the equipped item.").
     */
    public static Packet showOffPacket(Character chr, BotAttackProfile profile, int skillLevel, int weaponId,
                                WeaponType weaponType, boolean left) {
        BotSwingPlan plan = BotSwingPlan.of(profile, skillLevel, weaponId, left, profile.numDamage);
        if (plan.prepare() || plan.shots() > 1) {
            BotAttackProfile plain = profile.route == BotAttackProfile.Route.RANGED
                    ? BotAttackProfile.ranged(0, 1) : BotAttackProfile.basicSwing();
            plan = BotSwingPlan.of(plain, 0, weaponId, left, 1);
        }
        if (plan.action() < 0 || (plan.route() == BotAttackProfile.Route.MAGIC && plan.skillId() == 0)) {
            return null; // no pose a viewer would draw (a mage with no wand or staff: no spell)
        }
        int projectile = plan.route() == BotAttackProfile.Route.RANGED && BotClientPoses.isShootingWeapon(weaponId)
                ? BotAttackData.projectileFor(weaponType, chr) : 0;
        Map<Integer, AttackTarget> emptyTargets = Collections.emptyMap();
        return BotAttackEffects.attackPacket(chr, plan, emptyTargets, 0, projectile);
    }

    private static int equippedWeaponId(Character chr) {
        Item weapon = chr.getInventory(InventoryType.EQUIPPED).getItem(EQUIP_SLOT_WEAPON);
        return weapon != null ? weapon.getItemId() : 0;
    }

    // The bot's own level of the skill (what its real attack sends), else the skill's max level; 1 when the skill
    // can't be resolved.
    private static int resolveSkillLevel(Character chr, int skillId) {
        if (skillId <= 0) {
            return 0;
        }
        int own = chr.getSkillLevel(skillId);
        if (own > 0) {
            return own;
        }
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
