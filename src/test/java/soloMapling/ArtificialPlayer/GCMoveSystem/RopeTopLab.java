package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import server.maps.MapleMap;
import server.maps.Rope;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Dev tool, not a unit test (1.4.1 "bots stuck on top of ladders"): headless bots on real maps, full observed physics,
 * sent alternately to a spot on the platform just above a rope/ladder head and to a random platform. Samples every
 * 200 ms and reports bots that sit at a rope head - hanging on the rope within 40 px of its top, or standing at the
 * rope x on the top platform - for 5 s or more while they still have somewhere to go.
 *
 *   java -cp <classes>:<test-classes>:<deps> -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav \
 *       -Dlab.minutes=4 -Dlab.botsPerMap=8 -Dlab.maps=100000000,101000000 \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.RopeTopLab
 */
public final class RopeTopLab {

    private static final java.util.concurrent.atomic.AtomicInteger ARRIVED_HANGING =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int TRACE = Integer.getInteger("lab.trace", -1);

    private RopeTopLab() {
    }

    private static final class Bot {
        final Character chr;
        final Random rnd;
        final List<BotNavigationGraph.Region> ground;
        final List<Point> ropeHeads;
        final AtomicBoolean arrived = new AtomicBoolean();
        Point target;
        long startedAt;
        long atHeadSince;
        int headX = Integer.MIN_VALUE;
        int headY = Integer.MIN_VALUE;
        boolean reported;
        int trips;
        int stalls;
        boolean toHead;

        Bot(Character chr, long seed, List<BotNavigationGraph.Region> ground, List<Point> ropeHeads) {
            this.chr = chr;
            this.rnd = new Random(seed);
            this.ground = ground;
            this.ropeHeads = ropeHeads;
        }

        Point next() {
            toHead = !toHead && !ropeHeads.isEmpty();
            if (toHead) {
                Point h = ropeHeads.get(rnd.nextInt(ropeHeads.size()));
                return new Point(h.x + rnd.nextInt(121) - 60, h.y);
            }
            BotNavigationGraph.Region r = ground.get(rnd.nextInt(ground.size()));
            int x = r.minX + 20 + rnd.nextInt(Math.max(1, r.maxX - r.minX - 40));
            return r.pointAt(x);
        }
    }

    public static void main(String[] args) throws Exception {
        int minutes = Integer.getInteger("lab.minutes", 4);
        int perMap = Integer.getInteger("lab.botsPerMap", 8);
        String[] mapIds = System.getProperty("lab.maps",
                "100000000,101000000,102000000,103000000,104040000,100050000,105040200,107000400,103000102").split(",");
        if (!Boolean.getBoolean("lab.flaws")) {
            NavMovementFlaws.tuning.enabled = false;
        }
        NavMovementFlaws.tuning.logEveryMin = 0;

        List<Bot> bots = new ArrayList<>();
        List<MapleMap> maps = new ArrayList<>();
        Map<Character, List<Rope>> ropesByBot = new HashMap<>();
        int id = 1000;
        for (String s : mapIds) {
            int mapId = Integer.parseInt(s.trim());
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
            if (graph == null) {
                System.out.println("no graph for " + mapId);
                continue;
            }
            List<BotNavigationGraph.Region> ground = new ArrayList<>();
            for (BotNavigationGraph.Region r : graph.regions) {
                if (!r.isRopeRegion && r.maxX - r.minX >= 80) {
                    ground.add(r);
                }
            }
            List<Point> heads = new ArrayList<>();
            int belowHead = 0;
            List<Rope> ropes = new ArrayList<>(map.getRopes());
            for (Rope rope : ropes) {
                Point land = BotPhysicsEngine.findTopExitLanding(map, rope);
                if (land != null) {
                    heads.add(land);
                    if (land.y > rope.topY()) {
                        belowHead++; // the step-off foothold sits below the rope head (the 1.4.1 hang)
                    }
                }
            }
            System.out.printf("map %d: %d regions, %d ropes, %d with a top step-off, %d of them below the head%n",
                    mapId, graph.regions.size(), ropes.size(), heads.size(), belowHead);
            maps.add(map);
            ObserverTracker.markObservedNow(mapId);
            for (int i = 0; i < perMap; i++) {
                Character chr = Character.getDefault(new BotClient(0, 0));
                chr.setID(++id);
                chr.setLevel(10 + (id * 7) % 60);
                setMap(chr, map);
                Bot bot = new Bot(chr, mapId * 1000L + i, ground, heads);
                chr.setPosition(bot.next());
                GCMovement.enable(chr);
                bots.add(bot);
                ropesByBot.put(chr, ropes);
            }
        }

        long end = System.currentTimeMillis() + minutes * 60_000L;
        while (System.currentTimeMillis() < end) {
            for (MapleMap map : maps) {
                ObserverTracker.markObservedNow(map.getId());
            }
            long now = System.currentTimeMillis();
            for (Bot bot : bots) {
                Point p = bot.chr.getPosition();
                if (bot.chr.getId() == TRACE) {
                    BotMovementState st = state(bot.chr);
                    System.out.printf("TRACE t=%d pos=%d,%d climbing=%s rope=%s %s%n", now % 100000, p.x, p.y,
                            GCMovement.isClimbing(bot.chr), st.climbRope, describe(st));
                }
                Rope head = null;
                boolean climbing = GCMovement.isClimbing(bot.chr);
                for (Rope r : ropesByBot.get(bot.chr)) {
                    boolean near = Math.abs(p.x - r.x()) <= 10 && p.y >= r.topY() - 30 && p.y <= r.topY() + 40;
                    if (near) {
                        head = r;
                        break;
                    }
                }
                boolean farFromTarget = bot.target != null
                        && (Math.abs(p.x - bot.target.x) > 40 || Math.abs(p.y - bot.target.y) > 40);
                if (head != null && (climbing || farFromTarget)) {
                    if (bot.atHeadSince == 0 || Math.abs(p.x - bot.headX) > 4 || Math.abs(p.y - bot.headY) > 4) {
                        bot.atHeadSince = now;
                        bot.headX = p.x;
                        bot.headY = p.y;
                        bot.reported = false;
                    } else if (!bot.reported && now - bot.atHeadSince >= 5_000) {
                        bot.reported = true;
                        bot.stalls++;
                        BotMovementState st = state(bot.chr);
                        System.out.printf("STALL map=%d bot=%d pos=%s climbing=%s rope=x%d top%d bottom%d ladder=%s target=%s moving=%s %s%n",
                                bot.chr.getMapId(), bot.chr.getId(), p, climbing, head.x(), head.topY(), head.bottomY(),
                                head.isLadder(), bot.target, GCMovement.isMoving(bot.chr), st == null ? "" : describe(st));
                    }
                } else {
                    bot.atHeadSince = 0;
                }
                boolean done = !GCMovement.isMoving(bot.chr) || now - bot.startedAt >= 45_000;
                if (bot.target == null || done) {
                    if (bot.target != null && bot.arrived.get()) {
                        bot.trips++;
                    }
                    bot.target = bot.next();
                    bot.arrived.set(false);
                    bot.startedAt = now;
                    GCMovement.move(bot.chr, bot.target.x, bot.target.y, () -> {
                        bot.arrived.set(true);
                        if (GCMovement.isClimbing(bot.chr)) {
                            ARRIVED_HANGING.incrementAndGet(); // "arrived" while still on the rope: left hanging there
                        }
                    });
                }
            }
            Thread.sleep(TRACE > 0 ? 40 : 200);
        }
        int trips = 0;
        int stalls = 0;
        for (Bot bot : bots) {
            trips += bot.trips;
            stalls += bot.stalls;
        }
        System.out.printf("RESULT bots=%d minutes=%d trips=%d ropeTopStalls=%d arrivedHanging=%d%n", bots.size(), minutes, trips, stalls,
                ARRIVED_HANGING.get());
        System.out.println("RESULT " + NavStuckLog.describe());
        System.out.println("RESULT " + NavMovementFlaws.describe());
        System.exit(0);
    }

    @SuppressWarnings("unchecked")
    private static BotMovementState state(Character chr) {
        try {
            Field f = GCMovement.class.getDeclaredField("STATES");
            f.setAccessible(true);
            return ((Map<Integer, BotMovementState>) f.get(null)).get(chr.getId());
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String describe(BotMovementState st) {
        return "nav=" + st.lastNavDecision + " edge=" + (st.navEdge == null ? "-" : st.navEdge.type + " r"
                + st.navEdge.fromRegionId + ">r" + st.navEdge.toRegionId + " step" + st.navEdge.launchStepX)
                + " block=" + st.lastEdgeBlockReason + " moveTarget=" + st.moveTarget + " grinding=" + st.grinding
                + " inAir=" + st.inAir;
    }

    private static void setMap(Character chr, MapleMap map) throws ReflectiveOperationException {
        for (Class<?> c = chr.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals("map") && MapleMap.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    f.set(chr, map);
                    return;
                }
            }
        }
        throw new IllegalStateException("no map field");
    }
}
