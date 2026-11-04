package soloMapling.mechanics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * {@code gq-maze} - Guild Quest stage 4 (row stage 6): the four-room maze and Sharen III's remains.
 *
 * <p><b>What clears it.</b> The event leader talks to Sharen III's Soul (NPC 9040003) on {@code 990000700}:
 * {@code scripts/npc/9040003.js:33} writes {@code stage4clear = "true"}, and only for {@code cm.isEventLeader()}
 * ({@code :65}; anybody else is told to fetch the leader, {@code :73}). The Soul is spawned by reactor 9201001 (the
 * body, (1438,180) on 700, {@code Map9/990000700.img.xml}) when it leaves its last state
 * ({@code scripts/reactor/9201001.js:30-31}). The body wants four items, ONE each, dropped in its box
 * lt(-45,-20) rb(35,20), in this order ({@code Reactor.wz/9201001.img.xml}, event of state 0..3): shoes 4001032,
 * pants 4001031, top 4001033, crown 4001034. The solver reads the body's state ({@code reactorsOn(700)}) and lets
 * the holder of an item place it only when the body stands at that item's state.
 *
 * <p><b>Where the clothes are.</b> Each is one reactor in its own room, {@code rm.dropItems()}
 * ({@code scripts/reactor/9202005.js..9202008.js}): 611 pants 9202005, 620 shoes 9202006, 631 top 9202007, 641 crown
 * 9202008 (the last three are WZ links to 9202005's states: {@code info/link}, honoured by {@code ReactorFactory:75-77}).
 * Five plain hits take it from state 0 to 5; the fifth drops the item and EVERY further hit at state 5 drops another
 * ({@code Reactor.hitReactor}: a state that maps to itself calls act again), so the solver stops hitting at 5 and only
 * re-hits when nothing came out for {@link #RE_HIT_AFTER_MS}. The drop rows ({@code reactordrops 9202005..9202008 ->
 * 4001031..4001034}, {@code 131-reactordrops-data.sql:70-73}) are SEED data: <b>live DB unverified</b>.
 *
 * <p><b>The doors</b> ({@code Map9/990000600.img.xml} portals): 600 {@code in00}->610, {@code in01}->620 (open),
 * {@code in02}->630 (all plain pt 1), {@code in03} (pt 8, script {@code under30gate}: level &lt;= 30,
 * {@code under30gate.js:25}) ->640. 610/630/640 each hold a gate reactor ({@code secretgate1/2/3} = 9208010/9208011/
 * 9208012, {@code Reactor.wz}: type 100, state 1) that takes item 4001035 (Mark of Evil) in ONE stack of exactly
 * 9/8/20 dropped in its box lt(-73,-28) rb(76,32) ({@code MapleMap.java:2672}: the quantity must match whole); the
 * room's {@code in00} (pt 8, scripts {@code secretgate1_open..3}) then leads to the clothing room 611/631/641. 620 is
 * a clothing room with no gate. Every clothing room's {@code guild1F00} (pt 8, scripts {@code guild1F01..04}, shoes
 * room {@code guild1F02}) warps to 700 {@code st00} - the ONLY way to 700 - and 700's {@code out00} (pt 8,
 * {@code guild1F00.js}) returns the member to 600 through {@code backPortals[gridCheck]}.
 *
 * <p><b>Marks</b> ({@code 152-drop-data.sql:20691-20694}, SEED, <b>live DB unverified</b>): 610 holds 9 Puppet Golems
 * 9300024 (lvl 67, 35000 HP) that drop 4001035; 640 holds 20 Devil Slimes 9300027 (lvl 23, 1000 HP) that drop
 * 4001035; 630 holds one Gargoyle 9300025 (lvl 68, 35000 HP) that drops 4001036, which dropped in the box of the
 * invisible reactor 9201000 (at (-82,-208), lt(-80,-30) rb(80,30)) runs {@code scripts/reactor/9201000.js:31}
 * ({@code spawnMonster(9300033, 8, ...)}): eight Jr. Gargoyles (lvl 62, 15000 HP), each dropping a mark: the 8 marks.
 *
 * <p><b>The junior.</b> Only a member of level &lt;= 30 passes {@code under30gate}, so ONE junior - the bot of level
 * 1..30 with the lowest character id ({@link RunMember#level()}; 0 = not carried, which fits nothing) - does 640, the
 * 20 slimes, 641 and the crown, and is given no other room as long as another bot exists. No such bot: {@code Abandon}
 * while the crown is still needed (the row's composition seat should have prevented it).
 *
 * <p><b>Division of labour.</b> Tasks 620 (shoes), 610 (pants), 630 (top) go to the other bots, round robin by fewest
 * claims, the leader first (so the leader takes the shoe room and ends up on 700 where he must talk). Claims live in
 * {@code view.stageMemory()} ({@code claim:620|610|630|640}) and are re-given when the claimant leaves or dies. The
 * claimant of a mark room is its CARRIER: he alone holds the whole stack and drops it. Bots without a task are helpers:
 * they fight in 610 / 630 (alternately, then wherever a gate is still shut), hand marks to the carrier with
 * {@link SolverStep.DropFor} (pattern of {@link KillCollectHandInSolver}), never enter 640. A member who holds a
 * clothing item whose turn has not come waits on 700; a member standing on 700 with nothing to do stays there.
 *
 * <p><b>Cross-stage walk.</b> The previous stage's rooms (990000500 vassals, 501/502) are walked out of here:
 * {@code out00} (plain) from 501/502 to 500, then {@code next00} (pt 8, {@code watergate_open.js}, needs the reactor
 * {@code watergate} at state 1) to 600 portal 1.
 *
 * <p><b>UNPROVEN</b>: navigation inside 620 (a tall platform climb: the shoe reactor at y=-1037, the exit at y=-465),
 * 631 (a one-way portal maze on conveyor footholds), 611 and 641 (stepped platform courses); every live drop row named
 * above; whether bots of level 10-30 can kill 20 Devil Slimes alone in time; whether a drop of a mark lands in the box
 * (the carrier places the whole stack at the reactor's own position, so it does); the {@code guild1F00.js}
 * {@code gridCheck} return portal for a member that entered through a different clothing room than it comes back
 * from (the grid keeps the LAST room entered, so the bot lands at the door of that room on 600).
 */
public final class GqMazeSolver implements StageSolver {

    public static final String ID = "gq-maze";

    static final int M500 = 990000500;
    static final int M501 = 990000501;
    static final int M502 = 990000502;
    static final int M600 = 990000600;
    static final int M610 = 990000610;
    static final int M611 = 990000611;
    static final int M620 = 990000620;
    static final int M630 = 990000630;
    static final int M631 = 990000631;
    static final int M640 = 990000640;
    static final int M641 = 990000641;
    static final int M700 = 990000700;

    static final int PANTS = 4001031;
    static final int SHOES = 4001032;
    static final int TOP = 4001033;
    static final int CROWN = 4001034;
    /** Mark of Evil: what the three gate reactors eat. */
    static final int MARK = 4001035;
    /** What the Gargoyle drops; the box of reactor 9201000 eats one. */
    static final int TOKEN = 4001036;

    static final int BODY = 9201001;
    static final int TOKEN_REACTOR = 9201000;
    static final int SOUL = 9040003;
    static final int GOLEM = 9300024;
    static final int GARGOYLE = 9300025;
    static final int SLIME = 9300027;
    static final int JR_GARGOYLE = 9300033;
    static final int CLOTHING_DONE_STATE = 5;
    static final int BODY_DONE_STATE = 4;
    static final int JUNIOR_MAX_LEVEL = 30;

    /** A stack put on a reactor is acted on 5 s later ({@code MapleMap.java:2675}); wait this long before looking for it. */
    static final long FUSE_MS = 15_000;
    /** A clothing box at state 5 that gave nothing is hit once more after this long. */
    static final long RE_HIT_AFTER_MS = 15_000;
    /** A helper carries this many marks before walking them to the carrier. */
    static final int GIVE_AT = 3;

    /**
     * One of the four clothing tasks, in body order ({@code slot} is the body state at which its item goes in).
     * {@code gateRoom} 0 = no gate (the shoe room is open).
     */
    record Task(int slot, int gateRoom, int clothRoom, int item, int clothReactor, int gateReactor, String gateName,
                int gateX, int gateY, int need, int mob, String itemName, String claimKey) {
    }

    static final Task T_SHOES = new Task(0, 0, M620, SHOES, 9202006, 0, "", 0, 0, 0, 0, "shoes", "claim:620");
    static final Task T_PANTS = new Task(1, M610, M611, PANTS, 9202005, 9208010, "secretgate1", 811, 115, 9, GOLEM,
            "pants", "claim:610");
    static final Task T_TOP = new Task(2, M630, M631, TOP, 9202007, 9208011, "secretgate2", 412, 86, 8, JR_GARGOYLE,
            "top", "claim:630");
    static final Task T_CROWN = new Task(3, M640, M641, CROWN, 9202008, 9208012, "secretgate3", 585, -323, 20, SLIME,
            "crown", "claim:640");
    static final Task[] TASKS = {T_SHOES, T_PANTS, T_TOP, T_CROWN};

    /** Every room this solver drives (the orchestrator must not treat a member in one as a stray). */
    static final Set<Integer> ROOMS = Set.of(M500, M501, M502, M600, M610, M611, M620, M630, M631, M640, M641, M700);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Integer> extraMaps(StageView view) {
        return ROOMS;
    }

    // ---------------------------------------------------------------------------------------------------------
    // the plan: who is the junior, who owns which task (sticky claims in the stage memory)
    // ---------------------------------------------------------------------------------------------------------

    /** {@code owners[slot]} is the character id that owns that clothing task (0 = nobody). */
    record Plan(int junior, int[] owners, List<Integer> pool) {
    }

    static boolean eligible(RunMember m) {
        return m.bot() && m.level() >= 1 && m.level() <= JUNIOR_MAX_LEVEL;
    }

    private static int claim(Map<String, Object> mem, String key, IntPredicate valid, java.util.function.IntSupplier pick) {
        Object cur = mem.get(key);
        if (cur instanceof Integer i && valid.test(i)) {
            return i;
        }
        int next = pick.getAsInt();
        if (next == 0) {
            mem.remove(key);
            return 0;
        }
        if (cur == null) {
            mem.putIfAbsent(key, next);
        } else {
            mem.replace(key, cur, next);
        }
        Object won = mem.get(key);
        return won instanceof Integer w ? w : 0;
    }

    static Plan plan(StageView view) {
        Map<String, Object> mem = view.stageMemory();
        List<RunMember> bots = new ArrayList<>();
        for (RunMember m : view.members()) {
            if (m.bot() && view.alive(m.characterId())) {
                bots.add(m);
            }
        }
        bots.sort(Comparator.comparingInt(RunMember::characterId));
        IntPredicate isEligible = id -> bots.stream().anyMatch(b -> b.characterId() == id && eligible(b));
        int junior = claim(mem, T_CROWN.claimKey(), isEligible, () -> bots.stream().filter(GqMazeSolver::eligible)
                .mapToInt(RunMember::characterId).findFirst().orElse(0));

        List<Integer> pool = new ArrayList<>();
        // leader first: he ends up on 700 with the shoes, where the Soul talks to nobody else
        for (RunMember b : bots) {
            if (b.characterId() != junior && b.leader()) {
                pool.add(b.characterId());
            }
        }
        for (RunMember b : bots) {
            if (b.characterId() != junior && !b.leader()) {
                pool.add(b.characterId());
            }
        }
        if (pool.isEmpty() && junior != 0) {
            pool.add(junior); // a party of one bot: the junior walks every room, 640 last
        }
        int[] owners = new int[TASKS.length];
        owners[T_CROWN.slot()] = junior;
        Map<Integer, Integer> load = new HashMap<>();
        for (Task t : new Task[]{T_SHOES, T_PANTS, T_TOP}) {
            IntPredicate inPool = pool::contains;
            int owner = claim(mem, t.claimKey(), inPool, () -> pool.stream()
                    .min(Comparator.<Integer>comparingInt(p -> load.getOrDefault(p, 0))
                            .thenComparingInt(pool::indexOf)).orElse(0));
            owners[t.slot()] = owner;
            if (owner != 0) {
                load.merge(owner, 1, Integer::sum);
            }
        }
        return new Plan(junior, owners, pool);
    }

    // ---------------------------------------------------------------------------------------------------------
    // reading the world
    // ---------------------------------------------------------------------------------------------------------

    static StageView.ReactorPlacement reactor(StageView view, int map, int reactorId) {
        for (StageView.ReactorPlacement r : view.reactorsOn(map)) {
            if (r.reactorId() == reactorId) {
                return r;
            }
        }
        return null;
    }

    /** The body's state on 700, or -1 when it is not seen. */
    static int bodyState(StageView view) {
        StageView.ReactorPlacement r = reactor(view, M700, BODY);
        return r == null ? -1 : r.state();
    }

    static int gateState(StageView view, Task t) {
        if (t.gateRoom() == 0) {
            return 1;
        }
        StageView.ReactorPlacement r = reactor(view, t.gateRoom(), t.gateReactor());
        return r == null ? -1 : r.state();
    }

    static boolean pending(Task t, int body) {
        return body < 0 || body <= t.slot();
    }

    /** The Gargoyle's token has been spent: reactor 9201000 left state 0, or the Jr. Gargoyles stand. */
    static boolean tokenSpent(StageView view) {
        StageView.ReactorPlacement r = reactor(view, M630, TOKEN_REACTOR);
        return (r != null && r.state() >= 1) || view.mobsAliveOn(M630, JR_GARGOYLE) > 0;
    }

    // ---------------------------------------------------------------------------------------------------------
    // the step
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public SolverStep next(StageView view, RunMember member) {
        SolverStep protection = GqProtection.step(view, member);
        if (protection != null) {
            return protection;
        }
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int here = view.mapOf(id);

        // the previous stage's rooms: out of the vassal rooms and through watergate (script portal, needs the reactor)
        if (here == M501 || here == M502) {
            return new SolverStep.EnterPortal("out00");
        }
        if (here == M500) {
            return new SolverStep.ScriptedPortal("next00");
        }

        int body = bodyState(view);
        Plan plan = plan(view);
        if (plan.junior() == 0 && pending(T_CROWN, body) && !someoneHolds(view, CROWN)) {
            return new SolverStep.Abandon("no bot of level 1-" + JUNIOR_MAX_LEVEL + " in the party: only a member of "
                    + "level <= 30 passes under30gate (scripts/portal/under30gate.js:25) into 990000640, and the crown "
                    + CROWN + " only comes out of 990000641 behind it, so reactor " + BODY + " can never be completed");
        }

        if (body >= BODY_DONE_STATE) {
            return finale(view, member, here);
        }

        List<Task> held = new ArrayList<>();
        List<Task> mine = new ArrayList<>();
        for (Task t : TASKS) {
            if (!pending(t, body)) {
                continue;
            }
            if (view.itemCount(id, t.item()) > 0) {
                held.add(t);
            } else if (plan.owners()[t.slot()] == id) {
                mine.add(t);
            }
        }
        // an item whose turn has come: place it
        for (Task t : held) {
            if (body == t.slot()) {
                return deliver(view, member, t, here, body);
            }
        }
        // else the next thing this member owns (carrying what it holds)
        if (!mine.isEmpty()) {
            return obtain(view, member, plan, mine.get(0), here);
        }
        // holding an item for a later turn: wait for it beside the body
        if (!held.isEmpty()) {
            return waitOnBody(view, member, held.get(0), here, body);
        }
        if (here == M700) {
            return new SolverStep.Wait(1_500, "on 990000700 with nothing left to do: the others place the clothes on the "
                    + "body (reactor " + BODY + " at state " + body + "), staying here");
        }
        return help(view, member, plan, here);
    }

    private static boolean someoneHolds(StageView view, int item) {
        for (RunMember m : view.members()) {
            if (view.itemCount(m.characterId(), item) > 0) {
                return true;
            }
        }
        return false;
    }

    // ---- the end: the leader talks to Sharen III's Soul ----

    private static SolverStep finale(StageView view, RunMember member, int here) {
        if (!member.leader()) {
            return new SolverStep.Wait(1_500, "the body is dressed (reactor " + BODY + " at state "
                    + BODY_DONE_STATE + "): the leader alone talks to Sharen III's Soul (9040003.js:65)");
        }
        if (here != M700) {
            return move(view, member, M700);
        }
        if (view.npcPositionOn(M700, SOUL).isEmpty()) {
            return new SolverStep.Wait(1_500, "the body is dressed but NPC " + SOUL + " (Sharen III's Soul, spawned by "
                    + "9201001.js:30-31) is not on 990000700 yet");
        }
        return new SolverStep.TalkNpc(SOUL, 0, 0);
    }

    // ---- a clothing item in the bag ----

    private static SolverStep deliver(StageView view, RunMember member, Task t, int here, int body) {
        int id = member.characterId();
        if (here != M700) {
            return move(view, member, M700);
        }
        String key = "body:" + t.slot() + ":" + id;
        int count = view.itemCount(id, t.item());
        if (fuseBurning(view, key, count, 1)) {
            return new SolverStep.Wait(1_000, "the " + t.itemName() + " (" + t.item() + ") lies in the body's box; "
                    + "the reactor takes it 5 s after it landed");
        }
        arm(view, key);
        return new SolverStep.PlaceItem(t.item(), 1, String.valueOf(BODY), 1438, 180,
                "the " + t.itemName() + ": the body is at state " + body + " and wants it next (9201001.img event "
                        + t.slot() + ")");
    }

    private static SolverStep waitOnBody(StageView view, RunMember member, Task t, int here, int body) {
        if (here != M700) {
            return move(view, member, M700);
        }
        if (body < 0) {
            return new SolverStep.Wait(1_500, "reactor " + BODY + " (the body) is not seen on 990000700");
        }
        return new SolverStep.Wait(1_500, "holding the " + t.itemName() + " (" + t.item() + ") for body state "
                + t.slot() + ", the body is at state " + body + ": the earlier clothes go in first");
    }

    // ---- fetching an item ----

    private static SolverStep obtain(StageView view, RunMember member, Plan plan, Task t, int here) {
        int id = member.characterId();
        if (here == M700) {
            // the item may lie where it was put and was not taken: pick it up again after the fuse
            String key = "body:" + t.slot() + ":" + id;
            if (fuseBurning(view, key, 0, 1)) {
                return new SolverStep.Wait(1_000, "the " + t.itemName() + " lies in the body's box; the reactor "
                        + "takes it 5 s after it landed");
            }
            if (view.floorItemsOn(M700, t.item(), id) > 0) {
                return new SolverStep.Loot(t.item());
            }
        }
        int gate = gateState(view, t);
        if (t.gateRoom() != 0 && gate < 1) {
            if (here != t.gateRoom()) {
                return move(view, member, t.gateRoom());
            }
            return markRoom(view, member, plan, t);
        }
        if (here != t.clothRoom()) {
            return move(view, member, t.clothRoom());
        }
        return clothingBox(view, member, t, here);
    }

    private static SolverStep clothingBox(StageView view, RunMember member, Task t, int here) {
        int id = member.characterId();
        StageView.ReactorPlacement box = reactor(view, here, t.clothReactor());
        if (box == null) {
            return new SolverStep.Wait(1_500, "clothing box reactor " + t.clothReactor() + " is not seen on map " + here);
        }
        if (box.state() < CLOTHING_DONE_STATE) {
            return new SolverStep.HitReactor(t.clothReactor());
        }
        if (view.floorItemsOn(here, t.item(), id) > 0) {
            return new SolverStep.Loot(t.item());
        }
        Map<String, Object> mem = view.stageMemory();
        String key = "seen5:" + here;
        Object seen = mem.putIfAbsent(key, view.nowMs());
        long since = seen instanceof Long s ? s : view.nowMs();
        if (view.nowMs() - since >= RE_HIT_AFTER_MS) {
            mem.put(key, view.nowMs());
            return new SolverStep.HitReactor(t.clothReactor());
        }
        return new SolverStep.Wait(1_500, "clothing box " + t.clothReactor() + " is open but dropped no " + t.item()
                + " (" + t.itemName() + "; reactordrops 9202005..9202008 -> 4001031..4001034 are seed data, live DB "
                + "unverified)");
    }

    // ---- the mark rooms: 610 (golems), 630 (gargoyle then Jr. Gargoyles), 640 (slimes) ----

    private static SolverStep markRoom(StageView view, RunMember member, Plan plan, Task t) {
        int id = member.characterId();
        int room = t.gateRoom();
        int carrier = plan.owners()[t.slot()];
        int held = view.itemCount(id, MARK);
        boolean isCarrier = id == carrier;
        String gateKey = "gate:" + room + ":" + id;
        if (isCarrier) {
            if (fuseBurning(view, gateKey, held, t.need())) {
                return new SolverStep.Wait(1_000, "the stack of " + t.need() + " marks lies on " + t.gateName()
                        + "; the reactor takes it 5 s after it landed");
            }
            if (held >= t.need()) {
                arm(view, gateKey);
                return new SolverStep.PlaceItem(MARK, t.need(), t.gateName(), t.gateX(), t.gateY(),
                        "all " + t.need() + " marks as one stack: " + t.gateName() + " matches the whole quantity "
                                + "(MapleMap.java:2672)");
            }
        }
        if (room == M630 && !tokenSpent(view)) {
            return gargoyle(view, member);
        }
        if (!isCarrier && held > 0 && carrier != 0 && view.alive(carrier) && view.mapOf(carrier) == room
                && (view.mobsAliveOn(room, t.mob()) == 0 || held >= GIVE_AT)) {
            return new SolverStep.DropFor(MARK, held, carrier);
        }
        if (view.floorItemsOn(room, MARK, id) > 0) {
            return new SolverStep.Loot(MARK);
        }
        int alive = view.mobsAliveOn(room, t.mob());
        if (alive > 0) {
            return new SolverStep.Fight(t.mob(), MARK, isCarrier ? t.need() : Integer.MAX_VALUE);
        }
        if (!isCarrier) {
            return new SolverStep.Wait(1_500, "the " + t.mob() + " are dead; waiting for " + t.gateName()
                    + " to take the carrier's stack");
        }
        int total = held;
        for (RunMember m : view.members()) {
            if (m.characterId() != id && view.mapOf(m.characterId()) == room) {
                total += view.itemCount(m.characterId(), MARK);
            }
        }
        if (total >= t.need()) {
            return new SolverStep.Wait(1_000, "the marks are with the helpers: waiting for them to hand " + t.need()
                    + " to me");
        }
        return new SolverStep.Wait(1_500, "only " + total + " of " + t.need() + " Marks of Evil (" + MARK + ") and no "
                + t.mob() + " left alive on " + room + ": the mobs' 4001035 drop row (152-drop-data.sql:20691-20694) is "
                + "seed data, live DB unverified");
    }

    /** 630 before the Jr. Gargoyles: kill the Gargoyle, put its 4001036 in the invisible reactor's box. */
    private static SolverStep gargoyle(StageView view, RunMember member) {
        int id = member.characterId();
        int tokens = view.itemCount(id, TOKEN);
        String key = "token:" + id;
        if (fuseBurning(view, key, tokens, 1)) {
            return new SolverStep.Wait(1_000, "the 4001036 lies in the box of reactor " + TOKEN_REACTOR
                    + "; it takes it 5 s after it landed");
        }
        if (tokens > 0) {
            arm(view, key);
            return new SolverStep.PlaceItem(TOKEN, 1, String.valueOf(TOKEN_REACTOR), -82, -208,
                    "the Gargoyle's 4001036 in reactor 9201000's box: 9201000.js:31 spawns the eight Jr. Gargoyles");
        }
        if (view.floorItemsOn(M630, TOKEN, id) > 0) {
            return new SolverStep.Loot(TOKEN);
        }
        if (view.mobsAliveOn(M630, GARGOYLE) > 0) {
            return new SolverStep.Fight(GARGOYLE, TOKEN, 1);
        }
        return new SolverStep.Wait(1_500, "the Gargoyle " + GARGOYLE + " is dead and no 4001036 (" + TOKEN + ") is held "
                + "or on the floor: its drop row (152-drop-data.sql:20692) is seed data, live DB unverified, and "
                + "reactor " + TOKEN_REACTOR + " (the eight Jr. Gargoyles) has not fired");
    }

    // ---- helpers: bots with no clothing task ----

    private static SolverStep help(StageView view, RunMember member, Plan plan, int here) {
        int id = member.characterId();
        if (member.leader()) {
            return move(view, member, M700);
        }
        List<Integer> helpers = new ArrayList<>();
        int body = bodyState(view);
        for (int p : plan.pool()) {
            boolean busy = false;
            for (Task t : TASKS) {
                if (plan.owners()[t.slot()] == p && pending(t, body)) {
                    busy = true;
                }
            }
            if (!busy) {
                helpers.add(p);
            }
        }
        int k = Math.max(0, helpers.indexOf(id));
        Task first = k % 2 == 0 ? T_PANTS : T_TOP;
        Task second = first == T_PANTS ? T_TOP : T_PANTS;
        Task pick = null;
        for (Task t : new Task[]{first, second}) {
            if (gateState(view, t) < 1 && plan.owners()[t.slot()] != 0 && plan.owners()[t.slot()] != id) {
                pick = t;
                break;
            }
        }
        if (pick == null) {
            return new SolverStep.Wait(1_500, "every mark gate is open: nothing left to fight for");
        }
        if (here != pick.gateRoom()) {
            return move(view, member, pick.gateRoom());
        }
        return markRoom(view, member, plan, pick);
    }

    // ---------------------------------------------------------------------------------------------------------
    // fuse: a stack dropped on a reactor is acted on 5 s later; nobody picks it up again before then
    // ---------------------------------------------------------------------------------------------------------

    private static void arm(StageView view, String key) {
        view.stageMemory().put("armed:" + key, Boolean.TRUE);
    }

    /**
     * Whether the stack this member dropped under {@code key} is still inside its fuse. Armed when a PlaceItem is
     * issued; the moment the member holds fewer than {@code need} again the drop is taken to have landed.
     */
    private static boolean fuseBurning(StageView view, String key, int held, int need) {
        Map<String, Object> mem = view.stageMemory();
        if (mem.containsKey("armed:" + key) && held < need) {
            mem.remove("armed:" + key);
            mem.put("dropped:" + key, view.nowMs());
        }
        Object at = mem.get("dropped:" + key);
        if (!(at instanceof Long l)) {
            return false;
        }
        if (view.nowMs() - l < FUSE_MS) {
            return true;
        }
        mem.remove("dropped:" + key);
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------
    // routes: the shortest walk over the doors this member may use
    // ---------------------------------------------------------------------------------------------------------

    private static List<Integer> exits(StageView view, RunMember m, int map) {
        List<Integer> out = new ArrayList<>();
        switch (map) {
            case M600 -> {
                out.add(M610);
                out.add(M620);
                out.add(M630);
                if (m.level() >= 1 && m.level() <= JUNIOR_MAX_LEVEL) {
                    out.add(M640);
                }
            }
            case M610 -> {
                out.add(M600);
                if (gateState(view, T_PANTS) >= 1) {
                    out.add(M611);
                }
            }
            case M630 -> {
                out.add(M600);
                if (gateState(view, T_TOP) >= 1) {
                    out.add(M631);
                }
            }
            case M640 -> {
                out.add(M600);
                if (gateState(view, T_CROWN) >= 1) {
                    out.add(M641);
                }
            }
            case M611 -> {
                out.add(M610);
                out.add(M700);
            }
            case M631 -> {
                out.add(M630);
                out.add(M700);
            }
            case M641 -> {
                out.add(M640);
                out.add(M700);
            }
            case M620 -> {
                out.add(M600);
                out.add(M700);
            }
            case M700 -> out.add(M600);
            default -> {
            }
        }
        return out;
    }

    /** The step that takes {@code m} one door closer to {@code to}; a Wait naming the problem when there is no way. */
    static SolverStep move(StageView view, RunMember m, int to) {
        int from = view.mapOf(m.characterId());
        if (from == to) {
            return new SolverStep.Wait(1_000, "already on " + to);
        }
        Map<Integer, Integer> parent = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        parent.put(from, -1);
        queue.add(from);
        while (!queue.isEmpty()) {
            int cur = queue.poll();
            if (cur == to) {
                break;
            }
            for (int nxt : exits(view, m, cur)) {
                if (parent.containsKey(nxt) || nxt == M700 && to != M700) {
                    continue; // 700 is a dead end for anyone not going there: no wandering through it
                }
                parent.put(nxt, cur);
                queue.add(nxt);
            }
        }
        if (!parent.containsKey(to)) {
            return new SolverStep.Wait(1_500, "no door route from map " + from + " to " + to + " for member "
                    + m.characterId() + " (level " + m.level() + ")");
        }
        int hop = to;
        while (parent.get(hop) != from) {
            hop = parent.get(hop);
        }
        return door(from, hop);
    }

    /** The door from one room to a neighbour: plain pt 1 portals are entered, pt 8 ones run their script. */
    static SolverStep door(int from, int to) {
        if (to == M700) {
            return new SolverStep.ScriptedPortal("guild1F00"); // scripts/portal/guild1F01..04.js
        }
        if (from == M700) {
            return new SolverStep.ScriptedPortal("out00"); // scripts/portal/guild1F00.js
        }
        if (from == M600) {
            return switch (to) {
                case M610 -> new SolverStep.EnterPortal("in00");
                case M620 -> new SolverStep.EnterPortal("in01");
                case M630 -> new SolverStep.EnterPortal("in02");
                default -> new SolverStep.ScriptedPortal("in03"); // under30gate.js:25
            };
        }
        if (to == M611 || to == M631 || to == M641) {
            return new SolverStep.ScriptedPortal("in00"); // secretgate1_open..3: needs the gate at state 1
        }
        return new SolverStep.EnterPortal("out00");
    }

    // ---------------------------------------------------------------------------------------------------------
    // a human leader
    // ---------------------------------------------------------------------------------------------------------

    @Override
    public Optional<String> leaderPrompt(StageView view, RunMember leader) {
        if (bodyState(view) < BODY_DONE_STATE) {
            return Optional.empty();
        }
        int id = leader.characterId();
        if (view.mapOf(id) == M700) {
            return Optional.of("@" + id + " the body is dressed, talk to Sharen III's Soul by the gate (leader only)");
        }
        return Optional.of("@" + id + " the body is dressed: go in door in01, take the portal at the top of that room "
                + "(guild1F00) to Sharen III's Grave and talk to his Soul there (leader only)");
    }
}
