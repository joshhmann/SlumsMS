package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class BotChannelVirtualThreadTest {
    @Test void aWaitingActivityStartLeavesWorkersFreeForItsCallback() throws Exception {
        probe("activity");
    }
    @Test void waitingLoginsLeaveWorkersFreeToResumeTheStorageOwner() throws Exception {
        probe("channel");
    }
    private void probe(String mode) throws Exception {
        Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
                "-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=2",
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "reservation probe did not finish");
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes()));
        } finally { child.destroyForcibly(); }
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            CountDownLatch holding = new CountDownLatch(1), storageReady = new CountDownLatch(1);
            java.util.function.BooleanSupplier blocked = () -> {
                holding.countDown();
                try { storageReady.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return false;
            };
            Runnable work, inspect;
            if (args[0].equals("activity")) {
                var queue = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue();
                var decision = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Result(java.util.List.of(),
                        java.util.Optional.of(soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity.RESTOCK),
                        soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Outcome.CHOOSE);
                queue.offer(queue.prepare(decision, 100, 1000), 100);
                work = () -> queue.drain(101, true, blocked, null);
                inspect = () -> queue.state();
            } else {
                work = () -> BotChannels.withReservationLock(blocked::getAsBoolean);
                inspect = () -> BotChannels.release(1);
            }
            Thread owner = Thread.startVirtualThread(work);
            if (!holding.await(5, TimeUnit.SECONDS)) System.exit(2);
            Thread waiter = Thread.startVirtualThread(inspect);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waiter.getState() != Thread.State.BLOCKED && waiter.getState() != Thread.State.WAITING) {
                if (System.nanoTime() > deadline) System.exit(3);
                Thread.sleep(10);
            }
            Thread.startVirtualThread(storageReady::countDown);
            owner.join(10_000); waiter.join(1_000);
            if (owner.isAlive() || waiter.isAlive()) { System.err.println("all workers pinned by " + args[0] + " lock"); System.exit(4); }
        }
    }
}
