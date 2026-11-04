package server;

import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * persist PS-2 (b294): {@code Storage.saveToDB} runs inside the caller's character-save transaction, after
 * {@code ItemFactory} has already deleted the account's {@code inventoryitems} rows. The swallow meant a failed
 * insert left the transaction to commit the DELETE: the storage contents were gone and nobody was told.
 *
 * <p>The fake connection below is a transaction: the DELETE takes the rows out of the visible table and remembers
 * them, the INSERT is made to fail, and {@code rollback()} puts the rows back. The test drives the same contract
 * {@code Character.saveCharToDB} does — the exception must reach the caller, and the caller's rollback must leave
 * the old rows present.
 */
class StorageSaveFailureRollbackTest {

    private static final int STORAGE_ID = 1;
    private static final String OLD_ROW = "2:1";

    @Test
    void aFailedStorageInsertThrowsAndTheInventoryDeleteRollsBack() throws Exception {
        FakeTransaction db = new FakeTransaction();
        db.rows.add(OLD_ROW);
        Connection con = db.connection();

        Storage storage = storage();
        storage.store(new Item(4000000, (short) 0, (short) 1));

        Throwable thrown = null;
        try {
            storage.saveToDB(con);
        } catch (Exception e) {
            // what Character.saveCharToDB does with a failing statement inside its transaction
            thrown = e;
            con.rollback();
        }

        assertNotNull(thrown, "a failed storage save must not return normally: the caller rolls back on it");
        assertInstanceOf(SQLException.class, thrown);
        assertTrue(db.insertAttempted, "the fake INSERT must have run, or this proves nothing");
        assertTrue(db.deleteRan, "the inventory DELETE runs before the INSERT");
        assertTrue(db.rows.contains(OLD_ROW), "the rollback must put the old storage rows back: " + db.rows);
        assertEquals(0, db.deletedSize(), "nothing may stay deleted after the rollback");
    }

    private static Storage storage() throws Exception {
        Constructor<Storage> c = Storage.class.getDeclaredConstructor(int.class, byte.class, int.class);
        c.setAccessible(true);
        return c.newInstance(STORAGE_ID, (byte) 4, 0);
    }

    /** Just enough of a JDBC transaction for one storage save: storages UPDATE, inventoryitems DELETE, INSERT fails. */
    private static final class FakeTransaction {

        final List<String> rows = new ArrayList<>();      // "type:accountid"
        private final List<String> pendingDeleted = new ArrayList<>();
        private boolean deleteRan;
        private boolean insertAttempted;

        int deletedSize() {
            return pendingDeleted.size();
        }

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                        case "prepareStatement" -> statement((String) a[0]);
                        case "rollback" -> {
                            rows.addAll(pendingDeleted);
                            pendingDeleted.clear();
                            yield null;
                        }
                        case "commit" -> {
                            pendingDeleted.clear();
                            yield null;
                        }
                        case "getAutoCommit" -> true;
                        case "isClosed" -> false;
                        case "equals" -> p == a[0];
                        case "hashCode" -> System.identityHashCode(p);
                        case "toString" -> "FakeTransaction";
                        default -> null;
                    });
        }

        private PreparedStatement statement(String sql) {
            Map<Integer, Object> params = new HashMap<>();
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                            case "setInt", "setString", "setLong", "setShort", "setBoolean", "setNull" -> {
                                params.put((Integer) a[0], a.length > 1 ? a[1] : null);
                                yield null;
                            }
                            case "executeUpdate" -> {
                                String s = sql.stripLeading().toUpperCase();
                                if (s.startsWith("UPDATE")) {
                                    yield 1;
                                }
                                if (s.startsWith("DELETE")) {
                                    deleteRan = true;
                                    String type = String.valueOf(params.get(1));
                                    String owner = String.valueOf(params.get(2));
                                    String key = type + ":" + owner;
                                    int removed = 0;
                                    while (rows.remove(key)) {
                                        pendingDeleted.add(key);
                                        removed++;
                                    }
                                    yield removed;
                                }
                                if (s.startsWith("INSERT")) {
                                    insertAttempted = true;
                                    throw new SQLException("simulated inventoryitems insert failure");
                                }
                                yield 0;
                            }
                            case "getGeneratedKeys" -> (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{ResultSet.class}, (rp, rm, ra) -> switch (rm.getName()) {
                                        case "next" -> false;
                                        case "equals" -> rp == ra[0];
                                        case "hashCode" -> System.identityHashCode(rp);
                                        case "toString" -> "empty generated keys";
                                        default -> null;
                                    });
                            case "equals" -> p == a[0];
                            case "hashCode" -> System.identityHashCode(p);
                            case "toString" -> "FakeStatement";
                            default -> null;
                    });
        }
    }
}
