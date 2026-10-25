package config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Owner 2026-10-25 (1.5.2): the GM security levels are back to Cosmic's defaults (MapleRoot shipped 1). */
class GmSecurityDefaultsTest {

    @Test
    void gmTradeDropStorageAndDueyNeedLevelFourByDefault() {
        ServerConfig defaults = new ServerConfig();
        assertEquals(4, defaults.MINIMUM_GM_LEVEL_TO_TRADE);
        assertEquals(4, defaults.MINIMUM_GM_LEVEL_TO_DROP);
        assertEquals(4, defaults.MINIMUM_GM_LEVEL_TO_USE_STORAGE);
        assertEquals(4, defaults.MINIMUM_GM_LEVEL_TO_USE_DUEY);
    }
}
