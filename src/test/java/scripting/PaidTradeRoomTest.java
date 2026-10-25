package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.5.2: MapleRoot trade scripts took the payment and then gave the item with no room check, so a full inventory
 * lost the payment (gainItem quietly gives nothing when it does not fit).
 */
class PaidTradeRoomTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class TradeCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public int meso;

        public int getItemQuantity(int id) {
            return count(id);
        }

        public int getMeso() {
            return meso;
        }

        public void gainMeso(int m) {
            meso += m;
        }

        public void sendGetNumber(String text, int def, int min, int max) {
        }

        public void gainItem(int id, int qty, boolean show, boolean random, long expiry) {
            gainItem(id, qty);
        }

        @Override
        public void gainItem(int id, int qty) {
            if (qty > 0 && !roomForEverything) {
                return;   // as the server: nothing is given when it does not fit
            }
            super.gainItem(id, qty);
        }
    }

    private static Invocable npc(String script, TradeCm cm) {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script);
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    @Test
    void rootShopKeepsTheLeavesWhenTheItemWouldNotFit() throws Exception {
        TradeCm cm = new TradeCm();
        cm.inventory.put(4001126, 500);
        cm.roomForEverything = false;
        Invocable qol = npc("npc/qolitems.js", cm);
        qol.invokeFunction("start");
        qol.invokeFunction("action", 1, 0, 0);   // the first cash item, for leaves
        qol.invokeFunction("action", 1, 3, 2);   // two of them
        assertEquals(500, cm.count(4001126));
    }

    @Test
    void masteryBooksKeepTheLeavesWhenTheBookWouldNotFit() throws Exception {
        TradeCm cm = new TradeCm();
        cm.inventory.put(4001126, 500);
        cm.roomForEverything = false;
        Invocable books = npc("npc/masterybooks.js", cm);
        books.invokeFunction("start");
        books.invokeFunction("action", 1, 0, 0);
        books.invokeFunction("action", 1, 0, 0);
        books.invokeFunction("action", 1, 0, 0);
        assertEquals(500, cm.count(4001126));
    }

    @Test
    void aForgedMasteryBookSelectionTakesNothing() throws Exception {
        TradeCm cm = new TradeCm();
        cm.inventory.put(4001126, 500);
        Invocable books = npc("npc/masterybooks.js", cm);
        books.invokeFunction("start");
        books.invokeFunction("action", 1, 0, 0);
        books.invokeFunction("action", 1, 0, 99);
        books.invokeFunction("action", 1, 0, 0);
        assertEquals(500, cm.count(4001126));
    }

    @Test
    void oreExchangeTakesNoOreWhenTheTradeWouldNotFit() throws Exception {
        TradeCm cm = new TradeCm();
        cm.inventory.put(4020007, 50);
        cm.inventory.put(4020005, 50);
        cm.roomForEverything = false;
        Invocable ex = npc("npc/exchange.js", cm);
        ex.invokeFunction("start");
        ex.invokeFunction("action", 1, 0, 0);
        ex.invokeFunction("action", 1, 3, 5);
        assertEquals(100, cm.count(4020007) + cm.count(4020005));
    }
}
