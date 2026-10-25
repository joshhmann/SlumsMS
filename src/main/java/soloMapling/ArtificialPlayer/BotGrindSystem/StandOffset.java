package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.function.IntPredicate;

// Where a grinder stands to fight a mob. Mobs idle on their spawn point, so "walk to the mob's x" put every bot
// that fought that spawn on the same pixel (the health sweep's same-pixel overlap pairs, ~90% within 2 px of a
// WZ spawn point). Each bot instead keeps a stable personal offset (10..40 px, side from its id), capped to half
// its approach reach so it still hits, and the pick steps aside again when another bot already holds the pixel.
// Pure math here; GrindBrain.standX supplies the live platform / crowd predicates.
final class StandOffset {

    static final int MIN_PX = 10, MAX_PX = 40, STEP_PX = 14;

    private StandOffset() {
    }

    /** The bot's stable signed offset: magnitude MIN..MAX (capped to maxAbs), side from its id. 0 when maxAbs < 1. */
    static int offsetFor(int botId, int maxAbs) {
        if (maxAbs < 1) {
            return 0;
        }
        int h = botId * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        int mag = Math.min(maxAbs, MIN_PX + Math.floorMod(h, MAX_PX - MIN_PX + 1));
        return ((h >>> 20) & 1) == 0 ? mag : -mag;
    }

    /**
     * The stand x for a bot fighting a mob at targetX. Candidates, in order: its own offset, then widening steps
     * either side; the first that is inside [lo, hi], on the platform and not already held wins. If every one is
     * held it keeps its own offset spot; if even that is off the platform it stands at targetX.
     * maxAbs = the largest offset that keeps the target in reach.
     */
    static int standX(int botId, int targetX, int lo, int hi, int maxAbs, IntPredicate onPlatform, IntPredicate held) {
        int off = offsetFor(botId, maxAbs);
        if (off == 0) {
            return GrindBrain.clamp(targetX, lo, hi);
        }
        int side = off > 0 ? 1 : -1;
        int mag = Math.abs(off);
        int firstValid = Integer.MIN_VALUE;
        for (int k = 0; k <= 3; k++) {
            int m = Math.min(maxAbs, mag + k * STEP_PX);
            for (int s : new int[]{side, -side}) {
                int c = GrindBrain.clamp(targetX + s * m, lo, hi);
                if (!onPlatform.test(c)) {
                    continue;
                }
                if (firstValid == Integer.MIN_VALUE) {
                    firstValid = c;
                }
                if (!held.test(c)) {
                    return c;
                }
            }
        }
        return firstValid != Integer.MIN_VALUE ? firstValid : GrindBrain.clamp(targetX, lo, hi);
    }
}
