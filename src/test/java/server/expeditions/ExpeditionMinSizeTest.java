package server.expeditions;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpeditionMinSizeTest {
    private static final EnumSet<ExpeditionType> STOCK_SIX = EnumSet.of(ExpeditionType.BALROG_NORMAL, ExpeditionType.SCARGA,
            ExpeditionType.ZAKUM, ExpeditionType.HORNTAIL, ExpeditionType.CHAOS_ZAKUM, ExpeditionType.CHAOS_HORNTAIL,
            ExpeditionType.PINKBEAN, ExpeditionType.CWKPQ);

    @Test
    void mapleRootAndLotusBossesAllowOnePlayer() {
        boolean saved = YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS;
        YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = false;
        try {
            for (ExpeditionType t : ExpeditionType.values()) {
                if (t.ordinal() >= ExpeditionType.VONLEON.ordinal()) {
                    assertEquals(1, t.getMinSize(), t.name());
                }
            }
            for (ExpeditionType t : STOCK_SIX) {
                assertEquals(6, t.getMinSize(), t.name());
            }
        } finally {
            YamlConfig.config.server.USE_ENABLE_SOLO_EXPEDITIONS = saved;
        }
    }
}
