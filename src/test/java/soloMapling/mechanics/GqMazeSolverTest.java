package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guild Quest stage 4 (row stage 6): {@link GqMazeSolver} against a tiny world that applies the steps it is given
 * (portals with their kind and gates, a mob that drops what the seed rows say, reactors with the states
 * {@code Reactor.wz} gives them) and writes {@code stage4clear} only when the leader talks to the Soul it spawned
 * ({@code scripts/npc/9040003.js:33}).
 */
class GqMazeSolverTest {

    static final GqMazeSolver SOLVER = new GqMazeSolver();

    static final int M500 = GqMazeSolver.M500;
    static final int M501 = GqMazeSolver.M501;
    static final int M600 = GqMazeSolver.M600;
    static final int M610 = GqMazeSolver.M610;
    static final int M611 = GqMazeSolver.M611;
    static final int M620 = GqMazeSolver.M620;
    static final int M630 = GqMazeSolver.M630;
    static final int M631 = GqMazeSolver.M631;
    static final int M640 = GqMazeSolver.M640;
    static final int M641 = GqMazeSolver.M641;
    static final int M700 = GqMazeSolver.M700;

    static final int PANTS = GqMazeSolver.PANTS;
    static final int SHOES = GqMazeSolver.SHOES;
    static final int TOP = GqMazeSolver.TOP;
    static final int CROWN = GqMazeSolver.CROWN;
    static final int MARK = GqMazeSolver.MARK;
    static final int TOKEN = GqMazeSolver.TOKEN;

    static MechanicsModel.Stage buildStage() {
        return new MechanicsModel.Stage(6, M600, "the maze and Sharen III", 0, "gq-maze", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), new MechanicsModel.Clear("stage4clear", "true", false));
    }

    static RunMember bot(int id, boolean leader, int level) {
        return new RunMember(id, leader, true, MechanicsPersona.PERFECT, 100, level);
    }

    /** Leader 1 (level 50), four level-60 bots, junior 6 (level 25): shoes -> 1, pants -> 2, top -> 3, helpers 4 and 5. */
    static World standard() {
        return new World(bot(1, true, 50), bot(2, false, 60), bot(3, false, 60), bot(4, false, 60),
                bot(5, false, 60), bot(6, false, 25));
    }

    // ------------------------------------------------------------------------------------------------ the world

    record Logged(int id, int map, SolverStep step) {
    }

    static final class Re {
        final int oid;
        final int rid;
        final String name;
        int state;

        Re(int oid, int rid, String name) {
            this.oid = oid;
            this.rid = rid;
            this.name = name;
        }
    }

    static final class World implements StageView {
        final MechanicsModel.Stage stage = buildStage();
        final List<RunMember> members = new ArrayList<>();
        final Map<Integer, Integer> at = new HashMap<>();
        final Map<String, Integer> bag = new HashMap<>();
        final Map<Integer, List<Re>> reactors = new HashMap<>();
        final Map<String, Integer> mobs = new HashMap<>();
        final Map<String, Integer> floor = new HashMap<>();
        final Map<String, String> props = new HashMap<>();
        final Set<Integer> unprotected = new HashSet<>();
        final Set<Integer> brokenBoxes = new HashSet<>();
        final Map<String, Object> memory = new ConcurrentHashMap<>();
        final List<String> errors = new ArrayList<>();
        final List<Logged> steps = new ArrayList<>();
        final List<SolverStep.PlaceItem> placed = new ArrayList<>();
        final List<Integer> bodyOrder = new ArrayList<>();
        final Map<Integer, Set<Integer>> visited = new HashMap<>();
        boolean gargoyleFiresByItself;
        boolean soul;
        long now = 1_000_000;
        int nextOid = 1000;
        int boxHitsAtFive;

        World(RunMember... ms) {
            for (RunMember m : ms) {
                members.add(m);
                at.put(m.characterId(), M600);
                visited.computeIfAbsent(m.characterId(), k -> new TreeSet<>()).add(M600);
            }
            mob(M610, GqMazeSolver.GOLEM, 9);
            mob(M630, GqMazeSolver.GARGOYLE, 1);
            mob(M640, GqMazeSolver.SLIME, 20);
            react(M610, 9208010, "secretgate1");
            react(M611, 9202005, "");
            react(M620, 9202006, "");
            react(M630, 9208011, "secretgate2");
            react(M630, 9201000, "");
            react(M631, 9202007, "");
            react(M640, 9208012, "secretgate3");
            react(M641, 9202008, "");
            react(M700, 9201001, "");
            react(M700, 9208008, "ghostgate");
        }

        // ---- set-up helpers ----
        World at(int id, int map) {
            at.put(id, map);
            return this;
        }

        World bag(int id, int item, int n) {
            bag.put(id + ":" + item, n);
            return this;
        }

        World mob(int map, int mobId, int n) {
            mobs.put(map + ":" + mobId, n);
            return this;
        }

        World floor(int map, int item, int forId, int n) {
            floor.put(map + ":" + item + ":" + forId, n);
            return this;
        }

        World state(int map, int rid, int state) {
            re(map, rid).state = state;
            return this;
        }

        Re react(int map, int rid, String name) {
            Re r = new Re(nextOid++, rid, name);
            reactors.computeIfAbsent(map, k -> new ArrayList<>()).add(r);
            return r;
        }

        Re re(int map, int rid) {
            for (Re r : reactors.getOrDefault(map, List.of())) {
                if (r.rid == rid) {
                    return r;
                }
            }
            throw new IllegalArgumentException("no reactor " + rid + " on " + map);
        }

        RunMember member(int id) {
            return members.stream().filter(m -> m.characterId() == id).findFirst().orElseThrow();
        }

        SolverStep next(int id) {
            return SOLVER.next(this, member(id));
        }

        int held(int id, int item) {
            return bag.getOrDefault(id + ":" + item, 0);
        }

        // ---- StageView ----
        @Override public MechanicsModel.Stage stage() { return stage; }
        @Override public List<RunMember> members() { return members; }
        @Override public String roleOf(int characterId) { return null; }
        @Override public OptionalInt answerFor(int characterId) { return OptionalInt.empty(); }
        @Override public int itemCount(int characterId, int itemId) { return held(characterId, itemId); }
        @Override public Optional<String> eventProperty(String key) { return Optional.ofNullable(props.get(key)); }
        @Override public int[] positionOf(int characterId) { return new int[]{0, 0}; }
        @Override public int mapOf(int characterId) { return at.getOrDefault(characterId, M600); }
        @Override public boolean wearing(int characterId, int itemId) {
            return itemId == GqProtection.EARRING && !unprotected.contains(characterId);
        }
        @Override public long nowMs() { return now; }
        @Override public Map<String, Object> stageMemory() { return memory; }

        @Override
        public List<ReactorPlacement> reactorsOn(int mapId) {
            List<ReactorPlacement> out = new ArrayList<>();
            for (Re r : reactors.getOrDefault(mapId, List.of())) {
                out.add(new ReactorPlacement(r.oid, r.rid, r.name, r.state, 0, 0));
            }
            return out;
        }

        @Override
        public int floorItemsOn(int mapId, int itemId, int forCharacterId) {
            return floor.getOrDefault(mapId + ":" + itemId + ":" + forCharacterId, 0);
        }

        @Override
        public int mobsAliveOn(int mapId, int mobId) {
            return mobs.getOrDefault(mapId + ":" + mobId, 0);
        }

        @Override
        public Optional<int[]> npcPositionOn(int mapId, int npcId) {
            return soul && mapId == M700 && npcId == GqMazeSolver.SOUL ? Optional.of(new int[]{1500, 180})
                    : Optional.empty();
        }

        // ---- applying a step ----
        record Door(int to, boolean scripted, String gate) {
        }

        static final Map<String, Door> DOORS = new HashMap<>();

        static {
            door(M500, "next00", M600, true, null);
            door(GqMazeSolver.M501, "out00", M500, false, null);
            door(GqMazeSolver.M502, "out00", M500, false, null);
            door(M600, "in00", M610, false, null);
            door(M600, "in01", M620, false, null);
            door(M600, "in02", M630, false, null);
            door(M600, "in03", M640, true, "level");
            door(M610, "out00", M600, false, null);
            door(M630, "out00", M600, false, null);
            door(M640, "out00", M600, false, null);
            door(M610, "in00", M611, true, "secretgate1");
            door(M630, "in00", M631, true, "secretgate2");
            door(M640, "in00", M641, true, "secretgate3");
            door(M611, "out00", M610, false, null);
            door(M631, "out00", M630, false, null);
            door(M641, "out00", M640, false, null);
            door(M611, "guild1F00", M700, true, null);
            door(M631, "guild1F00", M700, true, null);
            door(M641, "guild1F00", M700, true, null);
            door(M620, "guild1F00", M700, true, null);
            door(M620, "out00", M600, false, null);
            door(M700, "out00", M600, true, null);
        }

        private static void door(int from, String name, int to, boolean scripted, String gate) {
            DOORS.put(from + ":" + name, new Door(to, scripted, gate));
        }

        void portal(RunMember m, String name, boolean scripted) {
            int id = m.characterId();
            int here = mapOf(id);
            Door d = DOORS.get(here + ":" + name);
            if (d == null) {
                errors.add(id + " no portal " + name + " on " + here);
                return;
            }
            if (d.scripted() != scripted) {
                errors.add(id + " portal " + name + " on " + here + " is " + (d.scripted() ? "scripted (pt 8)" : "plain")
                        + " but the step was " + (scripted ? "ScriptedPortal" : "EnterPortal"));
                return;
            }
            if ("level".equals(d.gate()) && m.level() > 30) {
                return; // under30gate.js:25
            }
            if (d.gate() != null && d.gate().startsWith("secretgate")) {
                boolean open = false;
                for (Re r : reactors.getOrDefault(here, List.of())) {
                    open |= r.name.equals(d.gate()) && r.state == 1;
                }
                if (!open) {
                    return; // "This door is closed."
                }
            }
            at.put(id, d.to());
            visited.computeIfAbsent(id, k -> new TreeSet<>()).add(d.to());
        }

        void give(int id, int item, int n) {
            bag.merge(id + ":" + item, n, Integer::sum);
        }

        void kill(int id, int here, int mobId) {
            mobs.merge(here + ":" + mobId, -1, Integer::sum);
            if (mobId == GqMazeSolver.GARGOYLE) {
                if (gargoyleFiresByItself) {
                    fireToken(here);
                } else {
                    give(id, TOKEN, 1);
                }
            } else {
                give(id, MARK, 1);
            }
        }

        void fireToken(int here) {
            re(here, 9201000).state = 1;
            mob(here, GqMazeSolver.JR_GARGOYLE, 8);
        }

        static int clothFor(int rid) {
            return switch (rid) {
                case 9202005 -> PANTS;
                case 9202006 -> SHOES;
                case 9202007 -> TOP;
                default -> CROWN;
            };
        }

        void place(RunMember m, SolverStep.PlaceItem p) {
            int id = m.characterId();
            int here = mapOf(id);
            placed.add(p);
            if (held(id, p.itemId()) < p.count()) {
                errors.add(id + " places " + p.count() + " x " + p.itemId() + " but holds " + held(id, p.itemId()));
                return;
            }
            Re target = null;
            boolean digits = p.reactor().chars().allMatch(Character::isDigit) && !p.reactor().isEmpty();
            for (Re r : reactors.getOrDefault(here, List.of())) {
                if (digits ? String.valueOf(r.rid).equals(p.reactor()) : r.name.equals(p.reactor())) {
                    target = r;
                }
            }
            if (target == null) {
                errors.add(id + " no reactor " + p.reactor() + " on " + here);
                return;
            }
            bag.merge(id + ":" + p.itemId(), -p.count(), Integer::sum);
            boolean taken = false;
            if (target.state == 0 && p.itemId() == MARK && (target.rid == 9208010 && p.count() == 9
                    || target.rid == 9208011 && p.count() == 8 || target.rid == 9208012 && p.count() == 20)) {
                target.state = 1;
                taken = true;
            } else if (target.state == 0 && target.rid == 9201000 && p.itemId() == TOKEN && p.count() == 1) {
                fireToken(here);
                taken = true;
            } else if (target.rid == 9201001 && target.state >= 0 && target.state <= 3) {
                int[] order = {SHOES, PANTS, TOP, CROWN};
                if (p.itemId() == order[target.state] && p.count() == 1) {
                    bodyOrder.add(p.itemId());
                    target.state++;
                    if (target.state == 4) {
                        soul = true;
                    }
                    taken = true;
                } else {
                    errors.add(id + " put " + p.itemId() + " on the body at state " + target.state);
                }
            }
            if (!taken) {
                floor.merge(here + ":" + p.itemId() + ":" + id, p.count(), Integer::sum);
            }
        }

        void apply(RunMember m, SolverStep s) {
            int id = m.characterId();
            int here = mapOf(id);
            if (s instanceof SolverStep.EnterPortal e) {
                portal(m, e.portalName(), false);
            } else if (s instanceof SolverStep.ScriptedPortal p) {
                portal(m, p.portalName(), true);
            } else if (s instanceof SolverStep.Fight f) {
                if (f.untilItemCount() > 0 && f.itemId() > 0 && held(id, f.itemId()) >= f.untilItemCount()) {
                    return;
                }
                for (int mob : f.mobIds()) {
                    if (mobsAliveOn(here, mob) > 0) {
                        kill(id, here, mob);
                        return;
                    }
                }
            } else if (s instanceof SolverStep.Loot l) {
                String key = here + ":" + l.itemId() + ":" + id;
                int n = floor.getOrDefault(key, 0);
                if (n == 0) {
                    errors.add(id + " loots " + l.itemId() + " that is not there");
                }
                floor.remove(key);
                give(id, l.itemId(), n);
            } else if (s instanceof SolverStep.DropFor d) {
                if (mapOf(d.toCharacterId()) != here || held(id, d.itemId()) < d.count()) {
                    errors.add(id + " cannot drop " + d.count() + " x " + d.itemId() + " for " + d.toCharacterId());
                    return;
                }
                bag.merge(id + ":" + d.itemId(), -d.count(), Integer::sum);
                floor.merge(here + ":" + d.itemId() + ":" + d.toCharacterId(), d.count(), Integer::sum);
            } else if (s instanceof SolverStep.PlaceItem p) {
                place(m, p);
            } else if (s instanceof SolverStep.HitReactor h) {
                Re r = null;
                for (Re each : reactors.getOrDefault(here, List.of())) {
                    if (each.rid == h.reactorId()) {
                        r = each;
                    }
                }
                if (r == null) {
                    errors.add(id + " hits reactor " + h.reactorId() + " that is not on " + here);
                    return;
                }
                if (r.state >= 5) {
                    boxHitsAtFive++;
                }
                r.state = Math.min(5, r.state + 1);
                if (r.state == 5 && !brokenBoxes.contains(r.rid)) {
                    floor.merge(here + ":" + clothFor(r.rid) + ":" + id, 1, Integer::sum);
                }
            } else if (s instanceof SolverStep.TalkNpc t) {
                if (t.npcId() == GqMazeSolver.SOUL && soul && m.leader()) {
                    props.put("stage4clear", "true");
                }
            } else if (s instanceof SolverStep.EquipItem q) {
                unprotected.remove(id);
                errors.add(id + " had to equip " + q.itemId());
            } else if (s instanceof SolverStep.Abandon a) {
                errors.add(id + " abandons: " + a.why());
            }
        }

        /** Runs until the stage is clear (returns the tick) or the ticks are up (-1). */
        int run(int maxTicks) {
            for (int tick = 0; tick < maxTicks; tick++) {
                if (cleared()) {
                    return tick;
                }
                List<RunMember> order = new ArrayList<>(members);
                order.sort(Comparator.comparingInt(RunMember::characterId));
                for (RunMember m : order) {
                    if (!m.bot()) {
                        continue;
                    }
                    SolverStep s = SOLVER.next(this, m);
                    steps.add(new Logged(m.characterId(), mapOf(m.characterId()), s));
                    apply(m, s);
                }
                now += 1_000;
            }
            return cleared() ? maxTicks : -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ junior

    @Test
    void theJuniorIsTheBotOfLevel30OrLessWithTheLowestId() {
        World w = new World(bot(1, true, 70), bot(2, false, 35), bot(3, false, 28), bot(4, false, 30),
                bot(5, false, 80), bot(6, false, 22));
        assertEquals(new SolverStep.ScriptedPortal("in03"), w.next(3), "under30gate is a pt-8 door (under30gate.js:25)");
        assertEquals(3, w.memory.get("claim:640"));
        // 4 (level 30 exactly) and 6 also fit under30gate but are not the junior: they never get the crown's room
        assertNotEquals(new SolverStep.ScriptedPortal("in03"), w.next(4));
        assertNotEquals(new SolverStep.ScriptedPortal("in03"), w.next(6));
        for (String k : new String[]{"claim:620", "claim:610", "claim:630"}) {
            assertNotEquals(3, w.memory.get(k), k + " must not go to the junior while other bots exist");
        }
        assertEquals(1, w.memory.get("claim:620"), "the leader takes the shoe room and so ends up on 700");
    }

    @Test
    void aMemberOverLevel30CannotBeTheJuniorAndNoJuniorAbandonsWhileTheCrownIsNeeded() {
        World w = new World(bot(1, true, 40), bot(2, false, 31), bot(3, false, 90), bot(4, false, 0),
                bot(5, false, 60), bot(6, false, 45));
        SolverStep s = w.next(2);
        SolverStep.Abandon a = assertInstanceOf(SolverStep.Abandon.class, s);
        assertTrue(a.why().contains("under30gate"), a.why());
        assertTrue(a.why().contains("4001034"), a.why());
        // level 0 means "not carried": it fits no seat, so it is no junior either
        assertInstanceOf(SolverStep.Abandon.class, w.next(4));
    }

    @Test
    void theCrownBeingPlacedAlreadyNeedsNoJunior() {
        World w = new World(bot(1, true, 40), bot(2, false, 50), bot(3, false, 90), bot(4, false, 70),
                bot(5, false, 60), bot(6, false, 45));
        w.state(M700, 9201001, 4);
        w.soul = true;
        w.at(1, M700);
        assertEquals(new SolverStep.TalkNpc(GqMazeSolver.SOUL, 0, 0), w.next(1));
    }

    // ------------------------------------------------------------------------------------------------ stacks

    @Test
    void marksGoOnTheGateAsOneExactStackNeverAPartialOne() {
        World w = standard();
        // 610, carrier 2: eight marks with a golem alive is not enough: it fights
        w.at(2, M610).bag(2, MARK, 8);
        assertInstanceOf(SolverStep.Fight.class, w.next(2), "8 of 9 marks: keep fighting, never drop a partial stack");
        w.bag(2, MARK, 9);
        SolverStep.PlaceItem nine = assertInstanceOf(SolverStep.PlaceItem.class, w.next(2));
        assertEquals(MARK, nine.itemId());
        assertEquals(9, nine.count());
        assertEquals("secretgate1", nine.reactor());
        w.bag(2, MARK, 10);
        SolverStep.PlaceItem exact = assertInstanceOf(SolverStep.PlaceItem.class, w.next(2));
        assertEquals(9, exact.count(), "an extra mark stays in the bag: the gate matches the whole quantity");

        // 630 after the Jr. Gargoyles stand: eight
        World w2 = standard();
        w2.at(3, M630).state(M630, 9201000, 1).mob(M630, GqMazeSolver.JR_GARGOYLE, 1).bag(3, MARK, 7);
        assertInstanceOf(SolverStep.Fight.class, w2.next(3));
        w2.bag(3, MARK, 8);
        SolverStep.PlaceItem eight = assertInstanceOf(SolverStep.PlaceItem.class, w2.next(3));
        assertEquals(8, eight.count());
        assertEquals("secretgate2", eight.reactor());

        // 640, the junior alone: twenty
        World w3 = standard();
        w3.at(6, M640).bag(6, MARK, 19);
        assertInstanceOf(SolverStep.Fight.class, w3.next(6));
        w3.bag(6, MARK, 20);
        SolverStep.PlaceItem twenty = assertInstanceOf(SolverStep.PlaceItem.class, w3.next(6));
        assertEquals(20, twenty.count());
        assertEquals("secretgate3", twenty.reactor());
    }

    @Test
    void aHelperHandsItsMarksToTheCarrierOnlyOnceItHasThreeOrTheMobsAreDead() {
        World w = standard();
        w.at(4, M610).at(2, M610).bag(4, MARK, 2);
        assertInstanceOf(SolverStep.Fight.class, w.next(4), "two marks are not worth the walk while golems stand");
        w.bag(4, MARK, 3);
        assertEquals(new SolverStep.DropFor(MARK, 3, 2), w.next(4));
        w.bag(4, MARK, 1).mob(M610, GqMazeSolver.GOLEM, 0);
        assertEquals(new SolverStep.DropFor(MARK, 1, 2), w.next(4), "with the golems dead the last mark goes over too");
        // the carrier picks up what was dropped for him
        w.floor(M610, MARK, 2, 1);
        assertEquals(new SolverStep.Loot(MARK), w.next(2));
    }

    // ------------------------------------------------------------------------------------------------ 630

    @Test
    void roomThirtyNeedsTheGargoylesTokenInReactor9201000BeforeTheEightMarksExist() {
        World w = standard();
        w.at(3, M630);
        SolverStep.Fight f = assertInstanceOf(SolverStep.Fight.class, w.next(3));
        assertEquals(List.of(GqMazeSolver.GARGOYLE), f.mobIds());
        assertEquals(TOKEN, f.itemId());

        w.mob(M630, GqMazeSolver.GARGOYLE, 0).floor(M630, TOKEN, 3, 1);
        assertEquals(new SolverStep.Loot(TOKEN), w.next(3));

        w.floor.clear();
        w.bag(3, TOKEN, 1);
        SolverStep.PlaceItem p = assertInstanceOf(SolverStep.PlaceItem.class, w.next(3));
        assertEquals(TOKEN, p.itemId());
        assertEquals(1, p.count());
        assertEquals("9201000", p.reactor(), "the invisible reactor has no name: its WZ id is the key");

        // the reactor fired: eight Jr. Gargoyles stand and are the mark source
        w.bag.clear();
        w.state(M630, 9201000, 1).mob(M630, GqMazeSolver.JR_GARGOYLE, 8);
        SolverStep.Fight jr = assertInstanceOf(SolverStep.Fight.class, w.next(3));
        assertEquals(List.of(GqMazeSolver.JR_GARGOYLE), jr.mobIds());
        assertEquals(MARK, jr.itemId());
        assertEquals(8, jr.untilItemCount());
    }

    @Test
    void aGargoyleThatFiresTheReactorByItselfStillEndsWithEightMarks() {
        World w = standard();
        w.gargoyleFiresByItself = true;
        w.at(3, M630);
        w.apply(w.member(3), w.next(3)); // kills the Gargoyle: its drop landed in the box and fired 9201000
        assertEquals(1, w.re(M630, 9201000).state);
        SolverStep.Fight jr = assertInstanceOf(SolverStep.Fight.class, w.next(3));
        assertEquals(List.of(GqMazeSolver.JR_GARGOYLE), jr.mobIds());
    }

    // ------------------------------------------------------------------------------------------------ the body

    @Test
    void theBodyGetsShoesPantsTopCrownEachOnlyWhenThePreviousIsIn() {
        World w = standard();
        // pants in hand at body state 0: it waits beside the body, it never drops them early
        w.at(2, M700).bag(2, PANTS, 1);
        SolverStep early = w.next(2);
        assertInstanceOf(SolverStep.Wait.class, early);
        assertTrue(((SolverStep.Wait) early).why().contains("4001031"), early.toString());

        w.at(1, M700).bag(1, SHOES, 1);
        SolverStep.PlaceItem shoes = assertInstanceOf(SolverStep.PlaceItem.class, w.next(1));
        assertEquals(SHOES, shoes.itemId());
        assertEquals(1, shoes.count());
        assertEquals("9201001", shoes.reactor());
        assertEquals(1438, shoes.x());
        assertEquals(180, shoes.y());

        w.state(M700, 9201001, 1);
        assertEquals(PANTS, assertInstanceOf(SolverStep.PlaceItem.class, w.next(2)).itemId());

        w.at(3, M700).bag(3, TOP, 1);
        assertInstanceOf(SolverStep.Wait.class, w.next(3), "the top waits for the pants (state 2)");
        w.state(M700, 9201001, 2);
        assertEquals(TOP, assertInstanceOf(SolverStep.PlaceItem.class, w.next(3)).itemId());

        w.at(6, M700).bag(6, CROWN, 1);
        assertInstanceOf(SolverStep.Wait.class, w.next(6), "the crown waits for the top (state 3)");
        w.state(M700, 9201001, 3);
        assertEquals(CROWN, assertInstanceOf(SolverStep.PlaceItem.class, w.next(6)).itemId());
    }

    @Test
    void aClothesCarrierOutsideSevenHundredWalksToTheBodyThroughTheClothingRoomsDoor() {
        // each clothing room's guild1F00 is a pt-8 door (scripts/portal/guild1F01..04.js) to 700
        int[][] cases = {{2, M611, PANTS}, {1, M620, SHOES}, {3, M631, TOP}, {6, M641, CROWN}};
        for (int[] c : cases) {
            World w = standard();
            w.at(c[0], c[1]).bag(c[0], c[2], 1);
            // shoes can go in at once; the others are not due yet, but their holders go and wait by the body
            assertEquals(new SolverStep.ScriptedPortal("guild1F00"), w.next(c[0]), "room " + c[1]);
        }
        World w = standard();
        w.at(2, M610).bag(2, PANTS, 1);
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(2), "from a mark room the way is back to 600");
    }

    @Test
    void aBoxHitFiveTimesDropsOnceAndIsNotHitAgainWhileItsItemLies() {
        World w = standard();
        w.at(2, M611).state(M610, 9208010, 1);
        w.state(M611, 9202005, 4);
        assertEquals(new SolverStep.HitReactor(9202005), w.next(2), "state 4: the fifth hit is still due");
        w.state(M611, 9202005, 5).floor(M611, PANTS, 2, 1);
        assertEquals(new SolverStep.Loot(PANTS), w.next(2), "state 5: take what fell, do not hit and drop another");
    }

    // ------------------------------------------------------------------------------------------------ missing drops

    @Test
    void aBoxThatGivesNothingWaitsNamingTheItemAndTheLiveDbThenHitsAgain() {
        World w = standard();
        w.state(M610, 9208010, 1).at(2, M611).state(M611, 9202005, 5);
        SolverStep s = w.next(2);
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, s);
        assertTrue(wait.why().contains("4001031"), wait.why());
        assertTrue(wait.why().contains("live DB unverified"), wait.why());
        w.now += GqMazeSolver.RE_HIT_AFTER_MS;
        assertEquals(new SolverStep.HitReactor(9202005), w.next(2), "one more hit drops again at state 5");
    }

    @Test
    void deadMarkMobsAndNoMarksWaitNamingTheMarkAndTheLiveDb() {
        World w = standard();
        w.at(2, M610).mob(M610, GqMazeSolver.GOLEM, 0).bag(2, MARK, 4);
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, w.next(2));
        assertTrue(wait.why().contains("4001035"), wait.why());
        assertTrue(wait.why().contains("4 of 9"), wait.why());
        assertTrue(wait.why().contains("live DB unverified"), wait.why());
    }

    @Test
    void aDeadGargoyleWithNoTokenWaitsNamingFortyZeroZeroOneThirtySix() {
        World w = standard();
        w.at(3, M630).mob(M630, GqMazeSolver.GARGOYLE, 0);
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, w.next(3));
        assertTrue(wait.why().contains("4001036"), wait.why());
        assertTrue(wait.why().contains("live DB unverified"), wait.why());
    }

    @Test
    void aMarkStackThatLandedIsLeftAloneForItsFuseThenPickedUpAgain() {
        World w = standard();
        w.at(2, M610).bag(2, MARK, 9);
        assertInstanceOf(SolverStep.PlaceItem.class, w.next(2));
        // the executor put it down: the bag is empty, the stack lies on the floor for him
        w.bag(2, MARK, 0).floor(M610, MARK, 2, 9);
        SolverStep.Wait fuse = assertInstanceOf(SolverStep.Wait.class, w.next(2), "do not pick the stack up inside the fuse");
        assertTrue(fuse.why().contains("5 s"), fuse.why());
        w.now += GqMazeSolver.FUSE_MS;
        assertEquals(new SolverStep.Loot(MARK), w.next(2), "gate still shut after the fuse: take the stack back");
    }

    // ------------------------------------------------------------------------------------------------ protection

    @Test
    void anUnprotectedMemberGetsTheProtectionStepBeforeAnything() {
        World w = standard();
        w.unprotected.add(3);
        w.bag(3, GqProtection.EARRING, 1);
        assertEquals(new SolverStep.EquipItem(GqProtection.EARRING, "the maps from 990000300 on take 30000 HP a tick "
                + "without it"), w.next(3));
        World none = standard();
        none.unprotected.add(3);
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, none.next(3));
        assertTrue(wait.why().contains("1032033"), wait.why());
    }

    // ------------------------------------------------------------------------------------------------ doors

    @Test
    void membersLeaveTheVassalRoomsAndWalkIntoTheMaze() {
        World w = standard();
        w.at(2, M501);
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(2));
        w.at(2, M500);
        assertEquals(new SolverStep.ScriptedPortal("next00"), w.next(2), "watergate is a pt-8 door (watergate_open.js)");
        assertTrue(SOLVER.extraMaps(w).containsAll(List.of(M500, M501, GqMazeSolver.M502, M600, M610, M611, M620, M630,
                M631, M640, M641, M700)));
    }

    @Test
    void aMemberStandingOnSevenHundredWithNothingToDoStaysThere() {
        World w = standard();
        w.at(4, M700);
        assertInstanceOf(SolverStep.Wait.class, w.next(4));
        w.at(5, M700).state(M610, 9208010, 1).state(M630, 9208011, 1);
        assertInstanceOf(SolverStep.Wait.class, w.next(5));
    }

    // ------------------------------------------------------------------------------------------------ claims

    @Test
    void aClaimantWhoLeavesLosesItsTask() {
        World w = new World(bot(1, true, 50), bot(2, false, 60), bot(3, false, 60), bot(4, false, 60),
                bot(5, false, 60), bot(6, false, 25), bot(7, false, 20));
        w.next(1);
        assertEquals(2, w.memory.get("claim:610"));
        assertEquals(6, w.memory.get("claim:640"));
        w.members.remove(w.member(2));
        w.members.remove(w.member(6));
        w.next(1);
        Object pants = w.memory.get("claim:610");
        assertNotEquals(2, pants);
        assertTrue(w.members.stream().anyMatch(m -> m.characterId() == (Integer) pants), "the new owner is still here");
        assertEquals(7, w.memory.get("claim:640"), "the next junior takes the crown's room");
    }

    // ------------------------------------------------------------------------------------------------ leader

    @Test
    void onlyTheLeaderTalksToSharenTheThirdsSoul() {
        World w = standard();
        w.state(M700, 9201001, 4).at(1, M700).at(2, M700);
        SolverStep.Wait none = assertInstanceOf(SolverStep.Wait.class, w.next(1));
        assertTrue(none.why().contains("9040003"), none.why());
        w.soul = true;
        assertEquals(new SolverStep.TalkNpc(GqMazeSolver.SOUL, 0, 0), w.next(1));
        assertInstanceOf(SolverStep.Wait.class, w.next(2));
        w.at(1, M600);
        assertEquals(new SolverStep.EnterPortal("in01"), w.next(1), "the leader is walked to 700 through the shoe room");
    }

    @Test
    void aHumanLeaderIsToldWhenTheBodyIsDressed() {
        World w = new World(RunMember.player(1, true), bot(2, false, 60), bot(3, false, 60), bot(4, false, 60),
                bot(5, false, 60), bot(6, false, 25));
        assertTrue(SOLVER.leaderPrompt(w, w.member(1)).isEmpty(), "not his turn yet");
        w.state(M700, 9201001, 4).soul = true;
        Optional<String> prompt = SOLVER.leaderPrompt(w, w.member(1));
        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().startsWith("@1 "), prompt.get());
        w.at(1, M700);
        assertTrue(SOLVER.leaderPrompt(w, w.member(1)).get().contains("Soul"));
    }

    // ------------------------------------------------------------------------------------------------ whole stage

    @Test
    void theWholeStageReachesStage4clearInAWorldThatOnlyGrantsItWhenTheLeaderTalks() {
        World w = standard();
        assertFalse(w.cleared());
        int ticks = w.run(3_000);
        assertTrue(ticks > 0, "the stage never cleared; last steps: "
                + w.steps.subList(Math.max(0, w.steps.size() - 12), w.steps.size()) + " errors " + w.errors);
        assertEquals("true", w.props.get("stage4clear"));
        assertTrue(w.errors.isEmpty(), "every step must be a door/portal/target the files have: " + w.errors);
        assertEquals(List.of(SHOES, PANTS, TOP, CROWN), w.bodyOrder, "the body takes the clothes in WZ order");
        // exact stacks only
        List<Integer> stacks = new ArrayList<>();
        for (SolverStep.PlaceItem p : w.placed) {
            if (p.itemId() == MARK) {
                stacks.add(p.count());
            }
        }
        stacks.sort(Integer::compare);
        assertEquals(List.of(8, 9, 20), stacks, "one stack per gate, 9 / 8 / 20 marks");
        // the junior is the only member that ever stood in 640/641, and stayed out of the others' rooms
        for (RunMember m : w.members) {
            Set<Integer> seen = w.visited.get(m.characterId());
            boolean inCrownRooms = seen.contains(M640) || seen.contains(M641);
            assertEquals(m.characterId() == 6, inCrownRooms, "member " + m.characterId() + " visited " + seen);
            if (m.characterId() == 6) {
                assertFalse(seen.contains(M610) || seen.contains(M611) || seen.contains(M620)
                        || seen.contains(M630) || seen.contains(M631), "the junior never gets another bot's room: " + seen);
            }
        }
        // only the leader ever talked to the Soul
        for (Logged l : w.steps) {
            if (l.step() instanceof SolverStep.TalkNpc) {
                assertEquals(1, l.id());
            }
        }
    }

    @Test
    void theWholeStageClearsWithAGargoyleThatFiresTheReactorAndWithTheLeaderAsJunior() {
        World w = new World(bot(1, true, 20), bot(2, false, 60), bot(3, false, 61), bot(4, false, 62),
                bot(5, false, 63), bot(6, false, 64));
        w.gargoyleFiresByItself = true;
        int ticks = w.run(3_000);
        assertTrue(ticks > 0, "no clear; last steps " + w.steps.subList(Math.max(0, w.steps.size() - 12), w.steps.size())
                + " errors " + w.errors);
        assertTrue(w.errors.isEmpty(), w.errors.toString());
        assertEquals(List.of(SHOES, PANTS, TOP, CROWN), w.bodyOrder);
        assertEquals(1, w.memory.get("claim:640"), "the leader is the only level <= 30: he does 640, 641 and the talk");
    }

    @Test
    void aHumanLeaderLeavesTheTalkToHimAndTheBotsStillDressTheBody() {
        World w = new World(RunMember.player(1, true), bot(2, false, 60), bot(3, false, 60), bot(4, false, 60),
                bot(5, false, 60), bot(6, false, 25));
        w.run(3_000);
        assertEquals(List.of(SHOES, PANTS, TOP, CROWN), w.bodyOrder);
        assertTrue(w.soul, "the Soul stands on 700");
        assertFalse(w.cleared(), "nobody steers the human: only he can talk (9040003.js:65)");
        assertTrue(w.errors.isEmpty(), w.errors.toString());
    }

    // ------------------------------------------------------------------------------------------------ negative

    @Test
    void aWorldWhosePantsBoxDropsNothingNeverClearsAndKeepsSayingWhatIsMissing() {
        World w = standard();
        w.brokenBoxes.add(9202005);
        int ticks = w.run(600);
        assertEquals(-1, ticks, "the pants never drop: the stage cannot clear");
        assertFalse(w.props.containsKey("stage4clear"));
        assertEquals(1, w.re(M700, 9201001).state, "only the shoes went in");
        assertTrue(w.steps.stream().noneMatch(l -> l.step() instanceof SolverStep.TalkNpc), "no Soul, no talk");
        boolean named = false;
        for (Logged l : w.steps) {
            if (l.step() instanceof SolverStep.Wait wait && wait.why().contains("4001031")
                    && wait.why().contains("live DB unverified")) {
                named = true;
            }
        }
        assertTrue(named, "a Wait names the missing pants and says the live DB is unverified");
        assertTrue(w.boxHitsAtFive >= 1, "it kept trying: one more hit at state 5 every 15 s");
        assertTrue(w.errors.isEmpty(), w.errors.toString());
    }
}
