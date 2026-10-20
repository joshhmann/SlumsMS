package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.SkillFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotProgression;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * Dev tool, not a unit test (ticket 02, on the ticket 04a nav-lab pattern): what movement skills buy a bot on real
 * maps. Two parts, both headless on the dev box's WZ:
 *
 *  plan - for each map and two movement profiles (a level 70-100 mage, a capped thief), N seeded random pairs of
 *         platforms are planned walk-only and with the skill (the navigator's own two-pass rule, preferSkillRoute):
 *         how many pairs each reaches, the planned time over pairs both reach, and how many casts a route takes.
 *  live - real-time bots with real builds (BotProgression.buildNew: real skill levels, MP, AP) walk to a stream of
 *         random points with the full driver, navigator and physics, forced FULL as if a player were on every map,
 *         once with movement skills on and once off (-Dlab.mode=on|off), flaws off as ticket 33 asks of the lab.
 *
 *   java -cp <classes>:<test-classes>:<deps> -Dwz-path=wz -Dbot.nav.cacheDir=cache/bot-nav \
 *       -Dlab.part=plan|live -Dlab.mode=on|off -Dlab.minutes=6 -Dlab.pairs=400 \
 *       -Dlab.maps=220050000,103010000,105040300,211000000 \
 *       soloMapling.ArtificialPlayer.GCMoveSystem.NavMobilityLab
 */
public final class NavMobilityLab {

    private NavMobilityLab() {
    }

    public static void main(String[] args) throws Exception {
        String part = System.getProperty("lab.part", "plan");
        String[] mapIds = System.getProperty("lab.maps", "220050000,103010000,105040300,211000000").split(",");
        NavMovementFlaws.tuning.enabled = false;
        NavMovementFlaws.tuning.logEveryMin = 0;
        if (part.equals("plan")) {
            plan(mapIds);
        } else {
            live(mapIds);
        }
        System.exit(0);
    }

    // ── plan ────────────────────────────────────────────────────────────────────────────────────────

    private static void plan(String[] mapIds) {
        int pairs = Integer.getInteger("lab.pairs", 400);
        Object[][] kinds = {
                {"mage", new BotMovementProfile(145, 120), BotNavigationGraph.SKILL_TELEPORT},
                {"thief", new BotMovementProfile(155, 123), BotNavigationGraph.SKILL_FLASH_JUMP},
        };
        for (String id : mapIds) {
            int mapId = Integer.parseInt(id.trim());
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            for (Object[] kind : kinds) {
                BotMovementProfile profile = (BotMovementProfile) kind[1];
                int mask = (Integer) kind[2];
                BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, profile);
                if (graph == null) {
                    System.out.println("no graph for " + mapId);
                    continue;
                }
                List<BotNavigationGraph.Region> ground = groundOf(graph);
                int skillEdges = 0;
                for (BotNavigationGraph.Region r : graph.regions) {
                    for (BotNavigationGraph.Edge e : graph.getOutgoing(r.id)) {
                        if (NavMovementSkills.allows(e, mask) && e.isMovementSkill()) {
                            skillEdges++;
                        }
                    }
                }
                Random rnd = new Random(mapId * 31L + mask);
                int walkReach = 0;
                int skillReach = 0;
                int both = 0;
                long walkMs = 0;
                long skillMs = 0;
                int skillRoutes = 0;
                int casts = 0;
                for (int i = 0; i < pairs; i++) {
                    BotNavigationGraph.Region a = ground.get(rnd.nextInt(ground.size()));
                    BotNavigationGraph.Region b = ground.get(rnd.nextInt(ground.size()));
                    if (a.id == b.id) {
                        continue;
                    }
                    Point start = a.pointAt((a.minX + a.maxX) / 2);
                    Point goal = b.pointAt((b.minX + b.maxX) / 2);
                    BotNavigationManager.SearchOutcome walk = BotNavigationManager.runSearch(graph, map, start, a.id,
                            b.id, goal, null, true, false, 0L, 0);
                    BotNavigationManager.SearchOutcome skill = BotNavigationManager.runSearch(graph, map, start, a.id,
                            b.id, goal, null, true, false, 0L, mask);
                    boolean walkOk = !walk.path().isEmpty();
                    boolean useSkill = !skill.path().isEmpty()
                            && (walk.path().isEmpty() || BotNavigationManager.preferSkillRoute(walk.cost(), skill.cost()));
                    BotNavigationManager.SearchOutcome chosen = useSkill ? skill : walk;
                    boolean chosenOk = !chosen.path().isEmpty();
                    walkReach += walkOk ? 1 : 0;
                    skillReach += chosenOk ? 1 : 0;
                    if (walkOk && chosenOk) {
                        both++;
                        walkMs += walk.cost();
                        skillMs += chosen.cost();
                    }
                    if (useSkill) {
                        int n = (int) chosen.path().stream().filter(BotNavigationGraph.Edge::isMovementSkill).count();
                        if (n > 0) {
                            skillRoutes++;
                            casts += n;
                        }
                    }
                }
                System.out.printf("PLAN map=%d %s regions=%d skillEdges=%d pairs=%d reach walk=%d skill=%d"
                                + " | both=%d meanWalkMs=%d meanChosenMs=%d saving=%.1f%% | routesUsingSkill=%d castsPerSkillRoute=%.2f%n",
                        mapId, kind[0], graph.regions.size(), skillEdges, pairs, walkReach, skillReach, both,
                        both == 0 ? 0 : walkMs / both, both == 0 ? 0 : skillMs / both,
                        walkMs == 0 ? 0 : 100.0 * (walkMs - skillMs) / walkMs,
                        skillRoutes, skillRoutes == 0 ? 0 : casts / (double) skillRoutes);
            }
        }
    }

    private static List<BotNavigationGraph.Region> groundOf(BotNavigationGraph graph) {
        List<BotNavigationGraph.Region> ground = new ArrayList<>();
        for (BotNavigationGraph.Region r : graph.regions) {
            if (!r.isRopeRegion && r.maxX - r.minX >= 80) {
                ground.add(r);
            }
        }
        return ground;
    }

    // ── live ────────────────────────────────────────────────────────────────────────────────────────

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

    // Mages and thieves across levels; each gets the real build of its job and level.
    private static final Job[] JOBS = {Job.FP_WIZARD, Job.IL_MAGE, Job.PRIEST, Job.BLAZEWIZARD3, Job.FP_ARCHMAGE,
            Job.HERMIT, Job.NIGHTLORD, Job.NIGHTWALKER2, Job.NIGHTWALKER3, Job.HERMIT};
    private static final int[] LEVELS = {38, 85, 95, 80, 130, 78, 135, 45, 90, 110};

    private static void live(String[] mapIds) throws Exception {
        String mode = System.getProperty("lab.mode", "on");
        int minutes = Integer.getInteger("lab.minutes", 6);
        NavMovementSkills.enabled = !mode.equals("off");
        SkillFactory.loadAllSkills();

        List<Bot> bots = new ArrayList<>();
        List<MapleMap> maps = new ArrayList<>();
        int id = 5000;
        for (String s : mapIds) {
            int mapId = Integer.parseInt(s.trim());
            MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
            BotNavigationGraph graph = BotNavigationGraphProvider.getGraph(map, BotMovementProfile.base());
            if (graph == null) {
                System.out.println("no graph for " + mapId);
                continue;
            }
            List<BotNavigationGraph.Region> ground = groundOf(graph);
            maps.add(map);
            ObserverTracker.markObservedNow(mapId);
            for (int i = 0; i < JOBS.length; i++) {
                Character chr = Character.getDefault(new BotClient(0, 0));
                chr.setID(++id);
                chr.setLevel(LEVELS[i]);
                chr.setJob(JOBS[i]);
                setMap(chr, map);
                BotProgression.buildNew(chr);
                Bot bot = new Bot(chr, mapId * 1000L + i, ground);
                chr.setPosition(bot.next());
                GCMovement.enable(chr);
                bots.add(bot);
                System.out.printf("bot %d job=%d level=%d teleport=%d flashJump=%d mp=%d/%d%n", chr.getId(),
                        chr.getJob().getId(), chr.getLevel(),
                        NavMovementSkills.skillFor(chr, BotNavigationGraph.EdgeType.TELEPORT) == 0 ? 0
                                : chr.getSkillLevel(NavMovementSkills.skillFor(chr, BotNavigationGraph.EdgeType.TELEPORT)),
                        NavMovementSkills.skillFor(chr, BotNavigationGraph.EdgeType.FLASH_JUMP) == 0 ? 0
                                : chr.getSkillLevel(NavMovementSkills.skillFor(chr, BotNavigationGraph.EdgeType.FLASH_JUMP)),
                        chr.getMp(), chr.getCurrentMaxMp());
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

        double hours = minutes / 60.0;
        for (MapleMap map : maps) {
            int trips = 0;
            int abandoned = 0;
            int slow = 0;
            long tripMs = 0;
            int n = 0;
            for (Bot bot : bots) {
                if (bot.chr.getMap() != map) {
                    continue;
                }
                n++;
                trips += bot.trips;
                abandoned += bot.abandoned;
                slow += bot.slow;
                tripMs += bot.tripMs;
            }
            System.out.printf("LIVE mode=%s map=%d bots=%d trips=%d trips/bot/h=%.1f meanTripMs=%d abandoned=%d slow60s=%d%n",
                    mode, map.getId(), n, trips, trips / (n * hours), trips == 0 ? 0 : tripMs / trips, abandoned, slow);
        }
        System.out.println("LIVE " + NavSkillCast.describe());
        System.out.println("LIVE " + NavEdgeLearning.describe());
        System.out.println("LIVE " + NavStuckLog.describe());
        for (Bot bot : bots) {
            System.out.printf("LIVE bot %d job=%d mp=%d/%d trips=%d%n", bot.chr.getId(), bot.chr.getJob().getId(),
                    bot.chr.getMp(), bot.chr.getCurrentMaxMp(), bot.trips);
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
