package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Ticket 56A: bots pay one real seller account at most the daily cap, and never above the price ceiling.
class BotSellerCapsTest {

    private static final Instant NOON = Instant.parse("2026-10-21T12:00:00Z");

    @BeforeEach
    void setUp() {
        BotYieldCaps.clearForTest();
        BotSellerCaps.clearForTest();
        BotYieldStore.useConnectionSourceForTest(() -> {
            throw new IllegalStateException("no pool in this test");
        });
        BotYieldCaps.setForTest(null, null, Clock.fixed(NOON, ZoneOffset.UTC));
        BotSellerCaps.setForTest(new BotSellerCaps.Limits(5_000_000, 1.0));
    }

    @AfterEach
    void tearDown() {
        BotYieldCaps.setForTest(null, null, null);
        BotSellerCaps.setForTest(null);
        BotYieldCaps.clearForTest();
        BotSellerCaps.clearForTest();
        BotYieldStore.useConnectionSourceForTest(null);
    }

    @Test
    void aSellerListingAHundredItemsAtBookValueIsPaidAtMostTheDailyCap() {
        long paid = 0;
        for (int i = 0; i < 100; i++) {
            if (BotSellerCaps.tryReserve(7, "Seller", 120_000)) {
                paid += 120_000;
            }
        }
        assertTrue(paid <= 5_000_000);
        assertEquals(41 * 120_000, paid);
        assertEquals(paid, BotSellerCaps.paidTodayForTest(7));
        assertTrue(BotSellerCaps.tryReserve(8, "Other", 120_000), "another account has its own cap");
        assertTrue(BotSellerCaps.report(5).stream().anyMatch(l -> l.contains("Seller") && l.contains("4,920,000")));
    }

    @Test
    void theCapComesBackTheNextDay() {
        assertTrue(BotSellerCaps.tryReserve(7, "Seller", 5_000_000));
        assertFalse(BotSellerCaps.tryReserve(7, "Seller", 1));
        BotYieldCaps.setForTest(null, null, Clock.fixed(NOON.plusSeconds(86_400), ZoneOffset.UTC));
        assertTrue(BotSellerCaps.tryReserve(7, "Seller", 1));
    }

    @Test
    void aFailedPurchaseGivesItsRoomBack() {
        assertTrue(BotSellerCaps.tryReserve(7, "Seller", 5_000_000));
        BotSellerCaps.release(7, 1_000_000);
        assertTrue(BotSellerCaps.tryReserve(7, "Seller", 1_000_000));
    }

    @Test
    void aZeroCapStopsBotsBuyingFromPlayers() {
        BotSellerCaps.setForTest(new BotSellerCaps.Limits(0, 1.0));
        assertFalse(BotSellerCaps.tryReserve(7, "Seller", 1));
    }

    @Test
    void priceCeilingIsRelativeToTheStaticValue() {
        assertTrue(BotSellerCaps.withinPriceCeiling(10_000, 10_000));
        assertTrue(BotSellerCaps.withinPriceCeiling(7_000, 10_000));
        assertFalse(BotSellerCaps.withinPriceCeiling(10_001, 10_000));
        assertFalse(BotSellerCaps.withinPriceCeiling(1, null), "no static value: no buy");
        BotSellerCaps.setForTest(new BotSellerCaps.Limits(5_000_000, 0.8));
        assertFalse(BotSellerCaps.withinPriceCeiling(9_000, 10_000));
    }

    @Test
    void theCountSurvivesARestartInTheBotyieldTable() throws Exception {
        Connection con = mock(Connection.class);
        PreparedStatement select = mock(PreparedStatement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(con.prepareStatement(BotYieldStore.SELECT_DAY)).thenReturn(select);
        when(con.prepareStatement(BotYieldStore.UPSERT)).thenReturn(upsert);
        when(select.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getInt("BotId")).thenReturn(BotSellerCaps.STORE_KEY_BASE + 7);
        when(rows.getLong("Meso")).thenReturn(4_950_000L);
        BotYieldStore.resetForTest();
        BotYieldStore.useConnectionSourceForTest(() -> con);

        assertFalse(BotSellerCaps.tryReserve(7, "Seller", 100_000), "4.95m was paid before the restart");
        assertTrue(BotSellerCaps.tryReserve(7, "Seller", 50_000));
        assertEquals(0, BotYieldCaps.dailyMesosForTest(BotStore.BOT_ID_BASE + BotSellerCaps.STORE_KEY_BASE + 7),
                "a seller row is not read as a bot's count");

        BotYieldCaps.flush();
        verify(upsert).setInt(1, BotSellerCaps.STORE_KEY_BASE + 7);
        verify(upsert).setDate(2, Date.valueOf(LocalDate.of(2026, 10, 21)));
        verify(upsert).setLong(3, 5_000_000L);
        verify(upsert).executeBatch();
    }
}
