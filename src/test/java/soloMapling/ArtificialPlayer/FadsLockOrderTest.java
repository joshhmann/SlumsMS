package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * b267, the 1.6.0.4 hang (2026-10-31 22:46 UTC): 25 threads parked on PlayerStorage's FAIR read/write lock while one
 * was blocked on the Fads monitor. {@code Fads.step} used to call the World (map players, PlayerStorage, life memory)
 * while holding Fads' own lock, and threads that hold PlayerStorage's read lock call {@code chairFor}. With a fair
 * lock a queued writer stops every new reader, so the cycle step(Fads) -> PlayerStorage -> Fads freezes the server.
 *
 * <p>Both tests fail on the old step (it holds the Fads lock while inside the World call) and pass now that the step
 * snapshots the World before it takes the lock. Deadlines are 5 s so a loaded build machine cannot fail them; the old
 * code never returns at all while the World read is held.
 */
class FadsLockOrderTest {

    static final long T0 = 1_800_000_000_000L, MIN = 60_000L;

    /** A world with one CHAIR fad on channel 1 whose player count blocks until the test lets it go. */
    static final class BlockingChairWorld implements Fads.World {
        final CountDownLatch inWorld = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        public List<Integer> onlineBots(int channel) { return channel == 1 ? List.of(1) : List.of(); }
        public List<Integer> channels() { return List.of(1); }
        public int channelOf(int botId) { return botId == 1 ? 1 : -1; }
        public int mapOf(int botId) { return 100000000; }
        public List<Integer> friendsOf(int botId) { return List.of(); }
        public double socialPull(int botId) { return .5; }
        public int playersAt(int channel, int mapId) { return 0; }

        public int playersOnChair(int channel, int chairId) {
            inWorld.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) fail("the test never released the World read");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }

    /** The same, but the player count takes PlayerStorage's shape: a FAIR read lock, held off by a writer. */
    static final class FairLockWorld implements Fads.World {
        final ReentrantReadWriteLock storage = new ReentrantReadWriteLock(true);
        final CountDownLatch reading = new CountDownLatch(1);

        public List<Integer> onlineBots(int channel) { return channel == 1 ? List.of(1) : List.of(); }
        public List<Integer> channels() { return List.of(1); }
        public int channelOf(int botId) { return botId == 1 ? 1 : -1; }
        public int mapOf(int botId) { return 100000000; }
        public List<Integer> friendsOf(int botId) { return List.of(); }
        public double socialPull(int botId) { return .5; }
        public int playersAt(int channel, int mapId) { return 0; }

        public int playersOnChair(int channel, int chairId) {
            reading.countDown();
            storage.readLock().lock(); // a fair lock: the held/queued writer stops this reader
            try {
                return 0;
            } finally {
                storage.readLock().unlock();
            }
        }
    }

    @AfterEach
    void tearDown() {
        Fads.useForTest(null, null);
        System.clearProperty("solomapling.fads.noFile");
    }

    @Test
    void stepDoesNotHoldTheLockWhileItReadsTheWorld() throws Exception {
        BlockingChairWorld world = new BlockingChairWorld();
        Fads.useForTest(world, new Random(7));
        assertTrue(Fads.startForTest(Fads.Kind.CHAIR, 1, 3010001, 1, T0));

        Thread stepper = new Thread(() -> Fads.step(T0 + MIN), "fads-step");
        stepper.start();
        assertTrue(world.inWorld.await(5, TimeUnit.SECONDS), "the step reached the World read");

        // "A thread holding the PlayerStorage lock": it asks Fads for a chair while the step is inside the World.
        FutureTask<Integer> asks = new FutureTask<>(() -> Fads.chairFor(1, 3010005));
        Thread holder = new Thread(asks, "playerstorage-holder");
        holder.start();
        try {
            assertEquals(3010001, asks.get(5, TimeUnit.SECONDS),
                    "chairFor must not wait for a step that is reading the World");
        } finally {
            world.release.countDown();
            stepper.join(5000);
            holder.join(5000);
        }
        assertFalse(stepper.isAlive(), "the step finished");
    }

    @Test
    void aHeldPlayerStorageWriterDoesNotHoldUpChairFor() throws Exception {
        FairLockWorld world = new FairLockWorld();
        Fads.useForTest(world, new Random(7));
        assertTrue(Fads.startForTest(Fads.Kind.CHAIR, 1, 3010001, 1, T0));

        CountDownLatch writerHas = new CountDownLatch(1), letWriterGo = new CountDownLatch(1);
        Thread writer = new Thread(() -> { // a login or logout: PlayerStorage.addPlayer/removePlayer
            world.storage.writeLock().lock();
            try {
                writerHas.countDown();
                if (!letWriterGo.await(10, TimeUnit.SECONDS)) fail("the test never let the writer go");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                world.storage.writeLock().unlock();
            }
        }, "playerstorage-writer");
        writer.start();
        assertTrue(writerHas.await(5, TimeUnit.SECONDS), "the writer holds the lock");

        Thread stepper = new Thread(() -> Fads.step(T0 + MIN), "fads-step");
        stepper.start();
        assertTrue(world.reading.await(5, TimeUnit.SECONDS), "the step reached the blocked World read");

        FutureTask<Integer> asks = new FutureTask<>(() -> Fads.chairFor(1, 3010005));
        Thread holder = new Thread(asks, "chair-asker");
        holder.start();
        try {
            assertEquals(3010001, asks.get(5, TimeUnit.SECONDS), "chairFor does not need the read lock");
            assertTrue(stepper.isAlive(), "the step is still blocked in the World while chairFor answers");
        } finally {
            letWriterGo.countDown();
            stepper.join(5000);
            holder.join(5000);
            writer.join(5000);
        }
        assertFalse(stepper.isAlive(), "the step finished once the writer let go");
    }

    /** Every other synchronized block in Fads is memory-only: reads from a bot and from the report never block a step. */
    @Test
    void readsAnswerWhileTheStepIsInTheWorld() throws Exception {
        BlockingChairWorld world = new BlockingChairWorld();
        Fads.useForTest(world, new Random(7));
        Fads.startForTest(Fads.Kind.CHAIR, 1, 3010001, 1, T0);
        Thread stepper = new Thread(() -> Fads.step(T0 + MIN), "fads-step");
        stepper.start();
        assertTrue(world.inWorld.await(5, TimeUnit.SECONDS));
        FutureTask<Boolean> reads = new FutureTask<>(() -> Fads.chairFor(1, 3010005) == 3010001
                && Fads.phraseFor(1) == null
                && Fads.spotFor(1, 1) == -1
                && !Fads.follows(1).isEmpty()
                && !Fads.active().isEmpty()
                && Fads.report().containsKey("fads"));
        Thread reader = new Thread(reads, "fads-reader");
        reader.start();
        try {
            assertTrue(reads.get(5, TimeUnit.SECONDS), "the memory reads answer while the step is in the World");
        } finally {
            world.release.countDown();
            stepper.join(5000);
            reader.join(5000);
        }
        assertFalse(stepper.isAlive(), "the step finished");
    }

    /** A channel with two bots, all reads cheap; the social pull lets the first pick through every time. */
    static final class StartWorld implements Fads.World {
        public List<Integer> channels() { return List.of(1); }
        public List<Integer> onlineBots(int channel) { return channel == 1 ? List.of(11, 12) : List.of(); }
        public int channelOf(int botId) { return 1; }
        public int mapOf(int botId) { return 100000000; }
        public List<Integer> friendsOf(int botId) { return List.of(); }
        public double socialPull(int botId) { return 1.0; }
        public int playersAt(int channel, int mapId) { return 0; }
        public int playersOnChair(int channel, int chairId) { return 0; }
    }

    /** Draws fixed for a plan: {@code nextDouble} clears the start gate, every {@code nextInt} takes the first option. */
    static final class StartRandom extends Random {
        public double nextDouble() { return 0.0; }
        public int nextInt(int bound) { return 0; }
    }

    /**
     * The world the step plans a start from is the one the starter comes from: the STARTED event, the {@code active}
     * view and the report all name that bot. (The plan is built outside the lock, which is why this is checkable at
     * all: the step no longer reads the World while it applies it.)
     */
    @Test
    void aFadStartedFromTheWorldKeepsTheStarterItPlanned() {
        Fads.useForTest(new StartWorld(), new StartRandom());
        List<Fads.Event> seen = new CopyOnWriteArrayList<>();
        Fads.Listener listener = seen::add;
        Fads.listen(listener);
        try {
            Fads.step(T0);
            Fads.Event started = seen.stream().filter(e -> e.change() == Fads.Change.STARTED).findFirst()
                    .orElseThrow(() -> new AssertionError("no fad started: " + Fads.report()));
            assertEquals(Fads.Kind.SPOT, started.fad().kind());
            assertEquals(11, started.botId(), "the event names the planned starter");
            assertEquals(11, started.fad().starter());
            assertTrue(String.valueOf(Fads.report().get("fads")).contains("starter 11"),
                    "the report names the starter: " + Fads.report());
        } finally {
            Fads.remove(listener);
        }
    }
}
