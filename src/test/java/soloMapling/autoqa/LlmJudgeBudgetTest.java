package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.CloudBudget;

import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.5.0 rc6 bug hunt (P2): the auto-QA judge called the cloud outside the daily budget. */
class LlmJudgeBudgetTest {

    private static final String BODY = "{\"choices\":[{\"message\":{\"content\":\"{\\\"on_topic\\\":true}\"}}],"
            + "\"usage\":{\"prompt_tokens\":300,\"completion_tokens\":20}}";

    private static CloudBudget budget(double capUsd) {
        return new CloudBudget(capUsd, 1, 1, () -> 1_790_000_000_000L, ZoneId.of("UTC"), null);   // $1 per Mtok
    }

    @Test
    void aJudgeCallIsChargedItsRealUsage() throws Exception {
        CloudBudget b = budget(1);
        AtomicInteger calls = new AtomicInteger();
        LlmJudge.Transport t = LlmJudge.budgeted(b, (system, user) -> {
            calls.incrementAndGet();
            return BODY;
        });
        assertEquals("{\"on_topic\":true}", t.complete("sys", "user"));
        assertEquals(1, calls.get());
        assertEquals(320 / 1e6, b.spentToday(), 1e-12, "charged from the response's own usage");
        assertEquals(0, b.reservedNow(), 1e-12);
    }

    @Test
    void aSpentBudgetStopsTheJudgeBeforeItCalls() throws Exception {
        CloudBudget b = budget(0.00001);   // smaller than one call's worst case
        AtomicInteger calls = new AtomicInteger();
        LlmJudge.Transport t = LlmJudge.budgeted(b, (system, user) -> {
            calls.incrementAndGet();
            return BODY;
        });
        assertNull(t.complete("sys", "user"));
        assertEquals(0, calls.get(), "no cloud call over the cap");
        assertNull(LlmJudge.budgeted(null, (s, u) -> BODY).complete("s", "u"), "no budget, no cloud call");
    }

    @Test
    void aFailedCallStillReleasesItsReservation() throws Exception {
        CloudBudget b = budget(1);
        assertNull(LlmJudge.budgeted(b, (s, u) -> null).complete("sys", "user"));
        assertEquals(0, b.reservedNow(), 1e-12);
    }
}
