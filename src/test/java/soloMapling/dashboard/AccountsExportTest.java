package soloMapling.dashboard;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dashboard's Accounts panel: the fields are there, and nothing sensitive is. */
class AccountsExportTest {

    private static final long NOW = LocalDateTime.of(2026, 10, 26, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();

    private static AccountsExport.Data data() {
        return new AccountsExport.Data(new AccountsExport.Totals(120, 14, 3), new AccountsExport.Invites(5, 9, 2, 1),
                List.of(new AccountsExport.Signup("Newbie", NOW, "ABCDEFGHJK", "Silvia", "for Bob"),
                        new AccountsExport.Signup("OldWay", NOW - 3_600_000, null, null, null)));
    }

    @Test
    void panelHasTotalsInvitesSignupsAndLockouts() {
        Map<String, Object> m = AccountsExport.build(NOW, data(), 7);
        assertEquals(Map.of("accounts", 120L, "week", 14L, "today", 3L), m.get("totals"));
        assertEquals(Map.of("active", 5L, "used", 9L, "expired", 2L, "revoked", 1L), m.get("invites"));
        assertEquals(7L, m.get("lockouts24h"));
        List<?> rows = (List<?>) m.get("signups");
        Map<?, ?> first = (Map<?, ?>) rows.get(0);
        assertEquals("2026-10-26 12:00", first.get("utc"));
        assertEquals("Oct 26 05:00", first.get("pt"));
        assertEquals("Newbie", first.get("name"));
        assertEquals("ABCD…", first.get("invite"));
        assertEquals("Silvia", first.get("madeBy"));
        assertEquals("for Bob", first.get("note"));
        assertEquals(false, first.get("autoRegister"));
        Map<?, ?> auto = (Map<?, ?>) rows.get(1);
        assertEquals(true, auto.get("autoRegister"));
        assertNull(auto.get("invite"));
    }

    @Test
    void neverExportsAFullCodeOrAnythingSensitive() {
        String json = Json.write(AccountsExport.build(NOW, data(), 0));
        assertFalse(json.contains("ABCDEFGHJK"));
        assertFalse(json.contains("EFGHJK"));
        String lower = json.toLowerCase();
        for (String bad : List.of("password", "hash", "ip", "email", "passhash")) {
            assertFalse(lower.replace("lockouts", "").replace("note", "").contains("\"" + bad), bad);
        }
        String sql = (AccountsExport.TOTALS + AccountsExport.SIGNUPS + AccountsExport.SIGNUPS_NO_INVITES
                + AccountsExport.INVITES).toLowerCase();
        for (String bad : List.of("password", "ip_hash", "email", "pin", "pic", "macs", "sitelogged")) {
            assertFalse(sql.contains(bad), bad);
        }
    }

    @Test
    void aMissingPartIsLeftOutNotFaked() {
        Map<String, Object> m = AccountsExport.build(NOW, new AccountsExport.Data(null, null, null), 0);
        assertFalse(m.containsKey("totals"));
        assertFalse(m.containsKey("invites"));
        assertTrue(((List<?>) m.get("signups")).isEmpty());
    }

    @Test
    void resetRequestsShowCountsAndTheLatestWithoutSecrets() {
        AccountsExport.Data d = new AccountsExport.Data(null, null, List.of(), new AccountsExport.ResetCounts(2, 5, 3, 1),
                List.of(new AccountsExport.Reset("Forgetful", NOW - 3_600_000, "approved", "Silvia", "bob#1234", NOW - 1_800_000),
                        new AccountsExport.Reset("Other", NOW - 7_200_000, "pending", "", "discord pal", 0)));
        Map<String, Object> m = AccountsExport.build(NOW, d, 0);
        Map<?, ?> r = (Map<?, ?>) m.get("resets");
        assertEquals(2L, r.get("pending"));
        assertEquals(5L, r.get("approved"));
        assertEquals(3L, r.get("used"));
        assertEquals(1L, r.get("denied"));
        List<?> latest = (List<?>) r.get("latest");
        Map<?, ?> first = (Map<?, ?>) latest.get(0);
        assertEquals("Forgetful", first.get("account"));
        assertEquals("approved", first.get("state"));
        assertEquals("Silvia", first.get("decidedBy"));
        assertEquals("bob#1234", first.get("contact"));
        assertEquals("Oct 26 04:00", first.get("pt"));
        assertNull(((Map<?, ?>) latest.get(1)).get("decidedBy"));
        String sql = (AccountsExport.RESET_COUNTS + AccountsExport.RESET_LATEST).toLowerCase();
        for (String bad : List.of("secret", "ip_hash", "accounts")) {
            assertFalse(sql.contains(bad), bad);
        }
        String json = Json.write(m).toLowerCase();
        assertFalse(json.contains("secret") || json.contains("hash"), json);
        assertFalse(AccountsExport.build(NOW, new AccountsExport.Data(null, null, null), 0).containsKey("resets"),
                "left out when migration 037 is missing");
    }

    @Test
    void realAccountsExcludeBotsTemplateAndAutoQa() {
        assertTrue(AccountsExport.REAL.contains("SMBotBase") && AccountsExport.REAL.contains("autoqa"));
    }
}
