package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSense;

import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 chat split: per-endpoint provenance counters, and the lines t-cv-11 sends leave everything to the model. */
class DecideProvenanceTest {

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    private static long n(String endpoint, String key) {
        return ((Number) ChatStats.endpointTotals().get(endpoint).get(key)).longValue();
    }

    @Test
    void outcomesAreCountedPerEndpoint() {
        ChatDecide ok = new ChatDecide((b, t) -> "{\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":0.9}}}", 400, 50)
                .named("intent+con@a");
        ChatDecide slow = new ChatDecide((b, t) -> {
            throw new HttpTimeoutException("deadline");
        }, 400, 50).named("tone@b");
        ChatDecide broken = new ChatDecide((b, t) -> "not json", 400, 50).named("broken@c");
        ChatDecide down = new ChatDecide((b, t) -> {
            throw new java.net.ConnectException("refused");
        }, 400, 50).named("down@d");
        ok.askNow("one", List.of(ChatTriage.INTENT));
        slow.askNow("two", List.of(ChatTriage.INTENT));
        broken.askNow("three", List.of(ChatTriage.INTENT));
        down.askNow("four", List.of(ChatTriage.INTENT));
        assertEquals(1, n("intent+con@a", "ok"));
        assertEquals(1, n("tone@b", "timeout"));
        assertEquals(1, n("broken@c", "empty"));
        assertEquals(1, n("down@d", "error"));
        assertEquals(0, n("tone@b", "ok"));
    }

    @Test
    void aCallAfterTheCallersDeadlineIsCountedLate() throws Exception {
        ChatDecide slow = new ChatDecide((b, t) -> {
            Thread.sleep(300);
            return "{\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":0.9}}}";
        }, 50, 50).named("slow@x");
        assertTrue(slow.askNow("five", List.of(ChatTriage.INTENT)).isEmpty());
        for (int i = 0; i < 40 && ChatStats.endpointTotals().get("slow@x") == null; i++) {
            Thread.sleep(25);
        }
        assertEquals(1, n("slow@x", "late"));
    }

    @Test
    void theSplitReadCountsWhoDecidedEachField() {
        ChatDecide main = new ChatDecide((b, t) -> "{\"answers\":{\"convo_intent\":{\"choice\":\"party\",\"confidence\":0.9},"
                + "\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}", 400, 50).named("intent+con@m");
        ChatDecide tone = new ChatDecide((b, t) -> "{\"answers\":{\"convo_tone\":{\"choice\":\"kind\",\"confidence\":0.95}}}",
                400, 50).named("tone@t");
        ConvoSense.Read read = ConvoSense.read(soloMapling.qa.ConvoSplitLines.LINES.get(0) + " zz", main, tone, null);
        assertEquals(ConvoSense.Source.DECIDE, read.intentSource());
        assertEquals(ConvoSense.Source.DECIDE, read.toneSource());
        assertEquals(1, n("intent+con@m", "ok"));
        assertEquals(1, n("tone@t", "ok"));
        Map<String, Long> reads = ChatStats.readTotals();
        assertEquals(1L, reads.get("intent:decide"));
        assertEquals(1L, reads.get("tone:decide"));
    }

    @Test
    void theCheckLinesLeaveIntentToneAndConToTheModel() {
        for (String line : soloMapling.qa.ConvoSplitLines.LINES) {
            ChatTriage.Result r = ChatTriage.rules(line);
            assertEquals(ChatTriage.Kind.TALK, r.kind(), line);
            assertTrue(ConvoSense.intentRule(line).isEmpty(), "intent rule: " + line);
            assertTrue(ConvoSense.toneRule(line, r).isEmpty(), "tone rule: " + line);
            assertFalse(r.conSource() == ChatTriage.Source.RULE, "con rule: " + line);
        }
    }

    @Test
    void callsRunOnTheClientsOwnPlatformThreadsNotTheSharedVirtualScheduler() {
        // TEST 2026-10-28: virtual threads started seconds late under the CPU quota; a 100 ms call came back "late"
        java.util.concurrent.atomic.AtomicBoolean virtual = new java.util.concurrent.atomic.AtomicBoolean(true);
        ChatDecide d = new ChatDecide((b, t) -> {
            virtual.set(Thread.currentThread().isVirtual());
            return "{\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":0.9}}}";
        }, 400, 8).named("own@p");
        assertTrue(d.askNow("six", List.of(ChatTriage.INTENT)).isPresent());
        assertFalse(virtual.get(), "a platform thread of the client's pool");
    }

    @Test
    void aCallThatWaitedPastTheDeadlineForAThreadIsSkippedAndCountedLate() throws Exception {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        ChatDecide d = new ChatDecide((b, t) -> {
            sent.incrementAndGet();
            hold.await();
            return "{\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":0.9}}}";
        }, 100, 2).named("busy@q"); // 2 threads
        d.ask("a", List.of(ChatTriage.INTENT));
        d.ask("b", List.of(ChatTriage.INTENT));
        Thread.sleep(1_050); // a new second of budget; both threads still busy
        assertTrue(d.askNow("c", List.of(ChatTriage.INTENT)).isEmpty(), "the caller is not kept waiting");
        hold.countDown();
        for (int i = 0; i < 80 && n2("busy@q", "calls") < 3; i++) {
            Thread.sleep(25);
        }
        assertEquals(2, sent.get(), "the queued call never reached the endpoint");
        assertEquals(3, n2("busy@q", "late"));
    }

    private static long n2(String endpoint, String key) {
        Map<String, Object> m = ChatStats.endpointTotals().get(endpoint);
        return m == null || !(m.get(key) instanceof Number x) ? 0 : x.longValue();
    }
}
