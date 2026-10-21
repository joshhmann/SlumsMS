package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 1.4.2: FM shoppers never earn, so they top up exactly a purchase's shortfall from a daily allowance.
class FMBotWalletTest {

    @BeforeEach
    @AfterEach
    void clear() {
        FMBotWallet.clearForTest();
    }

    @Test
    void aBotThatCanPayDrawsNothing() {
        assertEquals(0, FMBotWallet.reserveShortfall(1, 2_000_000, 1_500_000));
        assertEquals(FMBotWallet.DAILY_ALLOWANCE, FMBotWallet.remaining(1));
    }

    @Test
    void onlyTheShortfallIsDrawn() {
        assertEquals(1_000_000, FMBotWallet.reserveShortfall(1, 500_000, 1_500_000));
        assertEquals(FMBotWallet.DAILY_ALLOWANCE - 1_000_000, FMBotWallet.remaining(1));
        assertEquals(1_500_000, FMBotWallet.reserveShortfall(1, -5, 1_500_000));
    }

    @Test
    void theAllowanceRunsOutPerBot() {
        assertEquals(FMBotWallet.DAILY_ALLOWANCE, FMBotWallet.reserveShortfall(1, 0, FMBotWallet.DAILY_ALLOWANCE));
        assertEquals(-1, FMBotWallet.reserveShortfall(1, 0, 1));
        assertEquals(5, FMBotWallet.reserveShortfall(2, 0, 5));
    }

    @Test
    void aRefundGivesTheAllowanceBack() {
        FMBotWallet.reserveShortfall(1, 0, FMBotWallet.DAILY_ALLOWANCE);
        FMBotWallet.refund(1, 3_000_000);
        assertEquals(3_000_000, FMBotWallet.remaining(1));
        assertEquals(3_000_000, FMBotWallet.reserveShortfall(1, 0, 3_000_000));
    }
}
