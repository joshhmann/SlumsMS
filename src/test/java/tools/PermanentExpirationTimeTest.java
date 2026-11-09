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

    /** starter-pet-dried-up (9 Nov 2026): the client counts a pet dead when dateDead >= DEFAULT_TIME. */
    @Test
    void aPermanentPetsDateDeadIsPermanentNotTheNoExpiryValue() {
        long permanent = 150841440000000000L;   // 00 C0 9B 90 7D E5 17 02, 2078-12-31
        assertEquals(permanent, PacketCreator.petDateDead(Long.MAX_VALUE));
        assertEquals(permanent, PacketCreator.petDateDead(-1));
        assertTrue(PacketCreator.petDateDead(Long.MAX_VALUE) < NO_EXPIRY, "alive in the client's eyes");
        long soon = System.currentTimeMillis() + 86_400_000L;
        assertEquals(PacketCreator.getTime(soon), PacketCreator.petDateDead(soon), "a real date is unchanged");
    }

    @Test
    void thePetBranchWritesDateDeadThroughPetDateDead() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/tools/PacketCreator.java"));
        int branch = src.indexOf("p.writeByte(pet.getFullness());");
        assertTrue(branch > 0);
        String next = src.substring(branch, src.indexOf("p.writeShort(pet.getPetAttribute());", branch));
        assertTrue(next.contains("petDateDead(item.getExpiration())"), next);
    }
}
