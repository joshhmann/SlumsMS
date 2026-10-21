package server.cashshop;

import client.Character;
import client.Client;
import client.inventory.*;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.CashShop;
import server.ItemInformationProvider;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CashShopPurchaseTest {
    @org.junit.jupiter.api.BeforeAll static void initializeItemProviderWithoutExternalData() throws Exception {
        // The Root singleton loads cards and WZ providers in its static initializer.
        // Supply empty external boundaries before Mockito instruments the singleton.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            var connection = mock(java.sql.Connection.class, RETURNS_DEEP_STUBS);
            db.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }
    final Client client = mock(Client.class);
    final Character player = mock(Character.class);
    final CashShop cash = mock(CashShop.class);
    final ItemInformationProvider info = mock(ItemInformationProvider.class);
    final Map<InventoryType, Inventory> inventories = new EnumMap<>(InventoryType.class);
    final AtomicInteger balance = new AtomicInteger(1000);

    CashShopPurchaseTest() {
        when(client.getPlayer()).thenReturn(player);
        when(client.tryacquireClient()).thenReturn(true);
        when(player.getCashShop()).thenReturn(cash);
        when(player.canHoldUniques(anyList())).thenReturn(true);
        when(cash.getCash(1)).thenAnswer(x -> balance.get());
        doAnswer(x -> { balance.addAndGet(x.getArgument(1)); return null; }).when(cash).gainCash(eq(1), anyInt());
        when(info.getSlotMax(eq(player), anyInt())).thenReturn((short) 100);
        for (InventoryType type : InventoryType.values()) {
            inventories.put(type, new Inventory(player, type, (byte) 10));
        }
        when(player.getInventory(any())).thenAnswer(x -> inventories.get(x.getArgument(0)));
    }

    CashShopCatalog.Row row(int item) { return new CashShopCatalog.Row(item, 100, 1, 1, 0, 0, 2, "Test"); }

    @Test void laterDeliveryFailureRestoresEarlierGrantAndExistingStackWithoutCharging() {
        Item existing = new Item(2000000, (short) 1, (short) 7);
        inventories.get(InventoryType.USE).addItemFromDB(existing);
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var check = mockStatic(Inventory.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            catalog.when(() -> CashShopCatalog.byItemId(2000001)).thenReturn(row(2000001));
            check.when(() -> Inventory.checkSpots(eq(player), anyList())).thenReturn(true);
            AtomicInteger n = new AtomicInteger();
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> {
                        if (n.incrementAndGet() == 1) {
                            existing.setQuantity((short) 8);
                            inventories.get(InventoryType.USE).addItem(new Item(2000000, (short) 0, (short) 1));
                            return true;
                        }
                        return false;
                    });
            var result = CashShopWindowPurchase.buy(client, player, new int[]{2000000, 2000001});
            assertEquals(CashShopWindowPackets.BUY_INVENTORY_FULL, result.code());
            assertEquals(0, result.delivered());
            assertEquals(1000, balance.get());
            assertEquals(List.of(existing), new ArrayList<>(inventories.get(InventoryType.USE).list()));
            assertEquals(7, existing.getQuantity());
            verify(client).releaseClient();
        }
    }

    @Test void successfulCartDeliversAllAndDebitsExactlyOnce() {
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var check = mockStatic(Inventory.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            catalog.when(() -> CashShopCatalog.byItemId(2000001)).thenReturn(row(2000001));
            check.when(() -> Inventory.checkSpots(eq(player), anyList())).thenReturn(true);
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> inventories.get(InventoryType.USE).addItem(call.getArgument(1)) > 0);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{2000000, 2000001});
            assertTrue(result.ok());
            assertEquals(2, result.delivered());
            assertEquals(200, result.spent());
            assertEquals(800, balance.get());
            assertEquals(2, inventories.get(InventoryType.USE).list().size());
            verify(cash).gainCash(1, -200);
        }
    }

    @Test void insufficientBalanceDoesNotGrantAnything() {
        balance.set(50);
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            var result = CashShopWindowPurchase.buy(client, player, new int[]{2000000});
            assertEquals(CashShopWindowPackets.BUY_NO_NX, result.code());
            assertEquals(50, balance.get());
            assertTrue(inventories.get(InventoryType.USE).list().isEmpty());
        }
    }

    @Test void malformedAndDuplicateCartsCannotCharge() {
        assertEquals(CashShopWindowPackets.BUY_BAD_CART, CashShopWindowPurchase.buy(client, player, new int[0]).code());
        assertEquals(CashShopWindowPackets.BUY_BAD_CART, CashShopWindowPurchase.buy(client, player, new int[25]).code());
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            assertEquals(CashShopWindowPackets.BUY_BAD_CART,
                    CashShopWindowPurchase.buy(client, player, new int[]{2000000, 2000000}).code());
            assertEquals(1000, balance.get());
        }
    }

    @Test void exceptionDuringDeliveryAlsoRestoresInventory() {
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var check = mockStatic(Inventory.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            check.when(() -> Inventory.checkSpots(eq(player), anyList())).thenReturn(true);
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> {
                        inventories.get(InventoryType.USE).addItem(call.getArgument(1));
                        throw new IllegalStateException("Injected delivery failure");
                    });
            assertFalse(CashShopWindowPurchase.buy(client, player, new int[]{2000000}).ok());
            assertTrue(inventories.get(InventoryType.USE).list().isEmpty());
            assertEquals(1000, balance.get());
            verify(client).releaseClient();
        }
    }

    @Test void failedPetCartRemovesGeneratedRowsAndGrantedPets() throws Exception {
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var pets = mockStatic(Pet.class);
             var db = mockStatic(tools.DatabaseConnection.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5000000)).thenReturn(row(5000000));
            catalog.when(() -> CashShopCatalog.byItemId(5000001)).thenReturn(row(5000001));
            pets.when(() -> Pet.createPet(5000000)).thenReturn(12345);
            pets.when(() -> Pet.createPet(5000001)).thenReturn(12346);
            var connection = mock(java.sql.Connection.class);
            var statement = mock(java.sql.PreparedStatement.class);
            db.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            when(connection.prepareStatement("DELETE FROM pets WHERE petid=?")).thenReturn(statement);
            AtomicInteger n = new AtomicInteger();
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> n.incrementAndGet() == 1
                            && inventories.get(InventoryType.CASH).addItem(call.getArgument(1)) > 0);
            assertFalse(CashShopWindowPurchase.buy(client, player, new int[]{5000000, 5000001}).ok());
            assertEquals(1000, balance.get());
            assertTrue(inventories.get(InventoryType.CASH).list().isEmpty());
            verify(statement).setInt(1, 12345);
            verify(statement).setInt(1, 12346);
            verify(statement).executeBatch();
        }
    }

    @Test void priceTotalCannotWrapIntoAnAffordableInt() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            for (int id : new int[]{2000000, 2000001}) {
                catalog.when(() -> CashShopCatalog.byItemId(id))
                        .thenReturn(new CashShopCatalog.Row(id, Integer.MAX_VALUE, 1, 1, 0, 0, 2, "Overflow"));
            }
            assertEquals(CashShopWindowPackets.BUY_NO_NX,
                    CashShopWindowPurchase.buy(client, player, new int[]{2000000, 2000001}).code());
            assertEquals(1000, balance.get());
        }
    }

    // ---- services, gates and periods (cashshop-fixes) ----

    CashShopCatalog.Row row(int item, int price, int period) {
        return new CashShopCatalog.Row(item, price, 1, 5, 2, period, 2, "Test");
    }

    @Test void slotExpansionGrowsTheTabAndChargesOnlyOnSuccess() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(9111000)).thenReturn(row(9111000, 640, 0));
            when(player.canGainSlots(1, 8)).thenReturn(true);
            when(player.gainSlotsUnsaved(1, 8)).thenReturn(true);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{9111000});
            assertTrue(result.ok());
            assertEquals(1, result.delivered());
            assertEquals(360, balance.get());
            verify(player).gainSlotsUnsaved(1, 8);
            verify(player, never()).gainSlots(anyInt(), anyInt(), anyBoolean());
            for (Inventory inventory : inventories.values()) assertTrue(inventory.list().isEmpty());
        }
    }

    @Test void slotExpansionAtTheCapIsRefusedWithAMessageAndNoCharge() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(9114000)).thenReturn(row(9114000, 680, 0));
            when(player.canGainSlots(4, 8)).thenReturn(false);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{9114000});
            assertEquals(CashShopWindowPackets.BUY_LIMIT_REACHED, result.code());
            assertEquals(1000, balance.get());
            verify(player, never()).gainSlotsUnsaved(anyInt(), anyInt());
            verify(player).dropMessage(eq(1), contains("cannot be expanded"));
        }
    }

    @Test void storageExpansionIsNotAService() {
        assertEquals(-1, CashShopServices.slotExpansionType(9110000));
        assertEquals(1, CashShopServices.slotExpansionType(9111000));
        assertEquals(4, CashShopServices.slotExpansionType(9114000));
        assertFalse(CashShopServices.isService(9110000));
        assertFalse(CashShopServices.isService(9115000));
        assertTrue(CashShopServices.isService(5430000));
    }

    @Test void characterSlotIsGrantedNotDelivered() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5430000)).thenReturn(row(5430000, 690, 0));
            when(client.canGainCharacterSlot()).thenReturn(true);
            when(client.gainCharacterSlot()).thenReturn(true);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{5430000});
            assertTrue(result.ok());
            assertEquals(310, balance.get());
            verify(client).gainCharacterSlot();
            assertTrue(inventories.get(InventoryType.CASH).list().isEmpty());
        }
    }

    @Test void characterSlotAtTheCapIsRefusedWithoutCharge() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5430000)).thenReturn(row(5430000, 690, 0));
            when(client.canGainCharacterSlot()).thenReturn(false);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{5430000});
            assertEquals(CashShopWindowPackets.BUY_LIMIT_REACHED, result.code());
            assertEquals(1000, balance.get());
            verify(client, never()).gainCharacterSlot();
        }
    }

    @Test void lostCharacterSlotRaceUndoesSlotsAndItemsAndCharges() {
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var check = mockStatic(Inventory.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(2000000)).thenReturn(row(2000000));
            catalog.when(() -> CashShopCatalog.byItemId(9112000)).thenReturn(row(9112000, 100, 0));
            catalog.when(() -> CashShopCatalog.byItemId(5430000)).thenReturn(row(5430000, 100, 0));
            check.when(() -> Inventory.checkSpots(eq(player), anyList())).thenReturn(true);
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> inventories.get(InventoryType.USE).addItem(call.getArgument(1)) > 0);
            when(player.canGainSlots(2, 8)).thenReturn(true);
            when(player.gainSlotsUnsaved(anyInt(), anyInt())).thenReturn(true);
            when(client.canGainCharacterSlot()).thenReturn(true);
            when(client.gainCharacterSlot()).thenReturn(false);
            var result = CashShopWindowPurchase.buy(client, player, new int[]{2000000, 9112000, 5430000});
            assertFalse(result.ok());
            assertEquals(1000, balance.get());
            verify(player).gainSlotsUnsaved(2, 8);
            verify(player).gainSlotsUnsaved(2, -8);
            assertTrue(inventories.get(InventoryType.USE).list().isEmpty());
        }
    }

    @Test void stockLevelGatesApplyInTheWindow() {
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5030000)).thenReturn(row(5030000));
            catalog.when(() -> CashShopCatalog.byItemId(5140000)).thenReturn(row(5140000));
            catalog.when(() -> CashShopCatalog.byItemId(5431000)).thenReturn(row(5431000));
            catalog.when(() -> CashShopCatalog.byItemId(5432000)).thenReturn(row(5432000));
            when(player.getLevel()).thenReturn(15);
            for (int id : new int[]{5030000, 5140000, 5431000, 5432000}) {
                assertEquals(CashShopWindowPackets.BUY_LEVEL_TOO_LOW,
                        CashShopWindowPurchase.buy(client, player, new int[]{id}).code(), "item " + id);
            }
            when(player.getLevel()).thenReturn(29);
            assertNull(CashShopServices.check(client, player, 5030000));
            assertEquals(CashShopWindowPackets.BUY_LEVEL_TOO_LOW, CashShopServices.check(client, player, 5432000).code());
            when(player.getLevel()).thenReturn(30);
            assertNull(CashShopServices.check(client, player, 5431000));
            assertEquals(1000, balance.get());
        }
    }

    @Test void rateCouponsAreHiddenAndRefusedWhileTheSwitchIsOff() {
        final var server = config.YamlConfig.config.server;
        final boolean was = server.USE_SUPPLY_RATE_COUPONS;
        try (var ii = mockStatic(ItemInformationProvider.class); var catalog = mockStatic(CashShopCatalog.class)) {
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5211048)).thenReturn(row(5211048, 100, 1));
            server.USE_SUPPLY_RATE_COUPONS = false;
            assertFalse(CashShopServices.isListed(5211048));
            assertFalse(CashShopServices.isListed(5360000));
            assertTrue(CashShopServices.isListed(5431000), "Maple Life is not a rate coupon here");
            assertEquals(CashShopWindowPackets.BUY_NOT_ON_SALE,
                    CashShopWindowPurchase.buy(client, player, new int[]{5211048}).code());
            assertEquals(1000, balance.get());
            server.USE_SUPPLY_RATE_COUPONS = true;
            assertTrue(CashShopServices.isListed(5211048));
            assertNull(CashShopServices.check(client, player, 5211048));
        } finally {
            server.USE_SUPPLY_RATE_COUPONS = was;
        }
    }

    @Test void hiddenRowsLeaveTheIndexAndTheirCategory() {
        final var server = config.YamlConfig.config.server;
        final boolean was = server.USE_SUPPLY_RATE_COUPONS;
        try (var catalog = mockStatic(CashShopCatalog.class)) {
            catalog.when(CashShopCatalog::index).thenReturn(List.of(new int[]{5, 0, 2}, new int[]{5, 1, 1}));
            catalog.when(() -> CashShopCatalog.bucket(5, 0)).thenReturn(List.of(row(5211048), row(5360000)));
            catalog.when(() -> CashShopCatalog.bucket(5, 1)).thenReturn(List.of(row(5040000)));
            server.USE_SUPPLY_RATE_COUPONS = false;
            var idx = net.server.channel.handlers.CashShopWindowHandler.visibleIndex();
            assertEquals(1, idx.size());
            assertArrayEquals(new int[]{5, 1, 1}, idx.get(0));
            assertTrue(net.server.channel.handlers.CashShopWindowHandler.visibleBucket(5, 0).isEmpty());
            server.USE_SUPPLY_RATE_COUPONS = true;
            assertEquals(2, net.server.channel.handlers.CashShopWindowHandler.visibleIndex().size());
        } finally {
            server.USE_SUPPLY_RATE_COUPONS = was;
        }
    }

    @Test void rateCouponsExpireByTheirPeriod() {
        final var server = config.YamlConfig.config.server;
        final boolean was = server.USE_SUPPLY_RATE_COUPONS;
        final long now = 1_000_000_000L;
        try (var ii = mockStatic(ItemInformationProvider.class);
             var catalog = mockStatic(CashShopCatalog.class);
             var check = mockStatic(Inventory.class);
             var srv = mockStatic(net.server.Server.class);
             var delivery = mockStatic(InventoryManipulator.class)) {
            server.USE_SUPPLY_RATE_COUPONS = true;
            var serverInstance = mock(net.server.Server.class);
            srv.when(net.server.Server::getInstance).thenReturn(serverInstance);
            when(serverInstance.getCurrentTime()).thenReturn(now);
            ii.when(ItemInformationProvider::getInstance).thenReturn(info);
            catalog.when(() -> CashShopCatalog.byItemId(5211060)).thenReturn(row(5211060, 100, 1));
            catalog.when(() -> CashShopCatalog.byItemId(5211004)).thenReturn(row(5211004, 100, 30));
            check.when(() -> Inventory.checkSpots(eq(player), anyList())).thenReturn(true);
            List<Item> got = new ArrayList<>();
            delivery.when(() -> InventoryManipulator.addFromDrop(eq(client), any(Item.class), eq(false)))
                    .thenAnswer(call -> got.add(call.getArgument(1)));
            assertTrue(CashShopWindowPurchase.buy(client, player, new int[]{5211060, 5211004}).ok());
            assertEquals(now + java.util.concurrent.TimeUnit.HOURS.toMillis(2), got.get(0).getExpiration());
            assertEquals(now + java.util.concurrent.TimeUnit.DAYS.toMillis(30), got.get(1).getExpiration());
        } finally {
            server.USE_SUPPLY_RATE_COUPONS = was;
        }
    }
}
