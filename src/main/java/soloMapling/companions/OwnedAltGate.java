package soloMapling.companions;

import client.Character;
import client.Client;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance;

import java.awt.Point;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.1 ticket 13: the activity gate. An owned alt fights and loots only while its player is really playing with it,
 * so alts are a party you play with, never an AFK farm. Every rule must hold, checked each driver tick:
 * <ol>
 *   <li><b>Owner online</b>: the owner is in the world (alts already go home on logout or a channel change).</li>
 *   <li><b>Same map</b>: the alt stands on its owner's map (the same map object, so the same instance). An alt left on
 *       another map only follows; an alt inside a PQ whose owner left it does nothing there on its own.</li>
 *   <li><b>Not a town</b>: never in a town (no fighting, no looting in the Free Market or a town square).</li>
 *   <li><b>Nearby</b>: within {@link #RANGE_X_PX} px across and {@link #RANGE_Y_PX} px up/down of the owner (about one
 *       screen). Farther, it walks back first. 1.6.3 s-lanes: an alt holding a LANES spot with map reach is not held
 *       to this distance - it works its own lane across the map - but every other rule still applies to it.</li>
 *   <li><b>Owner active</b>: the owner moved, attacked, used a skill or item or chatted in the last
 *       {@link #idleMs()} ms (default 3 minutes; the client's own "player action" stamp). An idle owner's alts stop
 *       fighting and looting and just stand by them; they start again on the owner's next action.</li>
 *   <li><b>Per map</b>: at most {@link #FIGHTING_PER_MAP} alts fight on one map at once, whoever owns them.</li>
 * </ol>
 * The per-owner cap stays the shared five companions ({@link OwnedAltService#CAP}, alts plus bot friends). Alts never
 * open shops, trade or take party/guild invites (refused, OwnedAltService.interactionRefusal) and never enter an
 * instance their owner is not in.
 */
public final class OwnedAltGate {

    private OwnedAltGate() {
    }

    /** How far across from its owner an alt still fights (about one screen). */
    public static final int RANGE_X_PX = 900;
    /** How far up or down. */
    public static final int RANGE_Y_PX = 500;
    /** Alts fighting on one map at once, all owners together. */
    public static final int FIGHTING_PER_MAP = 6;
    /** Default idle limit: three minutes without a player action. */
    public static final long DEFAULT_IDLE_MS = 3 * 60_000L;

    /** Why an alt may not fight or loot now; {@link #OPEN} when it may. */
    public enum Why {
        OPEN, OWNER_GONE, OTHER_MAP, TOWN, TOO_FAR, OWNER_IDLE, MAP_FULL;

        /**
         * AL-2 (ticket 183): what the owner hears (once) when this closes the gate, naming the alt the reason is about -
         * a party of five alts held back for two different reasons has to say which alt is which. Null for the reasons
         * there is nobody to tell (the owner is gone) or nothing to tell (the gate is open).
         */
        public String tell(String altName) {
            String alt = altName == null || altName.isBlank() ? "Your alt" : altName;
            return switch (this) {
                case OPEN, OWNER_GONE -> null;
                case OTHER_MAP -> alt + " is on its way: it is on another map and fights again once it reaches you.";
                case TOWN -> alt + " does not fight in town. Take it somewhere with monsters and it joins in.";
                case TOO_FAR -> alt + " is walking back to you: it only fights close by, about a screen away.";
                case OWNER_IDLE -> alt + " stopped fighting: you have been idle for a while. Move or attack and it joins in again.";
                case MAP_FULL -> alt + " is holding back: this map already has " + FIGHTING_PER_MAP + " alts fighting.";
            };
        }
    }

    // QA: owners treated as idle now (t-oa-1 proves the idle rule without waiting three minutes)
    private static final Set<Integer> forcedIdle = ConcurrentHashMap.newKeySet();

    /** QA only: treat this owner as idle (true) or back to their real activity (false). */
    public static void forceIdleForQa(int ownerId, boolean idle) {
        if (idle) {
            forcedIdle.add(ownerId);
        } else {
            forcedIdle.remove(ownerId);
        }
    }

    /** The idle limit: {@code -Dslums.alts.idleMs}, else three minutes. */
    public static long idleMs() {
        return Math.max(1_000L, Long.getLong("slums.alts.idleMs", DEFAULT_IDLE_MS));
    }

    /**
     * The gate for one alt now. {@code otherAltsFightingHere} counts the other alts already fighting on the alt's map
     * (this one left out).
     */
    public static Why check(Character owner, Character alt, long now, int otherAltsFightingHere) {
        if (owner == null || alt == null || !owner.isLoggedinWorld()) {
            return Why.OWNER_GONE;
        }
        MapleMap map = alt.getMap();
        if (map == null || owner.getMap() != map) {
            return Why.OTHER_MAP;
        }
        if (map.isTown()) {
            return Why.TOWN;
        }
        Point a = alt.getPosition();
        Point o = owner.getPosition();
        boolean far = a == null || o == null || Math.abs(a.x - o.x) > RANGE_X_PX || Math.abs(a.y - o.y) > RANGE_Y_PX;
        // 1.6.3 s-lanes: only the distance part is relaxed, and only for a lane held over the whole map
        if (far && !CompanionStance.lanesWholeMap(alt.getId(), map.getId())) {
            return Why.TOO_FAR;
        }
        if (ownerIdle(owner, now)) {
            return Why.OWNER_IDLE;
        }
        if (otherAltsFightingHere >= FIGHTING_PER_MAP) {
            return Why.MAP_FULL;
        }
        return Why.OPEN;
    }

    /** The owner made no player action within the idle limit. */
    static boolean ownerIdle(Character owner, long now) {
        if (forcedIdle.contains(owner.getId())) {
            return true;
        }
        Client c = owner.getClient();
        long last = c == null ? 0 : c.getLastPlayerAction();
        return last > 0 && now - last > idleMs();
    }
}
