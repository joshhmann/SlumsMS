package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The talk tick must run on a thread whose CPU time can be measured (a virtual thread reports -1). */
class BotTalkTickThreadTest {
    @Test
    void tickThreadIsPlatformAndReportsCpuTime() throws Exception {
        ScheduledExecutorService ex = BotTalk.newTickExecutor();
        try {
            boolean[] virtual = new boolean[1];
            long[] cpu = new long[1];
            ex.submit(() -> {
                virtual[0] = Thread.currentThread().isVirtual();
                cpu[0] = BotTalk.threadCpuNow();
            }).get(5, TimeUnit.SECONDS);
            assertFalse(virtual[0], "tick thread is virtual");
            assertTrue(cpu[0] >= 0, "thread CPU time not measured: " + cpu[0]);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void virtualThreadsDoNotMeasureCpu() throws Exception {
        long[] cpu = {0};
        Thread.ofVirtual().start(() -> cpu[0] = BotTalk.threadCpuNow()).join();
        assertTrue(cpu[0] < 0, "documents why the tick cannot run on a virtual thread");
    }
}
