package server.life;

import constants.id.MobId;

import java.util.List;

/**
 * 1.5.4: the Tamable Hog in the 3rd-job mount quest. The v83 client decides hit or miss from its own WZ (hog eva 999,
 * PDDamage 1999), so nearly every swing arrives as a 0 (miss) and real hits do about 1 damage; only poison could get
 * it under the 40% HP the catch item needs. The server counts each damage line as at least 10% of its max HP and
 * never lets it die, because it must survive to be caught.
 */
public final class HogTaming {
    /** Minimum damage per line: 10% of the hog's 200 max HP. */
    public static final int MIN_DAMAGE = 20;

    private HogTaming() {
    }

    /** Total damage one attack does to a mob: unchanged for every mob except the Tamable Hog. */
    public static int totalDamage(int mobId, int reported, List<Integer> lines, long hp) {
        if (mobId != MobId.TAMABLE_HOG) {
            return reported;
        }
        long total = 0;
        for (Integer line : lines) {
            int dealt = line == null ? 0 : line < 0 ? line + Integer.MAX_VALUE : line;
            total += Math.max(dealt, MIN_DAMAGE);
        }
        return (int) Math.max(0, Math.min(total, hp - 1));
    }
}
