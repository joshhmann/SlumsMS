package soloMapling.companions;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotGrindSystem.Approach;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionItems;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSwitches;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.qa.QaLog;
import tools.PacketCreator;

import java.awt.Point;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 1.6.1 ticket 13 (owner 2026-10-28, "full alts"): what makes a summoned alt a party member and not a statue. One
 * shared 200 ms tick drives every alt out in the world:
 * <ol>
 *   <li><b>Death</b>: an alt lying dead ({@link BotDeaths}; a lethal hit really kills it, the normal death penalty
 *       applies) gets up in town after a few seconds and goes home (saved); its owner is told, and calls it again with
 *       {@code @botme}.</li>
 *   <li><b>Recovery</b>: its HP and MP come back like a player's (10 s ticks), and it drinks its own potions when hit
 *       or short of MP (BotVitals, the bots' potion logic, out of its own bag).</li>
 *   <li><b>Catch-up</b>: an alt left on another map (a boat, a route it cannot walk) for {@link #CATCHUP_STUCK_MS}
 *       while not walking, or {@link #CATCHUP_MAX_MS} in all, is put down beside its owner (as {@code @botme here};
 *       never into or out of an instance).</li>
 *   <li><b>The activity gate</b> ({@link OwnedAltGate}): closed, it only follows.</li>
 *   <li><b>Fight</b> ({@link OwnedAltCombat}): the nearest engaged, non-stranger mob by its owner - walks into reach and
 *       swings with its own class skills, stats, MP and ammo through the same attack system and packets on-screen bots
 *       use ({@link BotAttackDriver}); it keeps its own weapon (none appears), and does not fight without one it can
 *       draw.</li>
 *   <li><b>Loot</b> ({@link OwnedAltLoot}): its own and its party's drops into its own bag; a full bag is said once.</li>
 *   <li>Buffs: its learned class buffs, kept up by the population bots' maintenance ({@link BotBuffDriver}), paid
 *       out of its own MP.</li>
 *   <li>Otherwise it follows its owner (GCFollow, the cherry-picked 1.6.0.4 follow).</li>
 * </ol>
 */
public final class OwnedAltCompanion {

    private static final Logger log = LoggerFactory.getLogger(OwnedAltCompanion.class);

    private OwnedAltCompanion() {
    }

    static final long TICK_MS = 200;
    static final long CATCHUP_STUCK_MS = 8_000;
    static final long CATCHUP_MAX_MS = 45_000;
    private static final long LOOT_GAP_MS = 250;
    /** A target it walked to or swung at this long without landing a hit is left alone for {@link #GIVE_UP_MS}. */
    static final long STALL_MS = 10_000; // a 650 px walk at a slow walk speed fits in it
    static final long GIVE_UP_MS = 15_000;

    /** Per alt: what the driver remembers between ticks, and what QA reads. */
    static final class State {
        final int altId;
        volatile boolean fighting;
        volatile boolean gone;
        OwnedAltGate.Why told = OwnedAltGate.Why.OPEN;
        final Set<InventoryType> toldFull = EnumSet.noneOf(InventoryType.class);
        boolean toldMesoFull;
        boolean toldWeapon;
        long separatedSince;
        long lastTickMs;
        long nextLootMs;
        int lastMoveX = Integer.MIN_VALUE;
        int targetOid = -1;
        long targetSince;
        long lastLandedMs;
        final Map<Integer, Long> skip = new HashMap<>(); // mob oid -> until when it is left alone (stalled on it)
        volatile String doing = "nothing yet";
        // 1.6.2 ticket 99: holding a stance spot (CompanionStance), and the sibling supply check
        int holdX = Integer.MIN_VALUE;
        long holdIssuedMs;
        long nextShareMs;
        long nextBuffMs; // 1.6.2: the bots' self-buff loop (BotBuffDriver.selfBuff)
        // QA counters (t-oa-1)
        final AtomicLong swings = new AtomicLong();
        final AtomicLong damageDealt = new AtomicLong();
        final AtomicLong loots = new AtomicLong();
        final AtomicLong hitsTaken = new AtomicLong();
        final AtomicLong damageTaken = new AtomicLong();
        volatile OwnedAltGate.Why gate = OwnedAltGate.Why.OPEN;

        State(int altId) {
            this.altId = altId;
        }
    }

    private static final java.util.Random BUFF_RNG = new java.util.Random();
    private static final Map<Integer, State> states = new ConcurrentHashMap<>();
    private static final Set<Integer> owners = ConcurrentHashMap.newKeySet();
    private static volatile ScheduledFuture<?> task;

    /** The driver runs from the first summon on (one task for every alt). */
    static void start() {
        if (task != null) {
            return;
        }
        synchronized (OwnedAltCompanion.class) {
            if (task == null) {
                try {
                    task = server.TimerManager.getInstance().register(OwnedAltCompanion::tickAll, TICK_MS, TICK_MS);
                } catch (RuntimeException | LinkageError noTimer) {
                    log.warn("Owned alt driver did not start: {}", noTimer.toString());
                }
            }
        }
    }

    /** True while this character has alts out (a mob hitting them makes it the party's fight). */
    public static boolean hasAltsOut(int ownerId) {
        return owners.contains(ownerId);
    }

    /** The alt is leaving the world: the driver lets go of it (called before its movement is disabled). */
    static void forget(Character alt) {
        if (alt == null) {
            return;
        }
        State s = states.remove(alt.getId());
        if (s != null) {
            synchronized (s) {
                s.gone = true;
            }
        }
        BotAttackDriver.setTargetFilter(alt.getId(), null);
        BotAttackDriver.clearBot(alt.getId());
        BotBuffDriver.clearBot(alt.getId());
        CompanionStance.forget(alt.getId()); // 1.6.2 ticket 99
        BotDeaths.clearBot(alt.getId());
        OwnedAltCombat.forget(alt.getId());
    }

    // ── QA view (t-oa-1) ──────────────────────────────────────────────────────────────────────────────

    /** What one alt has done since it came out: swings that landed, damage dealt, pickups, hits taken. */
    public record Stats(long swings, long damageDealt, long loots, long hitsTaken, long damageTaken, boolean fighting,
                        String gate, String doing) {
    }

    public static Stats stats(int altId) {
        State s = states.get(altId);
        if (s == null) {
            return new Stats(0, 0, 0, 0, 0, false, "none", "not driven");
        }
        return new Stats(s.swings.get(), s.damageDealt.get(), s.loots.get(), s.hitsTaken.get(), s.damageTaken.get(),
                s.fighting, s.gate.name(), s.doing);
    }

    /** A mob's touch landed on an alt (BotContactDamage): counted, and that mob becomes the party's fight. */
    public static void tookHit(Character alt, Monster mob, int damage) {
        if (alt == null || !alt.isOwnedAlt()) {
            return;
        }
        OwnedAltCombat.noteHit(alt, mob, System.currentTimeMillis());
        State s = states.computeIfAbsent(alt.getId(), State::new);
        if (damage > 0) {
            s.hitsTaken.incrementAndGet();
            s.damageTaken.addAndGet(damage);
        }
    }

    // ── the tick ──────────────────────────────────────────────────────────────────────────────────────

    static void tickAll() {
        if (soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        List<OwnedAltService.Active> active = OwnedAltService.getInstance().active();
        Set<Integer> ownerIds = new HashSet<>();
        Set<Integer> altIds = new HashSet<>();
        Map<MapleMap, Integer> fightingOn = new HashMap<>();
        for (OwnedAltService.Active a : active) {
            ownerIds.add(a.owner().getId());
            altIds.add(a.alt().getId());
            State s = states.get(a.alt().getId());
            if (s != null && s.fighting && a.alt().getMap() != null) {
                fightingOn.merge(a.alt().getMap(), 1, Integer::sum);
            }
        }
        owners.retainAll(ownerIds);
        owners.addAll(ownerIds);
        states.keySet().removeIf(id -> !altIds.contains(id));
        long now = System.currentTimeMillis();
        // 1.6.2 ticket 99: each alt's formation place, by its slot among the owner's party companions
        Map<Integer, List<soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target>> slotsOf = new HashMap<>();
        boolean formations = CompanionSwitches.orders();
        for (OwnedAltService.Active a : active) {
            Character alt = a.alt();
            State s = states.computeIfAbsent(alt.getId(), State::new);
            if (formations) {
                var slots = slotsOf.computeIfAbsent(a.owner().getId(),
                        id -> soloMapling.ArtificialPlayer.BotPartySystem.PartyBotOrders.slots(a.owner()));
                int idx = 0;
                while (idx < slots.size() && slots.get(idx).id() != alt.getId()) {
                    idx++;
                }
                GCMovement.setFollowOffset(alt, CompanionStance.formation(a.owner().getId()).offsetFor(idx));
            }
            int others = fightingOn.getOrDefault(alt.getMap(), 0) - (s.fighting ? 1 : 0);
            try {
                tick(a.owner(), alt, s, now, others);
            } catch (RuntimeException e) {
                if (QaLog.every("owned-alt-tick:" + alt.getId(), 60_000)) {
                    log.warn("Owned alt {} tick failed", alt.getName(), e);
                }
            }
        }
    }

    static void tick(Character owner, Character alt, State s, long now, int otherAltsFightingHere) {
        synchronized (s) {
            if (s.gone || alt.isOwnedAltClosing() || alt.getMap() == null) {
                return;
            }
            long elapsed = s.lastTickMs == 0 ? 0 : now - s.lastTickMs;
            s.lastTickMs = now;
            if (BotDeaths.isDead(alt.getId())) {
                if (BotDeaths.readyToRespawn(alt.getId())) {
                    afterDeath(owner, alt, s);
                }
                return;
            }
            if (alt.getHp() <= 0) {
                return; // the death is on its way through the map's stat update
            }
            if (elapsed > 0) {
                BotVitals.regen(alt, Math.min(elapsed, 2_000));
            }
            // 161: the shared party-support path (heal, buff pots) - the same call a population grinder makes
            soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport.tick(alt);
            if (soloMapling.mechanics.MechanicsHold.committed(alt)) {
                // 1.6.1 KPQ automation: a party-quest run drives the alt (the same PQ logic as party bots)
                if (s.fighting) {
                    s.fighting = false;
                }
                s.doing = "playing the party quest";
                return;
            }
            if (catchUp(owner, alt, s, now)) {
                return;
            }
            OwnedAltGate.Why why = OwnedAltGate.check(owner, alt, now, otherAltsFightingHere);
            s.gate = why;
            if (why != OwnedAltGate.Why.OPEN) {
                if (s.fighting) {
                    standDown(owner, alt, s);
                }
                if (why.tell != null && s.told != why) {
                    tell(owner, why.tell);
                }
                s.told = why.tell != null ? why : s.told;
                keepFollowing(owner, alt);
                s.doing = "following (gate " + why + ")";
                return;
            }
            s.told = OwnedAltGate.Why.OPEN;
            // 1.6.2: its class buffs, by the population bots' own maintenance (BotBuffDriver), recast on time as they
            // run out and paid out of its own MP and MP potions (Pay.ALL); only the buffs it has learned
            s.nextBuffMs = BotBuffDriver.selfBuff(alt, now, s.nextBuffMs, () -> 0L, BotBuffDriver.Pay.ALL, BUFF_RNG);
            Set<Integer> party = OwnedAltCombat.partyIds(alt, owner);
            MapleMap map = alt.getMap();
            BotAttackDriver.setTargetFilter(alt.getId(),
                    m -> OwnedAltCombat.driverMayFace(m, map, owner, alt, party, now));

            String weapon = weaponProblem(alt);
            s.skip.values().removeIf(until -> until <= now);
            // 1.6.2 ticket 99: a staying alt goes after nothing, a sentry only what comes near its spot
            CompanionStance.Stance stance = CompanionSwitches.orders() ? CompanionStance.of(alt.getId(), alt.getMapId()) : null;
            Monster target = weapon == null
                    ? OwnedAltCombat.target(owner, alt, party, now, m -> s.skip.containsKey(m.getObjectId())
                    || !CompanionStance.mayChase(stance, m.getPosition())) : null;
            if (target == null && weapon != null && !s.toldWeapon
                    && OwnedAltCombat.target(owner, alt, party, now) != null) {
                s.toldWeapon = true;
                say(owner, alt, weapon);
            }
            if (target != null) {
                fight(alt, target, s, now);
                return;
            }
            s.targetOid = -1;
            if (s.fighting) {
                s.fighting = false;
                s.lastMoveX = Integer.MIN_VALUE;
            }
            if (loot(owner, alt, party, s, now)) {
                return;
            }
            if (now >= s.nextShareMs) {
                s.nextShareMs = now + SHARE_CHECK_MS;
                askSiblings(owner, alt);
            }
            keepFollowing(owner, alt);
            s.doing = "following";
        }
    }

    private static void keepFollowing(Character owner, Character alt) {
        if (holdStance(alt)) {
            return;
        }
        if (!GCMovement.isFollowing(alt) && !GCMovement.isTraveling(alt)) {
            GCMovement.follow(alt, owner);
        }
    }

    /** How often an alt checks whether it runs low on a supply a sibling could share (1.6.2 ticket 99). */
    static final long SHARE_CHECK_MS = 15_000;
    /** A hold move that did not get there is tried again after this long. */
    static final long HOLD_RETRY_MS = 3_000;

    /**
     * 1.6.2 ticket 99: an alt told to stay, move here, guard or patrol (CompanionStance) holds its spot instead of
     * following - only on the map it was told on, and only while its owner keeps it out (the gate still rules its
     * fighting). True when it holds (it walks to its spot, or stands there).
     */
    private static boolean holdStance(Character alt) {
        if (!CompanionSwitches.orders()) {
            return false;
        }
        CompanionStance.Stance stance = CompanionStance.of(alt.getId(), alt.getMapId());
        java.awt.Point at = alt.getPosition();
        java.awt.Point spot = CompanionStance.holdPoint(stance, at);
        if (spot == null) {
            return false;
        }
        State s = states.get(alt.getId());
        if (GCMovement.isFollowing(alt) || GCMovement.isTraveling(alt)) {
            GCMovement.stop(alt);
        }
        if (at == null || Math.abs(at.x - spot.x) <= CompanionStance.THERE_PX && Math.abs(at.y - spot.y) <= 60) {
            if (s != null) {
                s.holdX = Integer.MIN_VALUE;
                s.doing = "holding (" + stance.mode + ")";
            }
            return true;
        }
        long now = System.currentTimeMillis();
        if (s == null || s.holdX != spot.x || now - s.holdIssuedMs > HOLD_RETRY_MS && !GCMovement.isMoving(alt)) {
            java.awt.Point ground = alt.getMap() == null ? null : GCMovement.groundPointBelow(alt.getMap(), spot.x, spot.y);
            GCMovement.move(alt, spot.x, ground != null ? ground.y : spot.y);
            if (s != null) {
                s.holdX = spot.x;
                s.holdIssuedMs = now;
                s.doing = "walking to its spot (" + stance.mode + ")";
            }
        }
        return true;
    }

    /**
     * 1.6.2 ticket 99 (COMPANION_SUPPLIES_ENABLED): an alt low on potions, arrows or rocks it uses gets some from the
     * sibling alt with the most to spare, in a normal trade between the two (the owner's own characters).
     */
    private static void askSiblings(Character owner, Character alt) {
        if (!CompanionSwitches.supplies() || BotGiveTrade.isBusy(alt) || alt.getTrade() != null) {
            return;
        }
        List<Character> siblings = new java.util.ArrayList<>();
        for (OwnedAltService.Active a : OwnedAltService.getInstance().active()) {
            if (a.owner().getId() == owner.getId() && a.alt() != alt) {
                siblings.add(a.alt());
            }
        }
        if (siblings.isEmpty()) {
            return;
        }
        boolean usesMp;
        boolean usesAmmo;
        try {
            var needs = soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies.needs(alt);
            usesMp = needs.usesMp();
            usesAmmo = needs.ammoItemId() != 0;
        } catch (RuntimeException | LinkageError noData) {
            return;
        }
        for (SupplyShare.Kind kind : SupplyShare.Kind.values()) {
            boolean uses = switch (kind) {
                case HP -> true;
                case MP -> usesMp;
                case AMMO -> usesAmmo;
                case ROCKS -> SupplyShare.count(alt, kind, alt) > 0;
            };
            if (SupplyShare.low(alt, kind, uses)
                    && CompanionItems.shareWithSibling(alt, kind, siblings, OwnedAltOrders::say)) {
                audit(owner, alt.getName() + " gets " + SupplyShare.said(kind) + " from a sibling");
                return;
            }
        }
    }

    private static void standDown(Character owner, Character alt, State s) {
        s.fighting = false;
        s.lastMoveX = Integer.MIN_VALUE;
        GCMovement.follow(alt, owner);
        audit(owner, alt.getName() + " stops fighting (" + s.gate + ")");
    }

    /** Why the alt cannot fight with what it holds (said once), or null. */
    static String weaponProblem(Character alt) {
        Item held = alt.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        if (held == null) {
            return "I have no weapon equipped, so I can't fight.";
        }
        if (BotWeapons.UNDRAWABLE.contains(held.getItemId())) {
            return "I can't fight with this weapon - give me another one.";
        }
        return null;
    }

    private static void fight(Character alt, Monster target, State s, long now) {
        if (target.getObjectId() != s.targetOid) {
            s.targetOid = target.getObjectId();
            s.targetSince = now;
        }
        if (now - Math.max(s.targetSince, s.lastLandedMs) > STALL_MS) {
            // QA run 3: it could walk at (or swing past) a mob it never reached, and nothing else got done (no loot,
            // no new fight). Leave that one a while; the next tick picks another, loots or follows.
            s.skip.put(target.getObjectId(), now + GIVE_UP_MS);
            s.targetOid = -1;
            s.doing = "gave up on " + target.getName() + " (no hit in " + STALL_MS / 1000 + " s)";
            audit(alt.getOwnedAltOwner(), alt.getName() + " " + s.doing);
            return;
        }
        if (!s.fighting) {
            s.fighting = true;
            audit(alt.getOwnedAltOwner(), alt.getName() + " fights " + target.getName());
        }
        leaveFollow(alt);
        // 1.6.2: the shared approach step (Approach, as the grind brain's): in reach by the bots' rule, else walk to
        // the bots' stand spot for this mob (past its body, the class's distance, aside from anyone on that pixel)
        Point at = alt.getPosition();
        Point mob = target.getPosition();
        int[] reach = Approach.reach(alt);
        if (!Approach.inReach(at, mob, reach)) {
            int[] spot = Approach.fightSpot(alt, target, reach, BotCombatTuning.human());
            s.lastMoveX = Approach.walkTo(alt, spot[0], spot[1], s.lastMoveX);
            s.doing = "walking to " + target.getName() + " (dx " + (mob.x - at.x) + ")";
            return;
        }
        s.lastMoveX = Integer.MIN_VALUE;
        BotAttackDriver.AttackResult r = BotAttackDriver.botAttack(alt);
        s.doing = "swinging at " + target.getName() + (r == null || r.hit() ? "" : " (" + r.reason() + ")");
        if (r != null && r.hit()) {
            s.lastLandedMs = now;
            s.swings.incrementAndGet();
            s.damageDealt.addAndGet(Math.max(0, r.damage()));
            if (QaLog.on(QaLog.Topic.OWNED_ALT) && QaLog.every("owned-alt-hit:" + alt.getId(), 3_000)) {
                audit(alt.getOwnedAltOwner(), alt.getName() + " hits " + r.monsterName() + " for " + r.damage()
                        + (r.killed() ? " (killed)" : "") + " skill " + r.skillId());
            }
        }
    }

    private static boolean loot(Character owner, Character alt, Set<Integer> party, State s, long now) {
        if (now < s.nextLootMs) {
            return false;
        }
        Set<InventoryType> full = EnumSet.noneOf(InventoryType.class);
        boolean[] mesoFull = new boolean[1];
        MapItem drop = OwnedAltLoot.pick(alt, owner, party, now, full, mesoFull);
        for (InventoryType bag : full) {
            if (s.toldFull.add(bag)) {
                say(owner, alt, OwnedAltLoot.fullLine(bag));
            }
        }
        if (mesoFull[0] && !s.toldMesoFull) {
            s.toldMesoFull = true;
            say(owner, alt, "I can't carry any more mesos.");
        }
        if (drop == null) {
            return false;
        }
        s.doing = "looting " + drop.getItemId();
        if (OwnedAltLoot.inReach(alt, drop)) {
            s.lastMoveX = Integer.MIN_VALUE;
            InventoryType bag = OwnedAltLoot.bagOf(drop);
            alt.pickupItem(drop); // the player path: loot rules, party meso split, its own bag
            if (drop.isPickedUp()) {
                s.loots.incrementAndGet();
                if (bag != null) {
                    s.toldFull.remove(bag); // it had room again: a later full bag is said again
                } else {
                    s.toldMesoFull = false;
                }
            }
            s.nextLootMs = now + LOOT_GAP_MS;
            return true;
        }
        leaveFollow(alt);
        Point dp = drop.getPosition(); // the shared approach walk: to the ground under the drop, not re-issued per tick
        s.lastMoveX = Approach.walkTo(alt, dp.x, Approach.groundY(alt, dp), s.lastMoveX);
        return true;
    }

    /** Off the follow leash while it walks to a fight or a drop: the follow session would pull it back every 50 ms. */
    private static void leaveFollow(Character alt) {
        if (GCMovement.isFollowing(alt) || GCMovement.isTraveling(alt)) {
            GCMovement.stop(alt);
        }
    }

    /** Left on another map: after a while, put it down beside its owner. True when it moved this tick. */
    private static boolean catchUp(Character owner, Character alt, State s, long now) {
        if (owner.getMap() == null || alt.getMap() == owner.getMap()) {
            s.separatedSince = 0;
            return false;
        }
        if (s.separatedSince == 0) {
            s.separatedSince = now;
            return false;
        }
        long apart = now - s.separatedSince;
        if (!(apart >= CATCHUP_MAX_MS || apart >= CATCHUP_STUCK_MS && !GCMovement.isTraveling(alt))) {
            return false;
        }
        s.separatedSince = 0;
        if (OwnedAltService.getInstance().catchUp(owner, alt)) {
            audit(owner, alt.getName() + " catches up to map " + owner.getMapId() + " after " + apart + " ms");
            return true;
        }
        return false;
    }

    /** It has lain long enough: up in town, saved and home; the owner is told. */
    private static void afterDeath(Character owner, Character alt, State s) {
        s.gone = true;
        s.fighting = false;
        GCMovement.stop(alt);
        GCMovement.releaseCommitmentHold(alt);
        if (alt.getEventInstance() == null && alt.getMap() != null) {
            int town = alt.getMap().getReturnMapId();
            if (town <= 0 || town == MapId.NONE) {
                town = alt.getMapId();
            }
            try {
                alt.respawn(town); // as a player clicking OK: the return map with 50 HP
            } catch (RuntimeException e) {
                log.warn("Owned alt {} could not respawn in {}", alt.getName(), town, e);
            }
        } else if (alt.getHp() <= 0) {
            alt.updateHp(50); // died inside an instance: it leaves with the event's exit rules, not lying at 0 HP
        }
        audit(owner, alt.getName() + " died; respawned on map " + alt.getMapId() + ", going home");
        OwnedAltService.getInstance().sendHomeAfterDeath(alt, alt.getName() + " died and lost EXP, as a player does."
                + " It got up in town and went home (saved). Call it again with @botme " + alt.getName() + ".");
    }

    /**
     * The alt speaks in party chat. 1.6.2 (owner, grill 2026-10-30: "alt chat lines: whole party"): when the alt is in
     * its owner's party, the line goes out on the shared party voice ({@link
     * soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat#sayInParty}), so everyone in that party sees its loot
     * lines, "bag full", trade replies and acknowledgements; before, only the owner did. An alt outside its owner's
     * party still tells the owner alone, as before. The lines themselves are the alts' own, which never say what it is.
     */
    static void say(Character owner, Character alt, String text) {
        try {
            net.server.world.Party party = alt.getParty();
            if (party != null && owner != null && party.getMemberById(owner.getId()) != null) {
                soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat.sayInParty(alt, text);
            } else {
                owner.sendPacket(PacketCreator.multiChat(alt.getName(), text, 1));
            }
        } catch (RuntimeException ignored) {
            // the owner is leaving
        }
        audit(owner, alt.getName() + " says: " + text);
    }

    private static void tell(Character owner, String text) {
        try {
            owner.yellowMessage(text);
        } catch (RuntimeException ignored) {
            // the owner is leaving
        }
        audit(owner, "told: " + text);
    }

    private static void audit(Character owner, String message) {
        if (owner != null && QaLog.on(QaLog.Topic.OWNED_ALT)) {
            QaLog.owner(QaLog.Topic.OWNED_ALT, owner.getId(), message);
        }
    }
}
