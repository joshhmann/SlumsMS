package tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pet stored with expiration Long.MAX_VALUE used to overflow (x10000) into a 1970 FILETIME on the client. */
class PermanentExpirationTimeTest {

    private static final long NO_EXPIRY = PacketCreator.getTime(-1);

    @Test
    void longMaxEncodesAsTheStockNoExpiryValue() {
        assertEquals(NO_EXPIRY, PacketCreator.getTime(Long.MAX_VALUE));
        assertEquals(NO_EXPIRY, PacketCreator.getTime(Long.MAX_VALUE - 1));
    }

    @Test
    void noExpiryIsFarInTheFuture() {
        assertTrue(NO_EXPIRY > PacketCreator.getTime(System.currentTimeMillis() + 30L * 365 * 86_400_000));
    }

    @Test
    void ordinaryExpirationsAreUnchanged() {
        long t = System.currentTimeMillis() + 86_400_000L;
        assertTrue(PacketCreator.getTime(t) > PacketCreator.getTime(t - 1));
        assertTrue(PacketCreator.getTime(t) < NO_EXPIRY);
    }
}
