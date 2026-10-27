package soloMapling.site.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The server reads the same revoked list as the site's login service (its folder, not the old approvals folder). */
class RevokedFileTest {
    @Test
    void explicitFileWinsOverTheOldApprovalsFolder() {
        assertEquals(Path.of("/var/lib/slums-login-test/revoked.txt"),
                SiteAccounts.revokedFile("/var/lib/slums-login-test/revoked.txt", "/var/lib/slums-login-test/approvals"));
        assertEquals(Path.of("/x/approvals/revoked.txt"), SiteAccounts.revokedFile(null, "/x/approvals"));
        assertNull(SiteAccounts.revokedFile(" ", null));
    }
}
