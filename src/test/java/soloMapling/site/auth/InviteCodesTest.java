package soloMapling.site.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105: invite code format, validity choices and lifecycle, and migration 036. */
class InviteCodesTest {

    static final long NOW = 1_800_000_000_000L;
    static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void codesAreTenUnambiguousRandomCharacters() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String c = InviteCodes.newCode();
            assertEquals(10, c.length());
            assertTrue(c.chars().allMatch(ch -> InviteCodes.ALPHABET.indexOf(ch) >= 0), c);
            assertTrue(seen.add(c), "codes repeat");
        }
        for (char bad : "01IO".toCharArray()) {
            assertEquals(-1, InviteCodes.ALPHABET.indexOf(bad));
        }
        assertEquals(32, InviteCodes.ALPHABET.length());
    }

    @Test
    void typedCodesIgnoreCaseDashesAndSpaces() {
        assertEquals("ABCDEFGHJK", InviteCodes.normalize("abcde-fghjk"));
        assertEquals("ABCDEFGHJK", InviteCodes.normalize(" ABCDE FGHJK "));
        assertEquals("ABCDE-FGHJK", InviteCodes.display("ABCDEFGHJK"));
        assertNull(InviteCodes.normalize("ABCDE-FGHJ0"), "0 is not in the alphabet");
        assertNull(InviteCodes.normalize("ABCDE"));
        assertNull(InviteCodes.normalize(null));
        assertNull(InviteCodes.normalize("A".repeat(100)));
    }

    @Test
    void validityChoicesAndDefault() {
        assertEquals(InviteCodes.Validity.SEVEN_DAYS, InviteCodes.Validity.DEFAULT);
        assertEquals(NOW + DAY, InviteCodes.Validity.parse("1d").expiresAt(NOW));
        assertEquals(NOW + 7 * DAY, InviteCodes.Validity.parse("7D").expiresAt(NOW));
        assertEquals(NOW + 30 * DAY, InviteCodes.Validity.parse("30d").expiresAt(NOW));
        assertEquals(0, InviteCodes.Validity.parse("forever").expiresAt(NOW));
        assertNull(InviteCodes.Validity.parse("14d"));
        assertNull(InviteCodes.Validity.parse("hello"));
    }

    @Test
    void lifecycleOpenUsedExpiredRevoked() {
        InviteCodes.Invite open = InviteCodes.Invite.fresh("ABCDEFGHJK", NOW, 1, "Gm", "", NOW + DAY);
        assertEquals(InviteCodes.Status.OPEN, open.status(NOW + DAY - 1));
        assertEquals(InviteCodes.Status.EXPIRED, open.status(NOW + DAY));
        InviteCodes.Invite forever = InviteCodes.Invite.fresh("ABCDEFGHJK", NOW, 1, "Gm", "", 0);
        assertEquals(InviteCodes.Status.OPEN, forever.status(NOW + 10_000 * DAY));
        InviteCodes.Invite used = new InviteCodes.Invite("ABCDEFGHJK", NOW, 1, "Gm", "", 0, 5, "pal", NOW + 1, 0, "");
        assertEquals(InviteCodes.Status.USED, used.status(NOW + 2));
        InviteCodes.Invite revoked = new InviteCodes.Invite("ABCDEFGHJK", NOW, 1, "Gm", "", 0, 0, "", 0, NOW + 1, "Gm");
        assertEquals(InviteCodes.Status.REVOKED, revoked.status(NOW + 2));
    }

    @Test
    void listLinesShowExpiryAndUse() {
        InviteCodes.Invite open = InviteCodes.Invite.fresh("ABCDEFGHJK", NOW, 1, "Gm", "for bob", NOW + DAY);
        String line = InviteCodes.describe(open, NOW);
        assertTrue(line.startsWith("ABCDE-FGHJK  open, expires "), line);
        assertTrue(line.contains("PT") && line.contains("by Gm") && line.contains("\"for bob\""), line);
        assertTrue(InviteCodes.describe(InviteCodes.Invite.fresh("ABCDEFGHJK", NOW, 1, "Gm", "", 0), NOW)
                .contains("never expires"));
        assertTrue(InviteCodes.describe(open, NOW + 2 * DAY).contains("expired"));
        InviteCodes.Invite used = new InviteCodes.Invite("ABCDEFGHJK", NOW, 1, "Gm", "", 0, 5, "pal", NOW + 1, 0, "");
        assertTrue(InviteCodes.describe(used, NOW + 2).contains("used by pal"));
    }

    @Test
    void notesAreShortAndPrintable() {
        assertEquals("a b", InviteCodes.cleanNote("a\nb"));
        assertEquals(InviteCodes.NOTE_MAX, InviteCodes.cleanNote("x".repeat(500)).length());
        assertEquals("", InviteCodes.cleanNote(null));
    }

    @Test
    void migration036IsIdempotentLedgeredAndMatchesTheStore() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "036-invite-codes";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS invite_codes"));
        assertTrue(sql.contains("mapleroot_integration_migrations(migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("DROP TABLE IF EXISTS invite_codes") && rollback.contains("migration_id='" + id + "'"));
        String sql040 = Files.readString(dir.resolve("040-player-invites.sql"));
        for (String col : AuthStore.Jdbc.COLUMNS.split(",\\s*")) {
            assertTrue(sql.contains(col + " ") || sql040.contains(col + " "), col);
        }
        // Single use: the consume only matches an unused, unrevoked, unexpired code.
        assertTrue(AuthStore.Jdbc.CONSUME.contains("used_at = 0 AND revoked_at = 0 AND (expires_at = 0 OR expires_at > ?)"));
        assertTrue(AuthStore.Jdbc.REVOKE.contains("used_at = 0 AND revoked_at = 0"));
    }

    @Test
    void migration040AddsMakerColumnsInviterAndRollsBack() throws Exception {
        Path dir = Path.of("database/migrations");
        String id = "040-player-invites";
        String sql = Files.readString(dir.resolve(id + ".sql"));
        String rollback = Files.readString(dir.resolve(id + ".rollback.sql"));
        for (String col : new String[]{"created_by_char", "self_serve", "invited_by_account", "invited_by_char"}) {
            assertTrue(sql.contains("COLUMN_NAME = '" + col + "'"), col + " guarded add");
            assertTrue(rollback.contains("DROP COLUMN " + col), col + " dropped by rollback");
        }
        assertTrue(sql.contains("migration_id) VALUES ('" + id + "')"));
        assertTrue(rollback.contains("migration_id='" + id + "'"));
        assertTrue(AuthStore.Jdbc.COUNT_SELF_SERVE.contains("self_serve = 1") && AuthStore.Jdbc.COUNT_SELF_SERVE.contains("created_at > ?"));
        assertTrue(AuthStore.Jdbc.MARK_INVITER.contains("invited_by_account") && AuthStore.Jdbc.MARK_INVITER.contains("invited_by_char"));
        assertTrue(AuthStore.Jdbc.HIGHEST_LEVEL.contains("id < ?"), "bots are left out of the level gate");
        assertEquals(30, InviteCodes.PLAYER_MIN_LEVEL);
        assertEquals(2, InviteCodes.PLAYER_CODES_PER_WINDOW);
    }
}
