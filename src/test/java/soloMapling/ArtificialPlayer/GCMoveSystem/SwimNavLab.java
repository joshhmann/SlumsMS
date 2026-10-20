package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Dev tool, not a unit test (ticket 34): runs bots headless on swim maps with full observed physics - the same driver,
 * navigator, swim integrator and learning as a server, forced FULL as if a player stood on every map - and reports
 * how well they get around. Every bot swims to a stream of random points: half on platforms, half in open water,
 * all reachable in the swim graph from where the bot starts. A run in each mode with the same seeds gives the same
 * bots the same errands.
 *
 *   java -cp <classes>:<test-classes>:<deps> -Dwz-path=wz -Dbot.nav.cacheDir=<scratch> \
 *       -Dlab.mode=after|before -Dlab.minutes=5 -Dlab.botsPerMap=4 -Dlab.maps=230040000,230040100 \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.SwimNavLab
 *
 * Modes: before = -Dbot.swimnav.enabled=false (steer straight at the target, the fallback swim maps had before
 * ticket 34); after = the swim graph. Run each mode in its own cache dir: learned costs live there.
 *
 * Per trip: reached (arrival callback), slow (not there after lab.tripSec, default 60), abandoned (the driver gave the
 * move up). Stuck counts are the stuck log's (SWIM_STUCK, AIR_FROZEN = the frozen-in-water watchdog teleport,
 * FELL_OFF_MAP).
 */
public final class SwimNavLab {

    static final String[] AQUA_ROAD = {
            "230000000", "230010000", "230010001", "230010100", "230010200", "230010300", "230010400", "230020000",
            "230020100", "230020101", "230020200", "230020300", "230030000", "230030001", "230030100", "230030200",
            "230040000", "230040100", "230040200", "230040300", "230040400"
    };

    private SwimNavLab() {
    }

    private static final class Bot {
        final Character chr;
        final int mapId;
        final Random errands;
        final List<Point> targets;
        final AtomicBoolean arrived = new AtomicBoolean();
        boolean pending;
        long startedAt;
        int trips;
        int slow;
        int abandoned;
        long tripMs;
        long tripPx;
        Point target;

        Bot(Character chr, int mapId, long seed, List<Point> targets) {
            this.chr = chr;
            this.mapId = mapId;
            this.errands = new Random(seed);
            this.targets = targets;
        }

        Point next() {
            return targets.get(errands.nextInt(targets.size()));
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("lab.mode", "after");
        double minutes = Double.parseDouble(System.getProperty("lab.minutes", "5"));
        int perMap = Integer.getInteger("lab.botsPerMap", 4);
        long tripLimitMs = Integer.getInteger("lab.tripSec", 60) * 1000L;
        String[] mapIds = System.getProperty("lab.maps", String.join(",", AQUA_ROAD)).split(",");
        SwimNavigator.enabled = !mode.equals("before");
        NavMovementFlaws.tuning.logEveryMin = 0;

        List<Bot> bots = new ArrayList<>();
        List<MapleMap> maps = new ArrayList<>();
        int id = 5000;
        for (String raw : mapIds) {
            int mapId = Integer.parseInt(raw.trim());
            MapleMap map;
            try {
                map = BotNavigationMapLoader.loadMapGeometry(mapId);
            } catch (RuntimeException e) {
                System.out.println("skip " + mapId + ": " + e.getMessage());
                continue;
            }
            if (!map.isSwim()) {
                System.out.println("skip " + mapId + ": not a swim map");
                continue;
            }
            long t0 = System.nanoTime();
            SwimNavGraph graph = SwimNavGraph.getOrBuild(map); // targets come from it in both modes
            System.out.printf("%s (built in %d ms)%n", graph.describe(), (System.nanoTime() - t0) / 1_000_000);
            maps.add(map);
            ObserverTracker.markObservedNow(mapId);
            Random spawnPick = new Random(mapId);
            for (int i = 0; i < perMap; i++) {
                int spawn = randomFloorNode(graph, spawnPick);
                List<Point> targets = reachableTargets(graph, map, spawn);
                if (targets.size() < 4) {
                    continue;
                }
                Character chr = Character.getDefault(new BotClient(0, 0));
                chr.setID(++id);
                chr.setLevel(90 + id % 20);
                setMap(chr, map);
                chr.setPosition(graph.point(spawn));
                GCMovement.enable(chr);
                bots.add(new Bot(chr, mapId, mapId * 1000L + i, targets));
            }
        }

        long end = System.currentTimeMillis() + Math.round(minutes * 60_000);
        while (System.currentTimeMillis() < end) {
            for (MapleMap map : maps) {
                ObserverTracker.markObservedNow(map.getId());
            }
            long now = System.currentTimeMillis();
            for (Bot bot : bots) {
                if (bot.pending) {
                    if (bot.arrived.get()) {
                        bot.trips++;
                        bot.tripMs += now - bot.startedAt;
                    } else if (now - bot.startedAt >= tripLimitMs) {
                        bot.slow++;
                        if (Boolean.getBoolean("lab.debug")) {
                            BotMovementState st = stateOf(bot.chr);
                            System.out.printf("SLOW map=%d at=%s target=%s decision=%s inAir=%s swim=%s%n", bot.mapId,
                                    bot.chr.getPosition(), bot.target, st == null ? "?" : st.lastNavDecision,
                                    st != null && st.inAir,
                                    st == null || st.swimNav == null || st.swimNav.wps == null ? "-"
                                            : "p" + st.swimNav.p + "/" + st.swimNav.wps.length + " next "
                                            + st.swimNav.wps[st.swimNav.p] + " floor " + st.swimNav.floor[st.swimNav.p]);
                        }
                    } else if (!GCMovement.isMoving(bot.chr)) {
                        bot.abandoned++;
                        if (Boolean.getBoolean("lab.debug")) {
                            BotMovementState st = stateOf(bot.chr);
                            System.out.printf("ABANDON map=%d at=%s target=%s decision=%s swim=%s%n", bot.mapId,
                                    bot.chr.getPosition(), bot.target, st == null ? "?" : st.lastNavDecision,
                                    st == null || st.swimNav == null || st.swimNav.wps == null ? "-"
                                            : "p" + st.swimNav.p + "/" + st.swimNav.wps.length + " next "
                                            + st.swimNav.wps[st.swimNav.p] + " floor " + st.swimNav.floor[st.swimNav.p]);
                        }
                    } else {
                        if (Boolean.getBoolean("lab.debug") && now - bot.startedAt > 20_000 && (now / 100) % 5 == 0) {
                            BotMovementState st = stateOf(bot.chr);
                            if (st != null && st.swimNav != null && st.swimNav.wps != null) {
                                System.out.printf("TRACE bot=%d t=%d at=%s air=%s dec=%s p%d/%d next=%s floor=%s target=%s%n",
                                        bot.chr.getId(), (now - bot.startedAt) / 1000, bot.chr.getPosition(), st.inAir,
                                        st.lastNavDecision, st.swimNav.p, st.swimNav.wps.length,
                                        st.swimNav.wps[st.swimNav.p], st.swimNav.floor[st.swimNav.p], bot.target);
                            }
                        }
                        continue;
                    }
                }
                Point target = bot.next();
                Point from = bot.chr.getPosition();
                bot.tripPx += Math.abs(target.x - from.x) + Math.abs(target.y - from.y);
                bot.target = target;
                bot.arrived.set(false);
                bot.pending = true;
                bot.startedAt = now;
                GCMovement.move(bot.chr, target.x, target.y, () -> bot.arrived.set(true));
            }
            Thread.sleep(100);
        }

        Map<Integer, int[]> perMapStats = new LinkedHashMap<>();
        int trips = 0;
        int slow = 0;
        int abandoned = 0;
        long tripMs = 0;
        for (Bot bot : bots) {
            trips += bot.trips;
            slow += bot.slow;
            abandoned += bot.abandoned;
            tripMs += bot.tripMs;
            int[] s = perMapStats.computeIfAbsent(bot.mapId, k -> new int[4]);
            s[0] += bot.trips;
            s[1] += bot.slow;
            s[2] += bot.abandoned;
            s[3] += (int) (bot.tripMs / 1000);
        }
        int attempts = trips + slow + abandoned;
        System.out.printf("RESULT mode=%s maps=%d bots=%d minutes=%.1f attempts=%d reached=%d (%.1f%%) slow=%d (%.1f%%)"
                        + " abandoned=%d (%.1f%%) meanTripMs=%d%n",
                mode, maps.size(), bots.size(), minutes, attempts, trips, pct(trips, attempts), slow,
                pct(slow, attempts), abandoned, pct(abandoned, attempts), trips == 0 ? 0 : tripMs / trips);
        for (Map.Entry<Integer, int[]> e : perMapStats.entrySet()) {
            int[] s = e.getValue();
            int a = s[0] + s[1] + s[2];
            System.out.printf("RESULT map=%d attempts=%d reached=%.0f%% slow=%d abandoned=%d meanTripMs=%d%n",
                    e.getKey(), a, pct(s[0], a), s[1], s[2], s[0] == 0 ? 0 : s[3] * 1000L / s[0]);
        }
        Map<NavStuckLog.Kind, Integer> kinds = new java.util.EnumMap<>(NavStuckLog.Kind.class);
        for (NavStuckLog.Spot spot : NavStuckLog.todaySpots()) {
            kinds.merge(spot.kind, spot.count, Integer::sum);
        }
        System.out.println("RESULT stuck by kind " + kinds);
        System.out.println("RESULT " + SwimNavigator.describe());
        System.out.println("RESULT " + NavEdgeLearning.describe());
        for (String line : NavStuckLog.topToday(8)) {
            System.out.println("RESULT top " + line);
        }
        System.exit(0);
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
    }

    private static int randomFloorNode(SwimNavGraph graph, Random random) {
        List<Integer> floors = new ArrayList<>();
        for (int i = 0; i < graph.size(); i++) {
            if (graph.kinds[i] == SwimNavGraph.Kind.FLOOR) {
                floors.add(i);
            }
        }
        return floors.get(random.nextInt(floors.size()));
    }

    /* Every node reachable from spawn, as targets: platforms as they are, water nodes as they are. */
    static List<Point> reachableTargets(SwimNavGraph graph, MapleMap map, int spawn) {
        boolean[] seen = new boolean[graph.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(spawn);
        seen[spawn] = true;
        List<Point> floors = new ArrayList<>();
        List<Point> water = new ArrayList<>();
        while (!queue.isEmpty()) {
            int node = queue.poll();
            (graph.kinds[node] == SwimNavGraph.Kind.FLOOR ? floors : water).add(graph.point(node));
            for (BotNavigationGraph.Edge e : graph.outgoing(node)) {
                if (!seen[e.toRegionId]) {
                    seen[e.toRegionId] = true;
                    queue.add(e.toRegionId);
                }
            }
        }
        // Equal shares: the list is sampled uniformly, so interleave the two kinds.
        List<Point> targets = new ArrayList<>();
        int n = Math.min(floors.size(), water.size());
        Random r = new Random(map.getId());
        for (int i = 0; i < Math.max(n, 1) && !floors.isEmpty() && !water.isEmpty(); i++) {
            targets.add(floors.get(r.nextInt(floors.size())));
            targets.add(water.get(r.nextInt(water.size())));
        }
        return targets;
    }

    @SuppressWarnings("unchecked")
    private static BotMovementState stateOf(Character chr) {
        try {
            Field f = GCMovement.class.getDeclaredField("STATES");
            f.setAccessible(true);
            return ((Map<Integer, BotMovementState>) f.get(null)).get(chr.getId());
        } catch (ReflectiveOperationException e) {
            return null;
        }
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
