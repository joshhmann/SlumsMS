package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.7 showa-room-edges: two defects in the Showa expedition.
 *
 * <p>The run is created as "Showa" + channel (ShowaBattle setup), but Konpei (9120201) looked the instance up as
 * "Showa Gang" + channel, so the lookup was always null and a registered member who walked in after the start was
 * told the expedition had already begun.
 *
 * <p>And the 15-second respawnStages kept running after the clear, so the trash mobs refilled 801040100 while the
 * players stood there claiming their rewards.
 */
class ShowaRoomEdgesTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /* ---- NPC 9120201 fakes ------------------------------------------------------------------------------------- */

    public static final class FakeClient {
        public int getChannel() {
            return 3;
        }
    }

    public static final class FakeChar {
        public int getLevel() {
            return 150;   // SHOWA is 100-255
        }

        public FakeClient getClient() {
            return new FakeClient();
        }

        public String getName() {
            return "Tester";
        }
    }

    public static final class FakeLeader {
        public String getName() {
            return "Leader";
        }
    }

    public static final class FakeExpedition {
        public final List<FakeChar> members = new ArrayList<>();

        public boolean isLeader(FakeChar p) {
            return false;
        }

        public boolean isRegistering() {
            return false;
        }

        public boolean isInProgress() {
            return true;
        }

        public boolean contains(FakeChar p) {
            return members.contains(p);
        }

        public FakeLeader getLeader() {
            return new FakeLeader();
        }

        public String addMember(FakeChar p) {
            return "Added to the expedition.";
        }
    }

    public static final class FakeNpcEim {
        public final List<FakeChar> registered = new ArrayList<>();
        public String props = "canJoin=1";

        public int getIntProperty(String key) {
            return "canJoin".equals(key) ? 1 : 0;
        }

        public void registerPlayer(FakeChar p) {
            registered.add(p);
        }
    }

    public static final class FakeEm {
        public final Map<String, Object> instances = new HashMap<>();
        public final List<String> asked = new ArrayList<>();
        public String made;
        public java.util.function.Supplier<Object> factory = FakeNpcEim::new;

        public Object getInstance(String name) {
            asked.add(name);
            return instances.get(name);
        }

        public Object newInstance(String name) {
            made = name;
            Object eim = factory.get();
            instances.put(name, eim);
            return eim;
        }

        public void setProperty(String k, Object v) {
        }
    }

    public static final class FakeCm {
        public FakeChar player = new FakeChar();
        public FakeExpedition expedition = new FakeExpedition();
        public FakeEm em;
        public final List<String> ok = new ArrayList<>();
        public final List<String> simple = new ArrayList<>();
        public Object expeditionAskedFor;
        public boolean disposed;

        public FakeChar getPlayer() {
            return player;
        }

        public FakeExpedition getExpedition(Object type) {
            expeditionAskedFor = type;
            return expedition;
        }

        public FakeEm getEventManager(String name) {
            return em;
        }

        public void sendOk(String text) {
            ok.add(text);
        }

        public void sendSimple(String text) {
            simple.add(text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    /** Runs Konpei's first menu step as a registered non-leader member of a run that is already in progress. */
    private static FakeCm talkToKonpei(FakeCm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/9120201.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        ((Invocable) engine).invokeFunction("action", 1, 0, 0);
        return cm;
    }

    /* ---- ShowaBattle fakes ------------------------------------------------------------------------------------- */

    public static final class FakeMap {
        public int respawns;
        public Integer resetLevel;

        public void instanceMapRespawn() {
            respawns++;
        }

        public void resetPQ(int level) {
            resetLevel = level;
        }
    }

    public static final class FakeBattleEim {
        public final String name;
        public final FakeMap map = new FakeMap();
        public final List<String> scheduled = new ArrayList<>();
        public boolean cleared;

        public FakeBattleEim() {
            this("Showa3");
        }

        public FakeBattleEim(String name) {
            this.name = name;
        }

        public FakeMap getInstanceMap(int id) {
            return map;
        }

        public boolean isEventCleared() {
            return cleared;
        }

        public void schedule(String fn, long ms) {
            scheduled.add(fn + "@" + ms);
        }

        public void startEventTimer(long ms) {
        }

        public void setProperty(String k, Object v) {
        }

        public void setIntProperty(String k, Object v) {
        }

        public void setEventRewards(Object lvl, Object items, Object qty) {
        }

        public void setEventClearStageExp(Object exp) {
        }

        public void setEventClearStageMeso(Object meso) {
        }

        public void setExclusiveItems(Object items) {
        }
    }

    private static Object respawn(FakeBattleEim eim) throws Exception {
        ScriptEngine engine = new Loader().load("event/ShowaBattle.js");
        assertNotNull(engine);
        return ((Invocable) engine).invokeFunction("respawnStages", eim);
    }

    /* ---- tests -------------------------------------------------------------------------------------------------- */

    @Test
    void theRecruiterLooksUpTheInstanceTheEventCreates() throws Exception {
        ScriptEngine engine = new Loader().load("event/ShowaBattle.js");
        assertNotNull(engine);
        FakeEm em = new FakeEm();
        em.factory = FakeBattleEim::new;
        engine.put("em", em);
        ((Invocable) engine).invokeFunction("setup", 3);
        assertEquals("Showa3", em.made);

        FakeCm cm = new FakeCm();
        cm.em = new FakeEm();
        cm.em.newInstance(em.made);
        cm.expedition.members.add(cm.player);
        talkToKonpei(cm);

        assertEquals(List.of("Showa3"), cm.em.asked, "the recruiter must ask for the name the event registered");
        assertEquals(List.of(cm.player), ((FakeNpcEim) cm.em.instances.get("Showa3")).registered);
    }

    @Test
    void aRegisteredMemberWhoArrivesLateIsWarpedIn() throws Exception {
        FakeCm cm = new FakeCm();
        cm.em = new FakeEm();
        cm.em.instances.put("Showa3", new FakeNpcEim());
        cm.expedition.members.add(cm.player);

        talkToKonpei(cm);

        FakeNpcEim eim = (FakeNpcEim) cm.em.instances.get("Showa3");
        assertEquals(List.of(cm.player), eim.registered);
        assertTrue(cm.ok.isEmpty(), cm.ok.toString());
        assertTrue(cm.disposed);
    }

    @Test
    void anUnregisteredRunStillTellsTheMemberToWait() throws Exception {
        FakeCm cm = new FakeCm();
        cm.em = new FakeEm();   // nothing registered under the event's name
        cm.expedition.members.add(cm.player);

        talkToKonpei(cm);

        assertEquals(List.of("Showa3"), cm.em.asked);
        assertTrue(cm.ok.get(0).contains("already started"), cm.ok.toString());
    }

    @Test
    void afterTheClearTheRoomStopsRespawningAndStopsTheSchedule() throws Exception {
        FakeBattleEim eim = new FakeBattleEim();
        eim.cleared = true;

        respawn(eim);

        assertEquals(0, eim.map.respawns, "no mobs may refill the cleared room");
        assertTrue(eim.scheduled.isEmpty(), "the 15 s respawn chain must stop: " + eim.scheduled);
    }

    @Test
    void beforeTheClearTheRoomStillRespawning() throws Exception {
        FakeBattleEim eim = new FakeBattleEim();

        respawn(eim);

        assertEquals(1, eim.map.respawns);
        assertEquals(List.of("respawnStages@15000"), eim.scheduled);
    }

    @Test
    void setupStartsTheRespawnChainForAFreshRun() throws Exception {
        ScriptEngine engine = new Loader().load("event/ShowaBattle.js");
        assertNotNull(engine);
        FakeEm em = new FakeEm();
        em.factory = FakeBattleEim::new;
        engine.put("em", em);
        ((Invocable) engine).invokeFunction("setup", 3);

        FakeBattleEim eim = (FakeBattleEim) em.instances.get("Showa3");
        assertNotNull(eim, "setup must register the run it creates");
        assertEquals(List.of("respawnStages@15000"), eim.scheduled);
    }
}
