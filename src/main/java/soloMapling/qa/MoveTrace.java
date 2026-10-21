package soloMapling.qa;

import client.Character;
import server.movement.AbsoluteLifeMovement;
import server.movement.AbstractLifeMovement;
import server.movement.JumpDownMovement;
import server.movement.LifeMovementFragment;
import server.movement.TeleportMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code !qa run mv-1}'s hook into the game: the GM's own attacks ({@code AttackCapture.attack}, which the close,
 * ranged, magic and touch handlers all call) and movement packets ({@code MovePlayerHandler}). The hooks first call
 * {@link #armed}: one volatile read, false unless an mv-1 run is measuring; only then is anything parsed or built.
 */
public final class MoveTrace {

    /** What an armed character's attacks and movement go to. */
    public interface Listener {
        void attack(long now, int skill, String route, int x, int y, int stance, int speed);

        void move(long now, List<SlideMeter.Frag> frags);
    }

    private static final Map<Integer, Listener> listeners = new ConcurrentHashMap<>();
    private static volatile boolean any;

    private MoveTrace() {
    }

    /** True while an mv-1 run measures this character. */
    public static boolean armed(int characterId) {
        return any && listeners.containsKey(characterId);
    }

    static synchronized void arm(int characterId, Listener listener) {
        listeners.put(characterId, listener);
        any = true;
    }

    static synchronized void disarm(int characterId, Listener listener) {
        listeners.remove(characterId, listener);
        any = !listeners.isEmpty();
    }

    /** An attack packet of the character (route "close", "ranged", "magic" or "touch"). */
    public static void attack(Character chr, String route, int skill) {
        Listener l = any ? listeners.get(chr.getId()) : null;
        if (l == null) {
            return;
        }
        try {
            Point p = chr.getPosition();
            l.attack(System.currentTimeMillis(), skill, route, p == null ? 0 : p.x, p == null ? 0 : p.y,
                    chr.getStance(), chr.getTotalMoveSpeedStat());
        } catch (RuntimeException ignored) {
            // a measurement must never break an attack
        }
    }

    /** A movement packet of the character, already parsed. */
    public static void move(Character chr, List<LifeMovementFragment> fragments) {
        Listener l = any ? listeners.get(chr.getId()) : null;
        if (l == null) {
            return;
        }
        try {
            l.move(System.currentTimeMillis(), frags(fragments));
        } catch (RuntimeException ignored) {
            // a measurement must never break movement
        }
    }

    /** The fragments the meter uses: absolute moves (x, y, velocity, foothold, stance) and relative ones (stance). */
    static List<SlideMeter.Frag> frags(List<LifeMovementFragment> fragments) {
        List<SlideMeter.Frag> out = new ArrayList<>(fragments.size());
        for (LifeMovementFragment f : fragments) {
            if (f instanceof TeleportMovement t) {
                // a teleport/rush has no duration or foothold: its stance only
                out.add(SlideMeter.Frag.rel(t.getType(), 0, t.getNewstate()));
            } else if (f instanceof AbsoluteLifeMovement a) {
                Point v = a.getPixelsPerSecond();
                out.add(SlideMeter.Frag.abs(a.getType(), a.getDuration(), a.getPosition().x, a.getPosition().y,
                        v == null ? 0 : v.x, v == null ? 0 : v.y, a.getFh(), a.getNewstate()));
            } else if (f instanceof JumpDownMovement j) {
                Point v = j.getPixelsPerSecond();
                out.add(SlideMeter.Frag.abs(j.getType(), j.getDuration(), j.getPosition().x, j.getPosition().y,
                        v == null ? 0 : v.x, v == null ? 0 : v.y, j.getFh(), j.getNewstate()));
            } else if (f instanceof AbstractLifeMovement r) {
                out.add(SlideMeter.Frag.rel(r.getType(), r.getDuration(), r.getNewstate()));
            }
        }
        return out;
    }

    /* Tests: forget every listener. */
    static synchronized void reset() {
        listeners.clear();
        any = false;
    }
}
