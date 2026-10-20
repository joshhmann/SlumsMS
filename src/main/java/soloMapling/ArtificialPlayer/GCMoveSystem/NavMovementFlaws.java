package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Human movement flaws (SlumsMS ticket 33). Planning stays smart - the nav graph and what it learned - and execution
 * sometimes fumbles on purpose, so a watched bot looks like a player instead of a frame-perfect jumper.
 *
 * <p>Every flaw is made from the same moves a player sends (a walk, a jump, a jump off a rope), launched through the
 * normal physics, so the client sees an honest arc:
 * <ul>
 *   <li><b>Hesitation</b> - a short stop at the launch spot of a jump.</li>
 *   <li><b>False start</b> - a wasted hop in place before the jump.</li>
 *   <li><b>Overrun</b> - runs a step or three past the launch spot and walks back to it.</li>
 *   <li><b>Missed jump</b> - takes off a few steps early, comes up short and drops to whatever is below.</li>
 *   <li><b>Rope slip</b> - jumps off a rope part way up.</li>
 *   <li><b>Twitch</b> - a step or two the wrong way while walking.</li>
 *   <li><b>Hop</b> - running hops along flat ground; a few personalities do this all the time.</li>
 * </ul>
 *
 * <p>How sloppy a bot is comes from who it is: a per-bot skill drawn once from its character id (so it never
 * re-rolls), improved a little by level and by time spent on the current map. Harder jumps (narrow launch window,
 * long or rising) fail more; walks never fail. A miss or slip only fires when the physics simulation of the flawed
 * arc lands on a foothold the graph knows, from which the edge's landing is reachable within
 * {@code bot.flaws.recoveryMaxMs} of planned travel - so a flaw never kills or strands a bot - and a bot never misses
 * twice in a row or the same edge twice within a few minutes. Recovery is the normal re-plan from wherever it lands.
 *
 * <p>Flaws only run where a real player can see them ({@link ObserverTracker#isFull}); offscreen bots move by the
 * coarse planner as before. Never during a commitment (trade hold, rest hang), an event instance (party quest, boss),
 * or while following a player as a party companion. Every miss and slip is tagged with
 * {@link NavEdgeLearning#tagDeliberateFlaw}, so the edge's learned cost does not move.
 *
 * <p>Tunables are -D system properties (no config keys): see {@link Tuning}.
 */
public final class NavMovementFlaws {

    private static final Logger log = LoggerFactory.getLogger(NavMovementFlaws.class);

    /** -D switches, read once at startup. */
    static final class Tuning {
        /** {@code bot.flaws.enabled} (true): the whole feature. */
        boolean enabled = bool("bot.flaws.enabled", true);
        /** {@code bot.flaws.rate} (1.0): multiplies every flaw chance. 0 = off, 2 = twice as sloppy. */
        double rate = dbl("bot.flaws.rate", 1.0);
        /** {@code bot.flaws.skillMin} / {@code skillMax} (0.85 / 0.99): success rate on the hardest jump. */
        double skillMin = dbl("bot.flaws.skillMin", 0.85);
        double skillMax = dbl("bot.flaws.skillMax", 0.99);
        /** {@code bot.flaws.hopperShare} (0.2): share of bots that hop along as they walk. */
        double hopperShare = dbl("bot.flaws.hopperShare", 0.2);
        /** {@code bot.flaws.hopRate} (1.0): multiplies hop frequency (a hopper ~ one every 4 s of walking). */
        double hopRate = dbl("bot.flaws.hopRate", 1.0);
        /** {@code bot.flaws.react} (0.2): chance a bot emotes after a fall (one in five of those also says so). */
        double react = dbl("bot.flaws.react", 0.2);
        /** {@code bot.flaws.observedOnly} (true): only where a real player is on the map. */
        boolean observedOnly = bool("bot.flaws.observedOnly", true);
        /** {@code bot.flaws.recoveryMaxMs} (12000): a miss must leave the edge's landing within this much travel. */
        int recoveryMaxMs = (int) dbl("bot.flaws.recoveryMaxMs", 12_000);
        /** {@code bot.flaws.missCooldownMs} (45000): no second miss or slip by one bot within this long. */
        long missCooldownMs = (long) dbl("bot.flaws.missCooldownMs", 45_000);
        /** {@code bot.flaws.logEveryMin} (10): the INFO summary line's period; 0 = never. */
        long logEveryMin = (long) dbl("bot.flaws.logEveryMin", 10);
        /** Not a switch: the flaw lab turns tagging off to show what learning would do without it. */
        boolean tag = true;

        private static boolean bool(String key, boolean def) {
            String v = System.getProperty(key);
            return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
        }

        private static double dbl(String key, double def) {
            String v = System.getProperty(key);
            if (v == null || v.isBlank()) {
                return def;
            }
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
    }

    static volatile Tuning tuning = new Tuning();

    /** What a bot does wrong. */
    enum Kind {
        HESITATE, FALSE_START, OVERRUN, MISS, ROPE_SLIP, TWITCH, HOP
    }

    /** A planned flaw for the edge being approached (rolled once per approach). */
    enum Plan {
        NONE, HESITATE, FALSE_START, OVERRUN, EARLY, SLIP
    }

    /** Per-bot flaw state, owned by the bot's tick thread like the rest of {@link BotMovementState}. */
    static final class State {
        /** This bot never fumbles on purpose (a nav-lab or test bot measuring the real moves). */
        volatile boolean off;
        double skill = Double.NaN;
        boolean hopper;
        long holdUntilMs;
        BotNavigationGraph.Edge planEdge;
        Plan plan = Plan.NONE;
        int planPx;               // EARLY: px before the launch X; SLIP: the Y to let go at; OVERRUN: target X
        int overrunTicks;
        boolean airborne;         // a flaw launch is in the air: the navigator holds its edge until it lands
        BotNavigationGraph.Edge flawEdge; // the edge a miss/slip was tagged on
        int flawFromRegion = -1;
        Kind flawKind;
        int twitchTicks;
        int twitchDir;
        long missReadyAtMs;
        BotNavigationGraph.Edge lastMissEdge;
        long lastMissAtMs;
        int mapId = -1;
        long mapSinceMs;
    }

    private static final Map<Kind, AtomicLong> counts = new EnumMap<>(Kind.class);
    private static final AtomicLong unsafeSkipped = new AtomicLong();
    private static final AtomicLong falls = new AtomicLong();
    private static volatile long nextLogAtMs;
    /** Test seam: the random source. */
    static volatile DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

    static {
        for (Kind kind : Kind.values()) {
            counts.put(kind, new AtomicLong());
        }
    }

    private NavMovementFlaws() {
    }

    // ── who the bot is ──────────────────────────────────────────────────────────────────────────

    /** The bot's success rate on the hardest jump: fixed per character, a little better with level. */
    static double skill(BotMovementState entry) {
        State s = entry.flaws;
        if (Double.isNaN(s.skill)) {
            int id = entry.bot != null ? entry.bot.getId() : 0;
            s.skill = baseSkill(id);
            s.hopper = frac(id, 0x484f50L) < tuning.hopperShare;
        }
        int level = entry.bot != null ? entry.bot.getLevel() : 1;
        return withLevel(s.skill, level);
    }

    /** Skill from the character id alone: most bots are fairly clean, a tail is clumsy. */
    static double baseSkill(int characterId) {
        Tuning t = tuning;
        double u = Math.pow(frac(characterId, 0x534b494cL), 0.6); // skews toward the clean end
        return t.skillMin + (t.skillMax - t.skillMin) * u;
    }

    /** A level 120 bot has closed a third of the gap to perfect. */
    static double withLevel(double skill, int level) {
        double grown = Math.min(1.0, Math.max(0, level) / 120.0) / 3.0;
        return skill + (1.0 - skill) * grown;
    }

    /** A bot gets a little better on a map it has been on a while: up to 30 % fewer flaws after half an hour. */
    static double familiarity(State s, int mapId, long now) {
        if (s.mapId != mapId) {
            s.mapId = mapId;
            s.mapSinceMs = now;
        }
        double minutes = (now - s.mapSinceMs) / 60_000.0;
        return 1.0 - 0.3 * Math.min(1.0, minutes / 30.0);
    }

    static boolean hopper(BotMovementState entry) {
        skill(entry);
        return entry.flaws.hopper;
    }

    private static double frac(long id, long salt) {
        long z = id * 0x9E3779B97F4A7C15L + salt;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }

    // ── how hard a move is ──────────────────────────────────────────────────────────────────────

    /** 0 (a walk) to 1 (a long, rising jump off a sliver of a launch window). */
    static double difficulty(BotNavigationGraph.Edge edge, int walkStep) {
        if (edge == null) {
            return 0;
        }
        return switch (edge.type) {
            case WALK, PORTAL, DROP, TELEPORT, SWIM -> 0;
            case JUMP, FLASH_JUMP -> {
                if (edge.launchStepX == 0) {
                    yield 0.2; // straight up: hard to get wrong
                }
                int width = Math.max(0, edge.launchMaxX - edge.launchMinX);
                double narrow = 1.0 - clamp01(width / (4.0 * Math.max(1, walkStep)));
                double reach = clamp01(Math.abs(edge.endPoint.x - edge.startPoint.x) / 180.0);
                double rise = clamp01((edge.startPoint.y - edge.endPoint.y) / 80.0);
                yield clamp01(0.15 + 0.45 * narrow + 0.25 * reach + 0.15 * rise);
            }
            case CLIMB -> 0.4;
        };
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ── where flaws may happen ──────────────────────────────────────────────────────────────────

    /** Flaws are for an audience, and never while something that matters is going on. */
    static boolean eligible(BotMovementState entry) {
        Tuning t = tuning;
        if (!t.enabled || t.rate <= 0 || entry == null || entry.bot == null) {
            return false;
        }
        if (entry.flaws.off) {
            return false;
        }
        Character bot = entry.bot;
        MapleMap map = bot.getMap();
        if (map == null || map.isSwim()) {
            return false;
        }
        if (entry.heldForCommitment || entry.resting || entry.following || entry.graphWarmupFallback) {
            return false; // a trade, a rest hang, a party companion on its owner
        }
        if (bot.getEventInstance() != null) {
            return false; // party quest, boss run, escort
        }
        return !t.observedOnly || ObserverTracker.isFull(bot.getMapId());
    }

    /** The bot's chance of one flaw, scaled by everything that makes it more or less likely. */
    private static double sloppiness(BotMovementState entry, long now) {
        double miss = 1.0 - skill(entry);
        return miss * tuning.rate * familiarity(entry.flaws, entry.bot.getMapId(), now);
    }

    // ── a jump ──────────────────────────────────────────────────────────────────────────────────

    /** What the navigator does with a jump this tick. */
    enum JumpCall {
        /** Carry on as normal. */
        PROCEED,
        /** Hold off this tick (hesitating, overrunning): the flaw owns the bot. */
        HOLD,
        /** A flaw launched the bot: the tick is spent. */
        LAUNCHED
    }

    /** Rolls the approach's flaw the first time the navigator looks at this jump edge. */
    static Plan planFor(BotMovementState entry, BotNavigationGraph.Edge edge, int walkStep, long now) {
        State s = entry.flaws;
        if (s.planEdge == edge) {
            return s.plan;
        }
        s.planEdge = edge;
        s.plan = Plan.NONE;
        s.overrunTicks = 0;
        if (!eligible(entry)) {
            return s.plan;
        }
        double d = difficulty(edge, walkStep);
        if (d <= 0) {
            return s.plan;
        }
        double sloppy = sloppiness(entry, now);
        double missChance = sloppy * d;
        double quirkChance = sloppy * 2.0 * (0.3 + 0.7 * d);
        double roll = random.getAsDouble();
        boolean missAllowed = now >= s.missReadyAtMs
                && !(s.lastMissEdge == edge && now - s.lastMissAtMs < TimeUnit.MINUTES.toMillis(5));
        if (roll < missChance) {
            if (missAllowed && edge.type == BotNavigationGraph.EdgeType.JUMP && edge.launchStepX != 0) {
                s.plan = Plan.EARLY;
                s.planPx = walkStep * (2 + (int) (random.getAsDouble() * 3)); // 2-4 steps early
            } else {
                s.plan = Plan.HESITATE;
            }
            return s.plan;
        }
        if (roll < missChance + quirkChance) {
            double pick = random.getAsDouble();
            s.plan = pick < 0.5 ? Plan.HESITATE : pick < 0.75 ? Plan.FALSE_START : Plan.OVERRUN;
        }
        return s.plan;
    }

    /**
     * Called by the jump executor while the bot walks toward a launch window it is not in yet. With an EARLY plan,
     * the bot takes off from here once it is the planned distance short, if the simulated arc is safe.
     */
    static JumpCall approachingJump(BotNavigationGraph graph, BotMovementState entry, Character bot,
                                    BotNavigationGraph.Edge edge, int launchX) {
        MapleMap map = bot.getMap();
        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        long now = System.currentTimeMillis();
        if (planFor(entry, edge, walkStep, now) != Plan.EARLY || entry.inAir || entry.climbing) {
            return JumpCall.PROCEED;
        }
        State s = entry.flaws;
        int dir = Integer.signum(edge.launchStepX);
        Point pos = bot.getPosition();
        int ahead = (launchX - pos.x) * dir;
        if (ahead <= 0 || entry.moveDir != dir) {
            return JumpCall.PROCEED; // not approaching along the jump: maybe later
        }
        if (ahead > s.planPx) {
            return JumpCall.PROCEED; // not there yet
        }
        if (graph.findRegionId(map, pos) != edge.fromRegionId) {
            return JumpCall.PROCEED;
        }
        s.plan = Plan.NONE; // one try per approach, whatever happens
        BotPhysicsEngine.JumpLanding landing =
                BotPhysicsEngine.simulateJumpLanding(map, pos, dir * walkStep, entry.movementProfile);
        int landed = landingRegion(graph, landing);
        if (!safeLanding(graph, map, landed, landing, edge)) {
            unsafeSkipped.incrementAndGet();
            return JumpCall.PROCEED;
        }
        tag(entry, edge);
        beginFlawFlight(entry, edge, Kind.MISS, now);
        entry.navTargetPos = new Point(edge.endPoint);
        entry.navPreciseTarget = false;
        BotMovementManager.initiateJump(entry, bot, edge.launchStepX);
        entry.fixedAirArc = true; // the jump edge itself is in flight: the navigator keeps it as it would any jump
        return JumpCall.LAUNCHED;
    }

    /**
     * Called by the jump executor when the bot stands ready to launch. HESITATE stops for a beat, FALSE_START hops in
     * place, OVERRUN runs a few steps on and comes back.
     */
    static JumpCall readyToJump(BotNavigationGraph graph, BotMovementState entry, Character bot,
                                BotNavigationGraph.Edge edge) {
        MapleMap map = bot.getMap();
        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        long now = System.currentTimeMillis();
        State s = entry.flaws;
        if (s.holdUntilMs > now) {
            return JumpCall.HOLD;
        }
        if (s.overrunTicks > 0) {
            return JumpCall.HOLD;
        }
        Plan plan = planFor(entry, edge, walkStep, now);
        switch (plan) {
            case HESITATE -> {
                s.plan = Plan.NONE;
                s.holdUntilMs = now + 250 + (long) (random.getAsDouble() * 900);
                count(Kind.HESITATE);
                return JumpCall.HOLD;
            }
            case FALSE_START -> {
                s.plan = Plan.NONE;
                Point pos = bot.getPosition();
                BotPhysicsEngine.JumpLanding landing =
                        BotPhysicsEngine.simulateJumpLanding(map, pos, 0, entry.movementProfile);
                if (landingRegion(graph, landing) != edge.fromRegionId) {
                    unsafeSkipped.incrementAndGet();
                    return JumpCall.PROCEED;
                }
                hop(entry, bot, 0, Kind.FALSE_START);
                return JumpCall.LAUNCHED;
            }
            case OVERRUN -> {
                s.plan = Plan.NONE;
                int dir = Integer.signum(edge.launchStepX);
                BotNavigationGraph.Region from = graph.getRegion(edge.fromRegionId);
                if (dir == 0 || from == null || from.isRopeRegion) {
                    return JumpCall.PROCEED;
                }
                Point pos = bot.getPosition();
                int past = walkStep * (2 + (int) (random.getAsDouble() * 3));
                int x = pos.x + dir * past;
                int margin = 24;
                if (x < from.minX + margin || x > from.maxX - margin
                        || graph.findRegionId(map, from.pointAt(x)) != edge.fromRegionId) {
                    unsafeSkipped.incrementAndGet();
                    return JumpCall.PROCEED;
                }
                s.planPx = x;
                s.overrunTicks = 6 + past / Math.max(1, walkStep);
                count(Kind.OVERRUN);
                return JumpCall.HOLD;
            }
            default -> {
                return JumpCall.PROCEED;
            }
        }
    }

    /** The bot launched this jump for real: its next approach rolls afresh. */
    static void launched(BotMovementState entry) {
        entry.flaws.planEdge = null;
        entry.flaws.plan = Plan.NONE;
    }

    /** Where steering goes during an overrun or a twitch; {@code steering} otherwise. */
    static Point steer(BotMovementState entry, Point steering) {
        State s = entry.flaws;
        if (entry.inAir || entry.climbing) {
            return steering;
        }
        if (s.overrunTicks > 0) {
            s.overrunTicks--;
            Point pos = entry.bot.getPosition();
            if (Math.abs(pos.x - s.planPx) <= BotPhysicsEngine.walkStep(entry.bot.getMap(), entry.movementProfile)) {
                s.overrunTicks = 0; // got there: turn back
                return steering;
            }
            entry.navPreciseTarget = true;
            return new Point(s.planPx, pos.y);
        }
        if (s.twitchTicks > 0) {
            s.twitchTicks--;
            Point pos = entry.bot.getPosition();
            return new Point(pos.x + s.twitchDir * 60, pos.y);
        }
        return steering;
    }

    // ── a rope ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Called while the bot climbs toward a rope exit. A SLIP plan lets go part way up: a jump off the rope to the
     * side, if the simulated landing is safe.
     */
    static boolean climbing(BotNavigationGraph graph, BotMovementState entry, Character bot,
                            BotNavigationGraph.Edge edge) {
        Rope rope = entry.climbRope;
        if (rope == null) {
            return false;
        }
        State s = entry.flaws;
        long now = System.currentTimeMillis();
        Point pos = bot.getPosition();
        if (s.planEdge != edge) {
            s.planEdge = edge;
            s.plan = Plan.NONE;
            int span = rope.bottomY() - rope.topY();
            boolean goingUp = edge.endPoint.y < pos.y - 40;
            if (goingUp && span >= 100 && eligible(entry) && now >= s.missReadyAtMs
                    && !(s.lastMissEdge == edge && now - s.lastMissAtMs < TimeUnit.MINUTES.toMillis(5))
                    && random.getAsDouble() < sloppiness(entry, now) * difficulty(edge, 1) * 0.5) {
                s.plan = Plan.SLIP;
                // Let go somewhere in the lower half of what is left to climb: a short fall.
                int top = Math.max(rope.topY() + 30, edge.endPoint.y);
                int bottom = Math.min(rope.bottomY() - 20, pos.y);
                s.planPx = bottom - (int) ((bottom - top) * (0.15 + 0.35 * random.getAsDouble()));
            }
        }
        if (s.plan != Plan.SLIP || pos.y > s.planPx || entry.inAir) {
            return false;
        }
        s.plan = Plan.NONE;
        MapleMap map = bot.getMap();
        int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
        int dir = random.getAsDouble() < 0.5 ? -1 : 1;
        for (int attempt = 0; attempt < 2; attempt++, dir = -dir) {
            BotPhysicsEngine.JumpLanding landing =
                    BotPhysicsEngine.simulateRopeJumpLanding(map, pos, dir * walkStep, entry.movementProfile);
            int landed = landingRegion(graph, landing);
            if (landed >= 0 && landing.point().y - pos.y <= 260 && safeLanding(graph, map, landed, landing, edge)) {
                tag(entry, edge);
                beginFlawFlight(entry, edge, Kind.ROPE_SLIP, now);
                BotMovementManager.jumpOffRope(entry, bot, dir * walkStep);
                entry.fixedAirArc = true;
                return true;
            }
        }
        unsafeSkipped.incrementAndGet();
        return false;
    }

    // ── walking ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Called each AI tick while the bot walks on the ground toward {@code steering}: an occasional wrong-way twitch,
     * and running hops (often, for the few personalities that hop everywhere). Returns true when it launched a hop.
     */
    static boolean walking(BotMovementState entry, Point steering, int aiTickMs) {
        if (entry.inAir || entry.climbing || steering == null || entry.moveDir == 0) {
            return false;
        }
        State s = entry.flaws;
        if (s.overrunTicks > 0 || s.twitchTicks > 0 || s.holdUntilMs > System.currentTimeMillis()) {
            return false;
        }
        BotNavigationGraph.Edge edge = entry.navEdge;
        if (edge != null && edge.type != BotNavigationGraph.EdgeType.WALK) {
            return false; // walking up to a jump, drop, rope or portal: the jump layer owns that approach
        }
        Character bot = entry.bot;
        Point pos = bot.getPosition();
        int dir = entry.moveDir;
        int ahead = (steering.x - pos.x) * dir;
        if (ahead < 150) {
            return false; // nearly there (or about to fight): no showing off
        }
        if (!eligible(entry)) {
            return false;
        }
        BotNavigationGraph graph = entry.navGraph;
        BotNavigationGraph.Region region = graph != null ? graph.getRegion(entry.lastRegionId) : null;
        if (region == null || region.isRopeRegion) {
            return false;
        }
        long now = System.currentTimeMillis();
        double perTick = aiTickMs / 1000.0;
        double sloppy = sloppiness(entry, now);
        double roll = random.getAsDouble();
        double twitch = sloppy * 0.4 * perTick;
        if (roll < twitch) {
            int back = pos.x - dir * 60;
            if (back > region.minX + 30 && back < region.maxX - 30) {
                s.twitchDir = -dir;
                s.twitchTicks = 2 + (int) (random.getAsDouble() * 3);
                count(Kind.TWITCH);
            }
            return false;
        }
        double hopsPerSecond = (hopper(entry) ? 0.25 : 0.015) * tuning.hopRate * tuning.rate;
        if (roll < twitch + hopsPerSecond * perTick) {
            MapleMap map = bot.getMap();
            int walkStep = BotPhysicsEngine.walkStep(map, entry.movementProfile);
            BotPhysicsEngine.JumpLanding landing =
                    BotPhysicsEngine.simulateJumpLanding(map, pos, dir * walkStep, entry.movementProfile);
            if (landingRegion(graph, landing) != region.id
                    || landing.point().x < region.minX + 30 || landing.point().x > region.maxX - 30
                    || (landing.point().x - pos.x) * dir >= ahead) {
                return false;
            }
            hop(entry, bot, dir * walkStep, Kind.HOP);
            return true;
        }
        return false;
    }

    // ── flight and landing ──────────────────────────────────────────────────────────────────────

    /** A hop the navigator must not plan around: it holds its edge until the bot lands. */
    private static void hop(BotMovementState entry, Character bot, int stepX, Kind kind) {
        BotMovementManager.initiateJump(entry, bot, stepX);
        entry.fixedAirArc = true;
        entry.flaws.airborne = true;
        count(kind);
    }

    private static void tag(BotMovementState entry, BotNavigationGraph.Edge edge) {
        if (tuning.tag) {
            NavEdgeLearning.tagDeliberateFlaw(entry, edge);
        }
    }

    /** A nav-lab or measuring bot runs clean: its moves are what learning should see. */
    static void setOff(BotMovementState entry, boolean off) {
        entry.flaws.off = off;
    }

    private static void beginFlawFlight(BotMovementState entry, BotNavigationGraph.Edge edge, Kind kind, long now) {
        State s = entry.flaws;
        s.airborne = true;
        s.flawEdge = edge;
        s.flawFromRegion = edge.fromRegionId;
        s.flawKind = kind;
        s.missReadyAtMs = now + tuning.missCooldownMs;
        s.lastMissEdge = edge;
        s.lastMissAtMs = now;
        count(kind);
    }

    /** While a flaw hop or slip is in the air, the navigator keeps its committed edge instead of re-planning. */
    static boolean holdsNavigation(BotMovementState entry) {
        State s = entry.flaws;
        return s.airborne && entry.inAir && s.flawKind != Kind.MISS;
    }

    /**
     * Called by the navigator after learning has seen the bot's region this tick. A landing ends the flaw: the tag is
     * spent (whatever learning did with it), and a bot that came down somewhere unplanned may react to it.
     */
    static void afterObserve(BotMovementState entry, int regionId) {
        State s = entry.flaws;
        if (entry.inAir) {
            return;
        }
        s.airborne = false;
        if (s.flawEdge == null || entry.climbing) {
            return;
        }
        BotNavigationGraph.Edge edge = s.flawEdge;
        Kind kind = s.flawKind;
        s.flawEdge = null;
        s.flawKind = null;
        if (entry.navFlawEdge == edge) {
            entry.navFlawEdge = null; // nothing counted it: spend it here, so the next real try is judged honestly
        }
        boolean wentAstray = regionId >= 0 && regionId != edge.toRegionId && regionId != s.flawFromRegion;
        if (wentAstray || kind == Kind.ROPE_SLIP) {
            falls.incrementAndGet();
            maybeReact(entry);
        }
        maybeLog();
    }

    private static final String[] UGH = {"ugh", "oops", "lol", "bruh", "missed", "rip", "welp", "ow"};
    private static final int[] EMOTES = {3, 5, 6}; // troubled, angry, bewildered

    private static void maybeReact(BotMovementState entry) {
        Character bot = entry.bot;
        if (bot == null || random.getAsDouble() >= tuning.react) {
            return;
        }
        boolean say = random.getAsDouble() < 0.2;
        String line = UGH[(int) (random.getAsDouble() * UGH.length)];
        int emote = EMOTES[(int) (random.getAsDouble() * EMOTES.length)];
        try {
            ExecutorServiceManager.getExecutorService().execute(() -> {
                if (bot.getMap() == null) {
                    return;
                }
                SocialCommands.BotEmote(bot, emote);
                if (say) {
                    SocialCommands.BotSpeak(bot, line);
                }
            });
        } catch (RuntimeException ignored) {
            // no executor (tests, shutdown): skip the flourish
        }
    }

    // ── safety ──────────────────────────────────────────────────────────────────────────────────

    private static int landingRegion(BotNavigationGraph graph, BotPhysicsEngine.JumpLanding landing) {
        if (landing == null || landing.foothold() == null) {
            return -1;
        }
        return graph.regionIdByFootholdId.getOrDefault(landing.foothold().getId(), -1);
    }

    /**
     * A flawed arc is allowed only if it comes down on a known foothold - back where it started, where it was going,
     * or somewhere the edge's landing is reachable from within {@code recoveryMaxMs} of planned travel.
     */
    static boolean safeLanding(BotNavigationGraph graph, MapleMap map, int landed,
                               BotPhysicsEngine.JumpLanding landing, BotNavigationGraph.Edge edge) {
        if (landed < 0 || landing == null) {
            return false;
        }
        if (landed == edge.fromRegionId || landed == edge.toRegionId) {
            return true;
        }
        return recoveryCost(graph, map, landing.point(), landed, edge) <= tuning.recoveryMaxMs;
    }

    static int recoveryCost(BotNavigationGraph graph, MapleMap map, Point from, int fromRegion,
                            BotNavigationGraph.Edge edge) {
        try {
            BotNavigationManager.SearchOutcome outcome = BotNavigationManager.runSearch(graph, map, from, fromRegion,
                    edge.toRegionId, edge.endPoint, null, true, false, 0L);
            List<BotNavigationGraph.Edge> path = outcome.path();
            return path == null ? Integer.MAX_VALUE : outcome.cost();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ── read-outs ───────────────────────────────────────────────────────────────────────────────

    private static void count(Kind kind) {
        counts.get(kind).incrementAndGet();
    }

    static long countForTest(Kind kind) {
        return counts.get(kind).get();
    }

    static void resetForTest() {
        counts.values().forEach(c -> c.set(0));
        unsafeSkipped.set(0);
        falls.set(0);
        tuning = new Tuning();
        random = () -> ThreadLocalRandom.current().nextDouble();
    }

    /** For the GM read-out and the periodic log line. */
    public static String describe() {
        StringBuilder sb = new StringBuilder("movement flaws:");
        for (Kind kind : Kind.values()) {
            sb.append(' ').append(kind.name().toLowerCase()).append('=').append(counts.get(kind).get());
        }
        sb.append(" falls=").append(falls.get()).append(" unsafe-skipped=").append(unsafeSkipped.get());
        if (!tuning.enabled) {
            sb.append(" (disabled)");
        }
        return sb.toString();
    }

    private static void maybeLog() {
        long every = tuning.logEveryMin;
        if (every <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextLogAtMs) {
            return;
        }
        nextLogAtMs = now + TimeUnit.MINUTES.toMillis(every);
        log.info("{}", describe());
    }
}
