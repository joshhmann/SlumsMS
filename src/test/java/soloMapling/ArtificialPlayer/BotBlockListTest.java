package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * sweep#B1 (ticket 181): the block list is a static singleton. A bot that declines a trade adds to it on the trade
 * thread while another bot's tick reads and sweeps it. It was a plain ArrayList, so the iteration in the sweep threw
 * ConcurrentModificationException in the middle of a trade.
 */
class BotBlockListTest {

    private static final long ALREADY_EXPIRED = -1;
    private static final long LONG_BLOCK = 300;

    @Test
    void addingOnOneThreadWhileSweepingAndReadingOnAnotherNeverThrows() throws Exception {
        for (int round = 0; round < 5; round++) {
            BotBlockList list = new BotBlockList();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean adderDone = new AtomicBoolean();
            CountDownLatch go = new CountDownLatch(1);

            Thread adder = new Thread(() -> {
                try {
                    go.await();
                    for (int i = 0; i < 8_000; i++) {
                        // half the entries are dead on arrival, so every sweep has something to remove
                        list.addToBlockList(i % 50, i, i % 2 == 0 ? ALREADY_EXPIRED : LONG_BLOCK);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    adderDone.set(true);
                }
            }, "block-list-adder");
            Thread sweeper = new Thread(() -> {
                try {
                    go.await();
                    while (!adderDone.get()) {
                        list.removeExpiredEntries();
                        list.isBlocked(1, 3);
                        list.getBlockList();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }, "block-list-sweeper");
            adder.start();
            sweeper.start();
            go.countDown();
            adder.join(TimeUnit.SECONDS.toMillis(60));
            sweeper.join(TimeUnit.SECONDS.toMillis(60));

            assertFalse(adder.isAlive() || sweeper.isAlive(), "the two threads finish");
            assertNull(failure.get(), "round " + round + ": add and sweep share the list: " + failure.get());
        }
    }

    @Test
    void aBlockHoldsForItsDurationThenReadsAsGone() {
        BotBlockList list = new BotBlockList();
        list.addToBlockList(1, 2, LONG_BLOCK);
        list.addToBlockList(1, 3, ALREADY_EXPIRED);

        assertTrue(list.isBlocked(1, 2));
        assertFalse(list.isBlocked(1, 3), "an expired block does not count even before a sweep removes it");
        assertFalse(list.isBlocked(2, 2), "the block belongs to bot 1 only");
        assertTrue(list.getBlockEntry(1, 2).isPresent());
        assertTrue(list.getBlockEntry(1, 3).isEmpty());
        assertEquals(1, list.getActiveBlockCount());
    }

    @Test
    void listsAndCountsShowOnlyLiveBlocks() {
        BotBlockList list = new BotBlockList();
        list.addToBlockList(1, 10, LONG_BLOCK);
        list.addToBlockList(1, 11, LONG_BLOCK);
        list.addToBlockList(2, 10, LONG_BLOCK);
        list.addToBlockList(1, 12, ALREADY_EXPIRED);

        assertEquals(3, list.getBlockList().size());
        assertEquals(2, list.getBlocksForBot(1).size());
        assertEquals(List.of(10, 11), list.getBlockedTraders(1).stream().sorted().toList());
        assertEquals(3, list.getActiveBlockCount());
    }

    @Test
    void removeBlockAndClearTakeBlocksOff() {
        BotBlockList list = new BotBlockList();
        list.addToBlockList(1, 2, LONG_BLOCK);
        list.addToBlockList(3, 4, LONG_BLOCK);

        assertTrue(list.removeBlock(1, 2));
        assertFalse(list.removeBlock(1, 2), "nothing left to remove");
        assertFalse(list.isBlocked(1, 2));
        assertTrue(list.isBlocked(3, 4));

        list.clearBlockList();
        assertEquals(0, list.getActiveBlockCount());
    }

    @Test
    void aSweepLeavesTheLiveBlocksAlone() {
        BotBlockList list = new BotBlockList();
        for (int i = 0; i < 100; i++) {
            list.addToBlockList(1, i, ALREADY_EXPIRED);
        }
        list.addToBlockList(1, 1000, LONG_BLOCK);
        list.removeExpiredEntries();

        assertEquals(1, list.getBlockList().size());
        assertEquals(1, list.getActiveBlockCount());
    }
}
