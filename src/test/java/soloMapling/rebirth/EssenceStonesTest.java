package soloMapling.rebirth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.1 (owner 2026-10-29, b083): a player makes the Essence Stone the next rebirth needs at Wyatt the Reborn. Before,
 * no player could make one (@craft commented out, craft.js opened by nothing), so only bots could be reborn.
 */
class EssenceStonesTest {

    /** A bag: counts per item, and how many free Etc slots. */
    static final class Bag implements EssenceStones.Hands {
        final Map<Integer, Integer> items = new HashMap<>();
        boolean room = true;

        Bag with(int id) {
            items.merge(id, 1, Integer::sum);
            return this;
        }

        public int count(int itemId) { return items.getOrDefault(itemId, 0); }
        public boolean canHold(int itemId) { return room; }
        public void take(int itemId) { items.merge(itemId, -1, Integer::sum); }
        public void give(int itemId) { items.merge(itemId, 1, Integer::sum); }
    }

    @Test
    void eachRebirthsStoneIsMadeFromCraftJsTwoItems() throws Exception {
        String craft = Files.readString(Path.of("scripts/npc/craft.js"));
        int[][] recipes = {{0, 4033446, 4032133, 4001094}, {1, 4033442, 4021010, 4001693}, {2, 4033450, 4000659, 4032922}};
        for (int[] r : recipes) {
            assertTrue(craft.contains("cm.haveItem(" + r[2] + ") && cm.haveItem(" + r[3] + ")"), "craft.js recipe " + r[1]);
            assertTrue(craft.contains("cm.gainItem(" + r[1] + ", 1)"));
            Bag bag = new Bag().with(r[2]).with(r[3]);
            EssenceStones.Result res = EssenceStones.make(r[0], bag);
            assertTrue(res.made(), res.text());
            assertEquals(1, bag.count(r[1]), "the stone");
            assertEquals(0, bag.count(r[2]) + bag.count(r[3]), "both items taken");
            assertTrue(res.text().contains("#t" + r[1] + "#"));
        }
    }

    @Test
    void itSaysPlainlyWhatIsMissingAndTakesNothing() {
        Bag bag = new Bag().with(4032133);
        EssenceStones.Result res = EssenceStones.make(0, bag);
        assertFalse(res.made());
        assertEquals(1, bag.count(4032133), "nothing taken");
        assertEquals(0, bag.count(4033446));
        assertTrue(res.text().contains("Still missing: #r#t4001094##k (from a Horntail clear)"), res.text());
        assertTrue(res.text().contains("#t4032133#: 1") && res.text().contains("#t4001094#: 0"), res.text());
        EssenceStones.Result none = EssenceStones.make(2, new Bag());
        assertTrue(none.text().contains("a Cygnus clear") && none.text().contains("Lost Emblem"), none.text());
    }

    @Test
    void noSecondStoneWhileOneIsHeld() {
        Bag bag = new Bag().with(4033442).with(4021010).with(4001693);
        EssenceStones.Result res = EssenceStones.make(1, bag);
        assertFalse(res.made());
        assertEquals(1, bag.count(4033442));
        assertEquals(1, bag.count(4021010) + bag.count(4001693) - 1, "its items are kept");
        assertTrue(res.text().contains("already hold"));
    }

    @Test
    void aFullBagKeepsBothItems() {
        Bag bag = new Bag().with(4032133).with(4001094);
        bag.room = false;
        EssenceStones.Result res = EssenceStones.make(0, bag);
        assertFalse(res.made());
        assertEquals(1, bag.count(4032133));
        assertEquals(1, bag.count(4001094));
        assertTrue(res.text().contains("Make room"));
    }

    @Test
    void afterThreeRebirthsThereIsNoStone() {
        assertFalse(EssenceStones.make(3, new Bag().with(4000659).with(4032922)).made());
    }

    @Test
    void wyattOffersItInHisMenu() throws Exception {
        String wyatt = Files.readString(Path.of("scripts/npc/rebirth.js"));
        assertTrue(wyatt.contains("#L2##bMake an Essence Stone#l"), "the menu line");
        assertTrue(wyatt.contains("Java.type('soloMapling.rebirth.EssenceStones')"));
        assertTrue(wyatt.contains("if (selection === 2) {\n            cm.sendOk(EssenceStones.make(cm).text());"));
    }
}
