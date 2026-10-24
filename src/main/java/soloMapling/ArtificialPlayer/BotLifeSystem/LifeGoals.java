package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Derives at most two provisional aspirations. These never grant gear, EXP, money or job advances. */
public final class LifeGoals {
    private LifeGoals() {}
    public enum Kind { JOB_ADVANCE, WEAPON_UPGRADE, LEVEL }
    public record Goal(Kind kind, int targetLevel, String description, double pressure) {
        public Goal {
            if (targetLevel < 1 || !Double.isFinite(pressure) || pressure < 0 || pressure > 1) {
                throw new IllegalArgumentException("invalid goal");
            }
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(description);
        }
    }

    /** Unknown weapon data stays absent. Recognise v83 Explorer/Cygnus/Aran tiers, not custom fifth jobs or Evan. */
    public static List<Goal> seed(int level, int jobId, OptionalInt weaponRequiredLevel) {
        if (level < 1 || level > 200 || jobId < 0 || weaponRequiredLevel.isPresent()
                && weaponRequiredLevel.getAsInt() < 0) {
            throw new IllegalArgumentException("invalid character facts");
        }
        List<Goal> goals = new ArrayList<>();
        int advance = nextAdvance(jobId);
        if (advance > 0) {
            goals.add(new Goal(Kind.JOB_ADVANCE, advance, "next job advance at level " + advance,
                    Math.min(1, level / (double) advance)));
        }
        if (weaponRequiredLevel.isPresent()) {
            int target = Math.max(1, level / 10 * 10);
            int gap = target - weaponRequiredLevel.getAsInt();
            if (gap >= 10) {
                goals.add(new Goal(Kind.WEAPON_UPGRADE, target, "weapon requiring level " + target + " or higher",
                        Math.min(1, gap / 30.0)));
            }
        }
        int cap = jobId >= 1000 && jobId < 2000 ? 120 : 200;
        if (goals.isEmpty() && level < cap) {
            goals.add(new Goal(Kind.LEVEL, level + 1, "reach level " + (level + 1), .25));
        }
        return List.copyOf(goals);
    }

    private static int nextAdvance(int job) {
        if (job == 0 || job == 1000 || job == 2000) return 10;
        boolean supported = job >= 100 && job <= 532 || job >= 1100 && job <= 1512
                || job == 2100 || job == 2110 || job == 2111 || job == 2112;
        if (!supported) return 0;
        int suffix = job % 100;
        if (suffix == 0) return 30;
        if (suffix == 10 || suffix == 20 || suffix == 30) return 70;
        if (suffix == 11 || suffix == 21 || suffix == 31) return 120;
        return 0;
    }
}
