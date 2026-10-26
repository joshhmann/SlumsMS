package soloMapling.server;

import client.Character;
import net.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotMeleeRange;
import soloMapling.qa.QaLog;
import tools.PacketCreator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
    /*
     * A bot's melee attack packet (direction = the pose). 1.6.0: never one a viewer's client finds no range for - that
     * closes the viewer's client (BotMeleeRange; a gunslinger's Double Shot sent as melee did, twice on TEST). Such an
     * attack goes out as the weapon's plain swing instead (no skill, a pose with a range), and is logged so the path
     * that built it can be fixed; null when the weapon has no such pose (the caller sends nothing).
     */
    public static Packet closeRangeAttack(Character chr, int weaponItemId, int skill, int level, int stance, int count,
            Map<Integer, AttackTarget> targets, int speed, int direction, int display) {
        if (!BotMeleeRange.hasRange(skill, level, weaponItemId, direction)) {
            REWRITES.incrementAndGet();
            int plain = BotMeleeRange.safePlainPose(weaponItemId);
            warnOnce(skill, weaponItemId, direction, plain);
            if (QaLog.on(QaLog.Topic.ATTACK)) { // !qa bk-1: auto-QA fails on this line
                QaLog.map(QaLog.Topic.ATTACK, chr.getMapId(), "GUARD melee without a range: " + chr.getName()
                        + " skill " + skill + " pose " + direction + " weapon " + weaponItemId + " -> "
                        + (plain < 0 ? "not sent" : "plain pose " + plain));
            }
            if (plain < 0) {
                return null;
            }
            skill = 0;
            level = 0;
            direction = plain;
        }
        return PacketCreator.closeRangeAttack(chr, skill, level, stance, count, damage(targets), speed, direction, display);
    }

    private static final AtomicLong REWRITES = new AtomicLong();

    /* How many melee attacks the guard has rewritten (tests: no bot attack path should ever need it). */
    public static long rewrites() {
        return REWRITES.get();
    }

    private static final Logger log = LoggerFactory.getLogger(BotAttackPackets.class);
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    /* Once per skill / weapon class / pose: which attack would have closed the viewers' clients. */
    private static void warnOnce(int skill, int weaponItemId, int pose, int plain) {
        String key = skill + "/" + weaponItemId / 10000 + "/" + pose;
        if (warned.size() < 1000 && warned.add(key)) {
            log.warn("Bot melee attack (skill {}, weapon {}, pose {}) has no range a viewer's client finds: sent {}",
                    skill, weaponItemId, pose, plain < 0 ? "nothing" : "as a plain swing in pose " + plain);
        }
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
