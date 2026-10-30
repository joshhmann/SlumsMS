package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import server.life.Monster;
import server.maps.MapObject;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

/**
 * 1.6.2 (owner 2026-10-30, "one implementation per behaviour"): the shared approach step - walk into reach of a
 * target (a mob, or a drop), then act. The grind brain (GrindBrain.inAttackRange / standX, RoamStrategy's approach)
 * and owned alts (OwnedAltCompanion's fight and loot) both use it; what they then do (EngageBeat's swing, the alt's
 * swing, a bot's or an alt's pickup) and what they go for stay theirs.
 * <ul>
 *   <li>{@link #reach}: how close to a mob counts as "in reach" - this fraction of the attack's reach, floored at the
 *       melee box.</li>
 *   <li>{@link #standX}: where along the ledge to stand to fight a mob (past its body, the class's comfort distance,
 *       stepped aside from anyone already on that pixel; StandOffset's band).</li>
 *   <li>{@link #walkTo}: issue the walk, re-issued only when the spot moved or the walk stopped.</li>
 * </ul>
 */
public final class Approach {

    private Approach() {
    }

    /** Melee floor: within this dx of a mob it stops and swings. */
    public static final int MELEE_X = 70;
    /** Melee floor: within this dy of a mob it is in range. */
    public static final int MELEE_Y = 90;
    /** Stop at this fraction of the attack's reach. */
    public static final double REACH_FRAC = 0.80;
    /** A walk to within this many px of the last one, still under way, is not re-issued. */
    public static final int RETARGET_EPS = 16;
    private static final int DEFAULT_MOB_HALF_WIDTH = 20;

    /** How close (x, y) to a mob this character fights from: its attack reach x {@link #REACH_FRAC}, melee floored. */
    public static int[] reach(Character chr) {
        return new int[]{Math.max(MELEE_X, (int) (BotAttackDriver.attackReachX(chr) * REACH_FRAC)),
                Math.max(MELEE_Y, (int) (BotAttackDriver.attackReachY(chr) * REACH_FRAC))};
    }

    /** {@code target} is within {@code reach} ({x, y}) of {@code at}. */
    public static boolean inReach(Point at, Point target, int[] reach) {
        return at != null && target != null && Math.abs(target.x - at.x) <= reach[0]
                && Math.abs(target.y - at.y) <= reach[1];
    }

    /** Archers and mages hold mobs off; warriors close in. */
    public static boolean keepsDistance(Character chr, MovementStyle style) {
        if (style == MovementStyle.RANGED) {
            return true;
        }
        if (chr == null || chr.getJob() == null) {
            return false;
        }
        int niche = chr.getJob().getJobNiche(); // 2 = magician, 3 = bowman
        return niche == 2 || niche == 3;
    }

    /**
     * 1.6.2 b085: how far from this mob's centre to stand to fight it (StandOffset.band): past the body's edge, archers
     * and mages at their comfort distance, never beyond {@code reachX}.
     */
    public static int[] standBand(Monster mob, int reachX, MovementStyle style, boolean keepsDistance, boolean human) {
        java.awt.Rectangle body = mob != null ? GCMovement.mobBounds(mob) : null;
        int half = body != null ? body.width / 2 : DEFAULT_MOB_HALF_WIDTH;
        boolean far = style == MovementStyle.RANGED || (human && keepsDistance);
        int comfort = style == MovementStyle.RANGED ? EngageBeat.KITE_MIN_PX : EngageBeat.MAGE_COMFORT_PX;
        return StandOffset.band(half, reachX, far, comfort);
    }

    /**
     * Where to stand to fight a mob at (mobX, groundY): the mob's x shifted by this character's stable offset inside
     * {@code band}, kept inside [lo, hi] and on the mob's platform, and stepped aside when another character already
     * stands within the name-tag overlap box there. Called when a move is issued, never per tick.
     */
    public static int standX(Character chr, int[] band, int mobX, int groundY, int lo, int hi) {
        server.maps.MapleMap map = chr.getMap();
        if (map == null) {
            return GrindBrain.clamp(mobX, lo, hi);
        }
        int self = chr.getId();
        java.util.List<java.awt.Rectangle> bodies = mobBodiesNear(map, mobX, groundY, band[1]);
        return StandOffset.standX(self, mobX, lo, hi, band[0], band[1],
                x -> {
                    Point g = GCMovement.groundPointBelow(map, x, groundY - 12);
                    return g != null && Math.abs(g.y - groundY) <= 12;
                },
                x -> {
                    if (insideBody(bodies, x, groundY)) {
                        return true;   // 162 runfix: not a spot inside another mob (t-cb-2)
                    }
                    for (MapObject mo : map.getMapObjectsInRange(new Point(x, groundY), 30.0 * 30.0,
                            java.util.List.of(server.maps.MapObjectType.PLAYER))) {
                        Character o = (Character) mo;
                        Point op = o.getPosition();
                        if (o.getId() != self && op != null && Math.abs(op.x - x) < 16 && Math.abs(op.y - groundY) < 10) {
                            return true;
                        }
                    }
                    return false;
                });
    }

    /*
     * 162 runfix (t-cb-2 on TEST 10-30: 11 of 40 snapshots inside a mob): the band puts an archer or mage 120-180 px off
     * its target, which on a busy ledge is often inside ANOTHER mob (leetownz fighting a Red Snail from inside a Pig).
     * The stand spot now skips spots inside any hostile mob's touch box, as a spot another player holds.
     */
    static java.util.List<java.awt.Rectangle> mobBodiesNear(server.maps.MapleMap map, int mobX, int groundY, int span) {
        java.util.List<java.awt.Rectangle> out = new java.util.ArrayList<>();
        double r = span + 120.0;
        for (MapObject mo : map.getMapObjectsInRange(new Point(mobX, groundY), r * r,
                java.util.List.of(server.maps.MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || !SpotFinder.isHostile(m)) {
                continue;
            }
            java.awt.Rectangle b = GCMovement.mobBounds(m);
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    /** A character standing at (x, groundY) touches one of these bodies (the lower half, as BotContactDamage). */
    static boolean insideBody(java.util.List<java.awt.Rectangle> bodies, int x, int groundY) {
        for (java.awt.Rectangle r : bodies) {
            int lower = Math.max(1, r.height / 2);
            java.awt.Rectangle touch = new java.awt.Rectangle(r.x - 4, r.y + r.height - lower, r.width + 8, lower + 6);
            if (touch.contains(x, groundY - 8)) {
                return true;
            }
        }
        return false;
    }

    /** The ground under a point (for a walk's target y), or the point's own y when there is none. */
    public static int groundY(Character chr, Point p) {
        Point gp = chr.getMap() == null ? null : GCMovement.groundPointBelow(chr.getMap(), p.x, p.y);
        return gp != null ? gp.y : p.y;
    }

    /**
     * Pure: skip re-issuing the walk only while the walker really is still walking to (about) that spot. The movement
     * driver drops a move that makes no progress for 8 s; a walker whose target then stood still used to see "same x
     * as last time" forever and never walked again (TEST 2026-10-27: the crowd stood ~4,700 px from Jr. Balrog).
     */
    public static boolean alreadyHeading(int tx, int lastMoveX, boolean moving) {
        return moving && Math.abs(tx - lastMoveX) < RETARGET_EPS;
    }

    /** Walk to (tx, ty) unless already heading there. Returns the walk's x to remember for the next call. */
    public static int walkTo(Character chr, int tx, int ty, int lastMoveX) {
        if (alreadyHeading(tx, lastMoveX, GCMovement.isMoving(chr))) {
            return lastMoveX;
        }
        GCMovement.move(chr, tx, ty);
        return tx;
    }

    /**
     * The step for a walker with no grind brain (an owned alt): its spot to fight {@code mob} from - the stand band's
     * spot on the mob's ground within the band either side - as {x, groundY}.
     */
    public static int[] fightSpot(Character chr, Monster mob, int[] reach, boolean human) {
        Point mp = mob.getPosition();
        int ty = groundY(chr, mp);
        MovementStyle style = MovementStylePolicy.forBot(chr);
        int[] band = standBand(mob, reach[0], style, keepsDistance(chr, style), human);
        int span = Math.max(60, band[1]);
        return new int[]{standX(chr, band, mp.x, ty, mp.x - span, mp.x + span), ty};
    }
}
