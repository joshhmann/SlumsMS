package server.maps;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.SpawnPoint;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Dormant bug from the Nutnnut second pass: a spawn-rate multiplier bypassed spawn-point limits and was applied twice. */
class MapRespawnLimitsTest {

    private static SpawnPoint point(boolean allowed) {
        SpawnPoint sp = mock(SpawnPoint.class);
        when(sp.shouldSpawn()).thenReturn(allowed);
        when(sp.getMonster()).thenReturn(mock(Monster.class));
        return sp;
    }

    @Test
    void pointsThatMayNotSpawnNeverSpawn() {
        SpawnPoint denied = point(false);   // boss already alive, denied, mobTime < 0 or not yet due
        SpawnPoint ok = point(true);
        List<Monster> out = new ArrayList<>();
        assertEquals(1, MapleMap.spawnFrom(List.of(denied, ok), 5, out::add));
        verify(denied, never()).getMonster();
        assertEquals(1, out.size());
    }

    @Test
    void stopsAtTheRequestedCount() {
        List<SpawnPoint> pts = List.of(point(true), point(true), point(true));
        assertEquals(2, MapleMap.spawnFrom(pts, 2, m -> { }));
    }

    @Test
    void theMultiplierIsAppliedOnce() {
        // 10 spawn points, 6 players -> 100% of them, x2
        assertEquals(20, MapleMap.numShouldSpawn(6, 10, 2, 0, false));
        assertEquals(10, MapleMap.numShouldSpawn(6, 10, 1, 0, false));
        assertEquals(20, MapleMap.numShouldSpawn(6, 10, 2, 0, true));
        assertEquals(15, MapleMap.numShouldSpawn(6, 10, 2, 5, true));
    }
}
