package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.5.2: the Papulatus expedition (PapulatusBattle.js, recruiter 2041024, exit NPC Tana 2041025). */
class PapulatusScriptsTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMap {
        public Integer resetLevel;

        public void resetPQ(int level) {
            resetLevel = level;
        }
    }

    public static final class FakeEim {
        public final String name;
        public final Map<String, String> props = new HashMap<>();
        public final FakeMap map = new FakeMap();
        public boolean cleared;
        public final List<FakePlayer> players = new ArrayList<>();

        FakeEim(String name) {
            this.name = name;
        }

        public void setProperty(String k, Object v) {
            props.put(k, String.valueOf(v));
        }

        public String getProperty(String k) {
            return props.get(k);
        }

        public FakeMap getInstanceMap(int id) {
            return map;
        }

        public void startEventTimer(long ms) {
        }

        public void setEventRewards(int lvl, Object items, Object qty) {
        }

        public void setEventClearStageExp(Object exp) {
        }

        public void setExclusiveItems(Object items) {
        }

        public boolean isEventCleared() {
            return cleared;
        }

        public List<FakePlayer> getPlayers() {
            return players;
        }
    }

    public static final class FakeEm {
        public FakeEim made;

        public FakeEim newInstance(String name) {
            made = new FakeEim(name);
            return made;
        }

        public void setProperty(String k, Object v) {
        }
    }

    @Test
    void anExpeditionRunIsNamedForItsChannelAndLetsLateMembersIn() throws Exception {
        ScriptEngine engine = new Loader().load("event/PapulatusBattle.js");
        assertNotNull(engine);
        FakeEm em = new FakeEm();
        engine.put("em", em);
        ((Invocable) engine).invokeFunction("setup", 3);   // EventManager.startInstance(Expedition): the channel only
        assertEquals("Papulatus3", em.made.name, "2041024 looks it up as expedName + channel");
        assertEquals("1", em.made.props.get("canJoin"));
        assertEquals(1, em.made.map.resetLevel, "not scaled by the channel number");
    }

    @Test
    void aPartyRunStillUsesItsLobby() throws Exception {
        ScriptEngine engine = new Loader().load("event/PapulatusBattle.js");
        FakeEm em = new FakeEm();
        engine.put("em", em);
        ((Invocable) engine).invokeFunction("setup", 1, 4242);
        assertEquals("Papulatus4242", em.made.name);
        assertNull(em.made.props.get("canJoin"));
    }

    // --- Tana (2041025), the way out ---

    public static final class FakeApi {
        public final Map<Integer, Integer> items = new HashMap<>();

        public void gainItem(int id, int qty) {
            items.merge(id, qty, Integer::sum);
        }
    }

    public static final class FakePlayer {
        public final FakeApi api = new FakeApi();

        public FakeApi getAbstractPlayerInteraction() {
            return api;
        }
    }

    public static final class TanaCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public FakeEim eim;

        public FakeEim getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return 220080001;
        }

        public void warp(int map) {
            warpedTo = map;
        }
    }

    private static TanaCm talkAndAccept(FakeEim eim) throws Exception {
        TanaCm cm = new TanaCm();
        cm.eim = eim;
        ScriptEngine engine = new Loader().load("npc/2041025.js");
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        assertNotNull(cm.yesNo);
        npc.invokeFunction("action", 1, 0, 0);
        return cm;
    }

    @Test
    void withNoRunningInstanceTanaStillLetsYouOut() throws Exception {
        TanaCm cm = talkAndAccept(null);
        assertEquals(220080000, cm.warpedTo);
        assertTrue(cm.disposed);
    }

    @Test
    void afterAClearTheTalkEndsAndTheElixirsAreGivenOnce() throws Exception {
        FakeEim eim = new FakeEim("Papulatus1");
        eim.cleared = true;
        FakePlayer a = new FakePlayer(), b = new FakePlayer();
        eim.players.add(a);
        eim.players.add(b);
        TanaCm cm = talkAndAccept(eim);
        assertTrue(cm.disposed, "a cleared run used to leave the conversation open");
        assertEquals(220080000, cm.warpedTo);
        talkAndAccept(eim);
        assertEquals(100, a.api.items.get(2000005));
        assertEquals(100, b.api.items.get(2000005));
    }

    @Test
    void leavingEarlyGivesNothing() throws Exception {
        FakeEim eim = new FakeEim("Papulatus1");
        FakePlayer a = new FakePlayer();
        eim.players.add(a);
        TanaCm cm = talkAndAccept(eim);
        assertTrue(cm.disposed);
        assertTrue(a.api.items.isEmpty());
    }
}
