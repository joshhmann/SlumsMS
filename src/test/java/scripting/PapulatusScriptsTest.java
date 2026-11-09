package scripting;

import client.Character;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 1.5.2: the Papulatus expedition (PapulatusBattle.js, recruiter 2041024, exit NPC Tana 2041025). */
class PapulatusScriptsTest {

    private MockedStatic<ItemInformationProvider> itemData;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        BossRewardBagTestSupport.loadItemProviderClass();
    }

    @BeforeEach
    void stubItems() {
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, java.util.Map.of());
    }

    @AfterEach
    void closeItems() {
        itemData.close();
    }

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
        public final List<Object> players = new ArrayList<>();

        public FakeEim(String name) {
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

        public List<Object> getPlayers() {
            return players;
        }

        public String sendDmgDealt(int world) {
            return "";
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

    public static final class TanaCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public FakeEim eim;
        public Character player;

        public Character getPlayer() {
            return player;
        }

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

    private static TanaCm talkAndAccept(FakeEim eim, Character player) throws Exception {
        TanaCm cm = new TanaCm();
        cm.eim = eim;
        cm.player = player;
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
        TanaCm cm = talkAndAccept(null, new BossRewardBagTestSupport.Member(1, mock(World.class)).chr);
        assertEquals(220080000, cm.warpedTo);
        assertTrue(cm.disposed);
    }

    @Test
    void afterAClearTheTalkEndsAndTheElixirsAreGivenOnce() throws Exception {
        FakeEim eim = new FakeEim("Papulatus1");
        eim.cleared = true;
        BossRewardBagTestSupport.Member a = new BossRewardBagTestSupport.Member(11, mock(World.class));
        BossRewardBagTestSupport.Member b = new BossRewardBagTestSupport.Member(12, mock(World.class));
        eim.players.add(a.chr);
        eim.players.add(b.chr);
        TanaCm cm = talkAndAccept(eim, new BossRewardBagTestSupport.Member(10, mock(World.class)).chr);
        assertTrue(cm.disposed, "a cleared run used to leave the conversation open");
        assertEquals(220080000, cm.warpedTo);
        talkAndAccept(eim, new BossRewardBagTestSupport.Member(10, mock(World.class)).chr);
        assertEquals(100, a.countEverywhere(2000005), "the talker's offer pays everyone present once");
        assertEquals(100, b.countEverywhere(2000005));
    }

    @Test
    void leavingEarlyGivesNothing() throws Exception {
        FakeEim eim = new FakeEim("Papulatus1");
        BossRewardBagTestSupport.Member a = new BossRewardBagTestSupport.Member(21, mock(World.class));
        eim.players.add(a.chr);
        TanaCm cm = talkAndAccept(eim, new BossRewardBagTestSupport.Member(20, mock(World.class)).chr);
        assertTrue(cm.disposed);
        assertEquals(0, a.countEverywhere(2000005));
    }
}
