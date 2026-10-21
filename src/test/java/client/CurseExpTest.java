package client;

import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.4.2 Cosmic parity: the repack had commented out the Curse penalty in {@link Character#gainExp}, so the
 * mob debuff did nothing. v83/Cosmic halve both the base and the party share of every EXP gain while cursed.
 */
class CurseExpTest {

    private final boolean rebirthSystem = YamlConfig.config.server.USE_REBIRTH_SYSTEM;

    @BeforeEach
    void enableRebirths() {
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = true; // getMaxLevel reads the rebirth count, as on live
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_REBIRTH_SYSTEM = rebirthSystem;
    }

    @SuppressWarnings("unchecked")
    private static void curse(Character chr) throws Exception {
        Field f = Character.class.getDeclaredField("diseases");
        f.setAccessible(true);
        ((Map<Disease, Object>) f.get(chr)).put(Disease.CURSE, null);
    }

    @Test
    void cursedExpIsHalf() {
        assertEquals(500, Character.cursedExp(1000));
        assertEquals(3, Character.cursedExp(7), "rounds down like Cosmic's int *= 0.5");
        assertEquals(0, Character.cursedExp(0));
        assertEquals(Integer.MAX_VALUE / 2, Character.cursedExp(Integer.MAX_VALUE));
    }

    @Test
    void anUncursedCharacterGetsTheFullGain() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.gainExp(10, 4, false, false, true);

        assertEquals(14, chr.getExp());
    }

    @Test
    void aCursedCharacterGetsHalfTheGainAndHalfThePartyBonus() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        curse(chr);
        chr.gainExp(10, 4, false, false, true);

        assertEquals(7, chr.getExp());
    }
}
