package soloMapling.server;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc6 bug hunt (P3): the per-pair cooldown maps grew for the whole uptime. */
class BoundedMapsTest {

    @Test
    void belowTheSoftLimitNothingIsTouched() {
        Map<Long, Long> m = new ConcurrentHashMap<>();
        for (long i = 0; i < 10; i++) {
            m.put(i, 0L);
        }
        BoundedMaps.prune(m, 10, 20, v -> true);
        assertEquals(10, m.size());
    }

    @Test
    void overTheSoftLimitStaleEntriesGo() {
        Map<Long, Long> m = new ConcurrentHashMap<>();
        long now = 1_000_000;
        for (long i = 0; i < 100; i++) {
            m.put(i, i < 90 ? now - 60_000 : now);   // 90 stale, 10 fresh
        }
        BoundedMaps.prune(m, 50, 200, at -> now - at >= 30_000);
        assertEquals(10, m.size());
        assertTrue(m.values().stream().allMatch(at -> at == now), "only the fresh ones stay");
    }

    @Test
    void theHardLimitHoldsEvenWhenEverythingIsFresh() {
        Map<Long, Long> m = new ConcurrentHashMap<>();
        for (long i = 0; i < 10_000; i++) {
            m.put(i, 5L);
            BoundedMaps.prune(m, 100, 400, at -> false);
        }
        assertTrue(m.size() <= 400, "bounded: " + m.size());
    }
}
