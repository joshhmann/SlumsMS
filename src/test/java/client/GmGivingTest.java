package client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GmGivingTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(GmGiving.ALLOW_PROPERTY);
    }

    @Test
    void aGmCannotTradeWithAPlayerOrDropByDefault() {
        assertTrue(GmGiving.blocksTrade(true, false));
        assertTrue(GmGiving.blocksTrade(false, true));
        assertFalse(GmGiving.blocksTrade(true, true), "GM to GM is fine");
        assertFalse(GmGiving.blocksTrade(false, false), "player to player is untouched");
        assertTrue(GmGiving.blocksDrop(true));
        assertFalse(GmGiving.blocksDrop(false));
    }

    @Test
    void theJvmFlagAllowsIt() {
        System.setProperty(GmGiving.ALLOW_PROPERTY, "true");
        assertFalse(GmGiving.blocksTrade(true, false));
        assertFalse(GmGiving.blocksDrop(true));
    }
}
