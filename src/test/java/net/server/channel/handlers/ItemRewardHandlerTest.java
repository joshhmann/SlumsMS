package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Reward boxes (use items with a WZ {@code reward} child) hand out equips whose expiration comes from the reward
 * entry's {@code period} node. The 1.6.3 handler read that node as 36 seconds per unit; it is hours.
 */
class ItemRewardHandlerTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void absentPeriodMeansNoExpiry() {
        // WZ default when the reward entry has no <int name="period">: DataTool.getInt("period", child, -1)
        assertEquals(-1L, ItemRewardHandler.rewardExpiration(NOW, -1));
    }

    @Test
    void zeroPeriodExpiresImmediately() {
        // No shipped reward entry uses 0; the literal reading (0 units from now) is kept, not special-cased.
        assertEquals(NOW, ItemRewardHandler.rewardExpiration(NOW, 0));
    }

    @Test
    void periodIsHours() {
        assertEquals(NOW + TimeUnit.HOURS.toMillis(1), ItemRewardHandler.rewardExpiration(NOW, 1));
        assertEquals(NOW + TimeUnit.HOURS.toMillis(24), ItemRewardHandler.rewardExpiration(NOW, 24));
    }

    @Test
    void goldenPigsEggCapeLastsThreeHundredDays() {
        // 0202.img.xml item 02022503 reward 0: item 1132010 (equip) with period 7200.
        long expiration = ItemRewardHandler.rewardExpiration(NOW, 7200);
        assertEquals(NOW + TimeUnit.DAYS.toMillis(300), expiration);
        // 7200 days (the "DAYS" reading) would be ~19.7 years, i.e. permanent in practice.
        assertNotEquals(NOW + TimeUnit.DAYS.toMillis(7200), expiration);
        // The 1.6.3 code computed period * 36000 ms, i.e. 3 days for this entry.
        assertNotEquals(NOW + TimeUnit.DAYS.toMillis(3), expiration);
    }
}
