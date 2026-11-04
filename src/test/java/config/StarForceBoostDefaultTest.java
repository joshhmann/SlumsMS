package config;

import org.junit.jupiter.api.Test;
import server.StarBoost;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Owner decision (e): the stat share of the star-force crafting bonus defaults to 0.2, like the attack share. */
class StarForceBoostDefaultTest {
    @Test
    void theStatShareDefaultsToTheAttackShare() {
        ServerConfig c = new ServerConfig();
        assertEquals(0.2, c.STARFORCE_BOOST_PERCENTAGE2, 0.0);
    }

    @Test
    void aMissingYamlKeyGivesAnItemWithTenStrATwoPointBoost() {
        double saved = YamlConfig.config.server.STARFORCE_BOOST_PERCENTAGE2;
        try {
            YamlConfig.config.server.STARFORCE_BOOST_PERCENTAGE2 = new ServerConfig().STARFORCE_BOOST_PERCENTAGE2;
            StarBoost b = new StarBoost(10, 10, 10, 10, 10, 10).boostPercentage();
            assertEquals(2, b.getStr());
            assertEquals(2, b.getDex());
            assertEquals(2, b.getLuk());
            assertEquals(2, b.getInt());
        } finally {
            YamlConfig.config.server.STARFORCE_BOOST_PERCENTAGE2 = saved;
        }
    }
}
