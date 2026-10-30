package soloMapling.ArtificialPlayer.GCMoveSystem;

/*
 * Switches for the NN-011 movement fixes ported from nutnnut/Cosmic (AGPL-3.0. Credit: NutNNut), ticket 87 and
 * docs/design/divergence-ledger.md NN-011. Every one is OFF unless its -D property is "true"; the owner decides at
 * 1.6.1 integration which go on. One nav implementation for every bot (roster, owned alts, PQ bots): no switch here
 * is per-bot.
 *
 * Fixes that change what the nav-graph builder authors (graphTag() non-empty) also change the graph cache folder
 * (BotNavigationGraphProvider.cacheDir), so a graph built with a fix on is never loaded with it off, or the reverse.
 * Flip those only before boot (or rebuild the graphs, as the tests do).
 */
final class NavPortSwitches {

    private NavPortSwitches() {
    }

    private static boolean on(String property) {
        return "true".equalsIgnoreCase(System.getProperty(property));
    }

    /* nutnnut/Cosmic 3516aa238: a swimming bot pressed against a wall fires a swim-jump burst to rise over it. */
    static volatile boolean swimWallJump = on("bot.nav.swimWallJump");

    /* nutnnut/Cosmic 43b9f06c8 (runtime part): the parked-at-a-launch-gate watchdog keeps counting across the
     * incidental nav clears between AI ticks and counts freshly planned edges too, so a bot re-served the same
     * unexecutable hop every tick still gives it up after ~300-500 ms instead of freezing. */
    static volatile boolean blockedPosWatchdog = on("bot.nav.blockedPosWatchdog");

    /* nutnnut/Cosmic 43b9f06c8 (their v70 part b): the v67 phantom shared-ground guard also covers TELEPORT edges.
     * Graph-affecting. */
    static volatile boolean teleportPhantomGuard = on("bot.nav.teleportPhantomGuard");

    /* nutnnut/Cosmic 43b9f06c8 (their v70 part a), the riskiest: client wall collidability. Walking scans no walls
     * (the foothold chain is the only stop); in the air a wall collides only for a mover of its own zMass group or
     * the map's base group, instead of every wall the Foothold.isCollidableWall heuristic keeps. Graph-affecting:
     * walk-off, runway and arc sims all change. */
    static volatile boolean zmassWalls = on("bot.nav.zmassWalls");

    /** Graph-affecting switches as a cache-folder suffix ("" when all are off: the plain v-folder). */
    static String graphTag() {
        StringBuilder tag = new StringBuilder();
        if (teleportPhantomGuard) {
            tag.append("-tp");
        }
        if (zmassWalls) {
            tag.append("-zm");
        }
        return tag.toString();
    }
}
