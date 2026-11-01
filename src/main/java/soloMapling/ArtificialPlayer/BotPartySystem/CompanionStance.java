package soloMapling.ArtificialPlayer.BotPartySystem;

import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;

/**
 * 1.6.2 ticket 99: how a companion stands when it is not simply following - one implementation for owned alts and
 * party bot friends (nutnnut/Cosmic {@code BotChatManager}: follow / stop / move here / sentry / patrol, and its
 * formations, 8fd725404). A stance belongs to the map it was given on: on another map the companion follows again.
 *
 * <ul>
 *   <li><b>FOLLOW</b>: walk with the leader, at its formation place ({@link Formation}).</li>
 *   <li><b>STAY</b>: stand where it is and fight nothing it would have to walk to.</li>
 *   <li><b>MOVE_HERE</b>: walk to where the leader stood when told, then hold there as a sentry.</li>
 *   <li><b>SENTRY</b>: hold this spot; fight what comes within reach of it ({@link #SENTRY_REACH_PX}).</li>
 *   <li><b>PATROL</b>: walk back and forth {@link #PATROL_HALF_PX} px either side of the spot, fighting as it goes.</li>
 *   <li><b>LANES</b> (1.6.3 s-lanes): hold a lane of its own - the spot {@link CompanionLanes} handed it, spread out
 *       from its sibling companions instead of stacking on the leader - and fight within reach of it like a sentry.
 *       The lane's reach ({@link CompanionLanes.Reach}) comes with it per companion: within a screen of the leader,
 *       or over the whole map for the companions a screen could not seat when the party is two or more, and only
 *       the second one lets an owned alt hold it beyond the gate's distance ({@link #lanesWholeMap}).</li>
 * </ul>
 * None of this lets a companion leave its leader on its own: an owned alt still fights only inside the activity gate
 * (OwnedAltGate: owner online, same map, not a town, the owner active, about a screen away), and only a whole-map lane
 * lifts the distance part of that - so a sentry or a patrol is never an autopilot, and a far lane only works the
 * ground it was given.
 */
public final class CompanionStance {

    public enum Mode { FOLLOW, STAY, MOVE_HERE, SENTRY, PATROL, LANES }

    /** How far either side of its spot a patrol walks. */
    public static final int PATROL_HALF_PX = 250;
    /** How far from its spot a sentry steps out to hit something. */
    public static final int SENTRY_REACH_PX = 200;
    /** Closer than this to its spot counts as there. */
    public static final int THERE_PX = 25;

    /** One companion's stance: the mode, the spot (null when following) and the map it was given on. */
    public static final class Stance {
        public final Mode mode;
        public final Point spot;
        public final int mapId;
        /** A LANES stance's reach (null for the other stances). */
        public final CompanionLanes.Reach reach;
        volatile boolean headingRight = true;

        Stance(Mode mode, Point spot, int mapId, CompanionLanes.Reach reach) {
            this.mode = mode;
            this.spot = spot;
            this.mapId = mapId;
            this.reach = reach;
        }
    }

    private static final Stance FOLLOWING = new Stance(Mode.FOLLOW, null, -1, null);
    private static final Map<Integer, Stance> stances = new ConcurrentHashMap<>();
    private static final Map<Integer, Formation> formations = new ConcurrentHashMap<>();

    private CompanionStance() {
    }

    /** The mode an order verb sets, or null for an order that is no stance (grind, trade...). */
    public static Mode modeOf(Verb verb) {
        return switch (verb) {
            case FOLLOW -> Mode.FOLLOW;
            case STAY -> Mode.STAY;
            case MOVE_HERE -> Mode.MOVE_HERE;
            case SENTRY -> Mode.SENTRY;
            case PATROL -> Mode.PATROL;
            case LANES -> Mode.LANES;
            default -> null;
        };
    }

    /**
     * Sets a companion's stance: {@code mode} at {@code spot} on map {@code mapId}. STAY and SENTRY hold where the
     * companion stands ({@code own}); MOVE_HERE and PATROL use where the leader stands ({@code leader}).
     */
    public static Stance set(int botId, Mode mode, Point own, Point leader, int mapId) {
        if (mode == null || mode == Mode.FOLLOW) {
            stances.remove(botId);
            return FOLLOWING;
        }
        Point spot = switch (mode) {
            case MOVE_HERE, PATROL -> leader != null ? new Point(leader) : own == null ? null : new Point(own);
            default -> own != null ? new Point(own) : leader == null ? null : new Point(leader);
        };
        if (spot == null) {
            stances.remove(botId);
            return FOLLOWING;
        }
        Stance s = new Stance(mode, spot, mapId, null);
        stances.put(botId, s);
        return s;
    }

    /**
     * 1.6.3 s-lanes: sets a companion's own lane - {@code spot} on {@code mapId}, at {@code reach} - as handed out by
     * {@link CompanionLanes}. A null spot leaves it following.
     */
    public static Stance setAt(int botId, Mode mode, Point spot, int mapId, CompanionLanes.Reach reach) {
        if (mode == null || mode == Mode.FOLLOW || spot == null) {
            stances.remove(botId);
            return FOLLOWING;
        }
        Stance s = new Stance(mode, new Point(spot), mapId, reach);
        stances.put(botId, s);
        return s;
    }

    /**
     * 1.6.3 s-lanes: true when this companion holds a LANES spot over the whole map and the lanes switch is on. The
     * owned-alt activity gate then looks at everything but the distance to its owner (owner online, same map, not a
     * town, owner active: all still required). No other stance relaxes anything.
     */
    public static boolean lanesWholeMap(int botId, int mapId) {
        if (!CompanionSwitches.lanes()) {
            return false;
        }
        Stance s = of(botId, mapId);
        return s.mode == Mode.LANES && s.reach == CompanionLanes.Reach.MAP;
    }

    /** The companion's stance on {@code mapId}: following when none was given, or it was given on another map. */
    public static Stance of(int botId, int mapId) {
        Stance s = stances.get(botId);
        if (s == null) {
            return FOLLOWING;
        }
        if (s.mapId != mapId) {
            stances.remove(botId, s);
            return FOLLOWING;
        }
        return s;
    }

    /** The companion left (dismissed, logged off): its stance goes with it. */
    public static void forget(int botId) {
        stances.remove(botId);
    }

    /**
     * Where a holding companion should stand now, or null while it follows. A patrol turns at either end of its
     * beat; a sentry, a "move here" and a stay stand on their spot.
     */
    public static Point holdPoint(Stance s, Point at) {
        if (s == null || s.mode == Mode.FOLLOW || s.spot == null) {
            return null;
        }
        if (s.mode != Mode.PATROL) {
            return s.spot;
        }
        int left = s.spot.x - PATROL_HALF_PX;
        int right = s.spot.x + PATROL_HALF_PX;
        if (at != null) {
            if (s.headingRight && at.x >= right - THERE_PX) {
                s.headingRight = false;
            } else if (!s.headingRight && at.x <= left + THERE_PX) {
                s.headingRight = true;
            }
        }
        return new Point(s.headingRight ? right : left, s.spot.y);
    }

    /**
     * Whether a companion in this stance goes after a mob standing at {@code mob}: a follower or a patrol does
     * (inside its leader's rules), a sentry and a "move here" only within {@link #SENTRY_REACH_PX} of their spot, a
     * staying one never.
     */
    public static boolean mayChase(Stance s, Point mob) {
        if (s == null || mob == null) {
            return true;
        }
        return switch (s.mode) {
            case FOLLOW, PATROL -> true;
            case STAY -> false;
            case SENTRY, MOVE_HERE, LANES -> s.spot == null || Math.abs(mob.x - s.spot.x) <= SENTRY_REACH_PX
                    && Math.abs(mob.y - s.spot.y) <= SENTRY_REACH_PX;
        };
    }

    // ── formations ──

    /** The formation this leader's companions keep (split, 60 px apart, until told otherwise). */
    public static Formation formation(int leaderId) {
        return formations.getOrDefault(leaderId, Formation.DEFAULT);
    }

    /** Sets the leader's formation (null = back to the default). */
    public static void setFormation(int leaderId, Formation f) {
        if (f == null || f.equals(Formation.DEFAULT)) {
            formations.remove(leaderId);
        } else {
            formations.put(leaderId, f);
        }
    }

    /** The leader logged off: the formation is forgotten (nutnnut keeps it in memory only, as we do). */
    public static void forgetLeader(int leaderId) {
        formations.remove(leaderId);
    }

    /**
     * Gives each companion of a leader its formation place, in slot order: {@code place} receives slot index and
     * is called once per companion ({@code places.get(i)} with the offset for slot i).
     */
    public static void place(int leaderId, List<IntConsumer> places) {
        Formation f = formation(leaderId);
        for (int i = 0; i < places.size(); i++) {
            places.get(i).accept(f.offsetFor(i));
        }
    }
}
