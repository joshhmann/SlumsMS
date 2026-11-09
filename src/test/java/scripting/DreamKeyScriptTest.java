package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: scripts/item/dreamkey.js (the Thunder Stone 2430200's spec script) took the
 * four stone stacks and the stone, then handed over Dream Key 4032923 - on a full ETC tab the key was dropped on the
 * floor of the inventory code and the stones were gone. It now asks whether the key can be held first and, if not,
 * says so and keeps everything.
 *
 * <p>Reachability is checked too: only items whose spec names a script start one, so a WZ test proves 2430200 still
 * names dreamkey.
 */
class DreamKeyScriptTest {

    private static final int THUNDER_STONE = 2430200;
    private static final int DREAM_KEY = 4032923;
    private static final int[] STONES = {4000660, 4000661, 4000662, 4000663};

    public static final class FakeIm {
        final Set<Integer> stones = new HashSet<>();
        final List<Integer> gained = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        boolean roomForKey = true;
        boolean disposed;

        FakeIm(boolean allStones) {
            if (allStones) {
                for (int stone : STONES) {
                    stones.add(stone);
                }
            } else {
                stones.addAll(List.of(STONES[0], STONES[1], STONES[2]));
            }
        }

        public boolean hasItem(int id) {
            return stones.contains(id);
        }

        public boolean hasItem(int id, int qty) {
            return stones.contains(id);
        }

        public boolean canHold(int id) {
            return roomForKey;
        }

        public void gainItem(int id, int qty) {
            gained.add(id * qty);
        }

        public void playerMessage(int type, String text) {
            messages.add(text);
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static void start(FakeIm im) throws Exception {
        ScriptEngine engine = new Loader().load("item/dreamkey.js");
        assertNotNull(engine, "scripts/item/dreamkey.js did not evaluate");
        engine.put("im", im);
        ((Invocable) engine).invokeFunction("start");
    }

    @Test
    void aFullEtcTabKeepsEveryStone() throws Exception {
        FakeIm im = new FakeIm(true);
        im.roomForKey = false;

        start(im);

        assertTrue(im.gained.isEmpty(), "nothing is taken when the key would not fit: " + im.gained);
        assertTrue(im.disposed);
        assertEquals(1, im.messages.size());
        assertFalse(im.messages.get(0).contains(String.valueOf(DREAM_KEY)), "no item ids in player text");
    }

    @Test
    void withRoomTheKeyIsMadeAndTheCraftingStonesAreUsedUp() throws Exception {
        FakeIm im = new FakeIm(true);

        start(im);

        assertEquals(List.of(-STONES[0] * 5, -STONES[1] * 5, -STONES[2] * 5, -STONES[3] * 5,
                -THUNDER_STONE, DREAM_KEY), im.gained);
        assertTrue(im.messages.contains("You have obtained the Dream Key."));
    }

    @Test
    void withoutEveryStoneNothingIsTaken() throws Exception {
        FakeIm im = new FakeIm(false);

        start(im);

        assertTrue(im.gained.isEmpty(), "a short stack takes nothing: " + im.gained);
        assertTrue(im.disposed);
    }

    @Test
    void theThunderStoneStillNamesThisScript() throws Exception {
        soloMapling.WzTestData.assumePresent("Item.wz");
        String xml = java.nio.file.Files.readString(
                soloMapling.WzTestData.dir().resolve("Item.wz/Consume/0243.img.xml"));
        int at = xml.indexOf("<imgdir name=\"0" + THUNDER_STONE + "\">");
        assertTrue(at > 0, "the consumable " + THUNDER_STONE + " exists");
        int next = xml.indexOf("<imgdir name=\"0", at + 20);
        String block = xml.substring(at, next > 0 ? next : xml.length());
        assertTrue(block.contains("<string name=\"script\" value=\"dreamkey\"/>"),
                "the item runs scripts/item/dreamkey.js");
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
