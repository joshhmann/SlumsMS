package server.cashshop;

import client.BotClient;
import client.Character;
import client.Client;
import client.Ring;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import net.packet.OutPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.CashShop;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The Cash Shop window's couple/friendship ring purchase ({@link CashShopPartnerRings}): both ring rows, the
 * partner's copy, all-or-nothing, clean refusals, no dupes on a retried answer.
 */
class CashShopPartnerRingsTest {
    static final int FRIEND = 1112800;     // Friendship Ring : Clover
    static final int COUPLE = 1112001;     // Crush Ring
    static final int PRICE = 3600;

    @BeforeAll static void initializeItemProviderWithoutExternalData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            var connection = mock(java.sql.Connection.class, RETURNS_DEEP_STUBS);
            db.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    final Client buyerClient = mock(Client.class);
    final Client partnerClient = mock(Client.class);
    final Character buyer = mock(Character.class);
    final Character partner = mock(Character.class);
    final CashShop cash = mock(CashShop.class);
    final ItemInformationProvider info = mock(ItemInformationProvider.class);
    final Map<InventoryType, Inventory> buyerInv = new EnumMap<>(InventoryType.class);
    final Map<InventoryType, Inventory> partnerInv = new EnumMap<>(InventoryType.class);
    final AtomicInteger balance = new AtomicInteger(10_000);
    int startBalance = 10_000;
    final List<Ring> buyerRings = new ArrayList<>();
    final List<Ring> partnerRings = new ArrayList<>();

    /** The rings table, in memory. */
    final class FakeStore implements CashShopPartnerRings.RingStore {
        final List<int[]> rows = new ArrayList<>();
        int next = 500;
        int created;
        boolean fail;

        public int[] createPair(int itemId, Character a, Character b) throws java.sql.SQLException {
            if (fail) throw new java.sql.SQLException("injected");
            created++;
            int[] ids = {next++, next++};
            rows.add(ids);
            return ids;
        }

        public void deletePair(int[] ids) {
            rows.removeIf(r -> r[0] == ids[0] && r[1] == ids[1]);
        }
    }

    final class FakeDirectory implements CashShopPartnerRings.Directory {
        Character online = partner;
        boolean exists = true;

        public Character online(Character b, String name) {
            return online != null && name.equalsIgnoreCase(online.getName()) ? online : null;
        }

        public boolean exists(String name) {
            return exists;
        }
    }

    final class FakePrompt implements CashShopPartnerRings.Prompt {
        int asked;
        boolean ok = true;

        public void closeOpen(Client c) {
        }

        public boolean ask(Client c, Character chr, int itemId) {
            asked++;
            return ok;
        }
    }

    final FakeStore store = new FakeStore();
    final FakeDirectory directory = new FakeDirectory();
    final FakePrompt prompt = new FakePrompt();

    CashShopPartnerRingsTest() {
        CashShopPartnerRings.store = store;
        CashShopPartnerRings.directory = directory;
        CashShopPartnerRings.prompt = prompt;
        CashShopPartnerRings.clearPending();

        setUp(buyer, buyerClient, 101, "Romeo", buyerInv, buyerRings);
        setUp(partner, partnerClient, 202, "Mercutio", partnerInv, partnerRings);
        // Same gender on purpose: the owner allows any two players to share a couple ring.
        when(buyer.getGender()).thenReturn(0);
        when(partner.getGender()).thenReturn(0);
        when(buyer.getCashShop()).thenReturn(cash);
        when(buyerClient.tryacquireClient()).thenReturn(true);
        when(cash.getCash(1)).thenAnswer(x -> balance.get());
        doAnswer(x -> { balance.addAndGet(x.getArgument(1)); return null; }).when(cash).gainCash(eq(1), anyInt());
        when(info.getEquipById(anyInt())).thenAnswer(x -> new Equip(x.getArgument(0), (short) 0));
    }

    private void setUp(Character chr, Client client, int id, String name, Map<InventoryType, Inventory> inv,
                       List<Ring> rings) {
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getWorld()).thenReturn(0);
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        for (InventoryType type : InventoryType.values()) {
            inv.put(type, new Inventory(chr, type, (byte) 24));
        }
        when(chr.getInventory(any())).thenAnswer(x -> inv.get(x.getArgument(0)));
        doAnswer(x -> rings.add(x.getArgument(0))).when(chr).addPlayerRing(any(Ring.class));
        when(chr.getCrushRings()).thenAnswer(x -> rings.stream().filter(r -> r.getItemId() <= 1112012).toList());
        when(chr.getFriendshipRings()).thenAnswer(x -> rings.stream().filter(r -> r.getItemId() > 1112012).toList());
    }

    @AfterEach void restoreSeams() {
        CashShopPartnerRings.store = new CashShopPartnerRings.DbRingStore();
        CashShopPartnerRings.directory = new CashShopPartnerRings.GameDirectory();
        CashShopPartnerRings.prompt = new CashShopPartnerRings.ScriptPrompt();
        CashShopPartnerRings.clearPending();
    }

    static CashShopCatalog.Row row(int item) {
        return new CashShopCatalog.Row(item, PRICE, 1, 2, 9, 0, 2, "Test Ring");
    }

    /** Runs {@code body} with the catalogue, the item provider and inventory delivery stubbed. */
    private void withGame(java.util.function.Consumer<MockedStatic<InventoryManipulator>> body) {
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(FRIEND)).thenReturn(row(FRIEND));
            catalog.when(() -> CashShopCatalog.byItemId(COUPLE)).thenReturn(row(COUPLE));
            delivery.when(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), eq(false)))
                    .thenAnswer(call -> {
                        Client to = call.getArgument(0);
                        Item item = call.getArgument(1);
                        Map<InventoryType, Inventory> inv = to == buyerClient ? buyerInv : partnerInv;
                        return inv.get(InventoryType.EQUIP).addItem(item) >= 0;
                    });
            body.accept(delivery);
        }
    }

    private static Equip onlyRing(Map<InventoryType, Inventory> inv) {
        List<Item> items = new ArrayList<>(inv.get(InventoryType.EQUIP).list());
        assertEquals(1, items.size(), "exactly one ring in the Equip inventory");
        return (Equip) items.get(0);
    }

    @Test void purchaseCreatesBothRingRowsAndGivesThePartnerTheirs() {
        withGame(delivery -> {
            var r = CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "  mercutio ");
            assertTrue(r.ok(), "code " + r.code());
            assertEquals(PRICE, r.spent());
            assertEquals(10_000 - PRICE, balance.get());

            assertEquals(1, store.rows.size());
            int[] ids = store.rows.get(0);
            Equip mine = onlyRing(buyerInv);
            Equip theirs = onlyRing(partnerInv);
            assertEquals(FRIEND, mine.getItemId());
            assertEquals(FRIEND, theirs.getItemId());
            assertEquals(ids[0], mine.getRingId());
            assertEquals(ids[1], theirs.getRingId());

            assertEquals(1, buyerRings.size());
            assertEquals(1, partnerRings.size());
            Ring a = buyerRings.get(0), b = partnerRings.get(0);
            assertEquals(202, a.getPartnerChrId());
            assertEquals("Mercutio", a.getPartnerName());
            assertEquals(ids[1], a.getPartnerRingId());
            assertEquals(101, b.getPartnerChrId());
            assertEquals(ids[0], b.getPartnerRingId());
            verify(partner).dropMessage(eq(1), contains("Romeo bought you a Test Ring"));
            verify(buyerClient).releaseClient();
        });
    }

    @Test void sameGenderCoupleRingIsSold() {
        withGame(delivery -> {
            assertTrue(CashShopPartnerRings.purchase(buyerClient, buyer, COUPLE, "Mercutio").ok());
            assertEquals(1, buyerRings.size());
            assertEquals(1, buyer.getCrushRings().size(), "a couple ring goes in the crush list");
            assertEquals(1, partner.getCrushRings().size());
        });
    }

    @Test void offlinePartnerIsRefusedCleanly() {
        directory.online = null;
        directory.exists = true;
        withGame(delivery -> {
            var r = CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio");
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED, r.code());
            verify(buyer).dropMessage(eq(1), contains("Mercutio is not online"));
            assertNothingHappened(delivery);
        });
    }

    @Test void nonExistentPartnerIsRefusedCleanly() {
        directory.online = null;
        directory.exists = false;
        withGame(delivery -> {
            var r = CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Nobody");
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED, r.code());
            verify(buyer).dropMessage(eq(1), contains("There is no character named Nobody"));
            assertNothingHappened(delivery);
        });
    }

    @Test void selfBotAndBlankPartnersAreRefused() {
        withGame(delivery -> {
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED,
                    CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "romeo").code());
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED,
                    CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "   ").code());
            when(partner.getClient()).thenReturn(mock(BotClient.class));
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED,
                    CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio").code());
            verify(buyer).dropMessage(eq(1), contains("another player"));
            assertNothingHappened(delivery);
        });
    }

    @Test void notEnoughNxChargesNothing() {
        balance.set(PRICE - 1);
        startBalance = PRICE - 1;
        withGame(delivery -> {
            assertEquals(CashShopWindowPackets.BUY_NO_NX,
                    CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio").code());
            assertNothingHappened(delivery);
        });
    }

    @Test void partnerWithAFullEquipTabIsRefusedBeforeAnyRow() {
        withGame(delivery -> {
            for (short slot = 1; slot <= 24; slot++) {
                partnerInv.get(InventoryType.EQUIP).addItemFromDB(new Equip(1302000, slot));
            }
            var r = CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio");
            assertEquals(CashShopWindowPackets.BUY_PARTNER_REFUSED, r.code());
            verify(buyer).dropMessage(eq(1), contains("no free slot"));
            assertEquals(0, store.created);
            assertEquals(10_000, balance.get());
        });
    }

    @Test void failedPartnerDeliveryDeletesBothRowsAndTakesTheBuyersRingBack() {
        withGame(delivery -> {
            delivery.when(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), eq(false)))
                    .thenAnswer(call -> {
                        Client to = call.getArgument(0);
                        if (to == partnerClient) return false;
                        return buyerInv.get(InventoryType.EQUIP).addItem(call.getArgument(1)) >= 0;
                    });
            var r = CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio");
            assertFalse(r.ok());
            assertEquals(1, store.created);
            assertTrue(store.rows.isEmpty(), "both ring rows deleted again");
            assertTrue(buyerInv.get(InventoryType.EQUIP).list().isEmpty(), "buyer's copy taken back");
            assertTrue(partnerInv.get(InventoryType.EQUIP).list().isEmpty());
            assertEquals(10_000, balance.get());
            assertTrue(buyerRings.isEmpty());
            assertTrue(partnerRings.isEmpty());
        });
    }

    @Test void ringTableFailureChargesNothing() {
        store.fail = true;
        withGame(delivery -> {
            assertFalse(CashShopPartnerRings.purchase(buyerClient, buyer, FRIEND, "Mercutio").ok());
            assertNothingHappened(delivery);
            verify(buyerClient).releaseClient();
        });
    }

    @Test void aRetriedAnswerDoesNotBuyTwice() {
        withGame(delivery -> {
            CashShopPartnerRings.begin(buyerClient, buyer, FRIEND);
            assertEquals(1, prompt.asked);
            assertEquals(FRIEND, CashShopPartnerRings.pendingItem(buyer));

            String first = CashShopPartnerRings.complete(buyerClient, buyer, "Mercutio");
            String again = CashShopPartnerRings.complete(buyerClient, buyer, "Mercutio");
            assertTrue(first.startsWith("Done"), first);
            assertTrue(again.contains("no ring purchase waiting"), again);

            assertEquals(1, store.created);
            assertEquals(10_000 - PRICE, balance.get());
            assertEquals(1, buyerInv.get(InventoryType.EQUIP).list().size());
            assertEquals(1, partnerInv.get(InventoryType.EQUIP).list().size());

            // Buying the same ring with the same partner again is refused as well.
            CashShopPartnerRings.begin(buyerClient, buyer, FRIEND);
            String third = CashShopPartnerRings.complete(buyerClient, buyer, "Mercutio");
            assertTrue(third.startsWith("No ring"), third);
            verify(buyer).dropMessage(eq(1), contains("already share"));
            assertEquals(1, store.created);
            assertEquals(10_000 - PRICE, balance.get());
        });
    }

    @Test void closingThePromptCancelsAndAnswersTheWindow() {
        withGame(delivery -> {
            CashShopPartnerRings.begin(buyerClient, buyer, FRIEND);
            CashShopPartnerRings.dialogClosed(buyerClient, buyer);
            assertEquals(0, CashShopPartnerRings.pendingItem(buyer));
            assertTrue(CashShopPartnerRings.complete(buyerClient, buyer, "Mercutio").contains("no ring purchase"));
            assertNothingHappened(delivery);
            verify(buyerClient, atLeastOnce()).sendPacket(any());
        });
    }

    @Test void aPromptThatCannotOpenRefusesWithoutParking() {
        prompt.ok = false;
        withGame(delivery -> {
            CashShopPartnerRings.begin(buyerClient, buyer, FRIEND);
            assertEquals(0, CashShopPartnerRings.pendingItem(buyer));
            assertNothingHappened(delivery);
        });
    }

    @Test void aCartNeverTakesAPartnerRing() {
        when(buyer.canHoldUniques(anyList())).thenReturn(true);
        withGame(delivery -> {
            when(info.getSlotMax(eq(buyer), anyInt())).thenReturn((short) 1);
            var r = CashShopWindowPurchase.buy(buyerClient, buyer, new int[]{FRIEND});
            assertEquals(CashShopWindowPackets.BUY_BAD_CART, r.code());
            assertEquals(FRIEND, r.failedSn());
            assertNothingHappened(delivery);
        });
    }

    @Test void soldRingsAreCoupleOrFriendshipNeverWedding() {
        for (int id : CashShopPartnerRings.COUPLE_RINGS) {
            // Character.addPlayerRing files ids up to 1112012 as crush (couple) rings.
            assertTrue(id >= 1112001 && id <= 1112012, "couple " + id);
        }
        for (int id : CashShopPartnerRings.FRIENDSHIP_RINGS) {
            assertTrue(id > 1112012 && id / 100 == 11128, "friendship " + id);
            assertFalse(ItemId.isWeddingRing(id), "wedding " + id);
            assertFalse(id >= 1112803 && id <= 1112809, "wedding/engagement " + id);
        }
    }

    @Test void theRingLookCarriesOneRecordPerList() throws Exception {
        Character chr = mock(Character.class);
        Ring a = new Ring(1, 2, 9, 1112800, "A");
        Ring b = new Ring(3, 4, 8, 1112801, "B");
        a.equip();
        b.equip();
        when(chr.getFriendshipRings()).thenReturn(List.of(a, b));
        when(chr.getCrushRings()).thenReturn(List.of());
        var m = tools.PacketCreator.class.getDeclaredMethod("addRingLook", OutPacket.class, Character.class, boolean.class);
        m.setAccessible(true);

        OutPacket friend = mock(OutPacket.class);
        m.invoke(null, friend, chr, false);
        verify(friend, times(1)).writeByte(1);
        verify(friend).writeInt(1112800);
        verify(friend, never()).writeInt(1112801);

        OutPacket crush = mock(OutPacket.class);
        m.invoke(null, crush, chr, true);
        verify(crush).writeByte(0);
        verify(crush, never()).writeInt(anyInt());
    }

    private void assertNothingHappened(MockedStatic<InventoryManipulator> delivery) {
        assertEquals(0, store.created, "no ring rows");
        assertEquals(startBalance, balance.get(), "no NX taken");
        delivery.verify(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean()),
                never());
        assertTrue(buyerRings.isEmpty());
        assertTrue(partnerRings.isEmpty());
    }
}
