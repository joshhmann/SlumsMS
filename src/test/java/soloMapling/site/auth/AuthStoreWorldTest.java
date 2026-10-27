package soloMapling.site.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Sign-in lists the account's characters on Bera, world id 1 (a world-0 filter gave every session an empty list). */
class AuthStoreWorldTest {
    @Test
    void charactersComeFromBera() {
        assertEquals(1, AuthStore.MAIN_WORLD);
        assertFalse(AuthStore.CHARACTERS_SQL.contains("world = 0"));
    }
}
