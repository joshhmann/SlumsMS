package soloMapling.mechanics;

import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA's Zakum run on TEST 1.6.7 (zakum-sweep-c-20261109T041606Z, 9 Nov 2026): melee bots logged "gives up on Zakum's Arm
 * n ... (no way closer)" while others stood on the altar, and nothing in the feed said whether any arm took a hit. A
 * boss run's end now says how much of each part the row lists is still standing, so the next sweep answers that.
 */
class BossPartsLeftTest {

    private static Monster part(int id, String name, long hp, long maxHp, boolean fake) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        when(m.getName()).thenReturn(name);
        when(m.isAlive()).thenReturn(true);
        when(m.isFake()).thenReturn(fake);
        when(m.getHp()).thenReturn(hp);
        when(m.getMaxHp()).thenReturn(maxHp);
        when(m.getPosition()).thenReturn(new Point(-9, -216));
        return m;
    }

    @Test
    void theEndLineSaysHowMuchOfEachLivePartIsLeft() {
        MapleMap map = mock(MapleMap.class);
        Monster arm1 = part(8800003, "Zakum's Arm 1", 100, 100, false);
        Monster arm2 = part(8800004, "Zakum's Arm 2", 4_310, 10_000, false);
        Monster body = part(8800000, "Zakum", 1, 1, true);
        Monster ligator = part(3110100, "Ligator", 10, 10, false); // not a part this row lists
        when(map.getAllMonsters()).thenReturn(List.of(arm1, arm2, body, ligator));

        String line = LiveStageView.partsLeftLine(map, List.of(8800003, 8800004, 8800000));

        assertEquals("boss parts left: Zakum's Arm 1 100%, Zakum's Arm 2 43%, Zakum (shielded) 100%", line);
        assertEquals("boss parts left: none", LiveStageView.partsLeftLine(mock(MapleMap.class), List.of(8800003)));
        assertEquals("boss parts left: (room gone)", LiveStageView.partsLeftLine(null, List.of(8800003)));
    }

    @Test
    void theRunsEndReportsThePartsLeftForABossRun() throws Exception {
        // wiring: the line is only useful if the end of a run really prints it
        String src = Files.readString(Path.of("src/main/java/soloMapling/mechanics/MechanicsRuns.java"));
        assertTrue(src.contains("LiveStageView.partsLeftLine("), "MechanicsRuns must report the parts left at a boss run's end");
    }
}
