package soloMapling.qa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 auto-QA triage (t-wc-1): ga-2 never logged DONE because 10% upkeep never takes the excess to zero. */
class BotWealthSettledTest {

    static final String LINE = "Lv 100 Wand186: %s held, ceiling 12,000,000 (hard 24,000,000): 100%% of it, "
            + "mob meso intake 100%%, over by %s";

    @Test
    void farOverIsNotSettled() {
        assertFalse(QaScenarios.Ga2BotWealth.settled(String.format(LINE, "12,395,365", "395,365")));
    }

    @Test
    void aFewHundredOverAfterTheTicketCapIsSettled() {
        assertTrue(QaScenarios.Ga2BotWealth.settled(String.format(LINE, "12,001,078", "1,078")));
    }

    @Test
    void underTheCeilingIsSettled() {
        assertTrue(QaScenarios.Ga2BotWealth.settled("Lv 100 Wand186: 11,900,000 held, ceiling 12,000,000 (hard "
                + "24,000,000): 99% of it, mob meso intake 100%"));
    }

    @Test
    void settledAgreesWithWhereUpkeepStops() {
        // 1% of 12,000,000 is 120,000: upkeep stops there, so the run is DONE there and not before.
        assertTrue(QaScenarios.Ga2BotWealth.settled(String.format(LINE, "12,120,000", "120,000")));
        assertFalse(QaScenarios.Ga2BotWealth.settled(String.format(LINE, "12,120,001", "120,001")));
        for (long over : new long[] {1, 1_078, 119_999, 120_000, 120_001, 395_365}) {
            String line = String.format(LINE, String.format("%,d", 12_000_000 + over), String.format("%,d", over));
            assertEquals(!soloMapling.ArtificialPlayer.BotWealth.upkeepDue(over, 12_000_000),
                    QaScenarios.Ga2BotWealth.settled(line), "over by " + over);
        }
    }
}
