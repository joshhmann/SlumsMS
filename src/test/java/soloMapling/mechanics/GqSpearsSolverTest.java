package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guild Quest stage "spear hall" ({@link GqSpearsSolver}) against a fake mini world: reactors, floor drops, bags and
 * rooms are plain fields, and every earning step (a hit, a loot, a placement that the item reactor accepts 5 s later)
 * changes the world only when the step is applied - a solver that returns a no-op clears nothing.
 */
class GqSpearsSolverTest {

    private static final int HALL = GqSpearsSolver.HALL;
    private static final int R410 = GqSpearsSolver.ROOM_SPEARS;
    private static final int R420 = GqSpearsSolver.ROOM_KEY;
    private static final int R430 = GqSpearsSolver.ROOM_GOLEMS;
    private static final int R431 = GqSpearsSolver.ROOM_VAULT;
    private static final int TOWER = GqSpearsSolver.TOWER;
    private static final int SPEAR = GqSpearsSolver.SPEAR;
    private static final int KEY = GqSpearsSolver.STONE_KEY;
    private static final int RUSTY = GqSpearsSolver.RUSTY_KEY;

    // ---------------------------------------------------------------------------------------------- the fake world

    private static final class R {
        final int oid;
        final int id;
        final String name;
        final int x;
        final int y;
        int state;

        R(int oid, int id, String name, int x, int y) {
            this.oid = oid;
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    private record Drop(int map, int item) {
    }

    private static final class Bot {
        final int id;
        int level;
        int map = HALL;
        int x;
        int y = 100;
        boolean wearing = true;
        final Map<Integer, Integer> bag = new HashMap<>();

        Bot(int id, int level) {
            this.id = id;
            this.level = level;
        }
    }

    private record Pending(long at, int map, R reactor, int item) {
    }

    /** The portals of 990000440 (Map9/990000440.img.xml): name, x, y, target portal of the same map ("" = none). */
    private static final String[][] TOWER_PORTALS = {
            {"h000", "426", "367", "st00"}, {"h001", "550", "363", "h003"}, {"h002", "660", "364", "st00"},
            {"h003", "480", "175", "h006"}, {"h004", "618", "178", "st00"}, {"h005", "774", "171", "st00"},
            {"h006", "290", "-6", "st00"}, {"h007", "436", "-3", "st00"}, {"h008", "549", "-3", "st00"},
            {"h009", "672", "-3", "st00"}, {"h010", "792", "-6", "h011"}, {"h011", "490", "-183", "st00"},
            {"h012", "607", "-185", "h014"}, {"h013", "774", "-185", "st00"}, {"h014", "432", "-363", "st00"},
            {"h015", "555", "-363", "h017"}, {"h016", "669", "-362", "st00"}, {"h017", "309", "-543", "h021"},
            {"h018", "486", "-544", "st00"}, {"h019", "602", "-544", "st00"}, {"h020", "767", "-544", "st00"},
            {"h021", "429", "-723", "st00"}, {"h022", "547", "-725", "h025"}, {"h023", "661", "-724", "st00"},
            {"h024", "790", "-727", "st00"}, {"h025", "691", "-902", ""}, {"st00", "306", "504", ""}};

    private static final MechanicsModel.Stage STAGE = new MechanicsModel.Stage(4, HALL, "Hall of the Knight", 0,
            GqSpearsSolver.ID, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            new MechanicsModel.Clear(null, null, false, false, GqSpearsSolver.SPEAR_GATE, 4), null, List.of(),
            List.of(), null, null, 0, 0, List.of(), List.of(),
            List.of(GqSpearsSolver.STATUES, GqSpearsSolver.ARRIVAL, R410, R420, R430, R431, TOWER), 0,
            MechanicsModel.OnTimeLimit.END, List.of());

    private static final class World implements StageView {
        final Map<Integer, Bot> bots = new TreeMap<>();
        final List<RunMember> members = new ArrayList<>();
        final Map<Integer, List<R>> reactors = new HashMap<>();
        final List<Drop> floor = new ArrayList<>();
        final List<Pending> pending = new ArrayList<>();
        final Map<String, Object> memory = new HashMap<>();
        final List<String> violations = new ArrayList<>();
        final List<SolverStep> issued = new ArrayList<>();
        final List<String> altarsPlaced = new ArrayList<>();
        int keyMobAlive = 1;
        int keyHits;
        long now;
        boolean spearDrops = true;
        boolean keyDrops = true;
        boolean boxDrops = true;
        boolean altarsAct = true;
        String abandoned;
        final GqSpearsSolver solver = new GqSpearsSolver();

        World(int botCount) {
            for (int i = 1; i <= botCount; i++) {
                addBot(i, 20);
            }
            on(HALL).add(new R(100, GqSpearsSolver.SPEAR_GATE, "speargate", 256, 96));
            on(HALL).add(new R(101, 9_208_006, "stonegate", -767, -532));
            on(R410).add(new R(201, GqSpearsSolver.CHAINED_SPEAR, "", -182, -1155));
            on(R410).add(new R(202, GqSpearsSolver.CHAINED_SPEAR, "", 447, -1157));
            on(R410).add(new R(203, GqSpearsSolver.CHAINED_SPEAR, "", 1073, -1157));
            on(R430).add(new R(301, 9_208_005, "metalgate", 927, -352));
            on(R430).add(new R(302, GqSpearsSolver.BURIED_BOX, "", 876, 153));
            on(R431).add(new R(401, GqSpearsSolver.CHAINED_SPEAR, "", -183, -138));
            for (int k = 1; k <= 4; k++) {
                on(TOWER).add(new R(500 + k, 9_208_007, "spear" + k, GqSpearsSolver.ALTAR_X[k], -1054));
            }
        }

        Bot addBot(int id, int level) {
            Bot b = new Bot(id, level);
            bots.put(id, b);
            members.add(new RunMember(id, id == 1, true, MechanicsPersona.PERFECT, 0, level));
            return b;
        }

        List<R> on(int map) {
            return reactors.computeIfAbsent(map, k -> new ArrayList<>());
        }

        R find(int map, int oid) {
            for (R r : on(map)) {
                if (r.oid == oid) {
                    return r;
                }
            }
            return null;
        }

        R named(int map, String name) {
            for (R r : on(map)) {
                if (r.name.equals(name)) {
                    return r;
                }
            }
            return null;
        }

        void remove(int map, int oid) {
            on(map).removeIf(r -> r.oid == oid);
        }

        int gate() {
            return named(HALL, "speargate").state;
        }

        RunMember member(int id) {
            return members.stream().filter(m -> m.characterId() == id).findFirst().orElseThrow();
        }

        SolverStep next(int id) {
            return solver.next(this, member(id));
        }

        Map<String, Integer> claims() {
            Map<String, Integer> out = new TreeMap<>();
            for (Map.Entry<String, Object> e : memory.entrySet()) {
                if (e.getKey().startsWith("claim:") && e.getValue() instanceof Integer owner) {
                    out.put(e.getKey(), owner);
                }
            }
            return out;
        }

        // ------------------------------------------------------------------------------------------ StageView

        @Override
        public MechanicsModel.Stage stage() {
            return STAGE;
        }

        @Override
        public List<RunMember> members() {
            return members;
        }

        @Override
        public String roleOf(int characterId) {
            return "any";
        }

        @Override
        public OptionalInt answerFor(int characterId) {
            return OptionalInt.empty();
        }

        @Override
        public int itemCount(int characterId, int itemId) {
            Bot b = bots.get(characterId);
            return b == null ? 0 : b.bag.getOrDefault(itemId, 0);
        }

        @Override
        public Optional<String> eventProperty(String key) {
            return Optional.empty();
        }

        @Override
        public int[] positionOf(int characterId) {
            Bot b = bots.get(characterId);
            return new int[]{b.x, b.y};
        }

        @Override
        public int mapOf(int characterId) {
            return bots.get(characterId).map;
        }

        @Override
        public int reactorState(String name) {
            R r = named(HALL, name);
            return r == null ? -1 : r.state;
        }

        @Override
        public int reactorState(int reactorId) {
            for (R r : on(HALL)) {
                if (r.id == reactorId) {
                    return r.state;
                }
            }
            return -1;
        }

        @Override
        public List<ReactorPlacement> reactorsOn(int mapId) {
            List<ReactorPlacement> out = new ArrayList<>();
            for (R r : on(mapId)) {
                out.add(new ReactorPlacement(r.oid, r.id, r.name, r.state, r.x, r.y));
            }
            return out;
        }

        @Override
        public int floorItemsOn(int mapId, int itemId, int forCharacterId) {
            return (int) floor.stream().filter(d -> d.map() == mapId && d.item() == itemId).count();
        }

        @Override
        public int mobsAliveOn(int mapId, int mobId) {
            if (mapId == R420 && mobId == GqSpearsSolver.KEY_MOB) {
                return keyMobAlive;
            }
            if (mapId == R430 && mobId == GqSpearsSolver.GOLEM) {
                return 19;
            }
            return 0;
        }

        @Override
        public boolean wearing(int characterId, int itemId) {
            Bot b = bots.get(characterId);
            return b != null && b.wearing;
        }

        @Override
        public boolean alive(int characterId) {
            return bots.containsKey(characterId);
        }

        @Override
        public Map<String, Object> stageMemory() {
            return memory;
        }

        @Override
        public long nowMs() {
            return now;
        }

        // ------------------------------------------------------------------------------------------ the sim

        private void arrive(Bot b, int map) {
            b.map = map;
            b.x = 0;
            b.y = 100;
            if (map == TOWER) {
                b.x = -341; // out00 of 990000440
                b.y = 499;
            }
        }

        private String[] portal(String name) {
            for (String[] p : TOWER_PORTALS) {
                if (p[0].equals(name)) {
                    return p;
                }
            }
            return null;
        }

        private void enterPortal(Bot b, String name) {
            if (b.map == GqSpearsSolver.ARRIVAL && name.equals("next01")) {
                arrive(b, HALL);
            } else if (b.map == HALL && name.equals("in00")) {
                arrive(b, R410);
            } else if (b.map == HALL && name.equals("in01")) {
                arrive(b, R420);
            } else if (b.map == HALL && name.equals("in03")) {
                arrive(b, TOWER);
            } else if (name.equals("out00") && (b.map == R410 || b.map == R420 || b.map == R430 || b.map == TOWER)) {
                arrive(b, HALL);
            } else if (name.equals("out00") && b.map == R431) {
                arrive(b, R430);
            } else if (b.map == TOWER && portal(name) != null) {
                String[] p = portal(name);
                String[] to = p[3].isEmpty() ? null : portal(p[3]);
                if (to == null) {
                    violations.add("the tower portal " + name + " leads nowhere");
                } else {
                    b.x = Integer.parseInt(to[1]);
                    b.y = Integer.parseInt(to[2]);
                }
            } else {
                violations.add("no plain portal " + name + " on " + b.map);
            }
        }

        private void scriptedPortal(Bot b, String name) {
            if (b.map == GqSpearsSolver.STATUES && name.equals("next00")) {
                arrive(b, GqSpearsSolver.ARRIVAL);
            } else if (b.map == HALL && name.equals("in02")) {
                if (reactorState("stonegate") >= 1) {
                    arrive(b, R430);
                } else {
                    violations.add("in02 taken before stonegate is at state 1");
                }
            } else if (b.map == R430 && name.equals("in00")) {
                if (named(R430, "metalgate").state >= 1) {
                    arrive(b, R431);
                } else {
                    violations.add("in00 taken before metalgate is at state 1");
                }
            } else {
                violations.add("no scripted portal " + name + " on " + b.map);
            }
        }

        private void hit(Bot b, int oid) {
            R r = find(b.map, oid);
            if (r == null) {
                violations.add("no reactor " + oid + " on " + b.map);
                return;
            }
            r.state++;
            if (r.id == GqSpearsSolver.CHAINED_SPEAR && r.state >= 5) {
                remove(b.map, oid);
                if (spearDrops) {
                    floor.add(new Drop(b.map, SPEAR));
                }
            } else if (r.id == GqSpearsSolver.BURIED_BOX && r.state >= 30) {
                remove(b.map, oid);
                if (boxDrops) {
                    floor.add(new Drop(b.map, RUSTY));
                }
            } else if (r.id != GqSpearsSolver.CHAINED_SPEAR && r.id != GqSpearsSolver.BURIED_BOX) {
                violations.add("hit a reactor that is not a box or a spear: " + r.id);
            }
        }

        private void place(Bot b, SolverStep.PlaceItem p) {
            R r = named(b.map, p.reactor());
            if (b.bag.getOrDefault(p.itemId(), 0) < p.count()) {
                violations.add("PlaceItem of " + p.itemId() + " that the bot does not carry");
                return;
            }
            if (r == null) {
                violations.add("PlaceItem on " + p.reactor() + " which is not on " + b.map);
                return;
            }
            int wants = p.reactor().startsWith("spear") ? SPEAR : p.reactor().equals("stonegate") ? KEY : RUSTY;
            if (wants != p.itemId() || p.count() != 1) {
                violations.add("PlaceItem " + p.count() + " x " + p.itemId() + " on " + p.reactor());
                return;
            }
            if (r.state >= 1) {
                violations.add("PlaceItem on " + p.reactor() + " which is already at state " + r.state);
                return;
            }
            if (p.reactor().startsWith("spear") && b.y > -765) {
                violations.add("PlaceItem on an altar by a bot that is not at the top of the tower (y " + b.y + ")");
                return;
            }
            b.bag.merge(p.itemId(), -1, Integer::sum);
            floor.add(new Drop(b.map, p.itemId()));
            pending.add(new Pending(now + 5_000, b.map, r, p.itemId()));
            if (p.reactor().startsWith("spear")) {
                altarsPlaced.add(p.reactor());
            }
        }

        void apply(Bot b, SolverStep s) {
            if (s instanceof SolverStep.EnterPortal e) {
                enterPortal(b, e.portalName());
            } else if (s instanceof SolverStep.ScriptedPortal e) {
                scriptedPortal(b, e.portalName());
            } else if (s instanceof SolverStep.HitReactorByOid h) {
                hit(b, h.objectId());
            } else if (s instanceof SolverStep.Loot l) {
                Drop d = new Drop(b.map, l.itemId());
                if (floor.remove(d)) {
                    b.bag.merge(l.itemId(), 1, Integer::sum);
                } else {
                    violations.add("Loot of " + l.itemId() + " with nothing of it on " + b.map);
                }
            } else if (s instanceof SolverStep.Fight f) {
                if (!f.mobIds().equals(List.of(GqSpearsSolver.KEY_MOB)) || b.map != R420) {
                    violations.add("Fight " + f.mobIds() + " on " + b.map);
                } else if (keyMobAlive > 0 && ++keyHits >= 3) {
                    keyMobAlive--;
                    if (keyDrops) {
                        floor.add(new Drop(R420, KEY));
                    }
                }
            } else if (s instanceof SolverStep.PlaceItem p) {
                place(b, p);
            } else if (s instanceof SolverStep.EquipItem) {
                b.wearing = true;
            } else if (s instanceof SolverStep.Abandon a) {
                abandoned = a.why();
            } else if (!(s instanceof SolverStep.Wait || s instanceof SolverStep.Say
                    || s instanceof SolverStep.Done)) {
                violations.add("unexpected step " + s);
            }
        }

        /** One second: every bot asks and its step is applied, then the item reactors that were fed 5 s ago act. */
        void tick() {
            for (Bot b : new ArrayList<>(bots.values())) {
                SolverStep s = next(b.id);
                issued.add(s);
                if (s instanceof SolverStep.ScriptedPortal sp && sp.portalName().equals("in02")
                        && reactorState("stonegate") < 1) {
                    violations.add("solver sent a bot into in02 while stonegate is at state "
                            + reactorState("stonegate"));
                }
                if (s instanceof SolverStep.ScriptedPortal sp && sp.portalName().equals("in00")
                        && (named(R430, "metalgate") == null || named(R430, "metalgate").state < 1)) {
                    violations.add("solver sent a bot into in00 while metalgate is shut");
                }
                apply(b, s);
            }
            now += 1_000;
            List<Pending> due = new ArrayList<>();
            for (Pending p : pending) {
                if (p.at() <= now) {
                    due.add(p);
                }
            }
            pending.removeAll(due);
            for (Pending p : due) {
                boolean spear = p.reactor().name.startsWith("spear");
                if (spear && !altarsAct) {
                    continue;
                }
                if (p.reactor().state == 0 && floor.remove(new Drop(p.map(), p.item()))) {
                    p.reactor().state = 1;
                    if (spear) {
                        named(HALL, "speargate").state++;
                    }
                }
            }
        }

        /** Runs until the stage clears or a bot abandons; returns the ticks used, or -1 when it did not clear. */
        int run(int maxTicks) {
            for (int t = 0; t < maxTicks; t++) {
                if (cleared()) {
                    return t;
                }
                if (abandoned != null) {
                    return -1;
                }
                tick();
            }
            return cleared() ? maxTicks : -1;
        }
    }

    private static World world(int bots) {
        return new World(bots);
    }

    // ------------------------------------------------------------------------------------- assignment

    @Test
    void taskAssignmentIsDeterministicAndDisjointWhoeverIsAskedFirst() {
        int[][] orders = {{1, 2, 3, 4, 5, 6}, {6, 5, 4, 3, 2, 1}, {3, 1, 6, 2, 5, 4}};
        Map<String, Integer> first = null;
        for (int[] order : orders) {
            World w = world(6);
            w.bots.get(4).level = 30;
            w.bots.get(5).level = 10;
            w.bots.get(6).level = 25;
            w.members.clear();
            for (Bot b : w.bots.values()) {
                w.members.add(new RunMember(b.id, b.id == 1, true, MechanicsPersona.PERFECT, 0, b.level));
            }
            for (int id : order) {
                w.next(id);
            }
            Map<String, Integer> claims = w.claims();
            if (first == null) {
                first = claims;
            }
            assertEquals(first, claims, "the same claims whichever bot was asked first: " + order[0]);
            assertEquals(claims.size(), claims.values().stream().distinct().count(), "no bot holds two slots");
        }
        Map<String, Integer> expected = new TreeMap<>();
        expected.put("claim:hit:201", 1);
        expected.put("claim:hit:202", 2);
        expected.put("claim:hit:203", 3);
        expected.put("claim:key:1", 4); // the highest level free bot hunts the level 65 mob
        expected.put("claim:key:2", 6);
        expected.put("claim:key:3", 5);
        assertEquals(expected, first);
    }

    @Test
    void everyBotHasWorkWhileAnythingIsOpen() {
        for (int n : new int[]{1, 2, 3, 5, 6, 9, 20}) {
            World w = world(n);
            for (int id = 1; id <= n; id++) {
                SolverStep s = w.next(id);
                assertFalse(s instanceof SolverStep.Wait, "bot " + id + " of " + n + " idles: " + s);
            }
        }
    }

    @Test
    void aClaimOfABotThatLeftIsReleasedAndTakenByAFreeBot() {
        World w = world(5);
        for (int id = 1; id <= 5; id++) {
            w.next(id);
        }
        assertEquals(1, w.claims().get("claim:hit:201"));
        w.members.removeIf(m -> m.characterId() == 1);
        w.bots.remove(1);
        w.remove(R410, 202); // bot 2's reactor is spent: it is free again
        w.next(3);
        assertFalse(w.claims().containsValue(1), "bot 1 is gone: " + w.claims());
        assertEquals(2, w.claims().get("claim:hit:201"), "the freed bot took the abandoned reactor");
    }

    // ------------------------------------------------------------------------------------- the sequence

    @Test
    void membersOnThePreviousStagesRoomsWalkIn() {
        World w = world(1);
        w.bots.get(1).map = GqSpearsSolver.STATUES;
        assertEquals(new SolverStep.ScriptedPortal("next00"), w.next(1));
        w.bots.get(1).map = GqSpearsSolver.ARRIVAL;
        assertEquals(new SolverStep.EnterPortal("next01"), w.next(1));
    }

    @Test
    void threeSparsOnThe410ReactorsAreHitOnePerBotThenLooted() {
        World w = world(3);
        for (Bot b : w.bots.values()) {
            b.map = R410;
        }
        List<Integer> oids = new ArrayList<>();
        for (int id = 1; id <= 3; id++) {
            SolverStep s = w.next(id);
            assertInstanceOf(SolverStep.HitReactorByOid.class, s);
            oids.add(((SolverStep.HitReactorByOid) s).objectId());
        }
        assertEquals(List.of(201, 202, 203), oids, "one reactor each");

        w.remove(R410, 201);
        w.floor.add(new Drop(R410, SPEAR));
        assertEquals(new SolverStep.Loot(SPEAR), w.next(1), "the spear that fell is picked up");

        w.floor.clear();
        w.bots.get(1).bag.put(SPEAR, 1);
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(1), "a carrier in 410 leaves for the hall");
        w.bots.get(1).map = HALL;
        assertEquals(new SolverStep.EnterPortal("in03"), w.next(1), "and from the hall takes the tower door");
    }

    @Test
    void theKeyIsHuntedOnTheOneMobCarriedToTheHallAndPlacedOnTheStonegate() {
        World w = world(2);
        w.on(R410).clear();
        w.bots.get(1).map = R420;
        w.bots.get(2).map = R420;
        w.bots.get(2).level = 40;
        w.members.set(1, new RunMember(2, false, true, MechanicsPersona.PERFECT, 0, 40));
        for (int id = 1; id <= 2; id++) {
            SolverStep s = w.next(id);
            SolverStep.Fight f = assertInstanceOf(SolverStep.Fight.class, s);
            assertEquals(List.of(GqSpearsSolver.KEY_MOB), f.mobIds());
            assertEquals(KEY, f.itemId());
            assertEquals(1, f.untilItemCount());
        }
        assertEquals(2, w.claims().get("claim:key:1"), "the higher level hunts first");

        w.bots.get(1).bag.put(KEY, 1);
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(1), "a carrier leaves 420");
        w.bots.get(1).map = HALL;
        SolverStep place = w.next(1);
        SolverStep.PlaceItem p = assertInstanceOf(SolverStep.PlaceItem.class, place);
        assertEquals(KEY, p.itemId());
        assertEquals(1, p.count());
        assertEquals("stonegate", p.reactor());
    }

    @Test
    void nobodyEntersTheStonegateDoorBeforeTheGateIsOpen() {
        World w = world(6);
        for (Bot b : w.bots.values()) {
            b.map = HALL;
        }
        for (int id = 1; id <= 6; id++) {
            assertFalse(w.next(id) instanceof SolverStep.ScriptedPortal, "stonegate is at state 0");
        }
        w.on(R410).clear();
        w.named(HALL, "stonegate").state = 1;
        w = withBotsAt(w, HALL, 3);
        for (int id = 1; id <= 3; id++) {
            assertEquals(new SolverStep.ScriptedPortal("in02"), w.next(id), "the open door is taken");
        }
    }

    private static World withBotsAt(World w, int map, int n) {
        for (int id = 1; id <= n; id++) {
            w.bots.get(id).map = map;
        }
        return w;
    }

    @Test
    void theGolemRoomHitsTheBoxPlacesTheRustyKeyAndWalksOnWhenTheGateIsOpen() {
        World w = world(2);
        w.on(R410).clear();
        w.named(HALL, "stonegate").state = 1;
        withBotsAt(w, R430, 2);
        assertEquals(new SolverStep.HitReactorByOid(302), w.next(1), "the buried box: hits, never a golem");
        assertEquals(new SolverStep.HitReactorByOid(302), w.next(2));

        w.bots.get(1).bag.put(RUSTY, 1);
        SolverStep.PlaceItem p = assertInstanceOf(SolverStep.PlaceItem.class, w.next(1));
        assertEquals(RUSTY, p.itemId());
        assertEquals("metalgate", p.reactor());

        w.bots.get(1).bag.clear();
        w.remove(R430, 302);
        w.named(R430, "metalgate").state = 1;
        assertEquals(new SolverStep.ScriptedPortal("in00"), w.next(1), "the metalgate is open: on to 431");
        w.bots.get(1).map = R431;
        assertEquals(new SolverStep.HitReactorByOid(401), w.next(1), "the fourth chained spear");

        w.bots.get(1).bag.put(SPEAR, 1);
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(1), "431 -> 430");
        w.bots.get(1).map = R430;
        assertEquals(new SolverStep.EnterPortal("out00"), w.next(1), "430 -> the hall");
    }

    @Test
    void theTowerIsClimbedTierByTierFromTheMembersOwnHeightAndTheSpearGoesOnTheAltar() {
        World w = world(1);
        w.on(R410).clear();
        Bot b = w.bots.get(1);
        b.bag.put(SPEAR, 1);
        b.map = TOWER;
        b.y = 499;
        assertEquals(new SolverStep.EnterPortal("h001"), w.next(1));
        for (GqSpearsSolver.TowerPortal p : GqSpearsSolver.TOWER_CHAIN) {
            b.x = p.x();
            b.y = p.y();
            assertEquals(new SolverStep.EnterPortal(p.name()), w.next(1), "at y " + p.y());
        }
        b.x = 691;
        b.y = -902; // h025: the top
        SolverStep.PlaceItem p = assertInstanceOf(SolverStep.PlaceItem.class, w.next(1));
        assertEquals(SPEAR, p.itemId());
        assertEquals(1, p.count());
        assertEquals("spear3", p.reactor(), "only spear3 is under the landing shaft");
        assertEquals(582, p.x());
        assertEquals(-1054, p.y());
        assertNull(GqSpearsSolver.towerStep(-902));
        assertEquals("h001", GqSpearsSolver.towerStep(504).name());
    }

    @Test
    void anAltarAtStateOneIsNeverFedAgain() {
        World w = world(1);
        w.on(R410).clear();
        Bot b = w.bots.get(1);
        b.bag.put(SPEAR, 1);
        b.map = TOWER;
        b.y = -902;
        int[] order = {3, 4, 2, 1};
        for (int filled = 0; filled < 4; filled++) {
            SolverStep.PlaceItem p = assertInstanceOf(SolverStep.PlaceItem.class, w.next(1));
            assertEquals("spear" + order[filled], p.reactor());
            w.named(TOWER, "spear" + order[filled]).state = 1;
            w.named(HALL, "speargate").state = filled + 1;
            w.memory.keySet().removeIf(k -> k.startsWith("claim:altar:"));
        }
        assertEquals(new SolverStep.Done(), w.next(1), "four altars filled: the stage is clear");
    }

    @Test
    void twoCarriersTakeTwoAltarsWhoeverIsAskedFirst() {
        for (int[] order : new int[][]{{1, 2}, {2, 1}}) {
            World w = world(2);
            w.on(R410).clear();
            for (Bot b : w.bots.values()) {
                b.bag.put(SPEAR, 1);
                b.map = TOWER;
                b.y = -902;
            }
            Map<Integer, String> got = new TreeMap<>();
            for (int id : order) {
                got.put(id, ((SolverStep.PlaceItem) w.next(id)).reactor());
            }
            assertEquals("spear3", got.get(1));
            assertEquals("spear4", got.get(2));
        }
    }

    // ------------------------------------------------------------------------------ protection and waits

    @Test
    void anUnprotectedMemberPutsTheEarringOnFirst() {
        World w = world(1);
        Bot b = w.bots.get(1);
        b.wearing = false;
        assertInstanceOf(SolverStep.Wait.class, w.next(1), "no earring carried: wait for it");
        b.bag.put(GqProtection.EARRING, 1);
        assertEquals(new SolverStep.EquipItem(GqProtection.EARRING,
                "the maps from 990000300 on take 30000 HP a tick without it"), w.next(1));
        b.map = TOWER;
        assertInstanceOf(SolverStep.EquipItem.class, w.next(1), "first in every room");
    }

    @Test
    void missingSpearsAreWaitedOnByNameThenTheRunEnds() {
        World w = world(1);
        w.named(HALL, "stonegate").state = 1;
        w.named(R430, "metalgate").state = 1;
        w.next(1); // the chained spears are seen standing
        w.on(R410).clear();
        w.on(R431).clear();
        w.remove(R430, 302);
        SolverStep s = w.next(1);
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, s);
        assertTrue(wait.why().contains("4001025") && wait.why().contains("9202001"), wait.why());
        assertTrue(wait.why().contains("live DB unverified"), wait.why());
        w.now += GqSpearsSolver.LOST_GRACE_MS;
        assertInstanceOf(SolverStep.Abandon.class, w.next(1), "a spear that cannot come any more ends the run");
    }

    @Test
    void aMissingStonegateKeyNamesTheItemAndTheMob() {
        World w = world(1);
        w.next(1); // the mob and the chained spears are seen
        w.keyMobAlive = 0;
        w.on(R410).clear();
        w.on(R431).clear();
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, w.next(1));
        assertTrue(wait.why().contains("4001026") && wait.why().contains("9300019"), wait.why());
        w.now += GqSpearsSolver.LOST_GRACE_MS;
        SolverStep.Abandon a = assertInstanceOf(SolverStep.Abandon.class, w.next(1));
        assertTrue(a.why().contains("4001026"), a.why());
    }

    @Test
    void aMissingRustyKeyNamesTheItemAndTheBox() {
        World w = world(1);
        w.named(HALL, "stonegate").state = 1;
        w.next(1); // the box is seen standing
        w.remove(R430, 302);
        w.on(R410).clear();
        w.on(R431).clear();
        SolverStep.Wait wait = assertInstanceOf(SolverStep.Wait.class, w.next(1));
        assertTrue(wait.why().contains("4001037") && wait.why().contains("9202002"), wait.why());
        w.now += GqSpearsSolver.LOST_GRACE_MS;
        assertInstanceOf(SolverStep.Abandon.class, w.next(1));
    }

    @Test
    void anUnreadableRoomIsNeverTakenForASpentOne() {
        World w = world(1);
        w.on(R410).clear();
        w.on(R431).clear();
        w.remove(R430, 302);
        w.keyMobAlive = 0;
        w.now = 10 * GqSpearsSolver.LOST_GRACE_MS;
        assertFalse(w.next(1) instanceof SolverStep.Abandon, "nothing was ever seen standing");
    }

    // ------------------------------------------------------------------------------- the whole stage

    private static void assertCleanClear(World w, int ticks) {
        assertTrue(ticks >= 0, "the stage did not clear; abandoned: " + w.abandoned + "; gate " + w.gate()
                + "; violations " + w.violations);
        assertEquals(4, w.gate());
        assertTrue(w.cleared());
        assertEquals(List.of(), w.violations);
        assertEquals(4, w.altarsPlaced.stream().distinct().count(), "four different altars: " + w.altarsPlaced);
        for (SolverStep s : w.issued) {
            if (s instanceof SolverStep.Fight f) {
                assertFalse(f.mobIds().contains(GqSpearsSolver.GOLEM), "a golem is never a target");
                assertEquals(List.of(GqSpearsSolver.KEY_MOB), f.mobIds());
            }
        }
    }

    @Test
    void sixBotsClearTheStageInAWorldThatGrantsEverythingOnlyWhenAppliedAndFewerStillFinish() {
        int[] counts = {1, 2, 3, 5, 6, 12};
        int[] used = new int[counts.length];
        for (int i = 0; i < counts.length; i++) {
            World w = world(counts[i]);
            used[i] = w.run(6_000);
            assertCleanClear(w, used[i]);
        }
        assertTrue(used[3] < used[0], "five bots are faster than one: " + used[3] + " vs " + used[0]);
    }

    @Test
    void unprotectedMembersEquipTheEarringFirstThenTheStageClears() {
        World w = world(3);
        for (Bot b : w.bots.values()) {
            b.wearing = false;
            b.bag.put(GqProtection.EARRING, 1);
        }
        assertCleanClear(w, w.run(6_000));
        assertTrue(w.bots.values().stream().allMatch(b -> b.wearing));
    }

    @Test
    void aWorldThatNeverDropsTheKeyStaysUnclearedAndSaysWhatIsMissing() {
        World w = world(6);
        w.keyDrops = false;
        assertEquals(-1, w.run(3_000));
        assertFalse(w.cleared());
        assertNotNull(w.abandoned);
        assertTrue(w.abandoned.contains("4001026") && w.abandoned.contains("9300019"), w.abandoned);
        assertEquals(List.of(), w.violations);
    }

    @Test
    void aWorldThatNeverDropsTheRustyKeyStaysUnclearedAndSaysWhatIsMissing() {
        World w = world(6);
        w.boxDrops = false;
        assertEquals(-1, w.run(3_000));
        assertFalse(w.cleared());
        assertNotNull(w.abandoned);
        assertTrue(w.abandoned.contains("4001037") && w.abandoned.contains("9202002"), w.abandoned);
        assertEquals(List.of(), w.violations);
    }

    @Test
    void aWorldThatNeverDropsASpearStaysUnclearedAndSaysWhatIsMissing() {
        World w = world(6);
        w.spearDrops = false;
        assertEquals(-1, w.run(3_000));
        assertFalse(w.cleared());
        assertNotNull(w.abandoned);
        assertTrue(w.abandoned.contains("4001025") && w.abandoned.contains("9202001"), w.abandoned);
        assertEquals(List.of(), w.violations);
    }

    @Test
    void anAltarThatNeverActsLeavesTheStageUnclearedWithoutBreakingARule() {
        World w = world(6);
        w.altarsAct = false;
        assertEquals(-1, w.run(1_500));
        assertFalse(w.cleared());
        assertEquals(0, w.gate());
        assertEquals(List.of(), w.violations, "the retake of a spear nobody accepted breaks no rule");
    }

    // ------------------------------------------------------------------------------ the WZ it was built from

    private static final Path TOWER_WZ = Path.of(
            "/root/work/SlumsMS-152-maps/wz/Map.wz/Map/Map9/990000440.img.xml");

    /** The tower chain checked against the map file itself when the WZ checkout is on this machine. */
    @Test
    void theTowerChainIsTheOneInTheMapFile() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(TOWER_WZ), "no WZ checkout here");
        Element root = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(TOWER_WZ.toFile()).getDocumentElement();
        Map<String, int[]> pos = new HashMap<>();
        Map<String, String> tn = new HashMap<>();
        Map<String, Integer> tm = new HashMap<>();
        NodeList top = root.getChildNodes();
        for (int i = 0; i < top.getLength(); i++) {
            Node n = top.item(i);
            if (n instanceof Element e && "portal".equals(e.getAttribute("name"))) {
                NodeList ps = e.getChildNodes();
                for (int j = 0; j < ps.getLength(); j++) {
                    if (!(ps.item(j) instanceof Element p)) {
                        continue;
                    }
                    Map<String, String> a = new HashMap<>();
                    NodeList cs = p.getChildNodes();
                    for (int k = 0; k < cs.getLength(); k++) {
                        if (cs.item(k) instanceof Element c) {
                            a.put(c.getAttribute("name"), c.getAttribute("value"));
                        }
                    }
                    String name = a.get("pn");
                    pos.put(name, new int[]{Integer.parseInt(a.get("x")), Integer.parseInt(a.get("y"))});
                    tn.put(name, a.getOrDefault("tn", ""));
                    tm.put(name, Integer.parseInt(a.get("tm")));
                }
            }
        }
        List<GqSpearsSolver.TowerPortal> chain = GqSpearsSolver.TOWER_CHAIN;
        assertTrue(pos.get("st00")[1] > chain.get(0).y(), "the ground is below the first tier");
        for (int k = 0; k < chain.size(); k++) {
            GqSpearsSolver.TowerPortal p = chain.get(k);
            assertEquals(p.x(), pos.get(p.name())[0], p.name() + " x");
            assertEquals(p.y(), pos.get(p.name())[1], p.name() + " y");
            assertEquals(TOWER, tm.get(p.name()), p.name() + " is a same-map portal");
            String landing = tn.get(p.name());
            assertFalse(landing.isEmpty() || landing.equals("st00"), p.name() + " must not send back to the start");
            if (k + 1 < chain.size()) {
                int dy = Math.abs(pos.get(landing)[1] - chain.get(k + 1).y());
                assertTrue(dy <= GqSpearsSolver.TOWER_SLACK, "landing " + landing + " is on the tier of "
                        + chain.get(k + 1).name());
            } else {
                assertEquals(999_999_999, tm.get(landing), "the last landing has no target: the top");
            }
        }
        // and the test world's own portal table is the file's
        for (String[] row : TOWER_PORTALS) {
            assertEquals(Integer.parseInt(row[1]), pos.get(row[0])[0], row[0]);
            assertEquals(Integer.parseInt(row[2]), pos.get(row[0])[1], row[0]);
            assertEquals(row[3], tn.get(row[0]), row[0]);
        }
    }
}
