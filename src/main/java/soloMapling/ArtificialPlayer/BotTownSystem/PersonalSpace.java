package soloMapling.ArtificialPlayer.BotTownSystem;

import client.Character;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.5.0 rc6 (owner: "in towns bots stand exactly lined up, pixel to pixel; their name tags overlap so you can't click
 * a name"): personal space for town and hangout bots.
 *
 * <p>Two halves:
 * <ul>
 *   <li><b>Resting</b> ({@link #maybeKeep}, {@link #keepNow}): when a bot stands idle and another character (bot or
 *   player) is within {@link #CROWD_PX} horizontally on the same foothold, the one that arrived last steps 40-120 px to
 *   a free spot on its own ground (never off a ledge). Bots never move a player and never crowd one: next to a player
 *   the bot always steps. A deliberate pair (a couple, a bot talking to a player, bots in a scene, shop keepers at their
 *   shops) may stand closer, but not on the same pixel: the newer one steps to 15-30 px and faces its partner.</li>
 *   <li><b>Choosing</b> ({@link #freeSpotNear}): a destination is checked against where everyone stands and where other
 *   bots are already walking (short-lived reservations); a taken one is swapped for a spot on a jittered ring around
 *   it. Used for NPC fronts, portals, spawn points, the gachapon and every town drift/loiter target.</li>
 * </ul>
 *
 * <p>Cheap: a check runs when a bot comes to rest and then at most every {@link #CHECK_EVERY_MS} while it idles, over the
 * map's character list already in memory. Old-engine (recorded-path) bots step with the recorded small-move packets
 * ({@link MovementCommands#BotMoveSmallDistanceX}), which the client draws as a short walk, never a teleport; physics
 * bots walk with {@link GCMovement#move}.
 *
 * <p>Root cause it fixes: recorded-path walks end on the nearest RECORDED sample point (within 40 px of the target), so
 * two bots sent to nearby spots land on the very same pixel; town spot pickers and spawners only spaced the bots of one
 * call, never against bots already there; returning grinders whose ledge claims were full stayed on the arrival portal;
 * and the grind-only personal space (GrindBrain) never ran in town.
 */
public final class PersonalSpace {

    private PersonalSpace() {
    }

    /** Another character this close horizontally (px) on the same foothold is crowding. */
    public static final int CROWD_PX = 25;
    /** Same foothold: feet within this many px vertically. */
    public static final int SAME_FH_DY = 6;
    /** How far a crowded bot steps (px). */
    public static final int STEP_MIN = 40, STEP_MAX = 120;
    /** A free spot has nobody within this many px horizontally (and {@link #CLEAR_DY} vertically). */
    public static final int CLEAR_PX = 30, CLEAR_DY = 20;
    /** A deliberate pair stands this far apart (px). */
    public static final int PAIR_MIN = 15, PAIR_MAX = 30;
    /** Ground under a step may rise/fall this much per {@link #PATH_STEP} px; more is a ledge edge or a wall. */
    static final int LEDGE_STEP_DY = 8, PATH_STEP = 10;
    /** Recorded small moves are flat recordings: the target ground must be this level with the bot (px). */
    static final int FLAT_DY = 4, SLOPE_DY = 30;
    /** A recorded small move may run past its target by about this much (px); the ground there is checked too. */
    static final int OVERSHOOT_PX = 35;
    /** How often an idle bot looks around (ms). */
    public static final long CHECK_EVERY_MS = 4_000;
    /** A destination another bot reserved stays taken this long (ms). */
    static final long RESERVE_MS = 20_000;
    static final int TRIES = 12;

    /** The ground's y at {@code x} for a bot standing at height {@code nearY}, or null (no floor, off the map). */
    @FunctionalInterface
    public interface Ground {
        Integer yAt(int x, int nearY);
    }

    /**
     * One character as the check sees it. {@code player}: a real (or pretend) player, never moved. {@code movable}: a bot
     * that runs this check itself (so it can be left to step away on its own). {@code restSinceMs}: when it came to
     * rest where it stands.
     */
    public record Body(int id, int x, int y, boolean player, boolean movable, long restSinceMs) {
    }

    /* ------------------------------------------------------------------ pure rules */

    /** Whether {@code o} crowds {@code me}: within {@link #CROWD_PX} horizontally on the same foothold. */
    public static boolean crowds(Body me, Body o) {
        return Math.abs(me.x() - o.x()) < CROWD_PX && Math.abs(me.y() - o.y()) <= SAME_FH_DY;
    }

    /** Whether a deliberate pair stands too close (closer than {@link #PAIR_MIN}, on the same foothold). */
    public static boolean pairTooClose(Body me, Body o) {
        return Math.abs(me.x() - o.x()) < PAIR_MIN && Math.abs(me.y() - o.y()) <= SAME_FH_DY;
    }

    /**
     * Of a crowding pair, whether {@code me} steps: always next to a player or a character that won't move itself; else
     * the newer arrival steps, and on a tie the higher id.
     */
    public static boolean iStep(Body me, Body o) {
        if (o.player() || !o.movable()) {
            return true;
        }
        if (me.restSinceMs() != o.restSinceMs()) {
            return me.restSinceMs() > o.restSinceMs();
        }
        return me.id() > o.id();
    }

    /**
     * Who {@code me} has to make room for, or null: the closest character that crowds it (a deliberate partner only
     * when it stands closer than {@link #PAIR_MIN}) and for which {@code me} is the one to step.
     */
    public static Body crowder(Body me, List<Body> others, java.util.function.BiPredicate<Body, Body> pair) {
        Body best = null;
        for (Body o : others) {
            if (o == null || o.id() == me.id()) {
                continue;
            }
            boolean deliberate = pair != null && pair.test(me, o);
            if (deliberate ? !pairTooClose(me, o) : !crowds(me, o)) {
                continue;
            }
            if (!iStep(me, o)) {
                continue;
            }
            if (best == null || Math.abs(o.x() - me.x()) < Math.abs(best.x() - me.x())) {
                best = o;
            }
        }
        return best;
    }

    /** Whether {@code p} is clear of every point in {@code taken} ({@link #CLEAR_PX} / {@link #CLEAR_DY}). */
    public static boolean clear(Point p, List<Point> taken) {
        return clear(p, taken, CLEAR_PX);
    }

    static boolean clear(Point p, List<Point> taken, int gap) {
        for (Point t : taken) {
            if (t != null && Math.abs(t.x - p.x) < gap && Math.abs(t.y - p.y) < CLEAR_DY) {
                return false;
            }
        }
        return true;
    }

    /**
     * The ground at {@code tx} when the walk from {@code from} to it stays on the same ground: every
     * {@link #PATH_STEP} px the floor exists and rises or falls at most {@link #LEDGE_STEP_DY}, the run continues
     * {@code overshoot} px past the target, and the target ends within {@code maxDy} of the start. Null otherwise (a
     * ledge edge, a gap, a step up onto another platform).
     */
    public static Point walkable(Point from, int tx, Ground ground, int maxDy, int overshoot) {
        int dir = tx >= from.x ? 1 : -1;
        int end = tx + dir * Math.max(0, overshoot);
        int prevY = from.y;
        Point target = null;
        int x = from.x;
        while (x != end) {
            int nx = dir > 0 ? Math.min(x + PATH_STEP, end) : Math.max(x - PATH_STEP, end);
            if (dir > 0 ? (x < tx && nx > tx) : (x > tx && nx < tx)) {
                nx = tx; // sample the target itself
            }
            Integer gy = ground.yAt(nx, prevY);
            if (gy == null || Math.abs(gy - prevY) > LEDGE_STEP_DY) {
                return null;
            }
            prevY = gy;
            if (nx == tx) {
                target = new Point(tx, gy);
            }
            x = nx;
        }
        if (target == null) {
            Integer gy = ground.yAt(tx, from.y);
            if (gy == null) {
                return null;
            }
            target = new Point(tx, gy);
        }
        return Math.abs(target.y - from.y) <= maxDy ? target : null;
    }

    /**
     * Where a crowded bot at {@code me} steps: 40-120 px to a random side, on the same ground ({@link #walkable}), and
     * clear of {@code others}; random picks first, then a scan. Null when there is no such spot (the bot stays and
     * tries again later).
     */
    public static Point pickStep(Point me, List<Point> others, Random rng, Ground ground, int maxDy, int overshoot) {
        for (int i = 0; i < TRIES; i++) {
            int side = rng.nextBoolean() ? 1 : -1;
            int dist = STEP_MIN + rng.nextInt(STEP_MAX - STEP_MIN + 1);
            Point p = walkable(me, me.x + side * dist, ground, maxDy, overshoot);
            if (p != null && clear(p, others)) {
                return p;
            }
        }
        // a crowded stretch: scan every 5 px, nearer first, sides in random order, before giving up
        int first = rng.nextBoolean() ? 1 : -1;
        for (int dist = STEP_MIN; dist <= STEP_MAX; dist += 5) {
            for (int s = 0; s < 2; s++) {
                int side = s == 0 ? first : -first;
                Point p = walkable(me, me.x + side * dist, ground, maxDy, overshoot);
                if (p != null && clear(p, others)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Where the newer one of a deliberate pair stands: {@link #PAIR_MIN}-{@link #PAIR_MAX} px from its partner, side
     * by side on the partner's ground, on the side it already stands on when that works, clear of everyone else.
     */
    public static Point pairStep(Point me, Point partner, List<Point> others, Random rng, Ground ground, int maxDy) {
        int mySide = me.x == partner.x ? (rng.nextBoolean() ? 1 : -1) : Integer.signum(me.x - partner.x);
        for (int i = 0; i < TRIES; i++) {
            int side = i < TRIES / 2 ? mySide : -mySide;
            int tx = partner.x + side * (PAIR_MIN + 3 + rng.nextInt(PAIR_MAX - PAIR_MIN - 5));
            Integer ty = ground.yAt(tx, partner.y);
            if (ty == null || Math.abs(ty - partner.y) > SAME_FH_DY || Math.abs(ty - me.y) > maxDy) {
                continue;
            }
            Point p = new Point(tx, ty);
            if (clear(p, others, CROWD_PX)) {
                return p;
            }
        }
        return null;
    }

    /**
     * A free spot on a jittered ring around {@code hot} (an NPC front, a portal, a spawn point, the gachapon): {@code
     * hot} itself when it is clear, else the first clear grounded spot at 30, 64, 98 ... px out on either side (side
     * order random per ring, a few px of outward jitter), within {@code maxDy} of the hot spot's height. When
     * everything near is taken, the candidate with the most room.
     */
    public static Point ringSpot(Point hot, List<Point> taken, Random rng, Ground ground, int maxDy) {
        Integer hy = ground.yAt(hot.x, hot.y);
        Point centre = hy != null && Math.abs(hy - hot.y) <= maxDy ? new Point(hot.x, hy) : new Point(hot);
        if (clear(centre, taken)) {
            return centre;
        }
        Point roomiest = centre;
        int roomiestGap = nearestGap(centre, taken);
        for (int ring = 0; ring < RINGS; ring++) {
            int r = CLEAR_PX + RING_STEP * ring;
            int first = rng.nextBoolean() ? 1 : -1;
            for (int s = 0; s < 2; s++) {
                int side = s == 0 ? first : -first;
                int tx = hot.x + side * (r + rng.nextInt(RING_STEP - CLEAR_PX + 1)); // outward jitter only
                Integer ty = ground.yAt(tx, centre.y);
                if (ty == null || Math.abs(ty - centre.y) > maxDy) {
                    continue;
                }
                Point p = new Point(tx, ty);
                if (clear(p, taken)) {
                    return p;
                }
                int gap = nearestGap(p, taken);
                if (gap > roomiestGap) {
                    roomiest = p;
                    roomiestGap = gap;
                }
            }
        }
        return roomiest; // everything near is taken: the spot with the most room
    }

    /** Rings around a hot spot: {@link #CLEAR_PX}, +34, +68 ... px out (a few px of outward jitter keeps them apart). */
    static final int RINGS = 8, RING_STEP = 34;

    /** Horizontal distance to the nearest taken point on the same height band (Integer.MAX_VALUE: none). */
    static int nearestGap(Point p, List<Point> taken) {
        int best = Integer.MAX_VALUE;
        for (Point t : taken) {
            if (t != null && Math.abs(t.y - p.y) < CLEAR_DY) {
                best = Math.min(best, Math.abs(t.x - p.x));
            }
        }
        return best;
    }

    /* ------------------------------------------------------------------ rest tracking */

    /** When each bot came to rest where it stands, and when it last looked around. */
    static final class Rests {
        record Rest(int mapId, int x, int y, long sinceMs, long seenMs) {
        }

        private final Map<Integer, Rest> rests = new ConcurrentHashMap<>();

        /** Records a look at a bot; returns when it came to rest at this spot (now, if it just moved or arrived). */
        long observe(int id, int mapId, int x, int y, long now) {
            Rest r = rests.get(id);
            if (r == null || r.mapId() != mapId || Math.abs(r.x() - x) > SAME_FH_DY || Math.abs(r.y() - y) > SAME_FH_DY) {
                rests.put(id, new Rest(mapId, x, y, now, now));
                return now;
            }
            rests.put(id, new Rest(r.mapId(), r.x(), r.y(), r.sinceMs(), now));
            return r.sinceMs();
        }

        /** The rest of a bot that looked around lately (it will step away on its own), or null. */
        Rest active(int id, long now) {
            Rest r = rests.get(id);
            return r != null && now - r.seenMs() <= 3 * CHECK_EVERY_MS + 30_000 ? r : null;
        }

        void forget(int id) {
            rests.remove(id);
        }

        int size() {
            return rests.size();
        }
    }

    static final Rests RESTS = new Rests();
    private static final Map<Integer, Long> NEXT_CHECK = new ConcurrentHashMap<>();

    /* ------------------------------------------------------------------ reservations */

    record Reservation(int botId, int x, int y, long untilMs) {
    }

    /** Destinations bots are walking to, per map instance (channel, map). */
    private static final Map<Long, List<Reservation>> RESERVED = new ConcurrentHashMap<>();

    private static long key(MapleMap map) {
        int ch;
        try {
            ch = map.getChannelId();
        } catch (RuntimeException e) {
            ch = 0;
        }
        return ((long) ch << 32) | (map.getId() & 0xffffffffL);
    }

    static void reserve(long mapKey, int botId, Point p, long now) {
        List<Reservation> list = RESERVED.computeIfAbsent(mapKey, k -> new ArrayList<>());
        synchronized (list) {
            prune(list, now);
            if (botId > 0) {
                list.removeIf(r -> r.botId() == botId);
            }
            list.add(new Reservation(botId, p.x, p.y, now + RESERVE_MS));
        }
    }

    static List<Point> reserved(long mapKey, int exceptBotId, long now) {
        List<Reservation> list = RESERVED.get(mapKey);
        if (list == null) {
            return List.of();
        }
        List<Point> out = new ArrayList<>();
        synchronized (list) {
            prune(list, now);
            for (Reservation r : list) {
                if (exceptBotId <= 0 || r.botId() != exceptBotId) {
                    out.add(new Point(r.x(), r.y()));
                }
            }
        }
        return out;
    }

    private static void prune(List<Reservation> list, long now) {
        for (Iterator<Reservation> it = list.iterator(); it.hasNext(); ) {
            if (it.next().untilMs() <= now) {
                it.remove();
            }
        }
    }

    /** Tests. */
    static void clearForTest() {
        RESERVED.clear();
        NEXT_CHECK.clear();
        RESTS.rests.clear();
    }

    /* ------------------------------------------------------------------ the game side */

    /** Henesys' hangout rooms (not all are flagged as towns in the WZ). */
    private static final Set<Integer> HANGOUTS = Set.of(100000100, 100000102, 100000200, 100000202, 100000203);

    /** Free Market entrance and rooms. */
    static boolean isFreeMarket(int mapId) {
        return mapId >= 910000000 && mapId <= 910000099;
    }

    /** Town and hangout maps, where personal space applies (not fields: grinders have GrindBrain's own). */
    public static boolean appliesOn(MapleMap map) {
        if (map == null) {
            return false;
        }
        int id = map.getId();
        if (HANGOUTS.contains(id) || isFreeMarket(id) || id == 100000000) {
            return true;
        }
        try {
            if (map.isTown()) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // no flag: fall through
        }
        try {
            return townPresenceMaps().contains(id);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static volatile List<TownPresenceConfig.TownEntry> townsSeen;
    private static volatile Set<Integer> townMaps = Set.of();

    /** TownPresence.yaml's maps, rebuilt only when the config is (re)loaded. */
    private static Set<Integer> townPresenceMaps() {
        List<TownPresenceConfig.TownEntry> towns = TownPresenceConfig.towns();
        if (towns != townsSeen) {
            townMaps = Set.copyOf(TownPresenceConfig.allTownMapIds());
            townsSeen = towns;
        }
        return townMaps;
    }

    static Ground ground(MapleMap map) {
        return (x, nearY) -> {
            try {
                Point p = GCMovement.groundPointBelow(map, x, nearY);
                return p == null ? null : p.y;
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        };
    }

    /** Where everyone on the map stands, plus the reserved destinations, without {@code self}. */
    private static List<Point> taken(MapleMap map, int selfId, long now) {
        List<Point> out = new ArrayList<>(reserved(key(map), selfId, now));
        try {
            for (Character c : map.getAllPlayers()) {
                if (c != null && c.getId() != selfId && !c.isHidden() && c.getPosition() != null) {
                    out.add(new Point(c.getPosition()));
                }
            }
        } catch (RuntimeException ignored) {
            // nobody known: reservations only
        }
        return out;
    }

    /**
     * A destination near {@code want} on {@code map} that nobody stands on or is walking to: {@code want} when it is
     * clear, else a spot on a ring around it (see {@link #ringSpot}). Reserved for {@code botId} for a short while so
     * the next bot picks another one. With no map {@code want} comes back unchanged.
     */
    public static Point freeSpotNear(MapleMap map, Point want, int botId) {
        if (map == null || want == null) {
            return want;
        }
        long now = System.currentTimeMillis();
        Point p = ringSpot(want, taken(map, botId, now), ThreadLocalRandom.current(), ground(map), SLOPE_DY);
        reserve(key(map), botId, p, now);
        return p;
    }

    /**
     * Spawn points for a cohort, spread: each wanted point is kept when nobody stands there (nor on an earlier point
     * of the cohort, nor on a spot another bot is walking to), else moved to a ring spot around it. Keeps the list's
     * order and size; a null stays null.
     */
    public static List<Point> spreadSpawns(MapleMap map, List<Point> wanted) {
        if (map == null || wanted == null || wanted.isEmpty()) {
            return wanted;
        }
        long now = System.currentTimeMillis();
        List<Point> taken = taken(map, -1, now);
        Ground g = ground(map);
        Random rng = ThreadLocalRandom.current();
        List<Point> out = new ArrayList<>(wanted.size());
        for (Point w : wanted) {
            Point p = w == null ? null : ringSpot(w, taken, rng, g, SLOPE_DY);
            out.add(p);
            if (p != null) {
                taken.add(p);
                reserve(key(map), -1, p, now);
            }
        }
        return out;
    }

    /** The idle check, throttled to once per {@link #CHECK_EVERY_MS} per bot. True when the bot stepped. */
    public static boolean maybeKeep(Character bot) {
        if (bot == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long next = NEXT_CHECK.get(bot.getId());
        if (next != null && now < next) {
            return false;
        }
        return keepNow(bot);
    }

    /** The check now (the bot just came to rest). True when the bot stepped. */
    public static boolean keepNow(Character bot) {
        if (bot == null || bot.getMap() == null || bot.getPosition() == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        NEXT_CHECK.put(bot.getId(), now + CHECK_EVERY_MS + ThreadLocalRandom.current().nextLong(1_500));
        MapleMap map = bot.getMap();
        if (!appliesOn(map) || !soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning.spread()) {
            return false;
        }
        boolean physics = GCMovement.isEnabled(bot);
        if (physics ? (GCMovement.isMoving(bot) || !"GROUNDED".equals(GCMovement.sweepPose(bot)))
                : MovementCommands.isBotMoving(bot)) {
            return false; // walking, jumping, climbing: it is not resting yet
        }
        if (fixed(bot)) {
            return false;
        }
        Point pos = new Point(bot.getPosition());
        Ground ground = ground(map);
        Integer gy = ground.yAt(pos.x, pos.y);
        if (gy == null || Math.abs(gy - pos.y) > SAME_FH_DY) {
            return false; // on a rope or ladder, or in the air
        }
        long since = RESTS.observe(bot.getId(), map.getId(), pos.x, pos.y, now);
        Body me = new Body(bot.getId(), pos.x, pos.y, false, true, since);

        List<Character> chars;
        try {
            chars = map.getAllPlayers();
        } catch (RuntimeException e) {
            return false;
        }
        List<Body> bodies = new ArrayList<>(chars.size());
        Map<Integer, Character> byId = new java.util.HashMap<>();
        List<Point> spots = new ArrayList<>(reserved(key(map), bot.getId(), now));
        for (Character c : chars) {
            if (c == null || c.getId() == bot.getId() || c.isHidden() || c.getPosition() == null) {
                continue;
            }
            Point cp = c.getPosition();
            spots.add(new Point(cp));
            boolean player = !BotHelpers.isBot(c);
            Rests.Rest r = player ? null : RESTS.active(c.getId(), now);
            boolean movable = r != null && !fixed(c);
            bodies.add(new Body(c.getId(), cp.x, cp.y, player, movable, r == null ? 0L : r.sinceMs()));
            byId.put(c.getId(), c);
        }
        Body close = crowder(me, bodies, (a, b) -> deliberate(bot, byId.get(b.id())));
        if (close == null) {
            return false;
        }
        Character other = byId.get(close.id());
        boolean pair = deliberate(bot, other);
        Random rng = ThreadLocalRandom.current();
        Point to;
        if (pair) {
            List<Point> rest = new ArrayList<>(spots);
            rest.removeIf(p -> p.x == close.x() && p.y == close.y());
            to = pairStep(pos, new Point(close.x(), close.y()), rest, rng, ground, physics ? SLOPE_DY : FLAT_DY);
        } else {
            to = pickStep(pos, spots, rng, ground, physics ? SLOPE_DY : FLAT_DY, physics ? 0 : OVERSHOOT_PX);
        }
        if (to == null) {
            return false;
        }
        reserve(key(map), bot.getId(), to, now);
        step(bot, to, physics, pair ? new Point(close.x(), close.y()) : null);
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.SPREAD)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.SPREAD, map.getId(), "PERSONAL SPACE " + bot.getName()
                    + " stood " + Math.abs(close.x() - pos.x) + " px from " + (other == null ? close.id() : other.getName())
                    + (pair ? " (pair)" : close.player() ? " (player)" : "") + ": steps to x " + to.x
                    + (physics ? " (walk)" : " (small move)"));
        }
        return true;
    }

    /** A bot that holds its spot on purpose: a staged test bot next to the GM, or a shop keeper at its shop. */
    static boolean fixed(Character c) {
        if (c == null) {
            return true;
        }
        if (soloMapling.ArtificialPlayer.StageSpots.pinned(c.getId())) {
            return true;
        }
        try {
            return c.getPlayerShop() != null || c.getHiredMerchant() != null || c.getMiniGame() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether two characters are a deliberate pair: a couple, a bot and the player it is talking with, two bots in the
     * same kind of scene (drama, scripted cluster talk, ambient chatter), or two shop keepers.
     */
    public static boolean deliberate(Character a, Character b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            boolean botA = BotHelpers.isBot(a), botB = BotHelpers.isBot(b);
            if (!botA && !botB) {
                return false;
            }
            if (!botA || !botB) {
                Character bot = botA ? a : b, player = botA ? b : a;
                return talkPartnerOf(player) == bot.getId() || talkingTo(bot, player.getId());
            }
            if (soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.partnerOf(a.getId()) == b.getId()) {
                return true;
            }
            String ka = groupKey(a);
            return ka != null && ka.equals(groupKey(b));
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The scene-like key of a bot on its own (no partner known): scene, cluster, chatter, shop, or null. */
    public static String groupKey(Character c) {
        try {
            if (soloMapling.ArtificialPlayer.BotDramaSystem.DramaKit.inScene(c.getId())) {
                return "scene";
            }
            if (soloMapling.ArtificialPlayer.ConversationManager.getInstance().isInConversation(c.getId())) {
                return "cluster";
            }
            if (soloMapling.ArtificialPlayer.BotChatterSystem.BotChatter.isEngaged(c)) {
                return "chatter";
            }
            if (c.getPlayerShop() != null || c.getHiredMerchant() != null) {
                return "shop";
            }
        } catch (RuntimeException | LinkageError ignored) {
            // no signal
        }
        return null;
    }

    /** Whether a bot's own conversation slot holds this player (a SocialBot's open menu / chat). */
    static boolean talkingTo(Character bot, int playerId) {
        soloMapling.ArtificialPlayer.BotSM sm =
                soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(bot.getId());
        if (sm == null) {
            return false;
        }
        Character r = sm.getInteractors().getRespondant();
        return r != null && r.getId() == playerId;
    }

    /** The bot this player talks with now, or -1. */
    public static int talkPartnerOf(Character player) {
        try {
            return soloMapling.ArtificialPlayer.BotConversation.partnerOf(player.getId());
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /**
     * Walks the bot to {@code to}: a physics walk, or up to three recorded small moves (each a real movement packet
     * the client animates). Faces {@code faceTo} afterwards when given (a deliberate pair turns to its partner).
     */
    static void step(Character bot, Point to, boolean physics, Point faceTo) {
        MovementCommands.standUp(bot);
        if (physics) {
            Runnable face = faceTo == null ? null : () -> GCMovement.face(bot, faceTo.x < bot.getPosition().x);
            GCMovement.move(bot, to.x, to.y, face);
            return;
        }
        for (int i = 0; i < 3; i++) {
            int remaining = Math.abs(bot.getPosition().x - to.x);
            if (remaining < (faceTo != null ? 8 : 20)) {
                break;
            }
            int before = bot.getPosition().x;
            MovementCommands.BotMoveSmallDistanceX(bot, to);
            if (bot.getPosition().x == before) {
                break; // no recording, or the movement lock is held by a walk
            }
        }
        if (faceTo != null) {
            MovementCommands.botFaceTowardsPoint(bot, faceTo);
        }
    }

    /** Forgets a bot (logout, retype). */
    public static void forget(Character bot) {
        if (bot != null) {
            RESTS.forget(bot.getId());
            NEXT_CHECK.remove(bot.getId());
        }
    }
}
