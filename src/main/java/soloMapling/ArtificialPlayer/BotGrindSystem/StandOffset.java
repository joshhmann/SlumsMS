package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.function.IntPredicate;

// Where a grinder stands to fight a mob. Mobs idle on their spawn point, so "walk to the mob's x" put every bot
// that fought that spawn on the same pixel (the health sweep's same-pixel overlap pairs, ~90% within 2 px of a
// WZ spawn point). Each bot instead keeps a stable personal offset (10..40 px, side from its id), capped to half
// its approach reach so it still hits, and the pick steps aside again when another bot already holds the pixel.
// Pure math here; GrindBrain.standX supplies the live platform / crowd predicates.
final class StandOffset {

    static final int MIN_PX = 10, MAX_PX = 40, STEP_PX = 14;
    /** 1.6.2 b085: a melee bot stands this far past the mob's body edge (EngageBeat's touch step-out uses the same). */
    static final int MELEE_CLEAR_PX = 22;
    /** 1.6.2 b085: how wide a bot's personal band of stand distances is, from its nearest allowed distance. */
    static final int SPREAD_PX = 30;

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

    /** The bot's stable signed offset with its magnitude in [minAbs, maxAbs], side from its id. 0 when maxAbs < 1. */
    static int offsetFor(int botId, int minAbs, int maxAbs) {
        if (maxAbs < 1) {
            return 0;
        }
        int lo = Math.max(0, Math.min(minAbs, maxAbs));
        int h = botId * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        int mag = lo + Math.floorMod(h, maxAbs - lo + 1);
        return ((h >>> 20) & 1) == 0 ? Math.max(1, mag) : -Math.max(1, mag);
    }

    /**
     * 1.6.2 b085 (t-cb-2: 14 of 40 grinders near or over their mob): the stand distance band [min, max] from the
     * mob's centre for a bot with this approach reach. Before, every bot stood 10-40 px off the mob's x, which is
     * inside most mobs' bodies. A melee bot now stands just past the body's edge, a bot that keeps its distance
     * (archers, mages) at least comfortPx off; both stay inside the reach, which wins when the mob is too wide.
     */
    static int[] band(int mobHalfWidth, int reach, boolean keepsDistance, int comfortPx) {
        int edge = Math.max(0, mobHalfWidth) + MELEE_CLEAR_PX;
        int min = keepsDistance ? Math.max(edge, comfortPx) : edge;
        min = Math.min(min, reach);
        int max = Math.min(reach, min + SPREAD_PX);
        return new int[]{min, Math.max(min, max)};
    }

    /** standX for a stand band [minAbs, maxAbs]; when no spot in the band is on the platform, the old near spots. */
    static int standX(int botId, int targetX, int lo, int hi, int minAbs, int maxAbs, IntPredicate onPlatform,
                      IntPredicate held) {
        int off = offsetFor(botId, minAbs, maxAbs);
        if (off != 0) {
            int side = off > 0 ? 1 : -1;
            int mag = Math.abs(off);
            int firstValid = Integer.MIN_VALUE;
            for (int k = 0; k <= 3; k++) {
                int m = Math.min(maxAbs, mag + k * STEP_PX);
                for (int s : new int[]{side, -side}) {
                    int c = targetX + s * m;
                    if (c < lo || c > hi || !onPlatform.test(c)) {
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
            if (firstValid != Integer.MIN_VALUE) {
                return firstValid;
            }
            // the band is off the platform or the leash on both sides (a narrow ledge): the furthest spot that fits
            for (int m = maxAbs; m > 0; m -= 4) {
                for (int s : new int[]{side, -side}) {
                    int c = targetX + s * m;
                    if (c >= lo && c <= hi && onPlatform.test(c)) {
                        return c;
                    }
                }
            }
        }
        return standX(botId, targetX, lo, hi, Math.min(MAX_PX, Math.max(0, maxAbs)), onPlatform, held);
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
