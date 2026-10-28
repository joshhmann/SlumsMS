package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rooty's shop (rooty.js), opened from NPC 9010004's "Rooty" and "Donor Shop" entries.
 * <ul>
 *   <li>1.5.2: the shop bought whatever item id the client sent as the menu selection, and gave a free one-minute
 *   "try" of it in any category.</li>
 *   <li>1.6.0 (owner, 2026-10-26): MapleRoot's two near-duplicate shops (rooty.js and donorshop.js) are one catalog
 *   that both menu entries open, with every item id listed once.</li>
 * </ul>
 */
class RootyShopSelectionTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class ShopCm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public final List<String> opened = new ArrayList<>();
        public int npc = 9010004;
        public long lastExpiry = -1;

        public int getNpc() {
            return npc;
        }

        /** The player (a mock carrying area info, for Rooty's Bonus cap); null unless a test sets it. */
        public client.Character player;

        public client.Character getPlayer() {
            return player;
        }

        public int getItemQuantity(int id) {
            return count(id);
        }

        public void gainItem(int id, int qty, boolean show, boolean random, long expiry) {
            lastExpiry = expiry;
            gainItem(id, qty);
        }

        public void openNpc(int npcId, String script) {
            opened.add(npcId + ":" + script);
        }

        public void changeMusic(String song) {
        }

        /** The account's Slums Stamps; buyWithStamps follows SlumsStamps.buy (room, then take, then give). */
        public int stamps;
        public final List<Integer> stampPrices = new ArrayList<>();

        public int getStamps() {
            return stamps;
        }

        public String buyWithStamps(int price, int itemId, long expiry) {
            stampPrices.add(price);
            if (!roomForEverything) {
                return "NO_ROOM";
            }
            if (stamps < price) {
                return "SHORT";
            }
            stamps -= price;
            lastExpiry = expiry;
            gainItem(itemId, 1);
            return "BOUGHT";
        }
    }

    private static final int ROOT_COIN = 4310000, MAPLE_LEAF = 4001126, BILLION_COIN = 4001253;
    private static final int FIRST_ROOTY_CAP = 1000000, FIRST_DONOR_CAP = 1003536;   // first of each old Cap list
    private static final int MAPLE_SWORD_LV35 = 1302020, HIGH_FIVE_STAMP = 5590000, FORGED = 2049100;
    // MENU indexes in rooty.js
    private static final int NX_GOODIES = 0, MAPLE_WEAPONS = 2, DONOR_NX = 4, DONOR_SHOP = 5;

    private static Invocable shop(ShopCm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/rooty.js");
        assertNotNull(engine, "rooty.js evaluates");
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        return npc;
    }

    private static void pick(Invocable npc, int selection) throws Exception {
        npc.invokeFunction("action", 1, 0, selection);
    }

    private static List<Integer> catalogIds() throws Exception {
        ScriptEngine engine = new Loader().load("npc/rooty.js");
        String joined = (String) engine.eval("CATALOG.map(s => s.categories.map(c => c.items.join(',')).join(',')).join(',')");
        List<Integer> ids = new ArrayList<>();
        for (String s : joined.split(",")) {
            ids.add(Integer.parseInt(s.trim()));
        }
        return ids;
    }

    @Test
    void theMergedCatalogListsEveryItemOnce() throws Exception {
        List<Integer> ids = catalogIds();
        Set<Integer> seen = new HashSet<>();
        List<Integer> dups = new ArrayList<>();
        for (int id : ids) {
            if (!seen.add(id)) {
                dups.add(id);
            }
        }
        assertEquals(List.of(), dups, "item ids listed twice in rooty.js CATALOG");
        // both old shops' stock is in it: 2,300 Nx Goodies, 6,381 Donor NX, 52 Maple weapons, 2 High-Five Stamps
        assertTrue(seen.containsAll(Arrays.asList(FIRST_ROOTY_CAP, FIRST_DONOR_CAP, MAPLE_SWORD_LV35, HIGH_FIVE_STAMP)));
        assertEquals(2300 + 6381 + 52 + 2, ids.size());
    }

    @Test
    void bothMenuEntriesOpenTheOneShop() throws Exception {
        assertFalse(Files.exists(Path.of("scripts/npc/donorshop.js")), "the second copy of the shop is gone");
        for (int entry : new int[]{0, 1}) {
            ShopCm cm = new ShopCm();
            ScriptEngine engine = new Loader().load("npc/9010004.js");
            engine.put("cm", cm);
            Invocable npc = (Invocable) engine;
            npc.invokeFunction("start");
            npc.invokeFunction("action", 1, 0, entry);
            assertEquals(1, cm.opened.size());
            assertTrue(cm.opened.get(0).endsWith(":rooty"), "entry " + entry + " opened " + cm.opened);
        }
    }

    @Test
    void theDonorShopEntryShowsTheSameMenu() throws Exception {
        ShopCm rooty = new ShopCm();
        shop(rooty);
        ShopCm donor = new ShopCm();
        donor.npc = 2616;
        shop(donor);
        String rootyMenu = rooty.simple.substring(rooty.simple.indexOf("#L0#"));
        assertEquals(rootyMenu, donor.simple.substring(donor.simple.indexOf("#L0#")));
        assertTrue(rootyMenu.contains("Donor NX") && rootyMenu.contains("Maple Weapons"));
    }

    @Test
    void aMapleWeaponSellsForLeaves() throws Exception {
        // before 1.6.0 the Maple weapons never set the offered list, so 1.5.2's check refused every one of them
        ShopCm cm = new ShopCm();
        cm.inventory.put(MAPLE_LEAF, 7);
        Invocable npc = shop(cm);
        pick(npc, MAPLE_WEAPONS);
        pick(npc, 0);                 // Level 35, one page
        pick(npc, MAPLE_SWORD_LV35);
        assertTrue(cm.simple.contains("5 #i" + MAPLE_LEAF), cm.simple);
        pick(npc, 0);                 // Yes
        assertEquals(1, cm.count(MAPLE_SWORD_LV35));
        assertEquals(2, cm.count(MAPLE_LEAF));
    }

    /**
     * 1.6.1 volatility, "Rooty's Bonus": while the market event runs, Maple Weapons also offers a Chaos Scroll 60% for
     * 40 Maple Leaves (a new leaf sink); without the event the section is unchanged.
     */
    @Test
    void rootysBonusSellsAChaosScrollForLeavesOnlyWhileTheEventRuns() throws Exception {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L);
        var ev = soloMapling.Economy.exchange.volatility.MarketEvents.forTest(
                soloMapling.Economy.exchange.volatility.MarketConfig.allOn(), now::get, s -> { });
        soloMapling.Economy.exchange.volatility.MarketEvents.useForTest(ev);
        try {
            ShopCm off = new ShopCm();
            off.inventory.put(MAPLE_LEAF, 100);
            Invocable npc = shop(off);
            pick(npc, MAPLE_WEAPONS);
            assertFalse(off.simple.contains("Rooty's Bonus"), "no event: no bonus category");
            ev.start(soloMapling.Economy.exchange.volatility.MarketEvents.Kind.ROOTY_BONUS, 0, false, now.get());
            client.Character me = playerWithAreaInfo();
            ShopCm cm = new ShopCm();
            cm.player = me;
            cm.inventory.put(MAPLE_LEAF, 100);
            npc = shop(cm);
            pick(npc, MAPLE_WEAPONS);
            assertTrue(cm.simple.contains("Rooty's Bonus") && cm.simple.contains("(40 "), cm.simple);
            pick(npc, 4);                 // the bonus category, after the four weapon levels
            pick(npc, FORGED);            // 2049100, the Chaos Scroll 60%
            assertTrue(cm.simple.contains("40 #i" + MAPLE_LEAF), cm.simple);
            pick(npc, 0);                 // Yes
            assertEquals(1, cm.count(FORGED));
            assertEquals(60, cm.count(MAPLE_LEAF));
            // owner 2026-10-28: 2 per character per bonus event, kept across a relog (area info saves with the character)
            assertEquals(1, buyBonus(me, 60), "the second");
            assertEquals(0, buyBonus(me, 60), "the third is refused");
            ShopCm relog = new ShopCm();
            relog.player = me;
            relog.inventory.put(MAPLE_LEAF, 100);
            npc = shop(relog);
            pick(npc, MAPLE_WEAPONS);
            pick(npc, 4);
            pick(npc, FORGED);
            assertTrue(relog.ok != null && relog.ok.contains("already took"), String.valueOf(relog.ok));
            assertEquals(100, relog.count(MAPLE_LEAF), "no leaves taken");
            assertEquals(1, buyBonus(playerWithAreaInfo(), 60), "another character has its own 2");
            // the next bonus event starts a fresh count
            ev.end(soloMapling.Economy.exchange.volatility.MarketEvents.Kind.ROOTY_BONUS, now.get());
            now.addAndGet(60_000);
            ev.start(soloMapling.Economy.exchange.volatility.MarketEvents.Kind.ROOTY_BONUS, 0, false, now.get());
            assertEquals(1, buyBonus(me, 60), "a new event, a new cap");
            now.addAndGet(24 * 3_600_000L + 1);
            ShopCm after = new ShopCm();
            npc = shop(after);
            pick(npc, MAPLE_WEAPONS);
            assertFalse(after.simple.contains("Rooty's Bonus"), "the event expired");
        } finally {
            soloMapling.Economy.exchange.volatility.MarketEvents.useForTest(null);
        }
    }

    /** A mock player whose area info lives in a map (what the character saves). */
    static client.Character playerWithAreaInfo() {
        client.Character c = org.mockito.Mockito.mock(client.Character.class);
        java.util.Map<Integer, String> area = new java.util.HashMap<>();
        org.mockito.Mockito.when(c.getAreaInfo(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> area.get((Integer) i.getArgument(0)));
        org.mockito.Mockito.doAnswer(i -> {
            area.put(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(c).updateAreaInfo(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
        return c;
    }

    /** One Rooty's Bonus purchase attempt; returns the Chaos Scrolls gained (0 or 1). */
    private static int buyBonus(client.Character me, int leaves) throws Exception {
        ShopCm cm = new ShopCm();
        cm.player = me;
        cm.inventory.put(MAPLE_LEAF, leaves);
        Invocable npc = shop(cm);
        pick(npc, MAPLE_WEAPONS);
        pick(npc, 4);
        pick(npc, FORGED);
        if (!cm.disposed) {
            pick(npc, 0);
        }
        return cm.count(FORGED);
    }

    @Test
    void aForgedSelectionBuysNothing() throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(MAPLE_LEAF, 50);
        Invocable npc = shop(cm);
        pick(npc, MAPLE_WEAPONS);
        pick(npc, 0);
        cm.simple = null;
        pick(npc, FORGED);
        assertNull(cm.simple);
        assertTrue(cm.disposed);
        assertEquals(0, cm.count(FORGED));
        assertEquals(50, cm.count(MAPLE_LEAF));
    }

    @Test
    void anItemFromAnotherCategoryIsNotSoldAtThisPrice() throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(MAPLE_LEAF, 50);
        Invocable npc = shop(cm);
        pick(npc, MAPLE_WEAPONS);
        pick(npc, 0);                 // Level 35 for 5 leaves
        pick(npc, 1302142);           // a Level 77 weapon
        pick(npc, 0);
        assertEquals(0, cm.count(1302142));
        assertEquals(50, cm.count(MAPLE_LEAF));
    }

    @Test
    void cosmeticsAreTriedOnAndBoughtWithTheirSectionsCoin() throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(ROOT_COIN, 1);
        Invocable npc = shop(cm);
        pick(npc, NX_GOODIES);
        pick(npc, 0);                 // Cap: several pages
        pick(npc, 0);                 // page 1
        pick(npc, FIRST_ROOTY_CAP);
        assertTrue(cm.simple.contains("Try this item first"));
        pick(npc, 1);                 // try
        assertEquals(1, cm.count(FIRST_ROOTY_CAP));
        assertEquals(60_000, cm.lastExpiry);
        assertEquals(1, cm.count(ROOT_COIN));

        ShopCm buyer = new ShopCm();
        buyer.inventory.put(ROOT_COIN, 1);
        npc = shop(buyer);
        pick(npc, NX_GOODIES);
        pick(npc, 0);
        pick(npc, 0);
        pick(npc, FIRST_ROOTY_CAP);
        pick(npc, 0);
        assertEquals(1, buyer.count(FIRST_ROOTY_CAP));
        assertEquals(0, buyer.count(ROOT_COIN));
    }

    @Test
    void donorItemsCostSlumsStampsNotBillionCoins() throws Exception {
        ShopCm cm = new ShopCm();
        cm.inventory.put(ROOT_COIN, 100);
        cm.inventory.put(BILLION_COIN, 10);
        cm.stamps = 39;
        Invocable npc = shop(cm);
        assertTrue(cm.simple.contains("Your Slums Stamps: #b39#k"), "Rooty's header shows the balance: " + cm.simple);
        pick(npc, DONOR_SHOP);
        assertTrue(cm.simple.contains("(40 Slums Stamps)"), cm.simple);
        pick(npc, 0);
        pick(npc, HIGH_FIVE_STAMP);
        assertTrue(cm.ok.contains("not have enough Slums Stamps"), "39 stamps cannot buy a 40-stamp item: " + cm.ok);
        assertEquals(0, cm.count(HIGH_FIVE_STAMP));
        assertEquals(39, cm.stamps);

        cm.stamps = 40;
        npc = shop(cm);
        pick(npc, DONOR_SHOP);
        pick(npc, 0);
        pick(npc, HIGH_FIVE_STAMP);
        assertFalse(cm.simple.contains("Try this item first"), "only cosmetics can be tried on");
        pick(npc, 1);                 // a forged "try" on a non-cosmetic
        assertEquals(0, cm.count(HIGH_FIVE_STAMP));
        assertEquals(40, cm.stamps);

        npc = shop(cm);
        pick(npc, DONOR_SHOP);
        pick(npc, 0);
        pick(npc, HIGH_FIVE_STAMP);
        pick(npc, 0);
        assertEquals(1, cm.count(HIGH_FIVE_STAMP));
        assertEquals(0, cm.stamps, "exactly 40 taken");

        cm.stamps = 7;
        npc = shop(cm);
        pick(npc, DONOR_NX);
        assertTrue(cm.simple.contains("(6 Slums Stamps)"), cm.simple);
        pick(npc, 0);
        pick(npc, 0);
        pick(npc, FIRST_DONOR_CAP);
        pick(npc, 0);
        assertEquals(1, cm.count(FIRST_DONOR_CAP));
        assertEquals(1, cm.stamps, "exactly 6 taken");
        assertEquals(List.of(40, 6), cm.stampPrices);
        assertEquals(10, cm.count(BILLION_COIN), "Billion Coins buy nothing any more");
        assertEquals(100, cm.count(ROOT_COIN));
    }

    @Test
    void aStampPurchaseWithoutRoomTakesNothing() throws Exception {
        ShopCm cm = new ShopCm();
        cm.stamps = 50;
        Invocable npc = shop(cm);
        pick(npc, DONOR_SHOP);
        pick(npc, 0);
        pick(npc, HIGH_FIVE_STAMP);
        cm.roomForEverything = false;
        pick(npc, 0);
        assertTrue(cm.ok.contains("enough space"), cm.ok);
        assertEquals(50, cm.stamps);
        assertEquals(0, cm.count(HIGH_FIVE_STAMP));
    }

    @Test
    void noSectionIsPricedInBillionCoins() throws Exception {
        String js = Files.readString(Path.of("scripts/npc/rooty.js"));
        assertFalse(js.contains("currency: 4001253"), "the Billion Coin is retired (ticket 111)");
    }

    @Test
    void pagesStepForwardAndBack() throws Exception {
        ShopCm cm = new ShopCm();
        Invocable npc = shop(cm);
        pick(npc, NX_GOODIES);
        pick(npc, 0);
        pick(npc, 1);                 // page 2
        assertTrue(cm.simple.contains("Page 2 of"));
        pick(npc, 0);                 // next
        assertTrue(cm.simple.contains("Page 3 of"));
        pick(npc, 1);                 // previous
        assertTrue(cm.simple.contains("Page 2 of"));
    }
}
