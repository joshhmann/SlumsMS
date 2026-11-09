package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: two portals name the script in_cygnus, which did not exist - the garden's door
 * into the chamber (271040000 in00) and the chamber's exit (271040100 out00). The exit now walks back to the garden;
 * the garden door refuses a walk-in, because only the expedition puts a player into the fight instance
 * (CygnusBattle.playerEntry warps there itself), and tells the player where the way in is.
 */
class CygnusChamberPortalTest {

    private static final int GARDEN = 271040000;
    private static final int CHAMBER = 271040100;

    public static final class FakePlayer {
        final List<String> messages;

        FakePlayer(List<String> messages) {
            this.messages = messages;
        }

        public void dropMessage(int type, String text) {
            messages.add(text);
        }
    }

    public static final class FakePi {
        final int mapId;
        final List<String> playerMessages = new ArrayList<>();
        Integer warpedTo;
        Object portal;

        FakePi(int mapId) {
            this.mapId = mapId;
        }

        public int getMapId() {
            return mapId;
        }

        public FakePlayer getPlayer() {
            return new FakePlayer(playerMessages);
        }

        public void playPortalSound() {
        }

        public void warp(int map, String portal) {
            warpedTo = map;
            this.portal = portal;
        }
    }

    private static Object enter(int mapId, FakePi pi) throws Exception {
        ScriptEngine engine = new Loader().load("portal/in_cygnus.js");
        assertNotNull(engine, "scripts/portal/in_cygnus.js did not evaluate");
        return ((Invocable) engine).invokeFunction("enter", pi);
    }

    @Test
    void theChamberExitTakesAPlayerBackToTheGarden() throws Exception {
        FakePi pi = new FakePi(CHAMBER);

        assertEquals(Boolean.TRUE, enter(CHAMBER, pi));

        assertEquals(GARDEN, pi.warpedTo);
        assertEquals("out00", pi.portal, "the spot the garden gate drops players on");
        assertTrue(pi.playerMessages.isEmpty());
    }

    @Test
    void aWalkInAtTheGardenDoorCannotStartTheFight() throws Exception {
        FakePi pi = new FakePi(GARDEN);

        assertEquals(Boolean.FALSE, enter(GARDEN, pi));

        assertNull(pi.warpedTo, "the walk-in is not let through");
        assertEquals(1, pi.playerMessages.size());
        assertTrue(pi.playerMessages.get(0).contains("expedition leader"), pi.playerMessages.get(0));
    }

    @Test
    void exactlyTheTwoWzPortalsNameThisScriptAndTheyHaveNoTargetOfTheirOwn() throws Exception {
        soloMapling.WzTestData.assumePresent("Map.wz");
        int[] maps = {GARDEN, CHAMBER};
        int found = 0;
        for (int mapId : maps) {
            String xml = java.nio.file.Files.readString(
                    soloMapling.WzTestData.dir().resolve("Map.wz/Map/Map2/" + mapId + ".img.xml"));
            java.util.regex.Matcher portal = java.util.regex.Pattern
                    .compile("(?s)<imgdir name=\"\\d+\">(.*?)</imgdir>").matcher(xml);
            while (portal.find()) {
                String block = portal.group(1);
                if (!block.contains("<string name=\"script\" value=\"in_cygnus\"/>")) {
                    continue;
                }
                found++;
                assertTrue(block.contains("<int name=\"tm\" value=\"999999999\"/>"),
                        "map " + mapId + ": the script has to do the warp, a real target would bypass it");
            }
        }
        assertEquals(2, found, "the garden door and the chamber exit, and nothing else");
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
