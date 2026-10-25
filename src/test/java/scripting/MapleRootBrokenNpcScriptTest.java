package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.1: NPC scripts the MapleRoot baseline (03d13cb21) broke and this release restores from stock Cosmic.
 * Each script runs in the server's own Graal setup against a fake {@code cm}.
 */
class MapleRootBrokenNpcScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private static Invocable load(String path, Object cm) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " exists and evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** What the scripts below touch of NPCConversationManager: dialogs, inventory, mesos and warps. */
    public static class FakeCm {
        public final Map<Integer, Integer> inventory = new HashMap<>();
        public boolean roomForEverything = true;
        public String simple;
        public String yesNo;
        public String ok;
        public String next;
        public Integer warpedTo;
        public boolean disposed;

        public void sendSimple(String text) {
            simple = text;
        }

        public void sendYesNo(String text) {
            yesNo = text;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void sendNext(String text) {
            next = text;
        }

        public void dispose() {
            disposed = true;
        }

        public void warp(int map, int portal) {
            warpedTo = map;
        }

        public boolean haveItem(int itemId) {
            return haveItem(itemId, 1);
        }

        public boolean haveItem(int itemId, int quantity) {
            return count(itemId) >= quantity;
        }

        public boolean canHold(int itemId) {
            return roomForEverything;
        }

        public boolean canHold(int itemId, int quantity) {
            return roomForEverything;
        }

        public void gainItem(int itemId, int quantity) {
            inventory.merge(itemId, quantity, Integer::sum);
        }

        public void gainItem(int itemId) {
            gainItem(itemId, 1);
        }

        public int count(int itemId) {
            return inventory.getOrDefault(itemId, 0);
        }
    }

    // --- Spiegelmann (Monster Carnival) ---------------------------------------------------------------------------

    public static final class FakeMap {
        public boolean isCPQLoserMap() {
            return false;
        }

        public boolean isCPQWinnerMap() {
            return false;
        }
    }

    public static final class FakeChar {
        private final FakeMap map = new FakeMap();

        public int getMapId() {
            return 103000000;   // not a CPQ map: the plain "what would you like to do" menu
        }

        public FakeMap getMap() {
            return map;
        }
    }

    public static final class SpiegelmannCm extends FakeCm {
        private final FakeChar chr = new FakeChar();

        public FakeChar getPlayer() {
            return chr;
        }

        public FakeChar getChar() {
            return chr;
        }

        public int getMapId() {
            return chr.getMapId();
        }
    }

    private static final int MAPLE_COIN = 4001129;
    private static final int SCROLL_60_SHIELD_DEF = 2041211;

    /** Main menu -> "Trade Maple Coins" -> option 1, the scroll for 40 coins. */
    private static void tradeForTheScroll(String script, SpiegelmannCm cm) throws Exception {
        Invocable npc = load(script, cm);
        ((ScriptEngine) npc).eval("status = -1;");
        npc.invokeFunction("action", 1, 0, 0);
        npc.invokeFunction("action", 1, 0, 2);
        assertTrue(cm.simple.contains("#t" + SCROLL_60_SHIELD_DEF + "#"), "the trade menu offers the scroll: " + cm.simple);
        npc.invokeFunction("action", 1, 0, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/2042000.js", "npc/2042001.js", "npc/2042002.js"})
    void spiegelmannGivesTheScrollFor40MapleCoins(String script) throws Exception {
        SpiegelmannCm cm = new SpiegelmannCm();
        cm.inventory.put(MAPLE_COIN, 45);

        tradeForTheScroll(script, cm);

        assertEquals(1, cm.count(SCROLL_60_SHIELD_DEF), "the scroll is given");
        assertEquals(5, cm.count(MAPLE_COIN), "40 coins are taken");
        assertTrue(cm.disposed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/2042000.js", "npc/2042001.js", "npc/2042002.js"})
    void spiegelmannKeepsTheCoinsWhenTheScrollWouldNotFit(String script) throws Exception {
        SpiegelmannCm cm = new SpiegelmannCm();
        cm.inventory.put(MAPLE_COIN, 45);
        cm.roomForEverything = false;

        tradeForTheScroll(script, cm);

        assertEquals(0, cm.count(SCROLL_60_SHIELD_DEF));
        assertEquals(45, cm.count(MAPLE_COIN));
        assertTrue(cm.ok.contains("USE inventory"), cm.ok);
    }
}
