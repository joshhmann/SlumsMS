package client.inventory;

import client.BotClient;
import client.Character;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Adventurer's Bag (@bp) 1.6.1: nothing comes out cleaner than it went in, every move is all-or-nothing, and the
 * amounts are bounded. No WZ needed: the WZ facts come from {@link #RULES}.
 */
class AdventurerBagTest {

    static final int ORE = 4010000;          // ETC, slot max 100 here
    static final int POTION = 2000000;       // USE
    static final int PQ_PASS = 4001008;      // refused (party-quest item)
    static final int ONLY_ONE = 4031999;     // WZ "only"
    static final int STAR = 2070000;         // rechargeable: never baggable

    static final AdventurerBag.Rules RULES = new AdventurerBag.Rules() {
        @Override public short slotMax(Character chr, int itemId) { return 100; }
        @Override public boolean refused(int itemId) { return itemId == PQ_PASS; }
        @Override public boolean oneOfAKind(int itemId) { return itemId == ONLY_ONE; }
        @Override public void stampNew(Item fresh) { }
        @Override public void announce(Character chr, int mode, Item item) { }
    };

    /** RULES, but ORE is an account-bound id (WZ), so it always carries ACCOUNT_SHARING by itself. */
    static final AdventurerBag.Rules BOUND_ORE_RULES = new AdventurerBag.Rules() {
        @Override public short slotMax(Character chr, int itemId) { return RULES.slotMax(chr, itemId); }
        @Override public boolean refused(int itemId) { return RULES.refused(itemId); }
        @Override public boolean oneOfAKind(int itemId) { return RULES.oneOfAKind(itemId); }
        @Override public boolean accountBound(int itemId) { return itemId == ORE; }
        @Override public void stampNew(Item fresh) { flag(fresh, ItemConstants.ACCOUNT_SHARING); }
        @Override public void announce(Character chr, int mode, Item item) { }
    };

    /** Item.setFlag asks the WZ provider about account-bound ids; tests set the raw field instead. */
    static void flag(Item it, int flag) {
        try {
            java.lang.reflect.Field f = Item.class.getDeclaredField("flag");
            f.setAccessible(true);
            f.setShort(it, (short) flag);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static Character newChar() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(4242);
        return chr;
    }

    static AdventurerBag bag(Map<Integer, Integer> rows) {
        return new AdventurerBag(() -> new HashMap<>(rows));
    }

    static Item stack(Character chr, int itemId, int qty) {
        Item it = new Item(itemId, (short) 0, (short) qty);
        assertNotEquals(-1, chr.getInventory(ItemConstants.getInventoryType(itemId)).addItem(it));
        return it;
    }

    static int count(Character chr, int itemId) {
        return chr.getInventory(ItemConstants.getInventoryType(itemId)).countById(itemId);
    }

    static List<Item> stacks(Character chr, int itemId) {
        return chr.getInventory(ItemConstants.getInventoryType(itemId)).listById(itemId);
    }

    // ---- (a) flags, expiry and owner are never washed off -------------------------------------------------------

    @Test
    void flaggedExpiringOrTaggedStacksStayInTheInventory() {
        for (int variant = 0; variant < 6; variant++) {
            Character chr = newChar();
            AdventurerBag bag = bag(Map.of());
            Item it = stack(chr, ORE, 10);
            switch (variant) {
                case 0 -> flag(it, ItemConstants.UNTRADEABLE);
                case 1 -> flag(it, ItemConstants.KARMA_USE);
                case 2 -> flag(it, (short) (ItemConstants.ACCOUNT_SHARING | ItemConstants.UNTRADEABLE | ItemConstants.SANDBOX));
                case 3 -> flag(it, ItemConstants.LOCK);
                case 4 -> it.setExpiration(System.currentTimeMillis() + 3_600_000L);
                default -> it.setOwner("SomeGM");
            }

            assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, ORE, 10, RULES), "variant " + variant);
            assertEquals(0, bag.amount(ORE), "variant " + variant);
            assertEquals(10, count(chr, ORE), "variant " + variant);
            assertSame(it, stacks(chr, ORE).get(0), "the original stack, with its flags, is untouched");
            assertEquals(0, AdventurerBag.depositable(chr, ORE));
        }
    }

    @Test
    void anAccountBoundIdsOwnStampDoesNotBlockIt() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of());
        Item it = stack(chr, ORE, 10);
        flag(it, ItemConstants.ACCOUNT_SHARING);    // what Item.setFlag adds to every stack of an accountSharable id
        assertEquals(AdventurerBag.Result.OK, bag.deposit(chr, ORE, 10, BOUND_ORE_RULES));
        assertEquals(10, bag.amount(ORE));
        assertEquals(AdventurerBag.Result.OK, bag.withdraw(chr, ORE, 10, BOUND_ORE_RULES));
        assertEquals(ItemConstants.ACCOUNT_SHARING, stacks(chr, ORE).get(0).getFlag(), "the flag comes back exactly");
    }

    @Test
    void anAccountSharedStackOfAnOrdinaryItemStaysOutBecauseItCouldNotComeBackExactly() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of());
        Item it = stack(chr, ORE, 10);
        flag(it, ItemConstants.ACCOUNT_SHARING);    // a share mark on an id that is not account-bound by itself
        assertFalse(AdventurerBag.isPlain(it, RULES));
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, ORE, 10, RULES));
        assertEquals(0, bag.amount(ORE));
        assertEquals(0, AdventurerBag.depositable(chr, ORE, RULES));
        assertFalse(bag.storePickup(it, RULES), "auto-pickup leaves it for the inventory");
        assertSame(it, stacks(chr, ORE).get(0), "the stack is untouched");
    }

    @Test
    void onlyThePlainStackIsTakenWhenBothKindsAreHeld() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of());
        Item bound = stack(chr, ORE, 5);          // slot 1, first in slot order
        flag(bound, ItemConstants.UNTRADEABLE);
        stack(chr, ORE, 7);                       // slot 2, plain

        assertEquals(7, AdventurerBag.depositable(chr, ORE));
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, ORE, 8, RULES), "8 would need the bound stack");
        assertEquals(AdventurerBag.Result.OK, bag.deposit(chr, ORE, 7, RULES));

        assertEquals(7, bag.amount(ORE));
        List<Item> left = stacks(chr, ORE);
        assertEquals(1, left.size());
        assertSame(bound, left.get(0));
        assertEquals(5, bound.getQuantity());
        assertEquals(ItemConstants.UNTRADEABLE, bound.getFlag());
    }

    @Test
    void withdrawnItemsArePlainAndNeverMergeIntoAFlaggedOrExpiringStack() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, 30));
        Item bound = stack(chr, ORE, 5);
        flag(bound, ItemConstants.UNTRADEABLE);
        Item expiring = stack(chr, ORE, 5);
        expiring.setExpiration(System.currentTimeMillis() + 60_000L);
        long exp = expiring.getExpiration();

        assertEquals(AdventurerBag.Result.OK, bag.withdraw(chr, ORE, 30, RULES));

        assertEquals(5, bound.getQuantity());
        assertEquals(5, expiring.getQuantity());
        assertEquals(exp, expiring.getExpiration(), "an existing expiry is not wiped by the top-up");
        Item fresh = stacks(chr, ORE).stream().filter(x -> AdventurerBag.isPlain(x, RULES)).findFirst().orElseThrow();
        assertEquals(30, fresh.getQuantity());
        assertEquals(0, bag.amount(ORE));
    }

    @Test
    void questPartyQuestOneOfAKindAndRechargeableItemsAreRefused() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of());
        stack(chr, PQ_PASS, 3);
        stack(chr, ONLY_ONE, 1);
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, PQ_PASS, 3, RULES));
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, ONLY_ONE, 1, RULES));
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, STAR, 1, RULES));
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, 1302000, 1, RULES), "equips never go in");
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.deposit(chr, 5000000, 1, RULES), "cash/pets never go in");
        assertEquals(3, count(chr, PQ_PASS));
        assertEquals(1, count(chr, ONLY_ONE));
    }

    @Test
    void autoPickupKeepsFlaggedOrRefusedDropsOutOfTheBag() {
        AdventurerBag bag = bag(Map.of());
        Item plain = new Item(ORE, (short) 0, (short) 4);
        Item tagged = new Item(ORE, (short) 0, (short) 4);
        tagged.setOwner("x");
        Item expiring = new Item(POTION, (short) 0, (short) 4);
        expiring.setExpiration(System.currentTimeMillis() + 60_000L);
        Item pass = new Item(PQ_PASS, (short) 0, (short) 1);

        assertTrue(bag.storePickup(plain, RULES));
        assertFalse(bag.storePickup(tagged, RULES));
        assertFalse(bag.storePickup(expiring, RULES));
        assertFalse(bag.storePickup(pass, RULES));
        assertEquals(4, bag.amount(ORE));
        assertEquals(0, bag.amount(POTION));
        assertEquals(0, bag.amount(PQ_PASS));
    }

    // ---- (b) all-or-nothing moves, no dupes -----------------------------------------------------------------------

    @Test
    void aOneOfAKindItemAlreadyHeldStaysInTheBagInsteadOfVanishing() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ONLY_ONE, 1));
        stack(chr, ONLY_ONE, 1);
        assertEquals(AdventurerBag.Result.NOT_ALLOWED, bag.withdraw(chr, ONLY_ONE, 1, RULES));
        assertEquals(1, bag.amount(ONLY_ONE), "the old script decremented the bag even though gainItem gave nothing");
        assertEquals(1, count(chr, ONLY_ONE));
    }

    @Test
    void aWithdrawThatDoesNotFitChangesNothing() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, 5_000));
        Inventory etc = chr.getInventory(InventoryType.ETC);
        int free = etc.getNumFreeSlot();
        int fits = free * 100;

        assertEquals(AdventurerBag.Result.NO_SPACE, bag.withdraw(chr, ORE, fits + 1, RULES));
        assertEquals(5_000, bag.amount(ORE));
        assertEquals(0, count(chr, ORE));

        assertEquals(AdventurerBag.Result.OK, bag.withdraw(chr, ORE, fits, RULES));
        assertEquals(5_000 - fits, bag.amount(ORE));
        assertEquals(fits, count(chr, ORE));
        for (Item it : stacks(chr, ORE)) {
            assertTrue(it.getQuantity() <= 100, "no stack past the slot max");
        }
        assertEquals(0, etc.getNumFreeSlot());
    }

    @Test
    void withdrawUpToTakesOnlyWhatFitsAndKeepsTheRest() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, 64_000));   // > 32767: the old script's gainItem(short) threw here
        int fits = chr.getInventory(InventoryType.ETC).getNumFreeSlot() * 100;

        int moved = bag.withdrawUpTo(chr, ORE, 64_000, RULES);

        assertEquals(Math.min(fits, 64_000), moved);
        assertEquals(64_000, moved + bag.amount(ORE));
        assertEquals(moved, count(chr, ORE));
    }

    @Test
    void twoWithdrawsRacingForTheSameStockCannotBothSucceed() throws Exception {
        for (int round = 0; round < 50; round++) {
            Character chr = newChar();
            AdventurerBag bag = bag(Map.of(ORE, 100));
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger ok = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (bag.withdraw(chr, ORE, 100, RULES) == AdventurerBag.Result.OK) {
                        ok.incrementAndGet();
                    }
                });
                th.start();
                threads.add(th);
            }
            go.countDown();
            for (Thread th : threads) {
                th.join();
            }
            assertEquals(1, ok.get());
            assertEquals(100, count(chr, ORE) + bag.amount(ORE), "items in + items stored never grows");
        }
    }

    @Test
    void depositThenWithdrawConservesTheTotal() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of());
        stack(chr, POTION, 100);
        stack(chr, POTION, 40);
        assertEquals(AdventurerBag.Result.OK, bag.deposit(chr, POTION, 120, RULES));
        assertEquals(20, count(chr, POTION));
        assertEquals(120, bag.amount(POTION));
        assertEquals(AdventurerBag.Result.NOT_ENOUGH, bag.withdraw(chr, POTION, 121, RULES));
        assertEquals(AdventurerBag.Result.OK, bag.withdraw(chr, POTION, 120, RULES));
        assertEquals(140, count(chr, POTION));
        assertEquals(0, bag.amount(POTION));
    }

    @Test
    void aBagThatFailsToLoadRefusesToWork() {
        AdventurerBag broken = new AdventurerBag(() -> {
            throw new IllegalStateException("db down");
        });
        Character chr = newChar();
        stack(chr, ORE, 10);
        assertThrows(IllegalStateException.class, () -> broken.deposit(chr, ORE, 10, RULES));
        assertEquals(10, count(chr, ORE), "nothing taken when the bag can't be read");
    }

    @Test
    void theSaveWritesTheCurrentAmountsAndAddsMissingRowsOnce() throws Exception {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, 50));
        stack(chr, POTION, 10);
        assertEquals(AdventurerBag.Result.OK, bag.deposit(chr, POTION, 10, RULES));
        assertEquals(AdventurerBag.Result.OK, bag.withdraw(chr, ORE, 20, RULES));

        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement update = mock(PreparedStatement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(con.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.startsWith("SELECT") ? select : sql.startsWith("UPDATE") ? update : insert;
        });
        when(select.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getInt(1)).thenReturn(ORE);

        bag.save(con, 4242);

        verify(update).setInt(1, 30);
        verify(update).setInt(3, ORE);
        verify(update, times(1)).executeUpdate();
        verify(insert).setInt(2, POTION);
        verify(insert).setInt(3, 10);
        verify(insert, times(1)).executeUpdate();
    }

    @Test
    void aBagNeverOpenedThisSessionWritesNothing() throws Exception {
        AdventurerBag bag = bag(Map.of(ORE, 50));
        Connection con = mock(Connection.class);
        bag.save(con, 1);
        verifyNoInteractions(con);
    }

    // ---- (c) amounts ---------------------------------------------------------------------------------------------

    @Test
    void zeroNegativeAndHugeAmountsAreRejected() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, 10));
        stack(chr, ORE, 10);
        for (int bad : new int[]{0, -1, -32768, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(AdventurerBag.Result.BAD_AMOUNT, bag.deposit(chr, ORE, bad, RULES), "deposit " + bad);
            assertEquals(AdventurerBag.Result.BAD_AMOUNT, bag.withdraw(chr, ORE, bad, RULES), "withdraw " + bad);
            assertFalse(bag.add(ORE, bad));
            assertFalse(bag.take(ORE, bad));
        }
        assertEquals(10, bag.amount(ORE));
        assertEquals(10, count(chr, ORE));
    }

    @Test
    void theBagStopsAtItsPerItemCapInsteadOfOverflowing() {
        Character chr = newChar();
        AdventurerBag bag = bag(Map.of(ORE, AdventurerBag.MAX_PER_ITEM - 5));
        stack(chr, ORE, 10);
        assertEquals(AdventurerBag.Result.BAG_FULL, bag.deposit(chr, ORE, 10, RULES));
        assertEquals(10, count(chr, ORE));
        assertFalse(bag.storePickup(new Item(ORE, (short) 0, (short) 6), RULES));
        assertTrue(bag.add(ORE, 5));
        assertEquals(AdventurerBag.MAX_PER_ITEM, bag.amount(ORE));
        assertFalse(bag.add(ORE, Integer.MAX_VALUE), "no int overflow");
    }

    @Test
    void depositableCountsOnlyPlainStacksOfBaggableTypes() {
        Character chr = newChar();
        stack(chr, ORE, 3);
        Item tagged = stack(chr, ORE, 4);
        tagged.setOwner("me");
        assertEquals(3, AdventurerBag.depositable(chr, ORE));
        assertEquals(0, AdventurerBag.depositable(chr, STAR));
    }
}
