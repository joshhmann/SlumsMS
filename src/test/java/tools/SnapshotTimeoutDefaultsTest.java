package tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bounds a snapshot connection opens with (follow-up to ticket 174): 10 s to open and 30 s per socket read, and a
 * property that says 0, a negative number or nonsense must not switch either bound off - the driver reads 0 as "wait
 * forever", which is the unbounded hold on {@code LOAD_LOCK} / {@code ROSTER_LOCK} the change removes.
 *
 * <p>{@link SnapshotConnectionTimeoutTest} proves the bounds are honoured against a socket that never answers; this
 * class pins the shipped numbers and the fallback, both of which a driver would otherwise accept silently.
 */
class SnapshotTimeoutDefaultsTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(DatabaseConnection.SNAPSHOT_CONNECT_TIMEOUT_PROPERTY);
        System.clearProperty(DatabaseConnection.SNAPSHOT_SOCKET_TIMEOUT_PROPERTY);
    }

    @Test
    void theShippedBoundsAreTenSecondsToOpenAndThirtySecondsToRead() {
        assertAll("a snapshot connection is bounded by default",
                () -> assertEquals(10_000, DatabaseConnection.snapshotConnectTimeoutMs()),
                () -> assertEquals(30_000, DatabaseConnection.snapshotSocketTimeoutMs()));
    }

    @Test
    void aPropertyThatIsNotAPositiveNumberKeepsTheBoundedDefault() {
        for (String off : new String[]{"0", "-1", "nonsense", " "}) {
            System.setProperty(DatabaseConnection.SNAPSHOT_CONNECT_TIMEOUT_PROPERTY, off);
            System.setProperty(DatabaseConnection.SNAPSHOT_SOCKET_TIMEOUT_PROPERTY, off);
            assertAll("a bound of '" + off + "' keeps the bounded default",
                    () -> assertEquals(10_000, DatabaseConnection.snapshotConnectTimeoutMs()),
                    () -> assertEquals(30_000, DatabaseConnection.snapshotSocketTimeoutMs()));
        }
    }

    @Test
    void aPositivePropertyOverridesTheBound() {
        System.setProperty(DatabaseConnection.SNAPSHOT_CONNECT_TIMEOUT_PROPERTY, "2000");
        System.setProperty(DatabaseConnection.SNAPSHOT_SOCKET_TIMEOUT_PROPERTY, "45000");

        assertAll("a deployment whose database answers slowly can widen the bounds",
                () -> assertEquals(2_000, DatabaseConnection.snapshotConnectTimeoutMs()),
                () -> assertEquals(45_000, DatabaseConnection.snapshotSocketTimeoutMs()));
    }
}
