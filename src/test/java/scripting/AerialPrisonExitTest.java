package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.7 aerial-prison-dead-end (live bug B-26, 9 Nov 2026): the Aerial Prison's portal wanted Prison Key 4032860,
 * which nothing gives, so a player there had no way on. Without the key it now sends a character on or past "Defeat
 * the Lion King" (3171) to the Audience Room corridor and lets anyone else out to the tower roof.
 */
class AerialPrisonExitTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        final Set<Integer> items;

        FakePlayer(Set<Integer> items) {
            this.items = items;
        }

        public boolean haveItem(int id) {
            return items.contains(id);
        }

        public Object getAbstractPlayerInteraction() {   // the old script asked for it
            return null;
        }
    }

    public static final class FakePi {
        public final Set<Integer> items = new HashSet<>();
        public final Set<Integer> started = new HashSet<>();
        public final Set<Integer> completed = new HashSet<>();
        public final List<String> messages = new ArrayList<>();
        public Integer warpedTo;
        public Object portal;
        public final List<Integer> gained = new ArrayList<>();

        public FakePlayer getPlayer() {
            return new FakePlayer(items);
        }

        public boolean isQuestStarted(int q) {
            return started.contains(q);
        }

        public boolean isQuestCompleted(int q) {
            return completed.contains(q);
        }

        public void gainItem(int id, int qty) {
            gained.add(id * qty);
        }

        public void playPortalSound() {
        }

        public void playerMessage(int type, String text) {
            messages.add(text);
        }

        public void warp(int map, int portal) {
            warpedTo = map;
            this.portal = portal;
        }

        public void warp(int map, String portal) {
            warpedTo = map;
            this.portal = portal;
        }
    }

    private static Object enter(FakePi pi) throws Exception {
        ScriptEngine engine = new Loader().load("portal/211070101.1.js");
        assertNotNull(engine);
        return ((Invocable) engine).invokeFunction("enter", pi);
    }

    @Test
    void aCharacterOnTheLionKingQuestGoesToTheCorridorWithoutAKey() throws Exception {
        for (boolean done : new boolean[]{false, true}) {
            FakePi pi = new FakePi();
            (done ? pi.completed : pi.started).add(3171);
            assertEquals(Boolean.TRUE, enter(pi));
            assertEquals(211070000, pi.warpedTo, "3171 " + (done ? "done" : "started"));
            assertEquals("west00", pi.portal);
            assertTrue(pi.messages.get(0).contains("recruiter"), pi.messages.toString());
        }
    }

    @Test
    void anyoneElseIsLetOutToTheRoofAndToldTheWay() throws Exception {
        FakePi pi = new FakePi();
        assertEquals(Boolean.TRUE, enter(pi));
        assertEquals(211060801, pi.warpedTo);
        assertTrue(pi.messages.get(0).contains("Ifia"), pi.messages.toString());
    }

    @Test
    void theKeyStillOpensTheFightAndIsUsedUp() throws Exception {
        FakePi pi = new FakePi();
        pi.items.add(4032860);
        pi.started.add(3171);
        assertEquals(Boolean.TRUE, enter(pi));
        assertEquals(211070100, pi.warpedTo);
        assertEquals(List.of(-4032860), pi.gained);
    }
}
