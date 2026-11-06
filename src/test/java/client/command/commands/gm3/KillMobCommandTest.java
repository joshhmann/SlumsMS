package client.command.commands.gm3;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.5: {@code !killmob <id>} kills only that id, Horntail's heads included (which {@code !killall} skips), and does it
 * through {@link MapleMap#damageMonster} - the path a player's hit takes, which ends in {@code killMonster} and the
 * event instance's kill hook (Horntail's {@code defeatedHead}).
 */
class KillMobCommandTest {

    static Monster mob(int id, boolean alive, boolean friendly, int maxHp) {
        Monster m = mock(Monster.class);
        MonsterStats stats = mock(MonsterStats.class);
        when(stats.isFriendly()).thenReturn(friendly);
        when(m.getStats()).thenReturn(stats);
        when(m.getId()).thenReturn(id);
        when(m.isAlive()).thenReturn(alive);
        when(m.getMaxHp()).thenReturn((long) maxHp);
        return m;
    }

    @Test
    void killsOnlyThatIdThroughThePlayersDamagePathHorntailHeadsIncluded() {
        Character gm = mock(Character.class);
        when(gm.getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        Monster headA = mob(8810000, true, false, 3_300_000);
        Monster other = mob(8810019, true, false, 10_000);
        Monster deadHeadA = mob(8810000, false, false, 3_300_000);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.<MapObject>of(headA, other, deadHeadA));

        assertEquals(1, KillMobCommand.killAll(gm, map, 8810000));
        verify(map).damageMonster(gm, headA, 3_300_000L);
        verify(map, never()).damageMonster(eq(gm), eq(other), anyLong());
        verify(map, never()).damageMonster(eq(gm), eq(deadHeadA), anyLong());
    }

    @Test
    void aFriendlyMobIsNotKilled() {
        Character gm = mock(Character.class);
        when(gm.getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        Monster friendly = mob(9300061, true, true, 100);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), any())).thenReturn(List.<MapObject>of(friendly));
        assertEquals(0, KillMobCommand.killAll(gm, map, 9300061));
    }
}
