package server.bounce;

import java.util.List;
import java.util.Map;

/**
 * How hard MapleRoot's universal bounces throw the character, per job family. slums.dll writes these into the three
 * impulse immediates of {@code CUserLocal::DoActiveSkill_BoundJump} (0x0096C00A / 0x0096C021 / 0x0096C031: sideways
 * speed facing left, facing right, vertical speed) each time a bound jump is used, choosing the set from the skill
 * and the character's current job.
 *
 * <ul>
 *   <li>{@code native}: thieves (job 4xx) and Night Walkers (14xx), whose own book has Flash Jump. Real Flash Jump
 *       4111006 / 14101004 also uses {@code ninja_bounce.native}.</li>
 *   <li>{@code other}: every other job.</li>
 * </ul>
 *
 * Read from the optional {@code impulses:} section of bounce-zones.yaml; anything missing keeps its default. Sent in
 * the version 2 BOUNCE_ZONES_SYNC packet ({@link BounceZonePackets#impulses}).
 */
public record BounceImpulses(Impulse ninjaNative, Impulse ninjaOther, Impulse verticalNative, Impulse verticalOther) {

    /** The client refuses anything beyond this; a normal jump is about -555 vertical. */
    public static final int MAX = 5000;

    /** One bound jump: sideways speed facing left, facing right, and vertical (negative is up). */
    public record Impulse(int leftVx, int rightVx, int vy) {
        public Impulse {
            for (int v : new int[]{leftVx, rightVx, vy}) {
                if (v < -MAX || v > MAX) {
                    throw new IllegalArgumentException("impulse " + v + " is outside -" + MAX + ".." + MAX);
                }
            }
        }

        @Override
        public String toString() {
            return leftVx + "/" + rightVx + "/" + vy;
        }
    }

    /** MapleRoot as shipped: Ninja Bounce -683/606/-688, Vertical Bounce -1/0/-1200. */
    public static final Impulse MAPLEROOT_NINJA = new Impulse(-683, 606, -688);
    public static final Impulse MAPLEROOT_VERTICAL = new Impulse(-1, 0, -1200);
    /** Stock v83 Flash Jump (the exe's own immediates). */
    public static final Impulse STOCK_FLASH_JUMP = new Impulse(-350, 350, -250);
    /** A short hop straight up for the jobs without Flash Jump. */
    public static final Impulse SHORT_VERTICAL = new Impulse(-1, 0, -750);

    public static final BounceImpulses DEFAULT =
            new BounceImpulses(MAPLEROOT_NINJA, STOCK_FLASH_JUMP, MAPLEROOT_VERTICAL, SHORT_VERTICAL);

    private static final List<String> KEYS = List.of("left_vx", "right_vx", "vy");

    /** The {@code impulses:} section (or null); bad or missing parts keep their default and are described in problems. */
    static BounceImpulses parse(Object section, List<String> problems) {
        if (section == null) {
            return DEFAULT;
        }
        if (!(section instanceof Map<?, ?> m)) {
            problems.add("impulses: not a map, using the defaults");
            return DEFAULT;
        }
        for (Object k : m.keySet()) {
            if (!"ninja_bounce".equals(String.valueOf(k)) && !"vertical_bounce".equals(String.valueOf(k))) {
                problems.add("impulses: unknown key '" + k + "' ignored");
            }
        }
        return new BounceImpulses(
                impulse(m.get("ninja_bounce"), "native", "ninja_bounce", DEFAULT.ninjaNative, problems),
                impulse(m.get("ninja_bounce"), "other", "ninja_bounce", DEFAULT.ninjaOther, problems),
                impulse(m.get("vertical_bounce"), "native", "vertical_bounce", DEFAULT.verticalNative, problems),
                impulse(m.get("vertical_bounce"), "other", "vertical_bounce", DEFAULT.verticalOther, problems));
    }

    private static Impulse impulse(Object group, String family, String groupName, Impulse fallback, List<String> problems) {
        if (group == null) {
            return fallback;
        }
        String where = "impulses." + groupName + "." + family;
        if (!(group instanceof Map<?, ?> g)) {
            problems.add("impulses." + groupName + ": not a map, using the defaults");
            return fallback;
        }
        Object o = g.get(family);
        if (o == null) {
            return fallback;
        }
        if (!(o instanceof Map<?, ?> v)) {
            problems.add(where + ": not a map, using " + fallback);
            return fallback;
        }
        try {
            int[] out = {fallback.leftVx(), fallback.rightVx(), fallback.vy()};
            for (Object k : v.keySet()) {
                int i = KEYS.indexOf(String.valueOf(k));
                if (i < 0) {
                    throw new IllegalArgumentException("unknown key '" + k + "'");
                }
                out[i] = Integer.parseInt(String.valueOf(v.get(k)).trim());
            }
            return new Impulse(out[0], out[1], out[2]);
        } catch (IllegalArgumentException e) {
            problems.add(where + ": " + e.getMessage() + ", using " + fallback);
            return fallback;
        }
    }
}
