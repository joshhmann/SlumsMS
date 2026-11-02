package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Ticket 164: {@code scripts/event/CWKPQ.js} deploys 8 of the 35 sigil tiles that map 610030400 places and leaves the
 * rest as decoys. While {@code ReactorFactory.getReactor} forgot their state 0 those decoys were dead by accident;
 * once the map-load path registers state 0 as inert (type 999) a click on a decoy reaches
 * {@code Reactor.hitReactor}'s inert branch, which calls {@code act()} - and the decoy's script counts "glpq4"/"glpq5"
 * too, so a party could finish the stage on a decoy. The event script now deactivates them; this runs the real file in
 * the server's own Graal setup against a fake map carrying the 45 placements map 610030400 really has.
 */
class CwkpqReactorDeployTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** A placed reactor as the event script sees it: name, id, state, alive. */
    public static final class FakeReactor {
        private final String name;
        private final int id;
        private int state;
        private boolean alive = true;

        FakeReactor(String name, int id) {
            this.name = name;
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public int getId() {
            return id;
        }

        public int getState() {
            return state;
        }

        public void setState(int state) {
            this.state = state;
        }

        public boolean isAlive() {
            return alive;
        }

        public void setAlive(boolean alive) {
            this.alive = alive;
        }
    }

    /** The three calls generateMapReactors makes on a MapleMap, recorded. */
    public static final class FakeMap {
        final Map<String, FakeReactor> reactors = new LinkedHashMap<>();
        final List<String> lookedUp = new ArrayList<>();
        List<Object> shuffled;
        int shuffles;

        public FakeReactor getReactorByName(String name) {
            lookedUp.add(name);
            return reactors.get(name);
        }

        public List<Object> getReactors() {
            return new ArrayList<>(reactors.values());
        }

        public void shuffleReactors(List<Object> list) {
            shuffles++;
            shuffled = new ArrayList<>(list);
        }
    }

    private static FakeMap mapOf610030400() {
        FakeMap map = new FakeMap();
        map.reactors.put("1_7", new FakeReactor("1_7", 6109025));
        map.reactors.put("3_5", new FakeReactor("3_5", 6109027));
        map.reactors.put("6_2", new FakeReactor("6_2", 6109026));
        map.reactors.put("b_h", new FakeReactor("b_h", 6109021));
        map.reactors.put("d_f", new FakeReactor("d_f", 6109023));
        map.reactors.put("e_4", new FakeReactor("e_4", 6109024));
        map.reactors.put("g_c", new FakeReactor("g_c", 6109022));
        map.reactors.put("i_a", new FakeReactor("i_a", 6109014));
        map.reactors.put("stirge0", new FakeReactor("stirge0", 6109013));
        map.reactors.put("stirge1", new FakeReactor("stirge1", 6109013));
        int[] sigilIds = {6109016, 6109017, 6109018, 6109019, 6109020};
        for (int job = 0; job < sigilIds.length; job++) {
            for (char suffix = 'a'; suffix <= 'e'; suffix++) {
                String name = "4skill" + job + suffix;
                map.reactors.put(name, new FakeReactor(name, sigilIds[job]));
            }
        }
        int[] fakeIds = {6108000, 6108001, 6108005};
        int[] fakeCycles = {0, 1, 2, 0, 1, 1, 2, 0, 1, 2};
        for (int i = 0; i < 10; i++) {
            String name = "4fake" + (i / 5) + (char) ('a' + (i % 5));
            map.reactors.put(name, new FakeReactor(name, fakeIds[fakeCycles[i]]));
        }
        return map;
    }

    private static FakeMap deploy() {
        ScriptEngine engine = new Loader().load("event/CWKPQ.js");
        assertNotNull(engine, "scripts/event/CWKPQ.js exists and evaluates");
        FakeMap map = mapOf610030400();
        assertEquals(45, map.reactors.size(), "map 610030400 really places 45 reactors");
        try {
            ((Invocable) engine).invokeFunction("generateMapReactors", map);
        } catch (Exception e) {
            fail(e);
        }
        return map;
    }

    private static List<FakeReactor> sigilTiles(FakeMap map) {
        return map.reactors.values().stream()
                .filter(r -> r.getName().startsWith("4skill") || r.getName().startsWith("4fake"))
                .collect(Collectors.toList());
    }

    @Test
    void eightSigilTilesAreDeployedAndTheirStateIsSetToOne() {
        FakeMap map = deploy();
        List<FakeReactor> deployed = sigilTiles(map).stream().filter(r -> r.getState() == 1).collect(Collectors.toList());

        assertEquals(8, deployed.size(), "the deploy table has eight slots, one tile each");
        assertEquals(8, map.lookedUp.size(), "each deployed tile is looked up once by name");
        assertEquals(8, deployed.stream().map(FakeReactor::getName).distinct().count(), "no tile is deployed twice");
        assertEquals(1, map.shuffles, "the deployed tiles still get their positions shuffled");
        assertEquals(8, map.shuffled.size());
    }

    @Test
    void everyDecoySigilIsDeactivated() {
        FakeMap map = deploy();
        List<FakeReactor> deployed = sigilTiles(map).stream().filter(r -> r.getState() == 1).toList();
        List<FakeReactor> decoys = sigilTiles(map).stream().filter(r -> r.getState() != 1).toList();

        assertEquals(27, decoys.size(), "35 placed tiles, 8 of them deployed");
        for (FakeReactor decoy : decoys) {
            assertFalse(decoy.isAlive(),
                    "decoy sigil " + decoy.getName() + " must answer no hit, or its script counts glpq4/glpq5");
        }
        for (FakeReactor tile : deployed) {
            assertTrue(tile.isAlive(), "the deployed tile " + tile.getName() + " must stay hittable");
        }
    }

    @Test
    void theFlameAndStirgeSwitchesKeepTheirOwnState() {
        FakeMap map = deploy();
        String[] switches = {"1_7", "3_5", "6_2", "b_h", "d_f", "e_4", "g_c", "i_a", "stirge0", "stirge1"};

        for (String name : switches) {
            FakeReactor placed = map.reactors.get(name);
            assertTrue(placed.isAlive(), "reactor " + name + " is a touch-driven flame/stirge, not a sigil decoy");
            assertEquals(0, placed.getState(), "and its state is not touched by the sigil deploy");
        }
    }
}
