package soloMapling.Economy.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EconomyCountersTest {

    @BeforeEach
    void setUp() {
        EconomyCounters.resetForTest();
    }

    @Test
    void recordsSourcesAndSinksCorrectly() {
        // Faucets / Sources
        EconomyCounters.recordMobMesoDrop(false, 5_000);  // human
        EconomyCounters.recordMobMesoDrop(true, 12_000);  // bot
        EconomyCounters.recordNpcSell(25_000);
        EconomyCounters.recordQuestReward(10_000);

        // Sinks
        EconomyCounters.recordNpcBuy(15_000);
        EconomyCounters.recordJobMasterFee(800_000);
        EconomyCounters.recordUpkeep(50_000);
        EconomyCounters.recordFmTax(3_000);

        // Transfers
        EconomyCounters.recordTrade(100_000);
        EconomyCounters.recordFmSale(200_000);

        // Special currencies
        EconomyCounters.recordMapleLeafGained(50);
        EconomyCounters.recordMapleLeafSpent(20);
        EconomyCounters.recordNxEarned(250);
        EconomyCounters.recordNxSpent(100);

        var snap = EconomyCounters.snapshotAndReset();

        // Check sources
        assertEquals(5_000, snap.mobMesoDropsHuman());
        assertEquals(12_000, snap.mobMesoDropsBot());
        assertEquals(17_000, snap.totalMobMesoDrops());
        assertEquals(25_000, snap.npcSellMesos());
        assertEquals(10_000, snap.questRewardMesos());
        assertEquals(52_000, snap.totalSources());

        // Check sinks
        assertEquals(15_000, snap.npcBuyMesos());
        assertEquals(800_000, snap.jobMasterFees());
        assertEquals(50_000, snap.upkeepMesos());
        assertEquals(3_000, snap.fmTaxMesos());
        assertEquals(868_000, snap.totalSinks());

        // Check net delta
        assertEquals(52_000 - 868_000, snap.netMesoDelta());

        // Check transfers
        assertEquals(100_000, snap.tradeMesos());
        assertEquals(200_000, snap.fmSaleMesos());

        // Check currencies
        assertEquals(50, snap.mapleLeafGained());
        assertEquals(20, snap.mapleLeafSpent());
        assertEquals(30, snap.mapleLeafNet());
        assertEquals(250, snap.nxEarned());
        assertEquals(100, snap.nxSpent());
        assertEquals(150, snap.nxNet());

        // After snapshotAndReset, next snapshot should be 0
        var nextSnap = EconomyCounters.snapshotAndReset();
        assertEquals(0, nextSnap.totalSources());
        assertEquals(0, nextSnap.totalSinks());
    }

    @Test
    void handlesConcurrentUpdatesSafely() throws InterruptedException {
        int threads = 8;
        int iterations = 10_000;
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            ex.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < iterations; j++) {
                        EconomyCounters.recordMobMesoDrop(false, 10);
                        EconomyCounters.recordNpcBuy(5);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        ex.shutdown();

        var snap = EconomyCounters.snapshotAndReset();
        assertEquals((long) threads * iterations * 10, snap.mobMesoDropsHuman());
        assertEquals((long) threads * iterations * 5, snap.npcBuyMesos());
    }

    @Test
    void playerDroppedMesoPickupIsNotAFaucet() {
        EconomyCounters.resetForTest();
        EconomyCounters.recordMesoPickup(true, false, 1_000);
        EconomyCounters.recordMesoPickup(false, false, 300);
        assertEquals(300L, EconomyCounters.peek().mobMesoDropsHuman());
        EconomyCounters.resetForTest();
    }
}
