package soloMapling.mechanics;

import soloMapling.mechanics.StageView.ReactorPlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guild Quest, stage 2 of the quest ("Hall of the Knight", {@code 990000400}): four Longinus Spears
 * ({@code 4001025}) onto the four altars of {@code 990000440}. The clear is the reactor {@code speargate}
 * ({@code 9208004} on 990000400) at state 4: only {@code scripts/reactor/9208007.js:29-31} writes it (every altar
 * reactor {@code spear1..spear4} adds one when a spear is dropped in its box) and {@code
 * scripts/portal/speargate_open.js} opens {@code next00} on exactly that.
 *
 * <h2>The seven rooms (all in {@code Map.wz/Map/Map9/<id>.img.xml})</h2>
 * <ul>
 *   <li>{@code 990000301}: arrival corridor of the stage before; {@code next01} (pt 1, tm 990000400) is its way on.
 *       The statue room {@code 990000300} belongs to that earlier stage: its {@code next00} is the pt-8 door script
 *       {@code statuegate_open.js} (needs reactor {@code statuegate} at state 1, true once stage 1 is cleared), so a
 *       member still there takes {@link SolverStep.ScriptedPortal}.</li>
 *   <li>{@code 990000400} the hall: portals {@code in00} -> 410, {@code in01} -> 420, {@code in03} -> 440 (pt 1), {@code
 *       in02} (pt 8, {@code stonegate_open.js}: reactor {@code stonegate} state 1, then {@code warp(990000430, 0)})
 *       and {@code next00} (pt 8, {@code speargate_open.js}) which is the next stage's door.</li>
 *   <li>{@code 990000410}: three chained spears, reactor {@code 9202001} at (-182,-1155), (447,-1157), (1073,-1157).
 *       Five plain hits ({@code Reactor.wz/9202001.img.xml}: states 0..4, then the end), whose script {@code
 *       9202001.js} is {@code rm.dropItems()}; {@code reactordrops 9202001 -> 4001025} (seed {@code
 *       131-reactordrops-data.sql:66}, <b>live DB unverified</b>). Exit {@code out00}.</li>
 *   <li>{@code 990000420}: one {@code 9300019} (level 65, 40000 HP, mobTime -1: spawned once, never respawns,
 *       {@code MapFactory.loadLifeRaw:138-139}) with fourteen {@code 9300020} guards. It drops the stonegate key {@code
 *       4001026} (seed {@code 152-drop-data.sql:20688}, chance 999999, <b>live DB unverified</b>). Exit {@code out00}.</li>
 *   <li>{@code 990000430}: nineteen invincible golems {@code 9300021} (Mob.wz: {@code invincible 1}, 9999999 HP) -
 *       NEVER a {@link SolverStep.Fight} target here -, the buried box {@code 9202002} at (876,153) (30 hits,
 *       {@code Reactor.wz/9202002.img.xml}; drop {@code 4001037} per {@code reactordrops 9202002}, <b>live DB
 *       unverified</b>) and the item reactor {@code metalgate} ({@code 9208005}, wants {@code 4001037} x1, box lt(-73,-28)
 *       rb(76,32)) at (927,-352) next to the pt-8 portal {@code in00} ({@code metalgate_open.js}: state 1, then
 *       {@code warp(990000431, 0)}). Exit {@code out00} -> hall {@code in02}.</li>
 *   <li>{@code 990000431}: the fourth chained spear {@code 9202001} at (-183,-138). Exit {@code out00} -> 430
 *       {@code in00}.</li>
 *   <li>{@code 990000440}: the tower. Four altar reactors {@code 9208007} named {@code spear1..4} at y -1054 (x -2393,
 *       -1399, 582, 1571), each wanting {@code 4001025} x1 in its box lt(-20,-30) rb(20,30). Entered from 400 {@code
 *       in03} (arrives at {@code out00}, (-341,499)). See {@link #TOWER_CHAIN}.</li>
 * </ul>
 *
 * <h2>Who does what</h2>
 * Work is divided by claims in {@link StageView#stageMemory()} ({@code claim:<slot>} -> character id). Every call
 * first lets the caller assign <em>all</em> free bots, so the result does not depend on which bot is asked first:
 * free bots sorted by character id take the open slots in priority order - FETCH of a loose item, one HIT per
 * unspent chained spear on 410, three KEY (the three highest-level free bots: the one 9300019 is level 65), three BOX,
 * two VAULT slots. A bot holding a deliverable (spear, stonegate key, rusty key) delivers it first; spears are
 * assigned to altars the same way ({@code claim:altar:<k>}, preferred order 3, 4, 2, 1: only spear3 is under the
 * tower's landing shaft). A bot with no slot left helps the lane that is open (vault, box, key, a 410 reactor). A claim
 * of a bot that left or died is released; a claim whose task has closed is dropped. Fewer than five bots simply take
 * the slots one after another.
 *
 * <h2>The tower</h2>
 * Seven hidden same-map portals ({@code pt 1}, {@code tm 990000440}, {@code tn} a portal of the same map) lift a member
 * from the ground to the top: the landing portal of a tier is itself a portal on the next tier, and every wrong one
 * on a tier sends back to {@code st00}. {@link #towerStep} picks the next portal from the member's own y.
 *
 * <h2>UNPROVEN</h2>
 * Navigation (the bots' own pathfinding): the 410 reactors are on the upper floor (y about -1155); the golem room
 * with 19 invincible golems; the buried box at (876,153), the metalgate at (927,-352) high above the floor; the tower's
 * ground floor to each tier's portal; from the landing {@code h025} (691,-902) up to the altars (y -1054) and into the
 * side shafts of spear1/spear2/spear4 (only spear3 is under the landing shaft; the others are reached by ropes). Live
 * drop rows for 9202001, 9202002 and 4001026. The 5 s item-reactor delay under load ({@link #IN_FLIGHT_MS} is 15 s).
 * Owned alts die like players and the row cannot protect them in the golem room (the population bots only take
 * floor-1 contact damage, {@code BotContactDamage}). Whether a destroyed reactor disappears from {@link
 * StageView#reactorsOn} (the solver reads "absent or state at its end" as spent).
 */
public final class GqSpearsSolver implements StageSolver {

    public static final String ID = "gq-spears";

    static final int STATUES = 990_000_300;
    static final int ARRIVAL = 990_000_301;
    static final int HALL = 990_000_400;
    static final int ROOM_SPEARS = 990_000_410;
    static final int ROOM_KEY = 990_000_420;
    static final int ROOM_GOLEMS = 990_000_430;
    static final int ROOM_VAULT = 990_000_431;
    static final int TOWER = 990_000_440;
    /** The rooms this solver drives (the statue room and the arrival corridor are only walked out of). */
    static final Set<Integer> ROOMS = Set.of(HALL, ROOM_SPEARS, ROOM_KEY, ROOM_GOLEMS, ROOM_VAULT, TOWER);

    static final int SPEAR = 4_001_025;
    static final int STONE_KEY = 4_001_026;
    static final int RUSTY_KEY = 4_001_037;
    static final int CHAINED_SPEAR = 9_202_001;
    static final int BURIED_BOX = 9_202_002;
    static final int SPEAR_GATE = 9_208_004;
    static final int KEY_MOB = 9_300_019;
    /** Invincible: never a target. */
    static final int GOLEM = 9_300_021;

    static final int SPEARS_NEEDED = 4;
    /** A chained spear is spent at state 5 (five hits, Reactor.wz/9202001) - or when it is gone from the map. */
    static final int CHAINED_END = 5;
    /** The buried box takes 30 hits (Reactor.wz/9202002.img.xml: states 0..30). */
    static final int BOX_END = 30;

    /** How long a placed drop is "in flight": the item reactor acts 5 s after the drop (MapleMap.java:2665-2680). */
    static final long IN_FLIGHT_MS = 15_000;
    /** How long a lost one-shot source (never respawns) is waited on before the run is ended. */
    static final long LOST_GRACE_MS = 120_000;

    static final int KEY_SLOTS = 3;
    static final int BOX_SLOTS = 3;
    static final int VAULT_SLOTS = 2;

    /** Altars in the order carriers take them: spear3 is the one under the landing shaft. */
    static final int[] ALTAR_ORDER = {3, 4, 2, 1};
    static final int[] ALTAR_X = {0, -2393, -1399, 582, 1571};
    static final int ALTAR_Y = -1054;

    /** One hidden portal of the tower: its name and where it stands ({@code Map9/990000440.img.xml}). */
    record TowerPortal(String name, int x, int y) {
    }

    /**
     * The correct portal of each tier, bottom first. From the portal nodes of {@code Map9/990000440.img.xml}:
     * {@code h001} (550,363) -> h003; landing on h003 (480,175), which itself -> h006; h006 (290,-6) -> st00 is wrong,
     * {@code h010} (792,-6) -> h011; h011 (490,-183) -> st00 is wrong, {@code h012} (607,-185) -> h014; h014 (432,-363)
     * wrong, {@code h015} (555,-363) -> h017; h017 (309,-543) -> h021; h021 (429,-723) wrong, {@code h022} (547,-725) ->
     * h025 (691,-902), which has no target: the top. Entering the plain-portal chain is {@link SolverStep.EnterPortal}.
     */
    static final List<TowerPortal> TOWER_CHAIN = List.of(
            new TowerPortal("h001", 550, 363),
            new TowerPortal("h003", 480, 175),
            new TowerPortal("h010", 792, -6),
            new TowerPortal("h012", 607, -185),
            new TowerPortal("h015", 555, -363),
            new TowerPortal("h017", 309, -543),
            new TowerPortal("h022", 547, -725));
    /** A member this far below a tier's portal still counts as standing on that tier. */
    static final int TOWER_SLACK = 40;

    @Override
    public String id() {
        return ID;
    }

    /** The next tower portal for a member standing at height {@code y} of 440, or null once it is at the top. */
    static TowerPortal towerStep(int y) {
        for (TowerPortal p : TOWER_CHAIN) {
            if (p.y() <= y + TOWER_SLACK) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ what the solver reads

    private enum Kind { FETCH, HIT, KEY, BOX, VAULT }

    /** A unit of work a bot can claim: {@code key} names it in {@code stageMemory} as {@code claim:<key>}. */
    private record Slot(String key, Kind kind, int map, int item, int oid) {
        String claim() {
            return "claim:" + key;
        }
    }

    private static final class Facts {
        final StageView v;
        final long now;
        final Map<String, Object> mem;
        final List<RunMember> bots;
        final int gate;
        final int stone;
        final int metal;
        final List<ReactorPlacement> chained;
        final List<ReactorPlacement> vault;
        final ReactorPlacement box;
        final List<ReactorPlacement> tower;
        final int probe;
        int spearsHeld;
        int keysHeld;
        int rustyHeld;
        private final Map<Long, Integer> held = new HashMap<>();
        private List<Slot> slotCache;

        Facts(StageView v) {
            this.v = v;
            this.now = v.nowMs();
            this.mem = v.stageMemory();
            List<RunMember> b = new ArrayList<>();
            for (RunMember m : v.members()) {
                if (m.bot() && v.alive(m.characterId())) {
                    b.add(m);
                }
                spearsHeld += v.itemCount(m.characterId(), SPEAR);
                keysHeld += v.itemCount(m.characterId(), STONE_KEY);
                rustyHeld += v.itemCount(m.characterId(), RUSTY_KEY);
            }
            b.sort(Comparator.comparingInt(RunMember::characterId));
            this.bots = b;
            this.probe = b.isEmpty() ? 0 : b.get(0).characterId();
            this.gate = v.reactorState(SPEAR_GATE);
            this.stone = v.reactorState("stonegate");
            this.chained = byId(v.reactorsOn(ROOM_SPEARS), CHAINED_SPEAR);
            this.vault = byId(v.reactorsOn(ROOM_VAULT), CHAINED_SPEAR);
            List<ReactorPlacement> golems = v.reactorsOn(ROOM_GOLEMS);
            List<ReactorPlacement> boxes = byId(golems, BURIED_BOX);
            this.box = boxes.isEmpty() ? null : boxes.get(0);
            int m = -1;
            for (ReactorPlacement r : golems) {
                if ("metalgate".equals(r.name())) {
                    m = r.state();
                }
            }
            this.metal = m;
            this.tower = v.reactorsOn(TOWER);
        }

        private static List<ReactorPlacement> byId(List<ReactorPlacement> all, int reactorId) {
            List<ReactorPlacement> out = new ArrayList<>();
            for (ReactorPlacement r : all) {
                if (r.reactorId() == reactorId) {
                    out.add(r);
                }
            }
            out.sort(Comparator.comparingInt(ReactorPlacement::objectId));
            return out;
        }

        int held(int characterId, int itemId) {
            return held.computeIfAbsent(((long) characterId << 32) | itemId, k -> v.itemCount(characterId, itemId));
        }

        int floor(int map, int itemId, int forId) {
            return v.floorItemsOn(map, itemId, forId);
        }

        boolean botLive(int id) {
            for (RunMember m : bots) {
                if (m.characterId() == id) {
                    return true;
                }
            }
            return false;
        }

        /** The altar reactor's state, or -1 when the tower does not list it. */
        int altarState(int k) {
            for (ReactorPlacement r : tower) {
                if (("spear" + k).equals(r.name())) {
                    return r.state();
                }
            }
            return -1;
        }

        static String mark(int map, int item) {
            return "placing:" + map + ":" + item;
        }

        /** A drop this solver placed on that map lately: the item reactor has not acted yet. */
        boolean inFlight(int map, int item) {
            return mem.get(mark(map, item)) instanceof Long t && now - t < IN_FLIGHT_MS;
        }

        void markPlacing(int map, int item) {
            mem.put(mark(map, item), now);
        }

        boolean keyInWorld() {
            return keysHeld > 0 || floor(HALL, STONE_KEY, probe) > 0 || floor(ROOM_KEY, STONE_KEY, probe) > 0
                    || inFlight(HALL, STONE_KEY);
        }

        boolean rustyInWorld() {
            return rustyHeld > 0 || floor(ROOM_GOLEMS, RUSTY_KEY, probe) > 0 || inFlight(ROOM_GOLEMS, RUSTY_KEY);
        }

        boolean keyNeeded() {
            return stone < 1 && !keyInWorld();
        }

        /** The one 9300019 was seen standing and is dead now: it never respawns (mobTime -1). */
        boolean keyMobGone() {
            return mem.containsKey("seen:keymob") && v.mobsAliveOn(ROOM_KEY, KEY_MOB) == 0;
        }

        boolean boxOpen() {
            return stone >= 1 && metal < 1 && box != null && box.state() < BOX_END;
        }

        boolean vaultOpen() {
            return metal >= 1 && firstLive(vault) != null;
        }

        static ReactorPlacement firstLive(List<ReactorPlacement> rs) {
            for (ReactorPlacement r : rs) {
                if (r.state() < CHAINED_END) {
                    return r;
                }
            }
            return null;
        }

        /** What a bot would be told to carry to a reactor now: spears always, the keys while their gate is shut. */
        boolean delivering(int id) {
            return held(id, SPEAR) > 0 || (held(id, STONE_KEY) > 0 && stone < 1)
                    || (held(id, RUSTY_KEY) > 0 && metal < 1);
        }

        List<Slot> slots() {
            if (slotCache != null) {
                return slotCache;
            }
            List<Slot> out = new ArrayList<>();
            int[][] spots = {
                    {HALL, SPEAR}, {ROOM_SPEARS, SPEAR}, {ROOM_GOLEMS, SPEAR}, {ROOM_VAULT, SPEAR}, {TOWER, SPEAR},
                    {ROOM_KEY, STONE_KEY}, {HALL, STONE_KEY}, {ROOM_GOLEMS, RUSTY_KEY}};
            for (int[] s : spots) {
                if (floor(s[0], s[1], probe) > 0 && !inFlight(s[0], s[1])) {
                    out.add(new Slot("fetch:" + s[0] + ":" + s[1], Kind.FETCH, s[0], s[1], 0));
                }
            }
            for (ReactorPlacement r : chained) {
                if (r.state() < CHAINED_END) {
                    out.add(new Slot("hit:" + r.objectId(), Kind.HIT, ROOM_SPEARS, SPEAR, r.objectId()));
                }
            }
            if (keyNeeded() && !keyMobGone()) {
                for (int i = 1; i <= KEY_SLOTS; i++) {
                    out.add(new Slot("key:" + i, Kind.KEY, ROOM_KEY, STONE_KEY, 0));
                }
            }
            if (boxOpen()) {
                for (int i = 1; i <= BOX_SLOTS; i++) {
                    out.add(new Slot("box:" + i, Kind.BOX, ROOM_GOLEMS, RUSTY_KEY, box.objectId()));
                }
            }
            if (vaultOpen()) {
                ReactorPlacement r = firstLive(vault);
                for (int i = 1; i <= VAULT_SLOTS; i++) {
                    out.add(new Slot("vault:" + i, Kind.VAULT, ROOM_VAULT, SPEAR, r.objectId()));
                }
            }
            slotCache = out;
            return out;
        }
    }

    // ------------------------------------------------------------------ the decision

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        SolverStep guard = GqProtection.step(view, member);
        if (guard != null) {
            return guard;
        }
        int id = member.characterId();
        int at = view.mapOf(id);
        if (at == STATUES) {
            // scripts/portal/statuegate_open.js: reactor statuegate at state 1, then warp(990000301, 0)
            return new SolverStep.ScriptedPortal("next00");
        }
        if (at == ARRIVAL) {
            // Map9/990000301.img.xml portal next01: pt 1, tm 990000400, tn st00
            return new SolverStep.EnterPortal("next01");
        }
        if (!ROOMS.contains(at)) {
            return new SolverStep.Wait(1_000, "member " + id + " is on map " + at
                    + ", which is no room of the spear hall (990000301, 400, 410, 420, 430, 431, 440)");
        }
        Facts f = new Facts(view);
        updateSeen(f);
        String lost = lostLine(f);
        if (lost == null) {
            f.mem.remove("lostSince");
        } else {
            Object since = f.mem.putIfAbsent("lostSince", f.now);
            long first = since instanceof Long l ? l : f.now;
            if (f.now - first >= LOST_GRACE_MS) {
                return new SolverStep.Abandon(lost);
            }
        }

        // a drop that fell in this room and is not one of ours in flight: pick it up
        for (int item : new int[]{RUSTY_KEY, STONE_KEY, SPEAR}) {
            if (f.floor(at, item, id) > 0 && !f.inFlight(at, item)) {
                return new SolverStep.Loot(item);
            }
        }

        // deliveries: the thing in the bag goes to its reactor before anything else
        if (f.held(id, RUSTY_KEY) > 0 && f.metal < 1) {
            return deliverRusty(f, at);
        }
        if (f.held(id, STONE_KEY) > 0 && f.stone < 1) {
            return deliverKey(f, at);
        }
        if (f.held(id, SPEAR) > 0) {
            return deliverSpear(f, id, at);
        }

        // work: claim, then do
        assign(f);
        Slot mine = ownedSlot(f, id);
        if (mine == null) {
            mine = helperSlot(f, id);
        }
        if (mine != null) {
            SolverStep s = doSlot(f, at, mine);
            if (s != null) {
                return s;
            }
        }
        return new SolverStep.Wait(2_000, lost != null ? lost : idleLine(f));
    }

    /** The way from one room to another; null when already there. The rooms hang off the hall, 431 off 430. */
    static SolverStep toward(int at, int target) {
        if (at == target) {
            return null;
        }
        if (at == STATUES) {
            return new SolverStep.ScriptedPortal("next00");
        }
        if (at == ARRIVAL) {
            return new SolverStep.EnterPortal("next01");
        }
        if (at == HALL) {
            if (target == ROOM_SPEARS) {
                return new SolverStep.EnterPortal("in00");
            }
            if (target == ROOM_KEY) {
                return new SolverStep.EnterPortal("in01");
            }
            if (target == TOWER) {
                return new SolverStep.EnterPortal("in03");
            }
            // 430 and 431: the door in02 runs stonegate_open.js (reactor stonegate at state 1) and warps to 430
            return new SolverStep.ScriptedPortal("in02");
        }
        if (at == ROOM_GOLEMS && target == ROOM_VAULT) {
            // 430 portal in00, metalgate_open.js: reactor metalgate at state 1, then warp(990000431, 0)
            return new SolverStep.ScriptedPortal("in00");
        }
        // 410, 420, 430, 431, 440 each have out00 (431's leads to 430, the others to the hall)
        return new SolverStep.EnterPortal("out00");
    }

    private static SolverStep deliverRusty(Facts f, int at) {
        if (at != ROOM_GOLEMS) {
            return toward(at, ROOM_GOLEMS);
        }
        f.markPlacing(ROOM_GOLEMS, RUSTY_KEY);
        return new SolverStep.PlaceItem(RUSTY_KEY, 1, "metalgate", 927, -352,
                "the Rusty Key opens the metalgate (reactor 9208005 wants 4001037 x1)");
    }

    private static SolverStep deliverKey(Facts f, int at) {
        if (at != HALL) {
            return toward(at, HALL);
        }
        f.markPlacing(HALL, STONE_KEY);
        return new SolverStep.PlaceItem(STONE_KEY, 1, "stonegate", -767, -532,
                "the stonegate key (reactor 9208006 wants 4001026 x1)");
    }

    private static SolverStep deliverSpear(Facts f, int id, int at) {
        if (f.tower.isEmpty()) {
            return new SolverStep.Wait(2_000, "carrying 4001025 but the altar reactors spear1..spear4 (9208007) of "
                    + "990000440 are not readable");
        }
        assignAltars(f);
        int k = ownedAltar(f, id);
        if (k == 0) {
            return new SolverStep.Wait(2_000, "carrying 4001025 but no altar is free: every spear altar of "
                    + "990000440 is at state 1 or has a spear on its way");
        }
        if (at != TOWER) {
            return toward(at, TOWER);
        }
        TowerPortal step = towerStep(f.v.positionOf(id)[1]);
        if (step != null) {
            return new SolverStep.EnterPortal(step.name());
        }
        f.markPlacing(TOWER, SPEAR);
        return new SolverStep.PlaceItem(SPEAR, 1, "spear" + k, ALTAR_X[k], ALTAR_Y,
                "the Longinus Spear on altar spear" + k + " (reactor 9208007 wants 4001025 x1)");
    }

    // ------------------------------------------------------------------ altars

    private static String altarClaim(int k) {
        return "claim:altar:" + k;
    }

    /** An altar claim is open while the altar is empty and its owner still has the spear or one is in flight. */
    private static boolean altarClaimOpen(Facts f, int k, Object owner) {
        return owner instanceof Integer o && f.botLive(o) && f.altarState(k) == 0
                && (f.held(o, SPEAR) > 0 || f.inFlight(TOWER, SPEAR));
    }

    private static int ownedAltar(Facts f, int id) {
        for (int k = 1; k <= 4; k++) {
            if (Integer.valueOf(id).equals(f.mem.get(altarClaim(k))) && altarClaimOpen(f, k, id)) {
                return k;
            }
        }
        return 0;
    }

    /** Every carrier without an altar takes the next free one in {@link #ALTAR_ORDER}, lowest character id first. */
    private static void assignAltars(Facts f) {
        for (int k = 1; k <= 4; k++) {
            Object o = f.mem.get(altarClaim(k));
            if (o != null && !altarClaimOpen(f, k, o)) {
                f.mem.remove(altarClaim(k), o);
            }
        }
        for (RunMember b : f.bots) {
            int id = b.characterId();
            if (f.held(id, SPEAR) <= 0 || ownedAltar(f, id) != 0) {
                continue;
            }
            for (int k : ALTAR_ORDER) {
                if (f.altarState(k) == 0 && f.mem.get(altarClaim(k)) == null
                        && f.mem.putIfAbsent(altarClaim(k), id) == null) {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ claims

    private static Slot ownedSlot(Facts f, int id) {
        for (Slot s : f.slots()) {
            if (Integer.valueOf(id).equals(f.mem.get(s.claim()))) {
                return s;
            }
        }
        return null;
    }

    /**
     * Give every free bot an open slot, in one pass whoever asks first: stale claims (owner gone) and closed ones are
     * dropped, then the slots in priority order take the lowest-id free bot (a KEY slot the highest-level one).
     */
    private static void assign(Facts f) {
        List<Slot> slots = f.slots();
        Set<String> open = new java.util.HashSet<>();
        for (Slot s : slots) {
            open.add(s.claim());
        }
        for (String k : new ArrayList<>(f.mem.keySet())) {
            if (!k.startsWith("claim:") || k.startsWith("claim:altar:")) {
                continue;
            }
            Object o = f.mem.get(k);
            if (o == null) {
                continue;
            }
            if (!(o instanceof Integer owner) || !f.botLive(owner) || !open.contains(k)) {
                f.mem.remove(k, o);
            }
        }
        List<RunMember> free = new ArrayList<>();
        for (RunMember b : f.bots) {
            if (!f.delivering(b.characterId()) && ownedSlot(f, b.characterId()) == null) {
                free.add(b);
            }
        }
        for (Slot s : slots) {
            if (free.isEmpty()) {
                return;
            }
            if (f.mem.get(s.claim()) != null) {
                continue;
            }
            RunMember pick = free.get(0);
            if (s.kind() == Kind.KEY) {
                for (RunMember b : free) {
                    if (b.level() > pick.level()) {
                        pick = b;
                    }
                }
            }
            if (f.mem.putIfAbsent(s.claim(), pick.characterId()) == null) {
                free.remove(pick);
            }
        }
    }

    /** A bot with nothing of its own helps the lane that is open now: vault, box, key hunt, then a 410 reactor. */
    private static Slot helperSlot(Facts f, int id) {
        for (Kind kind : new Kind[]{Kind.VAULT, Kind.BOX, Kind.KEY}) {
            for (Slot s : f.slots()) {
                if (s.kind() == kind) {
                    return s;
                }
            }
        }
        List<Slot> hits = new ArrayList<>();
        for (Slot s : f.slots()) {
            if (s.kind() == Kind.HIT) {
                hits.add(s);
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        int rank = 0;
        for (int i = 0; i < f.bots.size(); i++) {
            if (f.bots.get(i).characterId() == id) {
                rank = i;
            }
        }
        return hits.get(rank % hits.size());
    }

    private static SolverStep doSlot(Facts f, int at, Slot s) {
        switch (s.kind()) {
            case FETCH: {
                SolverStep walk = toward(at, s.map());
                return walk != null ? walk : new SolverStep.Loot(s.item());
            }
            case HIT: {
                for (ReactorPlacement r : f.chained) {
                    if (r.objectId() == s.oid() && r.state() < CHAINED_END) {
                        SolverStep walk = toward(at, ROOM_SPEARS);
                        return walk != null ? walk : new SolverStep.HitReactorByOid(r.objectId());
                    }
                }
                return null;
            }
            case KEY: {
                SolverStep walk = toward(at, ROOM_KEY);
                // only the one 9300019: the fourteen 9300020 guards are not needed, and 430's golems never
                return walk != null ? walk : new SolverStep.Fight(List.of(KEY_MOB), STONE_KEY, 1);
            }
            case BOX: {
                if (f.box == null || f.box.state() >= BOX_END) {
                    return null;
                }
                SolverStep walk = toward(at, ROOM_GOLEMS);
                return walk != null ? walk : new SolverStep.HitReactorByOid(f.box.objectId());
            }
            case VAULT: {
                ReactorPlacement r = Facts.firstLive(f.vault);
                if (r == null) {
                    return null;
                }
                SolverStep walk = toward(at, ROOM_VAULT);
                return walk != null ? walk : new SolverStep.HitReactorByOid(r.objectId());
            }
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ what is missing

    /** Remember which one-shot sources have been seen standing, so an unreadable room is never taken for a spent one. */
    private static void updateSeen(Facts f) {
        if (!f.chained.isEmpty()) {
            f.mem.putIfAbsent("seen:410", Boolean.TRUE);
        }
        if (f.box != null) {
            f.mem.putIfAbsent("seen:box", Boolean.TRUE);
        }
        if (f.v.mobsAliveOn(ROOM_KEY, KEY_MOB) > 0) {
            f.mem.putIfAbsent("seen:keymob", Boolean.TRUE);
        }
    }

    /**
     * Why the stage can no longer be finished, or null. Each source is single-shot: the chained spears and the buried
     * box vanish after their last hit, and the one {@code 9300019} never respawns (mobTime -1). The line names the item
     * and the reactor or mob; the drop rows are seed data, <b>live DB unverified</b>.
     */
    private static String lostLine(Facts f) {
        if (f.keyMobGone() && f.stone < 1 && !f.keyInWorld()) {
            return "the stonegate key 4001026 did not drop: the only 9300019 on 990000420 is dead (it never respawns, "
                    + "mobTime -1) and no bag or floor holds the key (seed drop row 9300019 -> 4001026, live DB unverified)";
        }
        if (f.mem.containsKey("seen:box") && f.stone >= 1 && f.metal == 0 && f.box == null && !f.rustyInWorld()) {
            return "the Rusty Key 4001037 did not drop: reactor 9202002 on 990000430 is spent (30 hits) and no bag or "
                    + "floor holds the key (seed reactordrops 9202002 -> 4001037, live DB unverified)";
        }
        if (f.mem.containsKey("seen:410")) {
            int need = SPEARS_NEEDED - Math.max(f.gate, 0);
            int sources = 0;
            for (ReactorPlacement r : f.chained) {
                if (r.state() < CHAINED_END) {
                    sources++;
                }
            }
            sources += f.metal >= 1 ? (Facts.firstLive(f.vault) == null ? 0 : 1) : 1; // 431's spear is out of reach until metalgate opens
            int onFloor = 0;
            for (int map : new int[]{HALL, ROOM_SPEARS, ROOM_KEY, ROOM_GOLEMS, ROOM_VAULT, TOWER}) {
                onFloor += f.floor(map, SPEAR, f.probe);
            }
            if (need > f.spearsHeld + onFloor + sources) {
                return "Longinus Spear 4001025 is missing: " + need + " still needed, but only " + f.spearsHeld
                        + " are carried, " + onFloor + " lie on a floor and " + sources + " chained-spear reactors "
                        + "9202001 (990000410, 990000431) can still drop one (seed reactordrops 9202001 -> 4001025, "
                        + "live DB unverified)";
            }
        }
        return null;
    }

    /** What the party is waiting for when a bot has nothing to do. */
    private static String idleLine(Facts f) {
        StringBuilder sb = new StringBuilder("nothing to take: speargate 9208004 at state ").append(f.gate)
                .append(" of 4 (altars");
        for (int k = 1; k <= 4; k++) {
            sb.append(" spear").append(k).append('=').append(f.altarState(k));
        }
        sb.append("), 4001025 carried ").append(f.spearsHeld);
        if (f.stone < 1) {
            sb.append(", waiting for the stonegate (9208006) to take the key 4001026 from 9300019 on 990000420");
        } else if (f.metal < 1) {
            sb.append(", waiting for 4001037 from reactor 9202002 on 990000430 for the metalgate (9208005)");
        } else if (f.gate < SPEARS_NEEDED) {
            sb.append(", waiting for spears 4001025 from reactor 9202001 or for a carrier to reach the altars of "
                    + "990000440");
        }
        return sb.toString();
    }
}
