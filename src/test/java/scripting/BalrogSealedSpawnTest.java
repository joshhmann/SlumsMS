package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The first Balrog form schedules spawnSealedBalrog() ten seconds after it dies. spawnSealedBalrog() read
 * LifeFactory from spawnBalrog()'s function scope, so under GraalJS it threw ReferenceError and the sealed
 * Balrog (8830003 / 8830010) never appeared. These scripts use the instance manager's own accessor instead.
 */
class BalrogSealedSpawnTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** Stands in for LifeFactory.getMonster(mid): the engine only needs an object to hand to the map. */
    public static final class FakeMonster {
        private final int id;

        FakeMonster(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    /** Records what spawnSealedBalrog() spawns on the entry map. */
    public static final class FakeMap {
        public final List<Integer> spawned = new ArrayList<>();
        public Point at = null;

        public void spawnMonsterOnGroundBelow(Object monster, Object point) {
            spawned.add(((FakeMonster) monster).getId());
            at = (Point) point;
        }
    }

    public static final class FakeEim {
        private final FakeMap map = new FakeMap();
        public final List<Integer> requested = new ArrayList<>();

        public FakeMap getInstanceMap(int mapId) {
            return map;
        }

        public FakeMonster getMonster(int mid) {
            requested.add(mid);
            return new FakeMonster(mid);
        }

        public FakeMap map() {
            return map;
        }
    }

    @ParameterizedTest(name = "{0} spawns sealed Balrog {1}")
    @CsvSource({"event/BalrogBattle.js, 8830003", "event/BalrogBattle_Easy.js, 8830010"})
    void spawnSealedBalrogSpawnsOnEntryMap(String script, int sealedBalrog) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script + " evaluates");
        FakeEim eim = new FakeEim();

        ((Invocable) engine).invokeFunction("spawnSealedBalrog", eim);

        assertEquals(List.of(sealedBalrog), eim.map().spawned, script + " spawns the sealed Balrog");
        assertNotNull(eim.map().at, script + " spawns it at a ground point");
        assertEquals(412, eim.map().at.x, script + " spawn x");
        assertEquals(258, eim.map().at.y, script + " spawn y");
    }
}
