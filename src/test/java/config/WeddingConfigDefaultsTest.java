package config;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marriage audit F5: the WEDDING_* fields had no initializers, so a config.yaml without a wedding block (the repo's, and
 * live's if it never copied Cosmic's) zeroed the feature: every gift refused (limit 0), no blessing EXP, and an
 * interval of 0 minutes that makes World.java's scheduleAtFixedRate throw at boot. The defaults are Cosmic's stock
 * values (research/Cosmic/config.yaml:447-452).
 */
class WeddingConfigDefaultsTest {

    @Test
    void reservationTimingDefaultsToCosmicStock() {
        ServerConfig defaults = new ServerConfig();
        assertEquals(3, defaults.WEDDING_RESERVATION_DELAY);
        assertEquals(10, defaults.WEDDING_RESERVATION_TIMEOUT);
        assertEquals(60, defaults.WEDDING_RESERVATION_INTERVAL);
        assertTrue(defaults.WEDDING_RESERVATION_INTERVAL > 0, "a 0 interval crashes World's fixed-rate timer at boot");
    }

    @Test
    void giftLimitIsOnePerGuestPerWedding() {
        assertEquals(1, new ServerConfig().WEDDING_GIFT_LIMIT);
    }

    @Test
    void blessExpIsStockFlatExpPerBlessing() {
        assertEquals(30000, new ServerConfig().WEDDING_BLESS_EXP);
    }

    @Test
    void blesserEffectShowsOnTheBlesserByDefault() {
        assertTrue(new ServerConfig().WEDDING_BLESSER_SHOWFX);
    }

    @Test
    void aConfigFileWithoutAWeddingBlockKeepsTheDefaults() throws Exception {
        // ServerConfig directly: touching YamlConfig would load config.yaml in its static initializer
        ServerConfig parsed = new YamlReader(new StringReader("DB_CONNECTION_TIMEOUT_MS: 1234\n")).read(ServerConfig.class);
        assertEquals(1234, parsed.DB_CONNECTION_TIMEOUT_MS);
        assertEquals(60, parsed.WEDDING_RESERVATION_INTERVAL);
        assertEquals(1, parsed.WEDDING_GIFT_LIMIT);
    }
}
