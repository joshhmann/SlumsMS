package scripting.npc;

import client.BotClient;
import client.Character;
import client.inventory.AdventurerBag;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the real Adventurer's Bag script (scripts/npc/3004039.js) through its dialog with a stand-in {@code cm}
 * backed by a real Character inventory and a real {@link AdventurerBag}. The stand-in also offers the pre-1.6.1
 * calls (gainItem / updateStoredItem with their old semantics), so the same scenarios show what the old script did:
 * laundered a bound stack, overwrote the bag with a stale amount, debited the bag for an item it never handed over,
 * and threw on a withdraw-all above 32,767.
 */
class AdventurerBagScriptTest {

    static final int ORE = 4010000;          // in the script's ore list
    static final int ONLY_ONE = 4031999;

    static final AdventurerBag.Rules RULES = new AdventurerBag.Rules() {
        @Override public short slotMax(Character chr, int itemId) { return (short) (itemId == ORE ? 32000 : 100); }
        @Override public boolean refused(int itemId) { return false; }
        @Override public boolean oneOfAKind(int itemId) { return itemId == ONLY_ONE; }
        @Override public void stampNew(Item fresh) { }
        @Override public void announce(Character chr, int mode, Item item) { }
    };

    public static class Miip {
        public short getSlotMax(Character c, int itemId) { return RULES.slotMax(c, itemId); }
    }

    /** The calls 3004039.js makes, old and new. */
    public static class Cm {
        final Character chr;
        final AdventurerBag bag;
        public String lastText = "";

        Cm(Character chr, AdventurerBag bag) {
            this.chr = chr;
            this.bag = bag;
        }

        Inventory inv(int itemId) {
            return chr.getInventory(ItemConstants.getInventoryType(itemId));
        }

        public Character getPlayer() { return chr; }
        public String getName() { return "Tester"; }
        public void sendSimple(String s) { lastText = s; }
        public void sendOk(String s) { lastText = s; }
        public void sendGetNumber(String s, int def, int min, int max) { lastText = s; }
        public boolean disposed;
        public void dispose() { disposed = true; }

        public int itemQuantity(int itemId) { return inv(itemId).countById(itemId); }
        public int getStoredItemAmount(int charId, int itemId) { return bag.amount(itemId); }

        public String getInventoryAsString(String type) {
            StringBuilder sb = new StringBuilder();
            for (Item it : chr.getInventory(InventoryType.getByWZName(type)).list()) {
                sb.append("#L").append(it.getPosition()).append("#").append(it.getItemId()).append("#l");
            }
            return sb.toString();
        }

        List<Map.Entry<Integer, Integer>> stored(String type) {
            InventoryType want = type.equalsIgnoreCase("consume") ? InventoryType.USE
                    : type.equalsIgnoreCase("setup") ? InventoryType.SETUP : InventoryType.ETC;
            List<Map.Entry<Integer, Integer>> ret = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : bag.list()) {
                if (ItemConstants.getInventoryType(e.getKey()) == want) {
                    ret.add(e);
                }
            }
            return ret;
        }

        public String formatStoredItems(String type) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Map.Entry<Integer, Integer> e : stored(type)) {
                sb.append("#L").append(i++).append("#").append(e.getKey()).append("#l");
            }
            return sb.toString();
        }

        public Integer getSelectedStoredItemId(int index, String type) { return stored(type).get(index).getKey(); }

        public Item getItemByTypeAndPos(String type, int pos, Character c) {
            InventoryType t = type.equalsIgnoreCase("consume") ? InventoryType.USE
                    : type.equalsIgnoreCase("setup") ? InventoryType.SETUP : InventoryType.ETC;
            return chr.getInventory(t).getItem((short) pos);
        }

        // ---- 1.6.1 calls ----
        public int bagDepositable(int itemId) { return AdventurerBag.depositable(chr, itemId); }
        public String bagDeposit(int itemId, int qty) { return bag.deposit(chr, itemId, qty, RULES).name(); }
        public String bagWithdraw(int itemId, int qty) { return bag.withdraw(chr, itemId, qty, RULES).name(); }
        public int bagWithdrawUpTo(int itemId, int max) { return bag.withdrawUpTo(chr, itemId, max, RULES); }

        // ---- the pre-1.6.1 calls, with their old semantics ----
        public Miip getMIIP() { return new Miip(); }

        public int getFreeSlots(Integer itemId) { return inv(itemId).getNumFreeSlot(); }

        /** AbstractPlayerInteraction.gainItem: removeById takes any stack; a gain is a fresh plain item or nothing. */
        public Item gainItem(int itemId, short quantity) {
            Inventory inv = inv(itemId);
            if (quantity < 0) {
                int left = -quantity;
                for (Item it : inv.listById(itemId)) {
                    short take = (short) Math.min(left, it.getQuantity());
                    inv.removeItem(it.getPosition(), take, false);
                    left -= take;
                    if (left == 0) break;
                }
                return null;
            }
            if (RULES.oneOfAKind(itemId) && !inv.listById(itemId).isEmpty()) {
                return null;    // checkSpace refuses; the old script never looked at the result
            }
            short slotMax = RULES.slotMax(chr, itemId);
            if (Math.ceil(quantity / (double) slotMax) > inv.getNumFreeSlot()) {
                return null;
            }
            int left = quantity;
            while (left > 0) {
                short n = (short) Math.min(left, slotMax);
                inv.addItem(new Item(itemId, (short) 0, n));
                left -= n;
            }
            return null;
        }

        public void updateStoredItem(int itemId, int amount, boolean clear) {
            int cur = bag.amount(itemId);
            if (clear) {
                bag.take(itemId, cur);
            } else if (amount <= cur) {
                bag.take(itemId, cur - amount);
            } else {
                bag.add(itemId, amount - cur);
            }
        }
    }

    static Character newChar() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(4243);
        return chr;
    }

    static void flag(Item it, int flag) throws Exception {
        java.lang.reflect.Field f = Item.class.getDeclaredField("flag");
        f.setAccessible(true);
        f.setShort(it, (short) flag);
    }

    static final class Dialog implements AutoCloseable {
        final ScriptEngine engine;
        final Cm cm;

        Dialog(Cm cm) throws Exception {
            this.cm = cm;
            cm.disposed = false;
            engine = new ScriptEngineManager().getEngineByName("graal.js");
            engine.getBindings(ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
            engine.put("cm", cm);
            engine.eval(Files.readString(Path.of("scripts/npc/3004039.js")));
            ((Invocable) engine).invokeFunction("start");
        }

        /** One click; nothing once the script has ended the conversation (the server drops the session). */
        Dialog pick(int selection) throws Exception {
            if (cm.disposed) {
                return this;
            }
            ((Invocable) engine).invokeFunction("action", 1, 0, selection);
            return this;
        }

        @Override
        public void close() {
            ((com.oracle.truffle.js.scriptengine.GraalJSScriptEngine) engine).close();
        }
    }

    static int flaggedCount(Character chr, int itemId) {
        int n = 0;
        for (Item it : chr.getInventory(ItemConstants.getInventoryType(itemId)).listById(itemId)) {
            if (it.getFlag() != 0) n += it.getQuantity();
        }
        return n;
    }

    @Test
    void aBoundStackDoesNotComeBackOutTradeable() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = new AdventurerBag(HashMap::new);
        Item bound = new Item(ORE, (short) 0, (short) 10);
        flag(bound, ItemConstants.UNTRADEABLE);
        short slot = chr.getInventory(InventoryType.ETC).addItem(bound);
        Cm cm = new Cm(chr, bag);

        try (Dialog d = new Dialog(cm)) {
            d.pick(0).pick(1).pick(slot).pick(10);      // store > Etc > the bound stack > 10
        }
        try (Dialog d = new Dialog(cm)) {
            d.pick(3).pick(1).pick(0).pick(10);         // retrieve > Etc > first entry > 10 (stops early if empty)
        }

        assertEquals(10, flaggedCount(chr, ORE), "all 10 still carry their untradeable flag");
        assertEquals(10, chr.getInventory(InventoryType.ETC).countById(ORE) + bag.amount(ORE));
    }

    @Test
    void lootPickedUpWhileTheWithdrawPromptIsOpenIsKept() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = new AdventurerBag(() -> new HashMap<>(Map.of(ORE, 100)));
        Cm cm = new Cm(chr, bag);

        try (Dialog d = new Dialog(cm)) {
            d.pick(3).pick(1).pick(0);                  // the prompt reads "stored: 100"
            assertEquals(true, bag.storePickup(new Item(ORE, (short) 0, (short) 50), RULES)); // @backpack on loot
            d.pick(10);
        }

        assertEquals(10, chr.getInventory(InventoryType.ETC).countById(ORE));
        assertEquals(140, bag.amount(ORE), "the old script wrote back 100 - 10 = 90 and lost the 50 picked up");
    }

    @Test
    void aWithdrawTheInventoryRefusesLeavesTheItemInTheBag() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = new AdventurerBag(() -> new HashMap<>(Map.of(ONLY_ONE, 1)));
        chr.getInventory(InventoryType.ETC).addItem(new Item(ONLY_ONE, (short) 0, (short) 1));
        Cm cm = new Cm(chr, bag);

        try (Dialog d = new Dialog(cm)) {
            d.pick(3).pick(1).pick(0).pick(1);
        }

        assertEquals(1, bag.amount(ONLY_ONE), "the old script debited the bag although gainItem handed nothing over");
        assertEquals(1, chr.getInventory(InventoryType.ETC).countById(ONLY_ONE));
    }

    @Test
    void withdrawAllWorksAbove32767() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = new AdventurerBag(() -> new HashMap<>(Map.of(ORE, 64_000)));
        Cm cm = new Cm(chr, bag);

        try (Dialog d = new Dialog(cm)) {
            d.pick(5);
        }

        assertEquals(64_000, chr.getInventory(InventoryType.ETC).countById(ORE));
        assertEquals(0, bag.amount(ORE));
    }

    @Test
    void depositAllMovesPlainOresAndLeavesBoundOnes() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = new AdventurerBag(HashMap::new);
        Item bound = new Item(ORE, (short) 0, (short) 7);
        flag(bound, ItemConstants.UNTRADEABLE);
        chr.getInventory(InventoryType.ETC).addItem(bound);
        chr.getInventory(InventoryType.ETC).addItem(new Item(ORE, (short) 0, (short) 20));
        Cm cm = new Cm(chr, bag);

        try (Dialog d = new Dialog(cm)) {
            d.pick(4);
        }

        assertEquals(20, bag.amount(ORE));
        assertEquals(7, flaggedCount(chr, ORE));
        assertEquals(7, chr.getInventory(InventoryType.ETC).countById(ORE));
    }
}
