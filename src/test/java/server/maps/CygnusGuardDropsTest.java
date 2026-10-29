package server.maps;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.Monster;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 (owner 2026-10-29, migration 055): Cygnus' guards (8850000-8850004, the ones the fight spawns) now die through
 * the normal kill with drops, so their elite tables can drop; the field-clear that brings Cygnus back still runs and
 * stays drop-free. Before, a guard's death went straight to killAllMonsters (withDrops=false) and dropped nothing.
 */
class CygnusGuardDropsTest {

    private static Monster mob(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        when(m.getMaxHp()).thenReturn(2_000_000_000L);
        return m;
    }

    private static void guardDies(int guardId, int nextCygnus) {
        // mockStatic(LifeFactory) runs its static init, which reads Mob.wz (the HP-bar boss list)
        soloMapling.WzTestData.assumePresent("Mob.wz");
        MapleMap map = mock(MapleMap.class);
        doCallRealMethod().when(map).afterBossKilled(any(), any());
        Character chr = mock(Character.class);
        Monster guard = mob(guardId);
        Monster cygnus = mob(nextCygnus);
        try (MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class)) {
            life.when(() -> LifeFactory.getMonster(nextCygnus)).thenReturn(cygnus);
            assertTrue(map.afterBossKilled(chr, guard), "the map handles the guard's death");
        }
        var order = inOrder(map);
        order.verify(map).killMonster(guard, chr, true);   // the guard's own kill, with drops
        order.verify(map).killAllMonsters();                // then the field-clear, as before
        order.verify(map).spawnMonster(cygnus);             // and Cygnus comes back
        verify(map, never()).killMonster(any(Monster.class), any(), anyBoolean(), anyInt());
    }

    @Test
    void aGuardDropsItsLootThenTheFieldClears() {
        for (int guard = 8850000; guard <= 8850003; guard++) {
            guardDies(guard, 8850011);
        }
    }

    @Test
    void theLastGuardDropsAndBringsTheFormThatDies() {
        guardDies(8850004, 8850012);
    }
}
