package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

/**
 * Auto-QA seam for t-nn-1 / t-nn-2 (NN-011 nav ports, ticket 163): reads the live switch values and replays the swim
 * wall scenario of {@code SwimWallJumpBurstTest} in the server's JVM. The replay runs on a detached bot on a made-up map
 * (id {@link #SWIM_MAP}, no real map's graph, learning or channel instance is touched) with the switches as the server
 * has them; nothing is toggled.
 */
public final class NavPortQa {

    /** The made-up swim map's id (never a real map: 990xxxxxx is unused). */
    public static final int SWIM_MAP = 990_340_299;

    private NavPortQa() {
    }

    public static boolean swimWallJump() {
        return NavPortSwitches.swimWallJump;
    }

    public static boolean blockedPosWatchdog() {
        return NavPortSwitches.blockedPosWatchdog;
    }

    public static boolean swimNavigator() {
        return SwimNavigator.enabled;
    }

    /** What the replayed swimmer did against the rock: its highest y on the west face and furthest x. */
    public record SwimRun(int highestAtWestFace, int furthestEast, int ticks) {
    }

    /* A sea floor at y 0 from x -600 to 600 with a 600 px rock (x -40..40) in the middle; water up to y -1200. */
    static MapleMap tallRockMap(int mapId) {
        MapleMap map = new MapleMap(mapId, 0, 0, mapId, 1.0f);
        map.setSwim(true);
        map.setMapLineBoundings(-800, 100, -1200, 600);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        int[][] fhs = {
                {1, -600, 0, -40, 0, 0, 2},
                {2, -40, 0, -40, -600, 1, 3},
                {3, -40, -600, 40, -600, 2, 4},
                {4, 40, -600, 40, 0, 3, 5},
                {5, 40, 0, 600, 0, 4, 0},
        };
        for (int[] f : fhs) {
            Foothold fh = new Foothold(new Point(f[1], f[2]), new Point(f[3], f[4]), f[0]);
            fh.setPrev(f[5]);
            fh.setNext(f[6]);
            tree.insert(fh);
        }
        map.setFootholds(tree);
        return map;
    }

    /**
     * A detached swimming bot pressed against a 600 px rock with open water beyond it (the unit test's scenario):
     * {@code ticks} game ticks of the real navigation and swim physics with the live switches.
     */
    public static SwimRun swimWall(int ticks) {
        MapleMap map = tallRockMap(SWIM_MAP);
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(SWIM_MAP);
        chr.setMap(map);
        chr.setPosition(new Point(-120, 0));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.physX = -120;
        entry.physY = 0;
        Point target = new Point(200, -300);
        int highest = Integer.MAX_VALUE;
        int furthest = Integer.MIN_VALUE;
        for (int i = 0; i < ticks; i++) {
            boolean runAiTick = i % 2 == 0;
            BotNavigationManager.NavigationDirective nav = BotNavigationManager.resolveTarget(entry, target, runAiTick);
            if (!nav.consumedTick) {
                if (entry.inAir) {
                    BotMovementManager.tickSwimming(entry, nav.targetPos);
                } else {
                    BotMovementManager.tickGrounded(entry, nav.targetPos);
                }
            }
            entry.swimNextJumpAtMs -= BotPhysicsEngine.cfg.TICK_MS;
            Point p = entry.bot.getPosition();
            if (p.x >= -45 && p.x <= -38) {
                highest = Math.min(highest, p.y);
            }
            furthest = Math.max(furthest, p.x);
        }
        return new SwimRun(highest, furthest, ticks);
    }

    /** The real map whose geometry the watchdog replay uses (the Henesys department store), and its made-up id. */
    public static final int WATCHDOG_REAL_MAP = 100000102, WATCHDOG_MAP = 990_340_298;

    /** What the replayed parked bot did: AI ticks, ticks a launch gate refused it, and hops it gave up. */
    public record ParkedRun(int ticks, int gatedTicks, int giveUps) {
    }

    /**
     * {@code BlockedPosWatchdogTest}'s scenario: a detached bot on the department store's top floor (x -8, y -128)
     * heading for the exit (300, 182), its motor not run, the nav cleared after every AI tick the way an incidental
     * clear does. The geometry is the real map's, loaded under {@link #WATCHDOG_MAP}, so its graph (built and cached
     * once), learning and stuck-log entries sit under the made-up id and the real map's are never touched. Give-ups are counted where
     * the watchdog consumes the parked count.
     */
    public static ParkedRun parkedBot(int ticks) {
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(WATCHDOG_REAL_MAP, WATCHDOG_MAP);
        if (BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base()) == null) {
            return new ParkedRun(0, 0, 0);   // no graph could be built
        }
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(WATCHDOG_MAP);
        chr.setMap(map);
        Point ground = BotPhysicsEngine.findGroundPoint(map, new Point(-8, -128));
        chr.setPosition(new Point(ground));
        BotMovementState entry = new BotMovementState(chr, null);
        entry.flaws.off = true;
        entry.movementProfile = BotMovementProfile.base();
        entry.physX = ground.x;
        entry.physY = ground.y;
        Point exit = new Point(300, 182);
        int gated = 0, giveUps = 0;
        for (int i = 0; i < ticks; i++) {
            int before = entry.navBlockedPosTicks;
            BotNavigationManager.resolveTarget(entry, exit, true);
            if (before > 0 && entry.navBlockedPosTicks < before) {
                giveUps++;
            }
            if (entry.lastEdgeBlockReason != null && entry.lastEdgeBlockReason.endsWith("-pos")) {
                gated++;
            }
            BotMovementManager.clearNavigationState(entry);
        }
        return new ParkedRun(ticks, gated, giveUps);
    }

    /** Today's stuck spots of one kind on the real maps (the nav lab's log), most first, at most {@code n}. */
    public static List<String> today(NavStuckLog.Kind kind, int n) {
        return NavStuckLog.topToday(Math.max(n * 4, n)).stream().filter(l -> l.contains(" " + kind.name() + " "))
                .limit(n).toList();
    }
}
