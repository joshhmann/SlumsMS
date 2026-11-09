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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.8 card cygnus-fight-unreachable: scripts/item/dreamkey.js (the Thunder Stone 2430200's spec script) makes the
 * Dream Key 4032923 from the reference recipe - 1 Dawn, 1 Blaze, 1 Wind, 1 Night and the Thunder Stone itself - and
 * hands the key over. Everything is checked before anything is taken, so a missing stone or a full ETC tab keeps
 * every item (the stones-entry version of this card took 5 each).
 *
 * <p>Reachability is checked too: only items whose spec names a script start one, so a WZ test proves 2430200 still
 * names dreamkey.
 */
class DreamKeyScriptTest {

    private static final int THUNDER_STONE = 2430200;
    private static final int DREAM_KEY = 4032923;
    private static final int[] STONES = {4000660, 4000661, 4000662, 4000663};

    public static final class FakeIm {
        final Map<Integer, Integer> counts = new HashMap<>();
        /** Every gainItem call as "item:qty", in call order. */
        final List<String> gained = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        boolean roomForKey = true;
        boolean disposed;

        /** id, count, id, count ... */
        FakeIm(int... idsAndCounts) {
            for (int i = 0; i < idsAndCounts.length; i += 2) {
                counts.put(idsAndCounts[i], idsAndCounts[i + 1]);
            }
        }

        public boolean hasItem(int id) {
            return counts.getOrDefault(id, 0) >= 1;
        }

        public boolean hasItem(int id, int qty) {
            return counts.getOrDefault(id, 0) >= qty;
        }

        public boolean canHold(int id) {
            return roomForKey;
        }

        public void gainItem(int id, int qty) {
            gained.add(id + ":" + qty);
            counts.merge(id, qty, Integer::sum);
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

    /** Owner, 9 Nov: 5 of each stone (our recipe since the 18 Oct baseline), not the reference's 1. */
    private static final int PER_STONE = 5;

    private static FakeIm oneOfEach() {
        FakeIm im = new FakeIm();
        for (int stone : STONES) {
            im.counts.put(stone, PER_STONE);
        }
        im.counts.put(THUNDER_STONE, 1);
        return im;
    }

    @Test
    void fiveOfEachStoneAndTheThunderStoneMakesTheKey() throws Exception {
        FakeIm im = oneOfEach();

        start(im);

        assertEquals(List.of(STONES[0] + ":-5", STONES[1] + ":-5", STONES[2] + ":-5", STONES[3] + ":-5",
                THUNDER_STONE + ":-1", DREAM_KEY + ":1"), im.gained, "five of each stone, the Thunder Stone, then the key");
        for (int stone : STONES) {
            assertEquals(0, im.counts.get(stone), "stone " + stone + " is used up");
        }
        assertEquals(0, im.counts.get(THUNDER_STONE), "the Thunder Stone is used up");
        assertEquals(1, im.counts.get(DREAM_KEY), "one key");
        assertTrue(im.messages.contains("You have obtained the Dream Key."));
        assertTrue(im.disposed);
    }

    @Test
    void biggerStacksLoseOnlyFiveEach() throws Exception {
        FakeIm im = new FakeIm(STONES[0], 7, STONES[1], 7, STONES[2], 7, STONES[3], 7, THUNDER_STONE, 3);

        start(im);

        for (int stone : STONES) {
            assertEquals(2, im.counts.get(stone), "only five " + stone + " are taken");
        }
        assertEquals(2, im.counts.get(THUNDER_STONE), "only one Thunder Stone is taken");
        assertEquals(1, im.counts.get(DREAM_KEY));
    }

    @Test
    void fourOfAStoneIsNotEnough() throws Exception {
        for (int shortStone : STONES) {
            FakeIm im = oneOfEach();
            im.counts.put(shortStone, PER_STONE - 1);
            Map<Integer, Integer> before = new HashMap<>(im.counts);

            start(im);

            assertTrue(im.gained.isEmpty(), "4 of " + shortStone + " takes nothing: " + im.gained);
            assertEquals(before, im.counts);
            assertTrue(im.messages.get(0).contains("5 of each stone"), im.messages.get(0));
        }
    }

    @Test
    void aFullEtcTabKeepsEveryStone() throws Exception {
        FakeIm im = oneOfEach();
        im.roomForKey = false;

        start(im);

        assertTrue(im.gained.isEmpty(), "nothing is taken when the key would not fit: " + im.gained);
        assertEquals(PER_STONE, im.counts.get(STONES[0]), "the Dawn stones stay");
        assertEquals(1, im.counts.get(THUNDER_STONE), "the Thunder Stone stays");
        assertEquals(0, im.counts.getOrDefault(DREAM_KEY, 0), "no key");
        assertTrue(im.disposed);
        assertEquals(1, im.messages.size());
        assertFalse(im.messages.get(0).contains(String.valueOf(DREAM_KEY)), "no item ids in player text");
    }

    @Test
    void aMissingIngredientTakesNothing() throws Exception {
        for (int missing : new int[]{STONES[0], STONES[1], STONES[2], STONES[3], THUNDER_STONE}) {
            FakeIm im = oneOfEach();
            im.counts.remove(missing);
            Map<Integer, Integer> before = new HashMap<>(im.counts);

            start(im);

            assertTrue(im.gained.isEmpty(), "missing " + missing + " takes nothing: " + im.gained);
            assertEquals(before, im.counts, "nothing moves when " + missing + " is missing");
            assertTrue(im.disposed);
            assertEquals(1, im.messages.size());
            String said = im.messages.get(0);
            assertTrue(said.contains("5 of each stone"), said);
            for (String stone : List.of("Dawn", "Blaze", "Wind", "Night")) {
                assertTrue(said.contains(stone), said + " names the " + stone + " stone");
            }
            assertFalse(said.contains(String.valueOf(DREAM_KEY)), "no item ids in player text");
        }
    }

    @Test
    void withoutEveryStoneNothingIsTaken() throws Exception {
        FakeIm im = new FakeIm();

        start(im);

        assertTrue(im.gained.isEmpty(), "a player with none of the stones takes nothing: " + im.gained);
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
