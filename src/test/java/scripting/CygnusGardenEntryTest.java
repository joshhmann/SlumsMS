package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: the garden gate (scripts/portal/in_cygnusGarden.js, portal in00 of the Hall of
 * Honor 271030600) wanted Dream Key 4032923, whose only source is the Thunder Stone 2430200 - an item nothing drops,
 * sells or rewards - so no player could reach the Cygnus Garden. The key path is unchanged; a player who holds five
 * each of the four stones the key is made of may pay those 20 stones instead, and is told what was taken.
 */
class CygnusGardenEntryTest {

    private static final int GARDEN = 271040000;
    private static final int HALL_OF_HONOR = 271030600;
    private static final int DREAM_KEY = 4032923;
    private static final int[] STONES = {4000660, 4000661, 4000662, 4000663};

    public static final class FakePlayer {
        final Map<Integer, Integer> counts;
        final List<String> messages;

        FakePlayer(Map<Integer, Integer> counts, List<String> messages) {
            this.counts = counts;
            this.messages = messages;
        }

        public void dropMessage(int type, String text) {
            messages.add(text);
        }
    }

    public static final class FakePi {
        final Map<Integer, Integer> items;
        final List<Integer> gained = new ArrayList<>();
        final List<String> playerMessages = new ArrayList<>();
        final List<Integer> questDone = new ArrayList<>();
        final List<Integer> started = new ArrayList<>();
        Integer warpedTo;
        Object portal;

        FakePi(Map<Integer, Integer> items) {
            this.items = new HashMap<>(items);
        }

        public FakePlayer getPlayer() {
            return new FakePlayer(items, playerMessages);
        }

        public boolean haveItem(int id) {
            return items.getOrDefault(id, 0) > 0;
        }

        public boolean haveItem(int id, int qty) {
            return items.getOrDefault(id, 0) >= qty;
        }

        public boolean isQuestStarted(int q) {
            return started.contains(q);
        }

        public void forceCompleteQuest(int q) {
            questDone.add(q);
        }

        public void playPortalSound() {
        }

        public void warp(int map, String portal) {
            warpedTo = map;
            this.portal = portal;
        }

        public void gainItem(int id, int qty) {
            gained.add(id * qty);
        }
    }

    private static Object enter(FakePi pi) throws Exception {
        ScriptEngine engine = new Loader().load("portal/in_cygnusGarden.js");
        assertNotNull(engine, "scripts/portal/in_cygnusGarden.js did not evaluate");
        return ((Invocable) engine).invokeFunction("enter", pi);
    }

    private static Map<Integer, Integer> items(int... idsAndCounts) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < idsAndCounts.length; i += 2) {
            out.put(idsAndCounts[i], idsAndCounts[i + 1]);
        }
        return out;
    }

    @Test
    void theDreamKeyStillOpensTheGateAndIsUsedUp() throws Exception {
        FakePi pi = new FakePi(items(DREAM_KEY, 1));

        assertEquals(Boolean.TRUE, enter(pi));

        assertEquals(GARDEN, pi.warpedTo);
        assertEquals("out00", pi.portal);
        assertEquals(List.of(-DREAM_KEY), pi.gained, "only the key is taken");
    }

    @Test
    void fiveOfEachStoneAlsoOpensTheGateAndTakesExactlyThoseTwenty() throws Exception {
        FakePi pi = new FakePi(items(STONES[0], 5, STONES[1], 5, STONES[2], 5, STONES[3], 5));

        assertEquals(Boolean.TRUE, enter(pi));

        assertEquals(GARDEN, pi.warpedTo);
        assertEquals("out00", pi.portal);
        assertEquals(List.of(-STONES[0] * 5, -STONES[1] * 5, -STONES[2] * 5, -STONES[3] * 5), pi.gained,
                "five of each, nothing else");
        assertEquals(1, pi.playerMessages.size(), "one message, and it says what was taken");
        String said = pi.playerMessages.get(0);
        for (String stone : List.of("Dawn", "Blaze", "Wind", "Night")) {
            assertTrue(said.contains(stone), said + " names the " + stone + " stone");
        }
        assertTrue(said.contains("5"), said + " says how many");
        assertFalse(said.contains(String.valueOf(DREAM_KEY)), "no item ids in player text");
    }

    @Test
    void fourOfOneStoneIsNotEnoughAndNothingIsTaken() throws Exception {
        FakePi pi = new FakePi(items(STONES[0], 5, STONES[1], 5, STONES[2], 5, STONES[3], 4));

        assertEquals(Boolean.FALSE, enter(pi));

        assertNull(pi.warpedTo);
        assertTrue(pi.gained.isEmpty(), "not one stone is taken: " + pi.gained);
        assertEquals(1, pi.playerMessages.size());
    }

    @Test
    void withNeitherKeyNorStonesTheGateStaysShut() throws Exception {
        FakePi pi = new FakePi(items());

        assertEquals(Boolean.FALSE, enter(pi));

        assertNull(pi.warpedTo);
        assertTrue(pi.gained.isEmpty());
        assertTrue(pi.playerMessages.get(0).contains("Dream Key"), "the way in is still named");
    }

    @Test
    void theChiefAlexQuestStillCompletesAtTheGate() throws Exception {
        FakePi pi = new FakePi(items());
        pi.started.add(31149);

        assertEquals(Boolean.TRUE, enter(pi));

        assertEquals(List.of(31149), pi.questDone);
        assertNull(pi.warpedTo, "the quest step only reports back, as before");
    }

    @Test
    void theHallOfHonorGateIsThePortalThatRunsThisScript() throws Exception {
        soloMapling.WzTestData.assumePresent("Map.wz");
        String xml = java.nio.file.Files.readString(
                soloMapling.WzTestData.dir().resolve("Map.wz/Map/Map2/" + HALL_OF_HONOR + ".img.xml"));
        assertTrue(xml.contains("<string name=\"script\" value=\"in_cygnusGarden\"/>"), "portal in00 of the Hall of Honor");
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of("scripts/portal/in_cygnusGarden.js")));
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
