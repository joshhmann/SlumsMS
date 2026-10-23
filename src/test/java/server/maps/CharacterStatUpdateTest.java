package server.maps;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * rc6 bug hunt (P3, stock Cosmic): stat updates were queued on an unsynchronized ArrayList from many threads, so a
 * resize race left null slots and runCharacterStatUpdate threw, dropping every other queued update.
 */
class CharacterStatUpdateTest {

    @Test
    void concurrentRegistrationsAllRunAndAFailingOneDoesNotStopTheRest() throws Exception {
        MapleMap map = new MapleMap(910000997, 0, 0, 910000997, 1.0f);
        AtomicInteger ran = new AtomicInteger();
        int threads = 8;
        int each = 2_000;
        CountDownLatch go = new CountDownLatch(1);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            ts[t] = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    map.registerCharacterStatUpdate(ran::incrementAndGet);
                }
            });
            ts[t].start();
        }
        go.countDown();
        for (Thread t : ts) {
            t.join();
        }
        map.registerCharacterStatUpdate(null);
        map.registerCharacterStatUpdate(() -> {
            throw new IllegalStateException("one bad update");
        });
        map.registerCharacterStatUpdate(ran::incrementAndGet);

        map.runCharacterStatUpdate();
        assertEquals(threads * each + 1, ran.get(), "every queued update ran once");
        map.runCharacterStatUpdate();
        assertEquals(threads * each + 1, ran.get(), "and the queue was emptied");
    }
}
