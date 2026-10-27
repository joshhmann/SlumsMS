package soloMapling.server;

import org.junit.jupiter.api.Test;
import tools.DatabaseConnection;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** 1.6.0 nx-1: the NX merchant's code: its format, the store (one transaction), and minting. */
class NXCodeManagerTest {

    @Test
    void codeIsFifteenUnambiguousCharactersInThreeDashedGroups() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String code = NXCodeManager.generateGiftCardCode();
            assertTrue(code.matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{5}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{5}-"
                    + "[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{5}"), code);
            String stored = NXCodeManager.normalize(code);
            assertEquals(15, stored.length());
            assertTrue(stored.length() <= 17, "fits nxcode.code varchar(17)");
            seen.add(code);
        }
        assertEquals(500, seen.size());
    }

    @Test
    void normalizeDropsDashesAndSpacesAndUpperCases() {
        assertEquals("ABCDE23456FGHJK", NXCodeManager.normalize("abcde-23456-fghjk"));
        assertEquals("ABCDE23456FGHJK", NXCodeManager.normalize(" ABCDE 23456-FGHJK "));
        assertEquals("", NXCodeManager.normalize(null));
    }

    @Test
    void storesTheCodeWithoutDashesAndItsTenKNxCreditRow() throws SQLException {
        FakeNxDb db = new FakeNxDb();
        int id = NXCodeManager.createCompleteNXCode(db.connection(), "ABCDE-23456-FGHJK", 10000);
        assertEquals(1, id);
        Map<String, Object> row = db.code("ABCDE23456FGHJK");
        assertNotNull(row);
        assertNull(row.get("retriever"));
        assertTrue((long) row.get("expiration") > System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000);
        assertEquals(1, db.items.size());
        assertEquals(id, db.items.get(0).get("codeid"));
        assertEquals(4, db.items.get(0).get("type"), "type 4 = NX credit in CouponCodeHandler");
        assertEquals(10000, db.items.get(0).get("quantity"));
    }

    @Test
    void aFailedItemRowRollsTheCodeBack() throws SQLException {
        // before: the code row stayed (and was typed to the buyer) with no NX row, so redeeming it credited nothing
        FakeNxDb db = new FakeNxDb();
        db.failOn = "nxcode_items";
        assertThrows(SQLException.class, () -> NXCodeManager.createCompleteNXCode(db.connection(), "ABCDE-23456-FGHJK", 10000));
        assertTrue(db.codes.isEmpty());
        assertTrue(db.items.isEmpty());
    }

    @Test
    void idsComeFromAutoIncrementNotMaxPlusOne() throws SQLException {
        FakeNxDb db = new FakeNxDb();
        assertEquals(1, NXCodeManager.createCompleteNXCode(db.connection(), "AAAAA-AAAAA-AAAAA", 10000));
        assertEquals(2, NXCodeManager.createCompleteNXCode(db.connection(), "BBBBB-BBBBB-BBBBB", 10000));
        assertEquals(2, db.items.size());
    }

    @Test
    void mintReturnsAStoredCodeOrNullWhenTheDatabaseIsDown() throws SQLException {
        FakeNxDb db = new FakeNxDb();
        try (var database = mockStatic(DatabaseConnection.class)) {
            database.when(DatabaseConnection::getConnection).thenAnswer(a -> db.connection());
            String code = NXCodeManager.mintCode(10000);
            assertNotNull(code);
            assertNotNull(db.code(NXCodeManager.normalize(code)));

            db.failOn = "INSERT";
            assertNull(NXCodeManager.mintCode(10000), "no code is handed out unless it was stored");
        }
    }
}
