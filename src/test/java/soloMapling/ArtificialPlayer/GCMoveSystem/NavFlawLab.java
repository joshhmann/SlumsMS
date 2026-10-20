package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Dev tool, not a unit test (ticket 33): runs bots headless on real maps with full observed physics - the same driver,
 * navigator and learning as a server, forced FULL as if a player stood on every map - and reports what the movement
 * flaws cost and whether they leak into learning. Every bot walks to a stream of random points; a run in each mode
 * with the same seeds gives the same bots (skill comes from the id) the same errands.
 *
 *   java -cp <classes>:<test-classes>:<deps> -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav \
 *       -Dlab.mode=on|off|untagged -Dlab.minutes=10 -Dlab.botsPerMap=10 -Dlab.maps=100000000,101000000 \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavFlawLab
 *
 * Modes: off = -Dbot.flaws.enabled=false; on = defaults (or any -Dbot.flaws.*); untagged = flaws on but never
 * tagged for learning, to show the drift the tag prevents. Run each mode in its own directory: learned costs and the
 * stuck log live under the working directory.
 */
public final class NavFlawLab {

    private NavFlawLab() {
    }

    private static final class Bot {
        final Character chr;
        final Random errands;
        final List<BotNavigationGraph.Region> ground;
        final AtomicBoolean arrived = new AtomicBoolean();
        boolean pending;
        long startedAt;
        int trips;
        int abandoned;
        int slow;
        long tripMs;

        Bot(Character chr, long seed, List<BotNavigationGraph.Region> ground) {
            this.chr = chr;
            this.errands = new Random(seed);
            this.ground = ground;
        }

        Point next() {
            BotNavigationGraph.Region r = ground.get(errands.nextInt(ground.size()));
            int x = r.minX + 20 + errands.nextInt(Math.max(1, r.maxX - r.minX - 40));
            return r.pointAt(x);
        }
    }

    public static void main(String[] args) throws Exception {
        String mode = System.getProperty("lab.mode", "on");
        int minutes = Integer.getInteger("lab.minutes", 10);
        int perMap = Integer.getInteger("lab.botsPerMap", 10);
        String[] mapIds = System.getProperty("lab.maps", "100000000,101000000,104040000,103000000").split(",");
        if (mode.equals("off")) {
            NavMovementFlaws.tuning.enabled = false;
        } else if (mode.equals("untagged")) {
            NavMovementFlaws.tuning.tag = false;
        }
        NavMovementFlaws.tuning.logEveryMin = 0;

        List<Bot> bots = new ArrayList<>();
        List<MapleMap> maps = new ArrayList<>();
        int id = 1000;
        for (int m = 0; m < mapIds.length; m++) {
            int mapId = Integer.parseInt(mapIds[m].trim());
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
            System.out.printf("map %d: %d regions, %d walkable targets%n", mapId, graph.regions.size(), ground.size());
            maps.add(map);
            ObserverTracker.markObservedNow(mapId);
            for (int i = 0; i < perMap; i++) {
                Character chr = Character.getDefault(new BotClient(0, 0));
                chr.setID(++id);
                chr.setLevel(10 + (id * 7) % 60);
                setMap(chr, map);
                Bot bot = new Bot(chr, mapId * 1000L + i, ground);
                chr.setPosition(bot.next());
                GCMovement.enable(chr);
                bots.add(bot);
            }
        }

        long end = System.currentTimeMillis() + minutes * 60_000L;
        while (System.currentTimeMillis() < end) {
            for (MapleMap map : maps) {
                ObserverTracker.markObservedNow(map.getId());
            }
            long now = System.currentTimeMillis();
            for (Bot bot : bots) {
                if (bot.pending && GCMovement.isMoving(bot.chr) && now - bot.startedAt < 60_000) {
                    continue;
                }
                if (bot.pending) {
                    if (bot.arrived.get()) {
                        bot.trips++;
                        bot.tripMs += now - bot.startedAt;
                    } else if (now - bot.startedAt >= 60_000) {
                        bot.slow++;
                    } else {
                        bot.abandoned++;
                    }
                }
                Point target = bot.next();
                bot.arrived.set(false);
                bot.pending = true;
                bot.startedAt = now;
                GCMovement.move(bot.chr, target.x, target.y, () -> bot.arrived.set(true));
            }
            Thread.sleep(200);
        }

        int trips = 0;
        int abandoned = 0;
        int slow = 0;
        long tripMs = 0;
        for (Bot bot : bots) {
            trips += bot.trips;
            abandoned += bot.abandoned;
            slow += bot.slow;
            tripMs += bot.tripMs;
        }
        double hours = minutes / 60.0;
        System.out.printf("RESULT mode=%s bots=%d minutes=%d trips=%d trips/bot/h=%.1f meanTripMs=%d abandoned=%d slow60s=%d%n",
                mode, bots.size(), minutes, trips, trips / (bots.size() * hours), trips == 0 ? 0 : tripMs / trips,
                abandoned, slow);
        System.out.println("RESULT " + NavMovementFlaws.describe());
        System.out.println("RESULT " + NavEdgeLearning.describe());
        System.out.println("RESULT " + NavStuckLog.describe());
        for (MapleMap map : maps) {
            BotNavigationGraph graph = BotNavigationGraphProvider.peekGraph(map, BotMovementProfile.base());
            int penalised = 0;
            double excess = 0;
            for (BotNavigationGraph.Region r : graph.regions) {
                for (BotNavigationGraph.Edge e : NavObservedEdges.outgoing(graph, r.id)) {
                    double f = NavEdgeLearning.factor(graph, e);
                    if (f > 1.0) {
                        penalised++;
                        excess += f - 1.0;
                    }
                }
            }
            System.out.printf("RESULT map=%d penalisedEdges=%d excessCost=%.2f%n", map.getId(), penalised, excess);
        }
        System.exit(0);
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
