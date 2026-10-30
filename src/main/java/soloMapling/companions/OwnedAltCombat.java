package soloMapling.companions;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotGrindSystem.KsManners;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.awt.Point;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.1 ticket 13: what an owned alt fights. It fights with its owner's party, never on its own account:
 * <ul>
 *   <li><b>Engaged mobs only</b>: a mob its owner or any party member (itself included) has damaged, or a mob that
 *       touched its owner or itself in the last {@link #HIT_MEMORY_MS} ms. It never starts on a quiet mob alone.</li>
 *   <li><b>Never a stranger's</b> (KS manners): a mob a real player outside the party (or their alt) has damaged, a
 *       mob a population bot is on before the party, or one a real player outside the party stands by before the party
 *       touched it (the population bots' own KS-manners rule, {@link KsManners#ownerOf}), is left alone - and that
 *       holds for every mob its area attacks catch (BotAttackDriver's per-bot filter).</li>
 *   <li><b>By its owner</b>: only mobs within {@link #LEASH_X_PX} px across of the owner.</li>
 * </ul>
 */
public final class OwnedAltCombat {

    private OwnedAltCombat() {
    }

    /** How long a mob's touch on the owner (or the alt) makes it the party's fight. */
    static final long HIT_MEMORY_MS = 6_000;
    /** How far across from the owner an alt goes for a mob. */
    static final int LEASH_X_PX = 650;
    static final int LEASH_Y_PX = 250;

    // character id (owner or alt) -> mob object id -> when it last touched them
    private static final Map<Integer, Map<Integer, Long>> touchedBy = new ConcurrentHashMap<>();

    /**
     * A mob hit this character (a real owner's damage packet, or the alt's contact damage). Kept only for owners with
     * alts out and for alts, so a busy map costs nothing.
     */
    public static void noteHit(Character victim, Monster mob, long now) {
        if (victim == null || mob == null) {
            return;
        }
        if (!victim.isOwnedAlt() && !OwnedAltCompanion.hasAltsOut(victim.getId())) {
            return;
        }
        Map<Integer, Long> hits = touchedBy.computeIfAbsent(victim.getId(), id -> new ConcurrentHashMap<>());
        hits.put(mob.getObjectId(), now);
        if (hits.size() > 32) {
            hits.values().removeIf(t -> now - t > HIT_MEMORY_MS);
        }
    }

    static boolean recentlyHit(int characterId, Monster mob, long now) {
        Map<Integer, Long> hits = touchedBy.get(characterId);
        Long at = hits == null ? null : hits.get(mob.getObjectId());
        return at != null && now - at <= HIT_MEMORY_MS;
    }

    static void forget(int characterId) {
        touchedBy.remove(characterId);
    }

    /** The alt's party: every member id (its owner and itself included). */
    static Set<Integer> partyIds(Character alt, Character owner) {
        Set<Integer> ids = new HashSet<>();
        ids.add(alt.getId());
        if (owner != null) {
            ids.add(owner.getId());
        }
        Party party = alt.getParty();
        if (party != null) {
            for (PartyCharacter pc : party.getMembers()) {
                if (pc != null) {
                    ids.add(pc.getId());
                }
            }
        }
        return ids;
    }

    /** Someone outside the party has damaged this mob, or a real player outside it stands by it first. */
    static boolean strangers(Monster m, MapleMap map, Set<Integer> party) {
        Map<Integer, Long> dealt = m.damageSnapshot();
        boolean partyHit = false;
        boolean botHit = false;
        for (Integer id : dealt.keySet()) {
            if (party.contains(id)) {
                partyHit = true;
            } else if (BotHelpers.isArtificial(id)) {
                botHit = true; // a population bot: its mob unless the party was on it too (a bot's KS is not theirs)
            } else {
                return true; // a real player (or their alt) outside the party hit it: theirs, whoever was first
            }
        }
        if (botHit && !partyHit) {
            return true;
        }
        if (partyHit || map == null) {
            return false;
        }
        // 1.6.2: who stands by a mob is the population bots' KS-manners rule (KsManners), asked about the real players
        // outside the party; the party-damage rules above are the alt's layer on top
        return KsManners.ownerOf(map, KsManners.realPlayersOn(map, c -> !party.contains(c.getId())), m) != null;
    }

    /**
     * The party is fighting this mob now: a member hit it in the last {@link #ENGAGED_MS} ms, or it touched the owner
     * or the alt just now. (QA run 3, 2026-10-29: "ever damaged by the owner" kept a mob the owner hit in an earlier
     * fight engaged forever, and the alt went for it instead of the owner's new fight.)
     */
    static boolean engaged(Monster m, Character owner, Character alt, Set<Integer> party, long now) {
        if (recentlyHit(owner.getId(), m, now) || recentlyHit(alt.getId(), m, now)) {
            return true;
        }
        for (Integer id : party) {
            long at = m.lastHitBy(id);
            if (at > 0 && now - at <= ENGAGED_MS) {
                return true;
            }
        }
        return false;
    }

    /** How long a party member's hit keeps a mob the party's fight. */
    static final long ENGAGED_MS = 20_000;

    /** May the alt's swing touch this mob at all: never a stranger's. */
    static boolean mayHit(Monster m, MapleMap map, Set<Integer> party) {
        return m != null && BotAttackDriver.botMayTarget(m) && !strangers(m, map, party);
    }

    /**
     * The alt's per-bot attack filter: a mob it may hit that is the party's fight. t-oa-1 (TEST 2026-10-29): with
     * {@link #mayHit} alone the driver faced the nearest wild mob instead of the alt's target, swung at nothing for
     * STALL_MS, gave up, and never got to looting.
     */
    static boolean driverMayFace(Monster m, MapleMap map, Character owner, Character alt, Set<Integer> party,
                                 long now) {
        // audit (report A2): the owner leash holds for every mob a swing catches, area attacks included, not only for
        // the target it picks
        return byOwner(m, owner) && mayHit(m, map, party) && engaged(m, owner, alt, party, now);
    }

    /** The mob is within the owner leash ({@link #LEASH_X_PX} across, {@link #LEASH_Y_PX} up or down). */
    static boolean byOwner(Monster m, Character owner) {
        Point mp = m == null ? null : m.getPosition();
        Point op = owner == null ? null : owner.getPosition();
        return mp != null && op != null && Math.abs(mp.x - op.x) <= LEASH_X_PX && Math.abs(mp.y - op.y) <= LEASH_Y_PX;
    }

    /** The mob the alt goes for now: the nearest engaged one by its owner, or null (it follows instead). */
    static Monster target(Character owner, Character alt, Set<Integer> party, long now) {
        return target(owner, alt, party, now, m -> false);
    }

    /**
     * As {@link #target(Character, Character, Set, long)}, skipping the mobs {@code skip} names (ones it could not
     * land a hit on lately) and mobs on another ledge than the alt - by the attack driver's own surface test, so the
     * alt never stands at a mob the driver will not face (it follows instead).
     */
    static Monster target(Character owner, Character alt, Set<Integer> party, long now,
                          java.util.function.Predicate<Monster> skip) {
        MapleMap map = alt.getMap();
        Point at = alt.getPosition();
        Point op = owner.getPosition();
        if (map == null || at == null || op == null || owner.getMap() != map) {
            return null;
        }
        Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (Monster m : map.getAllMonsters()) {
            Point mp = m == null ? null : m.getPosition();
            if (mp == null || !m.isAlive() || !BotAttackDriver.botMayTarget(m)) {
                continue;
            }
            if (!byOwner(m, owner)) {
                continue;
            }
            if (!engaged(m, owner, alt, party, now) || skip.test(m) || !BotAttackDriver.onAttackableSurface(map, at, m)
                    || strangers(m, map, party)) {
                continue;
            }
            double d = at.distanceSq(mp);
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        return best;
    }
}
