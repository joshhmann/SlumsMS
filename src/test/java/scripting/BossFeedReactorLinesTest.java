package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.7 boss-feed-lines: the summon lines that live in the reactor scripts, not the event scripts. Zakum's altar
 * (2111001, also Chaos Zakum's) and Horntail's (2401000) each write one observe-only line through eim.feed.
 */
class BossFeedReactorLinesTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeLeader {
        public String getName() {
            return "AqLeader";
        }
    }

    public static final class FakeEim {
        public final List<String> lines = new ArrayList<>();
        public final List<String> props = new ArrayList<>();

        public void feed(String text) {
            lines.add(text);
        }

        public FakeLeader getLeader() {
            return new FakeLeader();
        }

        public int getPlayerCount() {
            return 6;
        }

        public void setProperty(String k, String v) {
            props.add(k + "=" + v);
        }

        public void restartEventTimer(long ms) {
        }
    }

    public static final class FakePlayer {
        final FakeEim eim;

        FakePlayer(FakeEim eim) {
            this.eim = eim;
        }

        public FakeEim getEventInstance() {
            return eim;
        }
    }

    public static final class FakeMap {
        final int id;

        FakeMap(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }

        public Object getMonsterById(int id) {
            return null;
        }

        public void spawnHorntailOnGroundBelow(java.awt.Point p) {
        }
    }

    public static final class FakeReactor {
        final FakeMap map;

        FakeReactor(int mapId) {
            map = new FakeMap(mapId);
        }

        public FakeMap getMap() {
            return map;
        }
    }

    public static final class FakeRm {
        public final FakeEim eim = new FakeEim();
        final FakeReactor reactor;
        public final List<Integer> spawned = new ArrayList<>();

        FakeRm(int mapId) {
            reactor = new FakeReactor(mapId);
        }

        public FakePlayer getPlayer() {
            return new FakePlayer(eim);
        }

        public FakeEim getEventInstance() {
            return eim;
        }

        public FakeReactor getReactor() {
            return reactor;
        }

        public void changeMusic(String m) {
        }

        public void spawnFakeMonster(int id) {
            spawned.add(id);
        }

        public void spawnMonster(int id) {
            spawned.add(id);
        }

        public void createMapMonitor(int map, String portal) {
        }

        public void mapMessage(int type, String text) {
        }
    }

    private static void act(String script, FakeRm rm) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine);
        engine.put("rm", rm);
        ((Invocable) engine).invokeFunction("act");
    }

    @Test
    void zakumsAltarWritesTheSpawnLine() throws Exception {
        FakeRm rm = new FakeRm(280030000);
        act("reactor/2111001.js", rm);
        assertEquals(List.of("RUN boss zakum spawn: body 8800000 and 8 arms on 280030000, party 6 [zakum, leader AqLeader]"),
                rm.eim.lines);
        assertEquals(9, rm.spawned.size(), "the body and 8 arms still spawn");
    }

    @Test
    void chaosZakumsAltarWritesItsOwnSpawnLine() throws Exception {
        FakeRm rm = new FakeRm(280030001);
        act("reactor/2111001.js", rm);
        assertEquals(List.of("RUN boss chaoszakum spawn: body 8800100 and 8 arms on 280030001, party 6 [chaoszakum, leader AqLeader]"),
                rm.eim.lines);
    }

    @Test
    void horntailsReactorWritesTheMainStartLine() throws Exception {
        FakeRm rm = new FakeRm(240060200);
        act("reactor/2401000.js", rm);
        assertEquals(List.of("RUN boss horntail main start: body 8810018 and 8 parts on 240060200, clock 60 min [horntail, leader AqLeader]"),
                rm.eim.lines);
    }
}
