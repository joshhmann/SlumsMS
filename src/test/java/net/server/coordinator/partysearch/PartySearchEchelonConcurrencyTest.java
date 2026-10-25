package net.server.coordinator.partysearch;

import client.Character;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic dfe03664a): PartySearchEchelon.attachPlayer and detachPlayer changed
 * a plain HashMap while holding only the shared READ lock, so two threads could change it at once. With bots joining
 * and leaving parties all the time the map's size went wrong (nutnnut saw it go negative and the party-search export
 * crash with "Illegal Capacity: -1" every ~15 s) and entries were lost. Many threads attach at once here; every one
 * of them must come out of the export.
 */
class PartySearchEchelonConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 1500;

    private static Character character(int id) throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(chr, id);
        return chr;
    }

    @Test
    void concurrentAttachesAreAllKept() throws Exception {
        List<Character> everyone = new ArrayList<>();   // strong references: the echelon holds weak ones
        for (int i = 0; i < THREADS * PER_THREAD; i++) {
            everyone.add(character(1_000_000_000 + i));
        }

        for (int round = 0; round < 5; round++) {
            PartySearchEchelon echelon = new PartySearchEchelon();
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int from = t * PER_THREAD;
                done.add(pool.submit(() -> {
                    start.await();
                    for (int i = from; i < from + PER_THREAD; i++) {
                        echelon.attachPlayer(everyone.get(i));
                    }
                    // and churn: leave and come back, as party-hopping bots do
                    for (int i = from; i < from + PER_THREAD; i += 3) {
                        echelon.detachPlayer(everyone.get(i));
                        echelon.attachPlayer(everyone.get(i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(60, TimeUnit.SECONDS);
            }
            pool.shutdown();

            assertEquals(THREADS * PER_THREAD, echelon.exportEchelon().size(), "round " + round + " lost entries");
        }
    }
}
