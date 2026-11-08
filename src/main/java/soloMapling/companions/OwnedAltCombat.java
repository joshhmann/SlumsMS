package soloMapling.companions;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotGrindSystem.KsManners;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance;
import soloMapling.ArtificialPlayer.BotTypes.FollowerFight;

import java.awt.Point;
import java.util.Comparator;
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
 *   <li><b>By its owner</b>: only mobs within {@link #LEASH_X_PX} px across of the owner - or, for an alt holding a
 *       whole-map lane (1.6.3 s-lanes), of its lane.</li>
 *   <li><b>In an event instance</b> (1.6.4 boss lane): the party's fight itself, wherever on the map it stands
 *       ({@link #withinLeash}). A boss' arms are far outside the owner box; an alt that kept to it stood through the
 *       fight.</li>
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

    /**
     * 1.6.4 boss lane (ticket 130): {@code -Dslums.altfight.instanceleash}, <b>off by default</b> (round 9: a switch is
     * off unless a brief says otherwise, and the release conf carries the on line). Turned on, an event instance reads
     * the leash as the party's own engagement rule instead of the box around the owner: on the Zakum altar its arms
     * stand far outside {@link #LEASH_X_PX} px and the alt otherwise stands still while its owner fights them. Outside
     * an instance the box is exactly what it always was, switch or no switch.
     */
    public static final String INSTANCE_LEASH_SWITCH = "slums.altfight.instanceleash";

    static boolean instanceLeashOn() {
        return Boolean.getBoolean(INSTANCE_LEASH_SWITCH);
    }

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
            touchedBy.remove(victim.getId()); // b135: its alts went home - nothing of its fights is kept
            return;
        }
        Map<Integer, Long> hits = touchedBy.computeIfAbsent(victim.getId(), id -> new ConcurrentHashMap<>());
        hits.values().removeIf(t -> now - t > HIT_MEMORY_MS); // b135: an old touch is let go, not kept to 32
        hits.put(mob.getObjectId(), now);
    }

    /**
     * The mob touched this character within {@link #HIT_MEMORY_MS}. Audit b135: a touch is released once it is old
     * or the mob is dead (its object id may come back on a new mob), so the attack driver never swings at a mob only
     * because it once bumped into the owner.
     */
    static boolean recentlyHit(int characterId, Monster mob, long now) {
        Map<Integer, Long> hits = touchedBy.get(characterId);
        if (hits == null) {
            return false;
        }
        Long at = hits.get(mob.getObjectId());
        if (at == null) {
            return false;
        }
        if (now - at > HIT_MEMORY_MS || !mob.isAlive()) {
            hits.remove(mob.getObjectId(), at);
            return false;
        }
        return true;
    }

    /** Audit b135: owners who no longer have alts out keep no touches (the driver's tick calls this). */
    static void retainOwners(java.util.Set<Integer> stillOut, java.util.Set<Integer> alts) {
        touchedBy.keySet().removeIf(id -> !stillOut.contains(id) && !alts.contains(id));
    }

    /** Test view: the touches kept for a character. */
    static int touchesKept(int characterId) {
        Map<Integer, Long> hits = touchedBy.get(characterId);
        return hits == null ? 0 : hits.size();
    }

    static void forget(int characterId) {
        touchedBy.remove(characterId);
    }

    /** The alt's party: every member id (its owner and itself included). Population followers share it (FollowerFight). */
    public static Set<Integer> partyIds(Character alt, Character owner) {
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

    /** strangers at the current time (tests and tools). */
    static boolean strangers(Monster m, MapleMap map, Set<Integer> party) {
        return strangers(m, map, party, System.currentTimeMillis());
    }

    /**
     * Someone outside the party has a claim on this mob. Audit 163 (report A3): by the one KS rule the population bots
     * use (KsManners: a hit claims a mob for CLAIM_MS after it lands, and the earlier claim wins), so
     * <ul>
     *   <li>a real player (or their alt) outside the party who is hitting it: theirs, whoever was first;</li>
     *   <li>a population bot that was on it BEFORE the party: the bot's (the party joining in does not take it over -
     *       before, any party damage did); a bot that came after the party: the party's;</li>
     *   <li>no live party claim: a real player outside the party standing by it makes it theirs (KsManners.ownerOf).</li>
     * </ul>
     */
    static boolean strangers(Monster m, MapleMap map, Set<Integer> party, long now) {
        Map<Integer, Long> dealt = m.damageSnapshot();
        long partySince = Long.MAX_VALUE;
        long botSince = Long.MAX_VALUE;
        for (Integer id : dealt.keySet()) {
            long since = KsManners.claimSince(m, id, now);
            if (since <= 0) {
                continue; // an old hit: no claim any more
            }
            if (party.contains(id)) {
                partySince = Math.min(partySince, since);
            } else if (BotHelpers.isArtificial(id)) {
                botSince = Math.min(botSince, since);
            } else {
                return true; // a real player (or their alt) outside the party is fighting it: theirs
            }
        }
        if (botSince < partySince) {
            return true; // a population bot was on it first
        }
        if (partySince != Long.MAX_VALUE || map == null) {
            return false;
        }
        // 1.6.2: who stands by a mob is the population bots' KS-manners rule (KsManners), asked about the real players
        // outside the party; the party-damage rules above are the alt's layer on top
        return KsManners.ownerOf(map, KsManners.realPlayersOn(map, c -> !party.contains(c.getId())), m, now) != null;
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
        return mayHit(m, map, party, System.currentTimeMillis());
    }

    static boolean mayHit(Monster m, MapleMap map, Set<Integer> party, long now) {
        return m != null && BotAttackDriver.botMayTarget(m) && !strangers(m, map, party, now);
    }

    /**
     * The alt's per-bot attack filter: a mob it may hit that is the party's fight. t-oa-1 (TEST 2026-10-29): with
     * {@link #mayHit} alone the driver faced the nearest wild mob instead of the alt's target, swung at nothing for
     * STALL_MS, gave up, and never got to looting.
     */
    public static boolean driverMayFace(Monster m, MapleMap map, Character owner, Character alt, Set<Integer> party,
                                 long now) {
        // audit (report A2): the owner leash holds for every mob a swing catches, area attacks included, not only for
        // the target it picks
        return withinLeash(m, owner, alt) && mayHit(m, map, party, now) && engaged(m, owner, alt, party, now);
    }

    /**
     * Where an alt's fights are kept near. Outside an event instance that is the owner leash ({@link #byOwner}, the
     * {@link #LEASH_X_PX} px box); inside one (a boss room, a PQ stage) it is the party's own engagement rule
     * ({@link #engaged}), which is what "the party's fight" already means - a boss' arms stand far outside the box and
     * an alt that kept to it stood still through the whole fight (ticket 130 boss lane). Requirement: the mob is still
     * checked against {@link #mayHit} and {@link #engaged}, so an instance never makes a quiet mob the alt's.
     * {@code -Dslums.altfight.instanceleash=true} turns the instance rule on; unset, the box applies everywhere.
     */
    static boolean withinLeash(Monster m, Character owner, Character alt) {
        return byOwner(m, owner, alt) || (instanceLeashOn() && alt != null && alt.getEventInstance() != null);
    }

    /** The mob is within the owner leash ({@link #LEASH_X_PX} across, {@link #LEASH_Y_PX} up or down). */
    static boolean byOwner(Monster m, Character owner, Character alt) {
        Point mp = m == null ? null : m.getPosition();
        Point op = leashFrom(owner, alt);
        return mp != null && op != null && Math.abs(mp.x - op.x) <= LEASH_X_PX && Math.abs(mp.y - op.y) <= LEASH_Y_PX;
    }

    /**
     * Where an alt's fights are kept near: its owner, or - 1.6.3 s-lanes - the whole-map lane it holds, since that is
     * where it stands. The lane only moves the leash's origin; whether the mob is the party's fight is unchanged.
     */
    private static Point leashFrom(Character owner, Character alt) {
        if (alt != null && alt.getMap() != null
                && CompanionStance.lanesWholeMap(alt.getId(), alt.getMapId())) {
            Point lane = CompanionStance.of(alt.getId(), alt.getMapId()).spot;
            if (lane != null) {
                return lane;
            }
        }
        return owner == null ? null : owner.getPosition();
    }

    /** The mob the alt goes for now: the nearest engaged one by its owner, or null (it follows instead). */
    static Monster target(Character owner, Character alt, Set<Integer> party, long now) {
        return target(owner, alt, party, now, m -> false);
    }

    /**
     * 1.6.7 companion-shared-picker: the pickers' sort keys beyond distance, in one place, for the cards that follow
     * this one. {@link #ranksBefore} asks it first and falls back to the nearest mob; a negative result puts {@code a}
     * first. Returning 0, which it does today, keeps every pick exactly what it was. The assist card and the boss
     * kill-order card plug in here instead of adding another rule to either caller.
     */
    public static volatile Comparator<Monster> priority = (a, b) -> 0;

    /**
     * 1.6.7 companion-shared-picker: the one ranking rule both pickers share - {@link #target} for an owned alt beside
     * its owner and {@link #targetInRun} inside a run - so a later sort key (assist, kill order) reaches both at once.
     * The {@link #priority} keys first, then the mob nearest the bot; ties keep the earlier candidate in map order, as
     * the distance-only rule always did.
     */
    static boolean ranksBefore(Monster a, Monster b, Point at) {
        int p = priority.compare(a, b);
        return p != 0 ? p < 0 : at.distanceSq(a.getPosition()) < at.distanceSq(b.getPosition());
    }

    /** A mob a picker may even look at: alive, with a position, and one a swing may target (never a friendly PQ mob). */
    private static boolean usable(Monster m) {
        return m != null && m.getPosition() != null && m.isAlive() && BotAttackDriver.botMayTarget(m);
    }

    /**
     * As {@link #target(Character, Character, Set, long)}, skipping the mobs {@code skip} names (ones it could not
     * land a hit on lately) and mobs on another ledge than the alt - by the attack driver's own surface test, so the
     * alt never stands at a mob the driver will not face (it follows instead).
     *
     * <p>1.6.2 (followerfix, owner rule "one implementation for alts and population bots"): a population bot that
     * follows a player in his party ({@code FollowerFight}) picks the party's fight by this same rule, its leader in
     * the owner's place.
     */
    public static Monster target(Character owner, Character alt, Set<Integer> party, long now,
                          java.util.function.Predicate<Monster> skip) {
        MapleMap map = alt.getMap();
        Point at = alt.getPosition();
        Point op = owner.getPosition();
        if (map == null || at == null || op == null || owner.getMap() != map) {
            return null;
        }
        Monster best = null;
        // 1.6.5 b311: the population followers' boss-part reach gate (FollowerFight, ticket 130 fix 2), same switch.
        // H1 run 2b: two flying Red Wyverns were the nearest engaged mobs and a melee Hero alt walked under them for
        // four minutes (STALL_MS, then GIVE_UP_MS, then the other one) and never swung at the head it could reach.
        boolean reachGate = FollowerFight.reachGateOn();
        BotAttackProfile profile = null;
        for (Monster m : map.getAllMonsters()) {
            if (!usable(m) || !withinLeash(m, owner, alt)) {
                continue;
            }
            if (!engaged(m, owner, alt, party, now) || skip.test(m) || !BotAttackDriver.onAttackableSurface(map, at, m)
                    || strangers(m, map, party, now)) {
                continue;
            }
            if (reachGate && FollowerFight.isBossPart(m)) {
                if (profile == null) {
                    profile = BotAttackDriver.primaryAttack(alt);
                }
                if (!FollowerFight.canGetInReach(map, at, m, profile)) {
                    continue; // a part it can never get in reach of: the next-nearest instead
                }
            }
            if (best == null || ranksBefore(m, best, at)) {
                best = m;
            }
        }
        return best;
    }

    /**
     * 1.6.7 companion-shared-picker: the picker inside a run - a dojo floor, an expedition's run, the leader's party
     * quest - where the whole instance is the party's fight, not one mob the party engaged beside its owner. The three
     * rules that read an owned alt's place beside its owner are therefore off: the leash ({@link #withinLeash}), the
     * "engaged" rule ({@link #engaged}) and the KS-manners "never a stranger's" rule ({@link #strangers}). Every filter
     * the run path already applied stays: alive, {@link BotAttackDriver#botMayTarget},
     * {@link BotAttackDriver#onAttackableSurface}, the boss-part reach gate when {@link FollowerFight#reachGateOn()},
     * and the caller's {@code skip}. Ranked by {@link #ranksBefore}, so the assist and kill-order cards reach a run
     * through this method alone.
     *
     * <p>Used by a party regular inside a run when
     * {@code PartyCompanionPolicy.on(Feature.SHARED_PICKER, bot)} (off by default for regulars, so an ordinary run
     * still picks {@code FollowerFight.nearest}).
     */
    public static Monster targetInRun(Character bot, java.util.function.Predicate<Monster> skip) {
        MapleMap map = bot == null ? null : bot.getMap();
        Point at = bot == null ? null : bot.getPosition();
        if (map == null || at == null) {
            return null;
        }
        boolean reachGate = FollowerFight.reachGateOn();
        BotAttackProfile profile = null;
        Monster best = null;
        for (Monster m : map.getAllMonsters()) {
            if (!usable(m) || skip.test(m) || !BotAttackDriver.onAttackableSurface(map, at, m)) {
                continue;
            }
            if (reachGate && FollowerFight.isBossPart(m)) {
                if (profile == null) {
                    profile = BotAttackDriver.primaryAttack(bot);
                }
                if (!FollowerFight.canGetInReach(map, at, m, profile)) {
                    continue;
                }
            }
            if (best == null || ranksBefore(m, best, at)) {
                best = m;
            }
        }
        return best;
    }
}
