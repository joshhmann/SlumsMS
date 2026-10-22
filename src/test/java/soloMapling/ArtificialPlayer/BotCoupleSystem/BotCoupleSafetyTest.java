package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.5.0 pre-release review: weddings only take free bots; loans, rings and loads never leave a bot changed for good. */
class BotCoupleSafetyTest {

    static final int A = BotStore.BOT_ID_BASE + 11;
    static final int B = BotStore.BOT_ID_BASE + 12;
    static final int C = BotStore.BOT_ID_BASE + 13;
    static final int D = BotStore.BOT_ID_BASE + 14;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    final CoupleStore.Memory store = new CoupleStore.Memory();
    final Map<Integer, Character> online = new HashMap<>();

    @BeforeEach
    void setUp() {
        BotCouples.useForTest(store, online::get);
        BotWeddings.resetForTest();
        OutfitLoans.useEquipperForTest((bot, itemId, slot) -> { // as the stock equip, without item data
            bot.getInventory(InventoryType.EQUIPPED).removeSlot(slot);
            bot.getInventory(InventoryType.EQUIPPED).addItemFromDB(new Item(itemId, slot, (short) 1));
            bot.equipChanged();
        });
    }

    @AfterEach
    void tearDown() {
        BotCouples.useForTest(null, null);
        BotWeddings.resetForTest();
        OutfitLoans.useEquipperForTest(null);
        CharacterStorage.removeActiveBot(A);
    }

    /** A bot with a real equipped inventory (no map: equipChanged is a mock no-op). */
    private static Character dressable(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("Bot" + id);
        Inventory equipped = new Inventory(c, InventoryType.EQUIPPED, (byte) 100);
        when(c.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        return c;
    }

    private static Item wear(Character c, int itemId, short slot) {
        Item e = new Item(itemId, slot, (short) 1);
        c.getInventory(InventoryType.EQUIPPED).addItemFromDB(e);
        return e;
    }

    private static int at(Character c, short slot) {
        Item i = c.getInventory(InventoryType.EQUIPPED).getItem(slot);
        return i == null ? 0 : i.getItemId();
    }

    // ── P1-1: a wedding only takes free bots ──

    @Test
    void aWeddingSkipsACoupleWithABusyBotAndTakesTheNextOne() {
        BotCouples.Couple ab = BotCouples.form(A, B, false, 0);
        BotCouples.Couple cd = BotCouples.form(C, D, false, 0);
        for (int id : new int[]{A, B, C, D}) {
            online.put(id, dressable(id));
        }
        BotCouples.useWeddingFreeForTest(chr -> chr.getId() != A); // A is partied with a player
        assertEquals(cd.id(), BotCouples.weddingCouple(BotCouples.all(), 3 * DAY).id());
        BotCouples.useWeddingFreeForTest(chr -> false);
        assertNull(BotCouples.weddingCouple(BotCouples.all(), 3 * DAY), "every bot busy: no wedding");
        BotCouples.useWeddingFreeForTest(chr -> true);
        assertEquals(ab.id(), BotCouples.weddingCouple(BotCouples.all(), 3 * DAY).id());
    }

    @Test
    void onlyATrainingOrSocialBotFreeOfPlayersIsFreeForAWedding() {
        Character a = dressable(A);
        assertFalse(BotCouples.freeForWedding(a), "not a live bot");
        BotSM follower = mock(soloMapling.ArtificialPlayer.BotTypes.FollowerBot.class);
        when(follower.getChr()).thenReturn(a);
        CharacterStorage.addActiveBot(A, follower);
        assertFalse(BotCouples.freeForWedding(a), "a follower is never taken");
    }

    @Test
    void aBotInACeremonyMayNotLogOut() {
        Character a = dressable(A);
        BotSM sm = mock(BotSM.class);
        when(sm.getChr()).thenReturn(a);
        assertTrue(BotPopulationDirector.mayLogOut(sm));
        BotWeddings.markInCeremonyForTest(A);
        assertFalse(BotPopulationDirector.mayLogOut(sm), "it would leave in the wedding clothes");
    }

    // ── P2-5: the wedding outfit is a loan ──

    @Test
    void theWeddingOutfitIsALoanTheStoreNeverWrites() {
        Character a = dressable(A);
        Item own = wear(a, 1052000, BotWeddings.OVERALL_SLOT);
        BotWeddings.dress(null, a, BotWeddings.TUXEDO);
        Item worn = a.getInventory(InventoryType.EQUIPPED).getItem(BotWeddings.OVERALL_SLOT);
        assertEquals(BotWeddings.TUXEDO, worn.getItemId());
        assertSame(own, OutfitLoans.persisted(a, worn), "the store writes its own overall");
        BotWeddings.undress(null, a);
        assertSame(own, a.getInventory(InventoryType.EQUIPPED).getItem(BotWeddings.OVERALL_SLOT), "its own back");
        assertSame(own, OutfitLoans.persisted(a, own));
    }

    @Test
    void aTuxLeftOnByAnOlderBuildIsNeverTakenForTheBotsOwnAndComesOff() {
        Character a = dressable(A);
        wear(a, BotWeddings.WEDDING_DRESS, BotWeddings.OVERALL_SLOT); // stored as its own by 1.5.0-rc3
        BotWeddings.dress(null, a, BotWeddings.TUXEDO);
        BotWeddings.undress(null, a);
        assertEquals(0, at(a, BotWeddings.OVERALL_SLOT), "the old dress is not 'its own': the slot is left empty");

        Character b = dressable(B);
        wear(b, BotWeddings.TUXEDO, BotWeddings.OVERALL_SLOT);
        BotWeddings.markInCeremonyForTest(B);
        assertFalse(BotWeddings.takeOffWeddingClothes(b), "in a ceremony: it keeps it on");
        BotWeddings.resetForTest();
        assertTrue(BotWeddings.takeOffWeddingClothes(b), "outside one: off it comes");
        assertEquals(0, at(b, BotWeddings.OVERALL_SLOT));
        assertFalse(BotWeddings.takeOffWeddingClothes(b));
    }

    // ── P2-6: the matching hat is a loan ──

    @Test
    void aMatchingHatIsALoanAndTheOwnHatComesBackTheNextDay() {
        BotCouples.Couple c = BotCouples.form(A, B, false, 0);
        Character a = dressable(A);
        Character b = dressable(B);
        wear(a, 1002357, BotCouples.CASH_HAT_SLOT);
        Item ownHat = wear(b, 1002186, BotCouples.CASH_HAT_SLOT);
        long matching = -1;
        long plain = -1;
        for (int d = 0; d < 60 && (matching < 0 || plain < 0); d++) {
            if (BotCouples.matchingDay(c, d * DAY + 1000)) {
                matching = matching < 0 ? d * DAY + 1000 : matching;
            } else if (matching >= 0) {
                plain = d * DAY + 1000;
            }
        }
        BotCouples.matchHats(c, a, b, matching);
        Item worn = b.getInventory(InventoryType.EQUIPPED).getItem(BotCouples.CASH_HAT_SLOT);
        assertEquals(1002357, worn.getItemId(), "matching");
        assertSame(ownHat, OutfitLoans.persisted(b, worn), "the stored row keeps its own hat");
        BotCouples.matchHats(c, a, b, matching + 60_000); // the next minute: nothing new is lent
        assertSame(ownHat, OutfitLoans.persisted(b, b.getInventory(InventoryType.EQUIPPED).getItem(BotCouples.CASH_HAT_SLOT)));
        BotCouples.matchHats(c, a, b, plain);
        assertSame(ownHat, b.getInventory(InventoryType.EQUIPPED).getItem(BotCouples.CASH_HAT_SLOT), "its own hat back");
    }

    @Test
    void aLoanOfAnEndedSessionIsIgnored() {
        Character first = dressable(A);
        wear(first, 1002186, BotCouples.CASH_HAT_SLOT);
        OutfitLoans.lend(first, BotCouples.CASH_HAT_SLOT, 1002357);
        Character relogged = dressable(A); // logged out and in: a new character object, its row held its own hat
        Item own = wear(relogged, 1002186, BotCouples.CASH_HAT_SLOT);
        assertSame(own, OutfitLoans.persisted(relogged, own));
        assertFalse(OutfitLoans.giveBack(relogged, BotCouples.CASH_HAT_SLOT));
        assertSame(own, relogged.getInventory(InventoryType.EQUIPPED).getItem(BotCouples.CASH_HAT_SLOT));
    }

    // ── P2-7: rings ──

    @Test
    void aCoupleRingTakesAFreeSlotAndNeverReplacesTheBotsOwnRing() {
        Character a = dressable(A);
        Item own = wear(a, 1112400, (short) -12);
        int ring = BotCouples.WEDDING_RINGS[0];
        assertTrue(BotCouples.wearRing(a, ring));
        assertSame(own, a.getInventory(InventoryType.EQUIPPED).getItem((short) -12), "its own ring stays");
        assertEquals(ring, at(a, (short) -13));
        Item couple = a.getInventory(InventoryType.EQUIPPED).getItem((short) -13);
        assertTrue(BotCouples.wearRing(a, ring));
        assertSame(couple, a.getInventory(InventoryType.EQUIPPED).getItem((short) -13), "on already: not re-equipped");
        verify(a, times(1)).equipChanged();

        Character full = dressable(B);
        for (short slot : BotCouples.RING_SLOTS) {
            wear(full, 1112400, slot);
        }
        assertFalse(BotCouples.wearRing(full, ring), "no free slot: it goes without");
        for (short slot : BotCouples.RING_SLOTS) {
            assertEquals(1112400, at(full, slot));
        }
    }

    @Test
    void aBreakUpTakesTheRingsOff() {
        BotCouples.Couple f = BotCouples.form(A, B, BotCouples.Stage.FRIENDS, false, 0);
        Character a = dressable(A);
        Character b = dressable(B);
        online.put(A, a);
        online.put(B, b);
        Item own = wear(a, 1112400, (short) -12);
        BotCouples.wearRing(a, BotCouples.ringOf(f));
        BotCouples.wearRing(b, BotCouples.ringOf(f));
        assertTrue(BotCouples.breakUp(f.id()));
        assertSame(own, a.getInventory(InventoryType.EQUIPPED).getItem((short) -12), "its own ring stays on");
        for (short slot : BotCouples.RING_SLOTS) {
            assertFalse(BotCouples.coupleRing(at(a, slot)));
            assertFalse(BotCouples.coupleRing(at(b, slot)));
        }
    }

    // ── P2-14: the blessing ──

    @Test
    void nobodyIsBlessedBeforeTheCoupleIsMarriedAndEachPlayerOnce() {
        BotCouples.Couple c = BotCouples.form(A, B, false, 0);
        Character groom = dressable(A);
        MapleMap map = mock(MapleMap.class);
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(42);
        when(player.getLevel()).thenReturn(30);
        when(map.getAllPlayers()).thenReturn(List.of(player));
        when(groom.getMap()).thenReturn(map);
        BotWeddings.Ceremony ceremony = new BotWeddings.Ceremony(c, A, B, 0);
        BotWeddings.bless(ceremony, groom);
        verify(player, never()).gainExp(anyInt(), anyBoolean(), anyBoolean());
        ceremony.married = true;
        BotWeddings.bless(ceremony, groom);
        BotWeddings.bless(ceremony, groom);
        verify(player, times(1)).gainExp(anyInt(), anyBoolean(), anyBoolean());
    }

    // ── P2-15: loading ──

    @Test
    void aFailedLoadIsNotAnEmptyTableAndNothingFormsUntilItLoads() {
        store.insert(new BotCouples.Couple(0, 11, 12, BotCouples.Stage.DATING, 5, 0, 0));
        store.failing = true;
        BotCouples.step(DAY, new java.util.Random(1));
        assertFalse(BotCouples.loaded());
        assertNull(BotCouples.form(C, D, false, DAY), "no write before the table is read");
        assertEquals(1, store.rows.size());
        store.failing = false;
        BotCouples.retryLoadForTest();
        BotCouples.step(DAY, new java.util.Random(1));
        assertTrue(BotCouples.loaded());
        assertEquals(B, BotCouples.partnerOf(A), "the stored couple is back");
        assertNotNull(BotCouples.form(C, D, false, DAY));
    }

    @Test
    void aBotInTwoStoredCouplesKeepsTheOlderOneAndCannotJoinASecond() {
        store.rows.put(1L, new BotCouples.Couple(1, 11, 12, BotCouples.Stage.DATING, 5, 0, 0));
        store.rows.put(2L, new BotCouples.Couple(2, 13, 11, BotCouples.Stage.DATING, 6, 0, 0)); // 11 in both columns
        assertEquals(1, BotCouples.all().size());
        assertEquals(B, BotCouples.partnerOf(A));
        assertEquals(-1, BotCouples.partnerOf(C));
        store.rows.put(3L, new BotCouples.Couple(3, 15, 14, BotCouples.Stage.DATING, 7, 0, 0)); // stored meanwhile
        assertNull(BotCouples.form(D, C, false, DAY), "D (14) is in a stored couple's second column");
    }
}
