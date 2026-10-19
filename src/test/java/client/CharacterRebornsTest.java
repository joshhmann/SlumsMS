package client;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CharacterRebornsTest {

    private final boolean rebirthSystem = YamlConfig.config.server.USE_REBIRTH_SYSTEM;

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = rebirthSystem;
    }

    // Bots have no characters row: the lookup used to throw on every EXP gain and cut the EXP share short.
    @Test
    void aBotHasNoRebirthsAndNeedsNoDatabase() {
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = true;
        Character bot = Character.getDefault(new BotClient(0, 0));

        assertEquals(0, bot.getReborns());
        assertEquals(200, bot.getMaxClassLevel());
    }
}
