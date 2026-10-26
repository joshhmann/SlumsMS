package soloMapling.Environment;

import constants.id.MapId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/*
 * 1.6.0 area levels (owner 2026-10-26: "go by city and the areas around it"): the Victoria cohorts' bands in
 * EnvironmentManager.TRAINING_COHORTS are AreaLevelBands derived from the real maps. This re-derives every band from
 * Map.wz / Mob.wz / String.wz and fails when a constant no longer matches the data. Skips without wz/ (CI); run it with
 * -Dwz-path=<a checkout's wz>.
 */
class AreaLevelBandsWzTest {

    private static Map<Integer, List<AreaLevelBands.GrindMap>> byTown;

    @BeforeAll
    static void readVictoria() throws IOException {
        if (!Files.isDirectory(WzTestData.dir().resolve("Map.wz")) || !Files.isDirectory(WzTestData.dir().resolve("Mob.wz"))
                || !Files.isDirectory(WzTestData.dir().resolve("String.wz"))) {
            return;
        }
        // Mob levels straight from Mob.wz: LifeFactory would want a database for drop data.
        soloMapling.ArtificialPlayer.BotGrindSystem.LevelPathSweepCheck.preloadMobLevels();
        Set<Integer> named = namedMaps();
        byTown = new HashMap<>();
        for (String dir : List.of("Map1", "Map2")) {
        try (Stream<Path> files = Files.list(WzTestData.dir().resolve("Map.wz/Map/" + dir))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".img.xml")) {
                    continue;
                }
                int mapId = Integer.parseInt(name.substring(0, name.indexOf('.')));
                MapMobIndex.MapMobInfo info = MapMobIndex.info(mapId);
                if (info.mobCount() == 0) {
                    continue;
                }
                byTown.computeIfAbsent(MapMobIndex.returnMapId(mapId), k -> new ArrayList<>())
                        .add(new AreaLevelBands.GrindMap(mapId, info.medianLevel(), info.mobCount(), named.contains(mapId)));
            }
        }
        }
    }

    /** Every map id String.wz's Map.img names (Map.img is region -> map id -> streetName/mapName). */
    private static Set<Integer> namedMaps() {
        Set<Integer> out = new HashSet<>();
        Data strings = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Map.img");
        for (Data region : strings) {
            for (Data map : region) {
                try {
                    out.add(Integer.parseInt(map.getName()));
                } catch (NumberFormatException ignored) {
                    // not a map entry
                }
            }
        }
        return out;
    }

    private static List<AreaLevelBands.GrindMap> area(int town) {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        WzTestData.assumePresent("String.wz");
        List<AreaLevelBands.GrindMap> maps = byTown.get(town);
        assertNotNull(maps, "no maps return to " + town);
        return maps;
    }

    private static EnvironmentManager.TrainingCohort cohort(int index) {
        return EnvironmentManager.TRAINING_COHORTS.get(index);
    }

    private static void assertBand(AreaLevelBands.Band derived, EnvironmentManager.TrainingCohort cohort) {
        assertNotNull(derived, "no band for " + cohort);
        assertEquals(derived, new AreaLevelBands.Band(cohort.loLevel(), cohort.hiLevel()), "cohort " + cohort);
    }

    @Test
    void lithHarbourIsTheBeginnerPort() {
        List<Integer> levels = AreaLevelBands.grindLevels(area(MapId.LITH_HARBOUR), 1, AreaLevelBands.SECOND_JOB - 1);
        assertBand(AreaLevelBands.derive(levels, 1, AreaLevelBands.SECOND_JOB), cohort(EnvironmentManager.COHORT_LITH));
        assertEquals(null, AreaLevelBands.upper(area(MapId.LITH_HARBOUR)), "Lith has no Lv30+ zone");
    }

    @Test
    void henesys() {
        assertBand(AreaLevelBands.core(area(MapId.HENESYS)), cohort(EnvironmentManager.COHORT_HENESYS));
        assertBand(AreaLevelBands.upper(area(MapId.HENESYS)), cohort(EnvironmentManager.COHORT_HENESYS_UPPER));
    }

    @Test
    void kerningCity() {
        assertBand(AreaLevelBands.core(area(MapId.KERNING_CITY)), cohort(EnvironmentManager.COHORT_KERNING));
        assertBand(AreaLevelBands.upper(area(MapId.KERNING_CITY)), cohort(EnvironmentManager.COHORT_KERNING_UPPER));
    }

    @Test
    void perion() {
        assertBand(AreaLevelBands.core(area(MapId.PERION)), cohort(EnvironmentManager.COHORT_PERION));
        assertBand(AreaLevelBands.upper(area(MapId.PERION)), cohort(EnvironmentManager.COHORT_PERION_UPPER));
    }

    @Test
    void ellinia() {
        assertBand(AreaLevelBands.core(area(MapId.ELLINIA)), cohort(EnvironmentManager.COHORT_ELLINIA));
        assertBand(AreaLevelBands.upper(area(MapId.ELLINIA)), cohort(EnvironmentManager.COHORT_ELLINIA_UPPER));
    }

    /** Sleepywood's upper zones (Drake caves, Sanctuary, Sleepy Dungeon) are homed on the Ant Tunnel Park deep hub. */
    @Test
    void sleepywood() {
        assertBand(AreaLevelBands.core(area(MapId.SLEEPYWOOD)), cohort(EnvironmentManager.COHORT_SLEEPYWOOD));
        assertBand(AreaLevelBands.upper(area(MapId.SLEEPYWOOD)), cohort(EnvironmentManager.COHORT_ANT_TUNNEL_PARK));
    }

    /** Ludibrium: its maps under the Path of Time line (the Lv63+ maps are the deep-hub cohort's). */
    @Test
    void ludibrium() {
        assertBand(AreaLevelBands.belowLine(area(MapId.LUDIBRIUM), AreaLevelBands.PATH_OF_TIME_LINE),
                cohort(EnvironmentManager.COHORT_LUDIBRIUM));
        assertEquals(EnvironmentManager.TRAINING_COHORTS.get(EnvironmentManager.COHORT_PATH_OF_TIME).loLevel() >= AreaLevelBands.PATH_OF_TIME_LINE, true);
    }

    /** El Nath: every grind map that returns to it (Ice Valley, the Sharp Cliff approaches, the cloud maps). */
    @Test
    void elNath() {
        assertBand(AreaLevelBands.upper(area(MapId.EL_NATH)), cohort(EnvironmentManager.COHORT_EL_NATH));
    }

    /** Prints every Victoria area's grind maps and bands: the worksheet behind the constants. */
    @Test
    void printTheWorksheet() {
        area(MapId.HENESYS);
        for (int town : List.of(MapId.LITH_HARBOUR, MapId.HENESYS, MapId.KERNING_CITY, MapId.PERION, MapId.ELLINIA,
                MapId.SLEEPYWOOD, MapId.LUDIBRIUM, MapId.EL_NATH, MapId.ORBIS, MapId.PATH_OF_TIME_HUB)) {
            List<AreaLevelBands.GrindMap> maps = byTown.get(town);
            if (maps == null) {
                continue;
            }
            System.out.println("[AreaLevelBands] " + town + " core " + AreaLevelBands.core(maps) + " upper "
                    + AreaLevelBands.upper(maps) + " levels " + AreaLevelBands.grindLevels(maps, 1, 199).stream().sorted().toList());
        }
    }
}
