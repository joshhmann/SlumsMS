package client.processor.npc;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MerchantFakeDb;
import service.NoteService;
import tools.DatabaseConnection;
import tools.FilePrinter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.7.0 P0-2b: Fredrick's retrieval is all-or-nothing. It goes in through the public entry point
 * {@link FredrickProcessor#fredrickRetrieveItems}, which exists on the 1.6.5 code too (there the stored rows are
 * deleted before the items are added and nothing is saved with them, so a failed add or a crash lost the goods).
 * The database is the recording {@link MerchantFakeDb}; the stored goods are one stackable line, 15 of item 4000000.
 */
class FredrickRetrieveTest {

    MerchantFakeDb db;
    MockedStatic<DatabaseConnection> dbc;
    MockedStatic<InventoryManipulator> inv;
    MockedStatic<Inventory> invStatic;
    MockedStatic<ItemInformationProvider> iip;
    MockedStatic<FilePrinter> files;
    AtomicInteger wallet;
    boolean saveCommits;
    boolean addWorks;
    Character chr;
    Client client;

    @BeforeEach
    void setUp() throws Exception {
        db = new MerchantFakeDb();
        db.query = FredrickRetrieveTest::storedGoods;
        dbc = mockStatic(DatabaseConnection.class);
        dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());
        inv = mockStatic(InventoryManipulator.class);
        addWorks = true;
        inv.when(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean())).thenAnswer(a -> addWorks);
        invStatic = mockStatic(Inventory.class);
        invStatic.when(() -> Inventory.checkSpotsAndOwnership(any(), any())).thenReturn(true);
        iip = mockStatic(ItemInformationProvider.class);
        iip.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
        files = mockStatic(FilePrinter.class);

        wallet = new AtomicInteger(1_000);
        saveCommits = true;
        client = mock(Client.class);
        when(client.tryacquireClient()).thenReturn(true);
        // The 1.7.0 code asks the character for a MerchantWithdrawal plan; on the 1.6.5 code that type does not exist,
        // so an unstubbed plan is answered by reflection (a no-op plan) and this file compiles against both.
        chr = mock(Character.class, inv2 -> {
            if (inv2.getMethod().getReturnType().getSimpleName().equals("MerchantWithdrawal")) {
                return inv2.getMethod().getReturnType().getDeclaredConstructors()[0].newInstance(0, 0);
            }
            return RETURNS_DEFAULTS.answer(inv2);
        });
        when(chr.getId()).thenReturn(9);
        when(chr.getName()).thenReturn("Owner");
        when(chr.getClient()).thenReturn(client);
        when(client.getPlayer()).thenReturn(chr);
        when(chr.getMeso()).thenAnswer(a -> wallet.get());
        doAnswer(a -> wallet.addAndGet(a.getArgument(0))).when(chr).gainMeso(anyInt(), anyBoolean());
        when(chr.saveCharToDB(anyBoolean(), any(Character.SaveExtra.class))).thenAnswer(a -> {
            if (!saveCommits) {
                return false;
            }
            Character.SaveExtra extra = a.getArgument(1);
            Connection con = db.open();
            con.setAutoCommit(false);
            try {
                extra.run(con);
                con.commit();
                return true;
            } catch (SQLException e) {
                con.rollback();
                return false;
            }
        });
    }

    @AfterEach
    void tearDown() {
        dbc.close();
        inv.close();
        invStatic.close();
        iip.close();
        files.close();
    }

    /** ItemFactory.MERCHANT.loadItems: one ETC line of quantity 5 with 3 bundles, so 15 items. */
    private static ResultSet storedGoods(String sql) {
        ResultSet rs = mock(ResultSet.class);
        try {
            if (sql.contains("inventorymerchant")) {
                when(rs.next()).thenReturn(true, false);
                when(rs.getShort("bundles")).thenReturn((short) 3);
            } else {
                when(rs.next()).thenReturn(true, false);
                when(rs.getInt("inventoryitemid")).thenReturn(1);
                when(rs.getByte("inventorytype")).thenReturn((byte) 4);
                when(rs.getInt("itemid")).thenReturn(4000000);
                when(rs.getInt("position")).thenReturn(1);
                when(rs.getInt("quantity")).thenReturn(5);
                when(rs.getInt("petid")).thenReturn(0);
                when(rs.wasNull()).thenReturn(false);
                when(rs.getString("owner")).thenReturn("");
                when(rs.getLong("expiration")).thenReturn(-1L);
                when(rs.getString("giftFrom")).thenReturn("");
                when(rs.getInt("flag")).thenReturn(0);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return rs;
    }

    private void retrieve() {
        new FredrickProcessor(mock(NoteService.class)).fredrickRetrieveItems(client);
    }

    private boolean deletedFredrickRows() {
        return db.anyCommitted("DELETE FROM `inventoryitems`");
    }

    @Test
    void aSuccessfulRetrievalCommitsTheDeleteTheMerchantMesosTheLogAndTheJournalTogether() {
        retrieve();

        MerchantFakeDb.Conn tx = db.conns.stream().filter(c -> c.committedAny("DELETE FROM `inventoryitems`")).findFirst().orElseThrow();
        assertEquals(1, tx.commits);
        assertTrue(tx.committedAny("MerchantMesos"));
        assertTrue(tx.committedAny("DELETE FROM `fredstorage`"));
        assertTrue(tx.committedAny("operation_journal"), "APPLIED rides in the same commit");
        inv.verify(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean()), times(1));
    }

    @Test
    void aFailedSaveLeavesEverythingStoredAndTheGoodsNotInTheBag() {
        saveCommits = false;

        retrieve();

        assertFalse(deletedFredrickRows(), "Fredrick still holds the goods");
        assertFalse(db.anyCommitted("MerchantMesos"));
        inv.verify(() -> InventoryManipulator.removeById(any(Client.class), any(), eq(4000000), eq(15), eq(false), eq(false)));
        assertEquals(1_000, wallet.get(), "wallet as it was");
    }

    @Test
    void anItemThatCannotBeAddedLeavesEverythingStored() {
        addWorks = false;

        retrieve();

        assertFalse(deletedFredrickRows(), "the stored rows must not be deleted for goods that never reached the bag");
        assertFalse(db.anyCommitted("MerchantMesos"));
        assertEquals(1_000, wallet.get());
    }

    @Test
    void aDeadJournalStopsTheRetrievalBeforeAnythingChanges() {
        db.failOn = "INSERT INTO operation_journal";

        retrieve();

        assertFalse(deletedFredrickRows());
        inv.verify(() -> InventoryManipulator.addFromDrop(any(Client.class), any(Item.class), anyBoolean()), never());
    }
}
