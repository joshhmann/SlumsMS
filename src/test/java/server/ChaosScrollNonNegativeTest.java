package server;

import client.inventory.Equip;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.*;

/**
 * P0nk/Cosmic#261, chaos scroll guard: a chaos scroll may take a stat down by up to the configured range, and with a
 * stat of 1 a bad roll leaves nothing. The equip must clamp at 0, never go negative. No existing test covered this.
 * The roll itself is random, so the test drives the real scrollEquipWithChaos and watches every value it applies.
 */
class ChaosScrollNonNegativeTest {

    private final AtomicInteger minStrSet = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxStrSet = new AtomicInteger(Integer.MIN_VALUE);
    private Equip equip;

    private static Method scrollEquipWithChaos;
    private static ItemInformationProvider ii;

    private int rateWas;
    private boolean enhancedWas;

    @BeforeAll
    static void initializeItemProviderWithoutExternalData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            var connection = mock(java.sql.Connection.class, RETURNS_DEEP_STUBS);
            db.when(tools.DatabaseConnection::getConnection).thenReturn(connection);
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
        ii = ItemInformationProvider.getInstance();
        scrollEquipWithChaos = ItemInformationProvider.class.getDeclaredMethod("scrollEquipWithChaos", Equip.class, int.class);
        scrollEquipWithChaos.setAccessible(true);
    }

    @BeforeEach
    void watchAnEquipWithOneStr() throws Exception {
        equip = mock(Equip.class);
        when(equip.getStr()).thenReturn((short) 1); // every other stat stays at its mock default of 0
        doAnswer(inv -> {
            minStrSet.accumulateAndGet((Short) inv.getArgument(0), Math::min);
            maxStrSet.accumulateAndGet((Short) inv.getArgument(0), Math::max);
            return null;
        }).when(equip).setStr(anyShort());

        rateWas = YamlConfig.config.server.CHSCROLL_STAT_RATE;
        enhancedWas = YamlConfig.config.server.USE_ENHANCED_CHSCROLL;
        YamlConfig.config.server.CHSCROLL_STAT_RATE = 1;
    }

    @AfterEach
    void restoreConfig() {
        YamlConfig.config.server.CHSCROLL_STAT_RATE = rateWas;
        YamlConfig.config.server.USE_ENHANCED_CHSCROLL = enhancedWas;
    }

    @Test
    void aBadRollNeverLeavesANegativeStat() throws Exception {
        YamlConfig.config.server.USE_ENHANCED_CHSCROLL = false;
        rollMany(true);
    }

    @Test
    void aBadRollNeverLeavesANegativeStatWithTheEnhancedScroll() throws Exception {
        // The enhanced scroll only ever raises a stat (it keeps max(previous, rolled)), so it cannot lower it at all.
        YamlConfig.config.server.USE_ENHANCED_CHSCROLL = true;
        rollMany(false);
    }

    private void rollMany(boolean theScrollCanLowerTheStat) throws Exception {
        final int rounds = 5_000;
        for (int i = 0; i < rounds; i++) {
            scrollEquipWithChaos.invoke(ii, equip, 5);
        }

        assertTrue(minStrSet.get() >= 0, "a chaos scroll left STR at " + minStrSet.get());
        assertNotNull(equip.getStr());
        assertTrue(maxStrSet.get() > 1, "the scroll never raised the stat, so the roll under test never ran");
        if (theScrollCanLowerTheStat) {
            assertTrue(minStrSet.get() < 1, "the scroll never lowered the stat, so the clamp was never exercised");
        }
    }
}
