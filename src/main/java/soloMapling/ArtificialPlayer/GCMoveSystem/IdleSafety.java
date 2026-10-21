package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Idle bots step out of mob packs (owner, 1.4.1 QA: bots that stood still to chat, rest or wait among mobs were
 * tossed around by contact hits).
 *
 * <p>A bot is idle when it stands on purpose with nowhere to go: a declared idle ({@link GCMovement#setIdle} - a
 * grind break's standing rest), a stop-reaction to a player, or a bot that is not grinding standing with no move,
 * follow or farm target (social and town bots, waiting bots, the {@code !qa} idle bots). A grinding bot between
 * swings is fighting, not idle, and is never moved. Frozen holds - a chair, a rope rest, a trade's commitment hold -
 * take no contact hits (the driver returns before the damage tick) and are left alone.
 *
 * <p>Before it idles, and every {@link #CHECK_MS} while it idles, the bot looks for live hostile mobs in reach. With
 * one near, it walks (normal nav, flaws and all) to the best safe spot it can reach: a point on a ledge with no live
 * mob within {@link #SAFE_PX}, a rope top's step-off, or a portal's ground. A contact hit taken while idle relocates
 * it at once, as soon as it lands. Unobserved maps are skipped: nobody sees them and contact damage does not run
 * there. The search is bounded (regions reachable over the cached nav graph, {@link #MAX_TRAVEL_PX} of travel,
 * {@link #MAX_CANDIDATES} points) and never builds a graph.
 */
final class IdleSafety {

    static volatile boolean enabled = !"false".equalsIgnoreCase(System.getProperty("bot.idleSafety", "true"));

    static final long CHECK_MS = 500;
    static final long NO_SPOT_BACKOFF_MS = 4_000;
    static final int MAX_DANGER_RELOCATIONS = 3;   // per idle episode; a hit still relocates past this
    static final int THREAT_X = 200;               // a live mob this close sideways ...
    static final int THREAT_Y = 90;                // ... and this close vertically is "in range"
    static final int SAFE_PX = 260;                // axis-aware clearance a safe spot needs from every live mob
    static final int CLEAR_CAP_PX = 700;           // clearance beyond this earns nothing more
    static final int MAX_TRAVEL_PX = 1400;         // walk + 3x climb budget for a relocation
    static final int MAX_CANDIDATES = 400;
    static final int EDGE_MARGIN = 30;
    static final double ABOVE_WEIGHT = 3.0;        // mobs do not climb: height above them is worth more
    static final double BELOW_WEIGHT = 1.5;
    static final double TRAVEL_WEIGHT = 0.25;

    enum Kind { LEDGE, ROPE_TOP, PORTAL }

    record Candidate(Point point, int regionId, Kind kind, int travel) {
    }

    record Choice(Candidate candidate, double clearance) {
    }

    private IdleSafety() {
    }

    // ── idle detection ──────────────────────────────────────────────────────────────────────────

    /** Why the bot counts as idle now, or null when it is moving, fighting, held or in the air. */
    static String idleReason(BotMovementState entry, long now) {
        if (entry.idleRelocating) {
            if (entry.moveTarget != null) {
                return null; // walking to its safe spot
            }
            entry.idleRelocating = false;
        }
        boolean noTarget = entry.moveTarget == null && !entry.following && entry.farmAnchor == null;
        if (entry.reactingUntilMs > now) {
            return "react";
        }
        if (!noTarget) {
            return null;
        }
        if (entry.idleReason != null) {
            return entry.idleReason;
        }
        if (!entry.grinding && entry.navEdge == null) {
            return "stand";
        }
        return null;
    }

    /**
     * One driver tick for a grounded, unfrozen bot on an observed map. Returns true when it issued a relocation
     * (the move target is set and the driver walks it this tick).
     */
    static boolean tick(BotMovementState entry, Character bot) {
        if (!enabled || bot == null || bot.getMap() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String reason = idleReason(entry, now);
        if (reason == null) {
            if (!entry.idleRelocating) {
                entry.idleEpisodeRelocations = 0;
            }
            entry.idleHitPending = false;
            return false;
        }
        if (entry.inAir || entry.climbing) {
            return false; // knocked into the air: relocate once it lands
        }
        MapleMap map = bot.getMap();
        if ("stand".equals(reason) && (map.getEventInstance() != null || GCTravel.isTraveling(bot))) {
            return false; // PQ/event bots stand where their script put them; a trip waits at its NPC or dock
        }
        boolean hit = entry.idleHitPending;
        if (!hit && now < entry.idleCheckAtMs) {
            return false;
        }
        entry.idleCheckAtMs = now + CHECK_MS;
        Point pos = bot.getPosition();
        if (!hit && (entry.idleEpisodeRelocations >= MAX_DANGER_RELOCATIONS || !threatNear(map, pos))) {
            return false;
        }
        List<Point> mobs = liveMobPositions(map);
        entry.idleHitPending = false;
        Choice choice = findSafeSpot(entry, map, pos, mobs);
        if (choice == null) {
            entry.idleCheckAtMs = now + NO_SPOT_BACKOFF_MS;
            qa(map, bot.getName() + " idle(" + reason + ") at " + fmt(pos) + (hit ? " was hit" : " has mobs near")
                    + ": no safe spot in reach, staying");
            return false;
        }
        Point to = choice.candidate().point();
        entry.reactingUntilMs = 0L;
        entry.moveTarget = new Point(to);
        entry.moveTargetPrecise = false;
        entry.moveTargetSource = "idle-safe";
        entry.moveBestDist = Integer.MAX_VALUE;
        entry.moveProgressAtMs = now;
        entry.idleRelocating = true;
        entry.idleEpisodeRelocations++;
        entry.idleRelocationsTotal++;
        qa(map, bot.getName() + " idle(" + reason + ") " + (hit ? "HIT" : "mobs near") + " at " + fmt(pos)
                + " -> " + choice.candidate().kind() + " " + fmt(to) + " clearance " + Math.round(choice.clearance())
                + " travel " + choice.candidate().travel());
        return true;
    }

    /** A contact hit landed (BotContactDamage): when the bot was idle it relocates as soon as it lands. */
    static void onHit(BotMovementState entry, Character bot, Monster mob) {
        String reason = idleReason(entry, System.currentTimeMillis());
        if (reason == null) {
            return;
        }
        entry.idleHitsTotal++;
        entry.idleHitPending = enabled;
        if (bot != null && bot.getMap() != null) {
            qa(bot.getMap(), bot.getName() + " idle(" + reason + ") took a hit from mob " + (mob != null ? mob.getId() : 0)
                    + " at " + fmt(bot.getPosition()) + (enabled ? "" : " (idle safety off)"));
        }
    }

    // ── threat model ────────────────────────────────────────────────────────────────────────────

    static List<Point> liveMobPositions(MapleMap map) {
        List<Point> out = new ArrayList<>();
        for (Monster mob : map.getAllMonsters()) {
            if (isLiveHostile(mob)) {
                out.add(new Point(mob.getPosition()));
            }
        }
        return out;
    }

    private static boolean isLiveHostile(Monster mob) {
        return mob != null && mob.isAlive() && mob.getPosition() != null
                && (mob.getStats() == null || !mob.getStats().isFriendly());
    }

    /** A live hostile mob in reach, by the same point query the contact-damage tick uses (cheap on a town map). */
    static boolean threatNear(MapleMap map, Point pos) {
        Rectangle box = new Rectangle(pos.x - THREAT_X, pos.y - THREAT_Y, THREAT_X * 2, THREAT_Y * 2);
        for (MapObject obj : map.getMapObjectsInRect(box, List.of(MapObjectType.MONSTER))) {
            if (isLiveHostile((Monster) obj)) {
                return true;
            }
        }
        return false;
    }

    static boolean threatNear(Point pos, List<Point> mobs) {
        Rectangle box = new Rectangle(pos.x - THREAT_X, pos.y - THREAT_Y, THREAT_X * 2, THREAT_Y * 2);
        for (Point m : mobs) {
            if (box.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** Axis-aware distance from a spot to the nearest live mob (height above a mob counts triple). */
    static double clearance(Point p, List<Point> mobs) {
        double best = Double.MAX_VALUE;
        for (Point m : mobs) {
            double gx = Math.abs(p.x - m.x);
            int dy = m.y - p.y; // > 0: the spot is above the mob
            double d = gx + Math.abs(dy) * (dy > 0 ? ABOVE_WEIGHT : BELOW_WEIGHT);
            best = Math.min(best, d);
        }
        return best;
    }

    /**
     * The best candidate: clear of every live mob by {@link #SAFE_PX}, then most clearance (capped), least travel,
     * rope tops and portals slightly preferred. When none is fully safe, the clearest one if it beats staying put by
     * a real margin; else null.
     */
    static Choice choose(List<Candidate> candidates, List<Point> mobs, Point here) {
        double hereClear = clearance(here, mobs);
        Choice best = null;
        double bestScore = -Double.MAX_VALUE;
        Choice fallback = null;
        for (Candidate c : candidates) {
            double clear = clearance(c.point(), mobs);
            if (clear < SAFE_PX) {
                if (clear > hereClear + 120 && (fallback == null || clear > fallback.clearance())) {
                    fallback = new Choice(c, clear);
                }
                continue;
            }
            double bonus = c.kind() == Kind.ROPE_TOP ? 40 : c.kind() == Kind.PORTAL ? 20 : 0;
            double score = Math.min(clear, CLEAR_CAP_PX) - TRAVEL_WEIGHT * c.travel() + bonus;
            if (score > bestScore) {
                bestScore = score;
                best = new Choice(c, clear);
            }
        }
        return best != null ? best : fallback;
    }

    // ── candidates ──────────────────────────────────────────────────────────────────────────────

    static int travel(Point from, Point to) {
        return Math.abs(to.x - from.x) + 3 * Math.abs(to.y - from.y);
    }

    private static Choice findSafeSpot(BotMovementState entry, MapleMap map, Point pos, List<Point> mobs) {
        BotNavigationGraph graph = BotNavigationGraphProvider.peekBestGraph(map, entry.movementProfile);
        if (graph == null) {
            return null; // never build a graph from the driver
        }
        int start = startRegion(graph, map, pos);
        if (start < 0) {
            return null;
        }
        Set<Integer> reachable = reachable(graph, start);
        List<Candidate> candidates = candidates(graph, reachable, pos);
        for (Portal portal : map.getPortals()) {
            Point p = portal.getPosition();
            Point ground = p == null ? null : BotPhysicsEngine.findGroundPoint(map, new Point(p.x, p.y - 5));
            int rid = ground == null ? -1 : graph.findRegionId(map, ground);
            if (rid >= 0 && reachable.contains(rid)) {
                add(candidates, ground, rid, Kind.PORTAL, pos);
            }
        }
        return choose(candidates, mobs, pos);
    }

    /** The ledge under the bot; when the foothold lookup misses (a pixel off), the ledge right under its x. */
    static int startRegion(BotNavigationGraph graph, MapleMap map, Point pos) {
        int rid = graph.findRegionId(map, pos);
        if (rid >= 0) {
            return rid;
        }
        int best = -1;
        int bestDy = 24;
        for (BotNavigationGraph.Region r : graph.regions) {
            if (r.isRopeRegion || pos.x < r.minX || pos.x > r.maxX) {
                continue;
            }
            int dy = Math.abs(r.pointAt(pos.x).y - pos.y);
            if (dy < bestDy) {
                bestDy = dy;
                best = r.id;
            }
        }
        return best;
    }

    /** Points among the reachable regions within the travel budget: ledge samples and rope-top step-offs. */
    static List<Candidate> candidates(BotNavigationGraph graph, Set<Integer> reachable, Point pos) {
        List<Candidate> out = new ArrayList<>();
        for (BotNavigationGraph.Region r : graph.regions) {
            if (out.size() >= MAX_CANDIDATES) {
                break;
            }
            if (!reachable.contains(r.id)) {
                continue;
            }
            if (r.isRopeRegion) {
                for (BotNavigationGraph.Edge e : graph.getOutgoing(r.id)) {
                    BotNavigationGraph.Region to = graph.getRegion(e.toRegionId);
                    if (to != null && !to.isRopeRegion && e.endPoint != null && e.endPoint.y <= r.minY + 30) {
                        add(out, e.endPoint, to.id, Kind.ROPE_TOP, pos);
                    }
                }
                continue;
            }
            int lo = r.minX + EDGE_MARGIN;
            int hi = r.maxX - EDGE_MARGIN;
            if (lo > hi) {
                lo = hi = (r.minX + r.maxX) / 2;
            }
            int steps = Math.min(8, Math.max(1, (hi - lo) / 60));
            for (int i = 0; i <= steps; i++) {
                int x = lo + (int) ((long) (hi - lo) * i / steps);
                add(out, r.pointAt(x), r.id, Kind.LEDGE, pos);
            }
        }
        return out;
    }

    private static void add(List<Candidate> out, Point p, int regionId, Kind kind, Point from) {
        if (p == null) {
            return;
        }
        int t = travel(from, p);
        if (t <= MAX_TRAVEL_PX) {
            out.add(new Candidate(new Point(p), regionId, kind, t));
        }
    }

    /** Regions reachable from {@code start} by walking, jumping, dropping and climbing (no job skills). */
    static Set<Integer> reachable(BotNavigationGraph graph, int start) {
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int rid = queue.poll();
            for (BotNavigationGraph.Edge e : NavObservedEdges.outgoing(graph, rid)) {
                if (e.toRegionId != rid && !e.isMovementSkill() && e.type != BotNavigationGraph.EdgeType.PORTAL
                        && seen.add(e.toRegionId)) {
                    queue.add(e.toRegionId);
                }
            }
        }
        return seen;
    }

    // ── !qa id-1 ────────────────────────────────────────────────────────────────────────────────

    private static void qa(MapleMap map, String msg) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.IDLE)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.IDLE, map.getId(), msg);
        }
    }

    private static String fmt(Point p) {
        return p == null ? "?" : "(" + p.x + "," + p.y + ")";
    }
}
