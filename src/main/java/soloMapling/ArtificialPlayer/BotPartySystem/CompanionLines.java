package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import tools.PacketCreator;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;

import java.awt.Point;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 follow-plus: the two short lines a following companion says to its owner, and the one line a stuck one
 * says to explain itself. The owner asked for a companion that feels like a player, not a chatty one: one line when
 * the pair arrives on a new town map, one line when the owner comes back after being away, and nothing else.
 *
 * <p>Both casual lines are said to the owner only - through the party channel when the companion shares the owner's
 * party (as the owned-alt lines already go), a plain chat line otherwise - and both are silent while the owner has
 * {@code @quiet bots} on ({@link BotNoiseFilter#get}, the same value the settings window shows). The stuck line
 * ({@link #say}) is not ambient: it answers the owner's own "where did you go", so quiet does not hide it.
 *
 * <p>Once per owner, not once per companion: two alts arriving in Henesys together say it once, and a player with
 * three companions does not get three welcome-backs. A map re-entered inside {@link #MAP_COOLDOWN_MS} stays quiet,
 * and {@link #GAP_MS} keeps any two casual lines apart.
 */
public final class CompanionLines {

    /** A map re-entered this soon after the last arrival line says nothing again. */
    public static final long MAP_COOLDOWN_MS = 600_000L; // 10 min
    /** The owner counts as away after standing still this long (the BotStory rule). */
    public static final long OWNER_AFK_MS = 300_000L; // 5 min
    /** Two casual lines to one owner are at least this far apart. */
    public static final long GAP_MS = 30_000L;

    /** One owner's memory: the map it is on, where it last stood, and when it last heard a line. */
    static final class Room {
        int mapId = -1;
        boolean arrivedSaid;
        int lastSaidMap = -1;
        long lastSaidMs;
        Point stillPos;
        long stillSinceMs;
        boolean away;
    }

    private static final Map<Integer, Room> ROOMS = new ConcurrentHashMap<>();

    private CompanionLines() {
    }

    /**
     * One poll for one follower: the arrival line on a new map, and the welcome-back line when the owner returns
     * after {@link #OWNER_AFK_MS} without moving. Safe to call for every companion of the same owner every poll -
     * the memory is per owner, so only the first one speaks.
     */
    public static void tick(Character owner, Character companion, long now) {
        if (owner == null || companion == null || owner.getMap() == null) {
            return;
        }
        if (!PartyCompanionPolicy.on(Feature.FOLLOW_PLUS, companion)) {
            return;
        }
        MapleMap map = owner.getMap();
        if (companion.getMap() != map) {
            return; // apart (travelling there): the arrival is said once they stand together
        }
        Point p = owner.getPosition();
        if (p == null) {
            return;
        }
        Room r = ROOMS.computeIfAbsent(owner.getId(), k -> new Room());
        if (r.mapId != owner.getMapId()) {
            r.mapId = owner.getMapId();
            r.arrivedSaid = false;
            r.stillPos = new Point(p);
            r.stillSinceMs = now;
            r.away = false;
        }
        if (!r.arrivedSaid) {
            r.arrivedSaid = true;
            boolean heard = r.lastSaidMap == owner.getMapId() && now - r.lastSaidMs < MAP_COOLDOWN_MS;
            if (!heard && map.isTown()) { // a town, not a hunting field or a PQ stage: this is "we're in Henesys"
                String name = map.getMapName();
                casual(owner, companion, "we're in " + (name == null || name.isBlank() ? "a new town" : name), now);
            }
        }
        // The owner away: it stood still for long enough that we remembered it as gone...
        if (p.equals(r.stillPos)) {
            if (now - r.stillSinceMs >= OWNER_AFK_MS) {
                r.away = true;
            }
        } else {
            boolean wasAway = r.away;
            r.away = false;
            r.stillPos = new Point(p);
            r.stillSinceMs = now;
            if (wasAway) {
                String name = map.getMapName();
                casual(owner, companion, "welcome back, still in "
                        + (name == null || name.isBlank() ? "the same map" : name), now);
            }
        }
    }

    /**
     * A casual line: silent with {@code @quiet bots} on, at most one every {@link #GAP_MS} per owner, and it does
     * not repeat the arrival line's map inside {@link #MAP_COOLDOWN_MS}.
     */
    private static void casual(Character owner, Character companion, String text, long now) {
        if (BotNoiseFilter.get(owner.getId()).bots()) {
            return; // @quiet bots: the player does not want bot chatter, not even from their own companions
        }
        Room r = ROOMS.computeIfAbsent(owner.getId(), k -> new Room());
        if (r.lastSaidMs != 0L && now - r.lastSaidMs < GAP_MS) {
            return;
        }
        r.lastSaidMap = owner.getMapId();
        r.lastSaidMs = now;
        say(owner, companion, text);
    }

    /**
     * Say one line to the owner: in the party channel when the companion is in the owner's party (the owned-alt
     * rule), a direct chat line otherwise. Never throws into a tick.
     */
    public static void say(Character owner, Character companion, String text) {
        if (owner == null || companion == null || text == null || text.isBlank()) {
            return;
        }
        try {
            var party = companion.getParty();
            if (party != null && party.getMemberById(owner.getId()) != null) {
                BotPartyChat.sayInParty(companion, text);
                return;
            }
        } catch (RuntimeException ignored) {
            // nothing to route through: fall back to the direct line
        }
        try {
            owner.sendPacket(PacketCreator.multiChat(companion.getName(), text, 1));
        } catch (RuntimeException ignored) {
            // the owner is mid logout
        }
    }

    /** Forget an owner (tests, and a closed companion session). */
    public static void forget(int ownerId) {
        ROOMS.remove(ownerId);
    }
}
