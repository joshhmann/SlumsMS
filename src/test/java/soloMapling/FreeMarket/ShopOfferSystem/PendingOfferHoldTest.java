package soloMapling.FreeMarket.ShopOfferSystem;

import client.Character;
import client.Client;
import client.inventory.Item;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.HiredMerchant;
import server.maps.PlayerShopItem;
import soloMapling.server.MethodScheduler;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * b181 F-04 / F-05: a stall slot took a lock when an offer was accepted and never gave it back, and the delayed AFK
 * accept priced whatever listing sat at that slot minutes later. The shop below is a hired merchant whose owner is
 * away, so every offer is answered on a timer: the timers are captured here instead of run.
 */
class PendingOfferHoldTest {

    private static final int LISTING_PRICE = 5_000_000;
    private static final int SHELL = 4_000_000;

    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<Packet> whispers = new ArrayList<>();

    private MockedStatic<MethodScheduler> scheduler;
    private MockedStatic<ItemInformationProvider> itemData;
    private HiredMerchant merchant;
    private PlayerShopItem listed;
    private Character buyer;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getName(anyInt())).thenReturn("Blue Snail Shell");
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);

        scheduler = mockStatic(MethodScheduler.class);
        scheduler.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong()))
                .thenAnswer(call -> {
                    scheduled.add(call.getArgument(0));
                    return null;
                });

        listed = listing(LISTING_PRICE);
        merchant = mock(HiredMerchant.class);
        when(merchant.getOwnerId()).thenReturn(4242);
        when(merchant.getOwner()).thenReturn("Bobby");
        when(merchant.getMapId()).thenReturn(910000001);
        when(merchant.isOpen()).thenReturn(true);
        when(merchant.getItems()).thenReturn(List.of(listed));

        buyer = mock(Character.class);
        when(buyer.getName()).thenReturn("Buyer");
        when(buyer.isLoggedin()).thenReturn(true);
        Client client = mock(Client.class);
        when(client.getChannel()).thenReturn(0);
        when(buyer.getClient()).thenReturn(client);
        doAnswer(call -> {
            whispers.add(call.getArgument(0));
            return null;
        }).when(buyer).sendPacket(any(Packet.class));
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        itemData.close();
    }

    @Test
    void aSettledOfferGivesTheSlotBack() {
        stallOwnedBy(4242);
        offer();
        assertEquals(1, scheduled.size(), "the offer was taken");

        offer();
        assertEquals(1, scheduled.size(), "a second offer waits behind the first");

        scheduled.get(0).run();   // the owner's answer lands
        offer();
        assertEquals(2, scheduled.size(), "the slot takes offers again once the first one settled");
    }

    @Test
    void aSlotWhoseListingWasReplacedGivesTheSlotBack() {
        stallOwnedBy(4243);
        offer();
        assertEquals(1, scheduled.size());

        // whatever was in slot 0 was sold and another listing took the slot while the answer waited
        when(merchant.getItems()).thenReturn(List.of(listing(1_000_000)));
        offer("ill give 1m for the blue snail shell");
        assertEquals(2, scheduled.size(), "the hold died with its listing, so the slot is free again");
    }

    @Test
    void anAcceptThatWaitedOnAGoneListingDoesNotPriceItAndTellsThePlayer() {
        stallOwnedBy(4244);
        offer();
        assertEquals(1, scheduled.size());

        // someone else took the listing while the owner was away
        when(merchant.getItems()).thenReturn(List.of(listing(1_000_000)));
        scheduled.get(0).run();

        assertEquals(LISTING_PRICE, listed.getPrice(), "the gone listing keeps the price it had");
        verify(merchant, never()).broadcastToVisitorsThreadsafe(any());
        assertEquals(1, whispers.size(), "the buyer is told the deal is off");
        String line = whisperMessage(whispers.get(0));
        assertTrue(line.contains("is gone from my shop"), "the buyer is told why: " + line);
    }

    /** Each test owns its own stall, so a hold one test leaves behind is never another test's. */
    private void stallOwnedBy(int ownerId) {
        when(merchant.getOwnerId()).thenReturn(ownerId);
    }

    private void offer() {
        offer("ill give 5m for the blue snail shell");
    }

    private void offer(String message) {
        ShopOfferSystem.getInstance().onHiredMerchantChat(buyer, merchant, message);
    }

    private static PlayerShopItem listing(int price) {
        return new PlayerShopItem(new Item(SHELL, (short) 1, (short) 1), (short) 1, price);
    }

    private static String whisperMessage(Packet packet) {
        ByteBufInPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(packet.getBytes()));
        in.readShort();   // opcode
        in.readByte();    // whisper | receive
        in.readString();  // sender
        in.readByte();    // channel
        in.readByte();    // from an admin
        return in.readString();
    }
}
