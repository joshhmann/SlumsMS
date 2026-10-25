package scripting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.5.2: Rooty (rooty.js) and the Donor Shop (donorshop.js) bought whatever item id the client sent as the menu
 * selection, and gave a free one-minute "try" of it in any category.
 */
class RootyShopSelectionTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class ShopCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public int getItemQuantity(int id) {
            return count(id);
        }

        public void gainItem(int id, int qty, boolean show, boolean random, long expiry) {
            gainItem(id, qty);
        }
    }

    private static final int CURRENCY = 4310000, OFFERED = 1002000, FORGED = 2049100;

    private static Invocable shop(String script, ShopCm cm, boolean cosmetic) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script + " evaluates");
        engine.put("cm", cm);
        engine.eval("currentIDs = [" + OFFERED + "]; price = 1; currencyItemId = " + CURRENCY + "; cosmetic = " + cosmetic
                + "; time = 1;");
        return (Invocable) engine;
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/rooty.js", "npc/donorshop.js"})
    void aForgedSelectionBuysNothing(String script) throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(CURRENCY, 5);
        Invocable npc = shop(script, cm, false);
        npc.invokeFunction("confirmPurchase", FORGED);
        assertNull(cm.simple);
        assertTrue(cm.disposed);
        ((ScriptEngine) npc).eval("selectedItem = " + FORGED + ";");
        npc.invokeFunction("completePurchase", 1, 0);
        npc.invokeFunction("completePurchase", 1, 1);
        assertEquals(0, cm.count(FORGED));
        assertEquals(5, cm.count(CURRENCY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/rooty.js", "npc/donorshop.js"})
    void anOfferedItemIsStillSold(String script) throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(CURRENCY, 5);
        Invocable npc = shop(script, cm, false);
        npc.invokeFunction("confirmPurchase", OFFERED);
        assertNotNull(cm.simple);
        npc.invokeFunction("completePurchase", 1, 0);
        assertEquals(1, cm.count(OFFERED));
        assertEquals(4, cm.count(CURRENCY));
    }

    @Test
    void theFreeTryIsForCosmeticsOnly() throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(CURRENCY, 5);
        Invocable npc = shop("npc/rooty.js", cm, false);
        npc.invokeFunction("confirmPurchase", OFFERED);
        npc.invokeFunction("completePurchase", 1, 1);
        assertEquals(0, cm.count(OFFERED));
    }
}
