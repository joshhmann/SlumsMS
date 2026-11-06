package client;

import client.processor.npc.FredrickProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MerchantFakeDb;
import tools.DatabaseConnection;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * 1.7.0 P0-2b: the MerchantMesos writes and the Fredrick log delete used to swallow SQL errors (printStackTrace), so
 * a failed write looked like a success and the mesos could be taken twice. The failure now reaches the caller and
 * nothing is half-applied. The return values are read by reflection so this file also compiles, and fails, on the
 * 1.6.5 code where those methods return void.
 */
class MerchantMesosFailureTest {

    MerchantFakeDb db;
    MockedStatic<DatabaseConnection> dbc;

    @BeforeEach
    void setUp() {
        db = new MerchantFakeDb();
        dbc = mockStatic(DatabaseConnection.class);
        dbc.when(DatabaseConnection::getConnection).thenAnswer(a -> db.open());
    }

    @AfterEach
    void tearDown() {
        dbc.close();
    }

    private static Object call(Object target, Class<?> type, String name, Class<?>[] sig, Object... args) throws Exception {
        Method m = type.getMethod(name, sig);
        return m.invoke(target, args);
    }

    private static Character fresh() {
        return Character.getDefault(mock(Client.class));
    }

    private void breakDb(String fragment) {
        db.failOn = fragment;
        db.failState = "40001";   // a deadlock with the owner's autosave
    }

    @Test
    void aFailedAddMerchantMesosIsReportedAndChangesNothing() throws Exception {
        Character chr = fresh();
        assertEquals(Boolean.TRUE, call(chr, Character.class, "addMerchantMesos", new Class<?>[]{int.class}, 50));
        assertEquals(50, chr.getMerchantMeso());

        breakDb("MerchantMesos");
        Object result = call(chr, Character.class, "addMerchantMesos", new Class<?>[]{int.class}, 70);

        assertEquals(Boolean.FALSE, result, "the caller must be told the write failed");
        assertEquals(50, chr.getMerchantMeso(), "memory did not move ahead of the database");
    }

    @Test
    void aFailedSetMerchantMesoIsReportedAndChangesNothing() throws Exception {
        Character chr = fresh();
        call(chr, Character.class, "setMerchantMeso", new Class<?>[]{int.class}, 80);

        breakDb("MerchantMesos");
        Object result = call(chr, Character.class, "setMerchantMeso", new Class<?>[]{int.class}, 0);

        assertEquals(Boolean.FALSE, result);
        assertEquals(80, chr.getMerchantMeso());
    }

    @Test
    void aFailedFredrickLogRemovalIsReported() throws Exception {
        assertEquals(Boolean.TRUE, call(null, FredrickProcessor.class, "removeFredrickLog", new Class<?>[]{int.class}, 5));

        breakDb("fredstorage");
        Object result = call(null, FredrickProcessor.class, "removeFredrickLog", new Class<?>[]{int.class}, 5);

        assertEquals(Boolean.FALSE, result, "the Fredrick log delete must not look like it worked");
    }

    private Character ownerWithMerchantMesos(int merchantMesos) {
        Character chr = spy(fresh());
        doReturn(merchantMesos).when(chr).getMerchantNetMeso();
        try {
            doAnswer(a -> {
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
            }).when(chr).saveCharToDB(anyBoolean(), any(Character.SaveExtra.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return chr;
    }

    @Test
    void aFailedWithdrawalNeitherPaysTheWalletNorSpendsTheMerchantMesos() {
        Character chr = ownerWithMerchantMesos(500);
        breakDb("MerchantMesos");

        chr.withdrawMerchantMesos();

        assertEquals(0, chr.getMeso(), "no mesos handed out when the MerchantMesos write failed (else they are taken twice)");
        assertFalse(db.anyCommitted("MerchantMesos"));
    }

    @Test
    void aWithdrawalThatSucceedsPaysTheWalletAndZeroesTheMerchantMesosInOneCommit() {
        Character chr = ownerWithMerchantMesos(500);

        chr.withdrawMerchantMesos();

        assertEquals(500, chr.getMeso());
        assertEquals(1, db.totalCommits());
        assertTrue(db.anyCommitted("MerchantMesos"));
    }
}
