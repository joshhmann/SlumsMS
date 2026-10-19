package soloMapling.server;

import client.Character;
import net.packet.Packet;
import tools.PacketCreator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Adapt bot targets to MapleRoot's existing attack wire format.
 * Player combat serialization stays untouched. MapleRoot's format does not encode
 * the upstream per-target delay; animation timing requires a client runtime check.
 */
public final class BotAttackPackets {
    public record AttackTarget(short delay, List<Integer> damageLines) {}
    private BotAttackPackets() {}
    private static Map<Integer, List<Integer>> damage(Map<Integer, AttackTarget> targets) {
        Map<Integer, List<Integer>> damage = new LinkedHashMap<>();
        targets.forEach((id, target) -> damage.put(id, target.damageLines()));
        return damage;
    }
    public static Packet closeRangeAttack(Character chr, int skill, int level, int stance, int count,
            Map<Integer, AttackTarget> targets, int speed, int direction, int display) {
        return PacketCreator.closeRangeAttack(chr, skill, level, stance, count, damage(targets), speed, direction, display);
    }
    public static Packet rangedAttack(Character chr, int skill, int level, int stance, int count, int projectile,
            Map<Integer, AttackTarget> targets, int speed, int direction, int display) {
        return PacketCreator.rangedAttack(chr, skill, level, stance, count, projectile, damage(targets), speed, direction, display);
    }
    public static Packet magicAttack(Character chr, int skill, int level, int stance, int count,
            Map<Integer, AttackTarget> targets, int charge, int speed, int direction, int display) {
        return PacketCreator.magicAttack(chr, skill, level, stance, count, damage(targets), charge, speed, direction, display);
    }
}
