package soloMapling.Environment;

import constants.id.MapId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/* 1.6.0 area levels: how a Victoria area's bands come out of its maps' mob levels (AreaLevelBands). No WZ needed;
 * AreaLevelBandsWzTest runs the same derivation over the real maps. */
class AreaLevelBandsTest {

    private static AreaLevelBands.GrindMap map(int mapId, int level) {
        return new AreaLevelBands.GrindMap(mapId, level, 30, true);
    }

    private static List<AreaLevelBands.GrindMap> maps(int... levels) {
        List<AreaLevelBands.GrindMap> out = new ArrayList<>();
        int id = 102_030_000;
        for (int level : levels) {
            out.add(map(id++, level));
        }
        return out;
    }

    @Test
    void aBandRunsFiveUnderTheEasyMapsToFiveOverTheHardOnes() {
        // 10th percentile 32 -> 27 -> 25, 90th percentile 48 -> 53 -> 55
        assertEquals(new AreaLevelBands.Band(25, 55),
                AreaLevelBands.derive(List.of(32, 32, 35, 35, 37, 40, 40, 48, 48, 52), 1, 200));
    }

    @Test
    void oneOddMapDoesNotStretchTheArea() {
        // Perion's Remains <Cliff> (73) is one map out of fifteen: the 90th percentile is the Tomb's 63
        List<Integer> perionUpper = List.of(32, 32, 42, 42, 44, 44, 45, 45, 45, 47, 57, 57, 63, 63, 73);
        assertEquals(new AreaLevelBands.Band(30, 70), AreaLevelBands.derive(perionUpper, 30, 200));
    }

    @Test
    void theFloorAndCeilingHold() {
        assertEquals(new AreaLevelBands.Band(10, 15), AreaLevelBands.derive(List.of(2, 2, 7, 10), 10, 30));
        assertEquals(new AreaLevelBands.Band(1, 15), AreaLevelBands.derive(List.of(2, 2, 7, 10), 1, 30));
        assertEquals(new AreaLevelBands.Band(20, 30), AreaLevelBands.derive(List.of(28, 29, 29), 10, 30));
    }

    @Test
    void noMapsIsNoBand() {
        assertNull(AreaLevelBands.derive(List.of(), 1, 200));
        assertNull(AreaLevelBands.upper(maps(4, 8, 12, 20)), "a low area has no upper band");
        assertNull(AreaLevelBands.core(maps(35, 40)), "a high area has no core band");
    }

    @Test
    void anAreaSplitsAtTheSecondJobLine() {
        List<AreaLevelBands.GrindMap> kerning = maps(6, 6, 7, 8, 8, 10, 12, 12, 12, 15, 15, 20, 21, 22,
                32, 32, 32, 35, 35, 35, 37, 40, 40, 48, 48, 48, 48, 48, 52, 52);
        assertEquals(new AreaLevelBands.Band(10, 25), AreaLevelBands.core(kerning));
        assertEquals(new AreaLevelBands.Band(30, 55), AreaLevelBands.upper(kerning));
    }

    @Test
    void onlyRealGrindMapsCount() {
        assertTrue(AreaLevelBands.isGrindMap(map(104_040_000, 4)), "Henesys Hunting Ground I");
        assertFalse(AreaLevelBands.isGrindMap(new AreaLevelBands.GrindMap(105_050_150, 24, 25, false)),
                "an unnamed Cave of Mushrooms copy");
        assertFalse(AreaLevelBands.isGrindMap(new AreaLevelBands.GrindMap(100_000_100, 4, 3, true)), "three spawns");
        assertFalse(AreaLevelBands.isGrindMap(map(109_040_001, 200)), "an event map's Lv200 dummies");
        assertFalse(AreaLevelBands.isGrindMap(map(108_000_100, 35)), "a job-advancement instance");
        assertFalse(AreaLevelBands.isGrindMap(map(MapId.HENESYS_PIG_FARM_BASE + 3, 10)), "a mini-dungeon copy");
        assertFalse(AreaLevelBands.isGrindMap(map(100_000_000, -1)), "a town has no mobs");

        // the filtered maps do not pull the band: forty unnamed copies would otherwise drag it to their level
        List<AreaLevelBands.GrindMap> area = new ArrayList<>(maps(35, 40, 45, 55, 60));
        for (int i = 0; i < 40; i++) {
            area.add(new AreaLevelBands.GrindMap(105_050_150 + i, 35, 25, false));
        }
        assertEquals(AreaLevelBands.upper(maps(35, 40, 45, 55, 60)), AreaLevelBands.upper(area));
    }
}
