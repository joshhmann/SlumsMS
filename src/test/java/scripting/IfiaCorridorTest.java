package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.6.1 (owner 8 Nov 2026): Ifia (2161001) offers the way back to Von Leon's corridor 211070000 once "Defeat the
 * Lion King" (3171) is started or done. Before, only quests 3173-3176 sent you there, and the one Return Scroll from
 * 3170 was the only other way, so after a first fight nobody could reach the recruiter again.
 */
class IfiaCorridorTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class IfiaCm {
        public final Set<Integer> started = new HashSet<>();
        public final Set<Integer> completed = new HashSet<>();
        public String simple;
        public Integer warpedTo;
        public String portal;
        public boolean disposed;

        public boolean isQuestStarted(int quest) {
            return started.contains(quest);
        }

        public boolean isQuestCompleted(int quest) {
            return completed.contains(quest);
        }

        public void sendSimple(String text) {
            simple = text;
        }

        public void warp(int map, String portal) {
            warpedTo = map;
            this.portal = portal;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable talk(IfiaCm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/2161001.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        return npc;
    }

    @Test
    void afterTheLionKingQuestSheSendsYouBackToTheCorridor() throws Exception {
        for (boolean done : new boolean[]{false, true}) {
            IfiaCm cm = new IfiaCm();
            (done ? cm.completed : cm.started).add(3171);
            Invocable npc = talk(cm);
            assertTrue(cm.simple.contains("#L1#Take me to the Audience Room corridor.#l"), cm.simple);
            assertTrue(cm.simple.contains("#L0#Take me to the Rose Garden.#l"), "the Rose Garden stays: " + cm.simple);
            npc.invokeFunction("action", 1, 4, 1);
            assertEquals(211070000, cm.warpedTo, "3171 " + (done ? "done" : "started"));
            assertEquals("west00", cm.portal);
            assertTrue(cm.disposed);
        }
    }

    @Test
    void withoutTheQuestOnlyTheRoseGardenIsOffered() throws Exception {
        IfiaCm cm = new IfiaCm();
        cm.completed.add(3170);
        Invocable npc = talk(cm);
        assertFalse(cm.simple.contains("Audience Room"), cm.simple);
        npc.invokeFunction("action", 1, 4, 1);
        assertNull(cm.warpedTo, "a forged choice 1 goes nowhere");

        IfiaCm rose = new IfiaCm();
        npc = talk(rose);
        npc.invokeFunction("action", 1, 4, 0);
        assertEquals(211080000, rose.warpedTo);
    }

    @Test
    void duringTheQueensQuestsSheStillSendsYouStraightThere() throws Exception {
        IfiaCm cm = new IfiaCm();
        cm.started.add(3174);
        talk(cm);
        assertEquals(211070000, cm.warpedTo);
        assertNull(cm.simple);
    }
}
