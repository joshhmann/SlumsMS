package soloMapling.mechanics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every row in {@code content/} against the real WZ, skipped without {@code -Dwz-path} (or a {@code wz/} folder).
 *
 * <p>A content row is only as good as the ids in it: a stage on a map the client does not have is a run that warps into
 * nothing, and a {@code mobs:} id with no {@code Mob.wz} entry is a boss that never stands up. Neither shows up in a
 * parser test, which is why this one reads the dump. It covers every row rather than the newest four, so the check keeps
 * working as rows are added: the list comes from {@code index.txt}, not from a hardcoded set.
 */
class MechanicsRowWzIntegrityTest {

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null && Files.isDirectory(Path.of("wz"))) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void everyRowNamesMapsAndMobsTheClientActuallyHas() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");

        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the rows parse: " + r.errors());

        Path mapRoot = WzTestData.dir().resolve("Map.wz").resolve("Map");
        Path mobRoot = WzTestData.dir().resolve("Mob.wz");
        List<String> missing = new ArrayList<>();
        int maps = 0;
        int mobs = 0;
        for (MechanicsModel.Content c : r.content()) {
            List<Integer> wanted = new ArrayList<>();
            wanted.add(c.recruitMap());
            wanted.add(c.exitMap());
            for (MechanicsModel.Stage s : c.stages()) {
                wanted.add(s.mapId());
                wanted.addAll(s.maps());
                for (int mob : s.mobs()) {
                    mobs++;
                    if (!Files.exists(mobRoot.resolve(mob + ".img.xml"))) {
                        missing.add(c.id() + " stage " + s.index() + ": no Mob.wz entry for " + mob);
                    }
                }
            }
            for (int map : wanted) {
                if (map <= 0) {
                    continue;
                }
                maps++;
                if (!mapImage(mapRoot, map)) {
                    missing.add(c.id() + ": no Map.wz image for " + map);
                }
            }
        }
        assertTrue(missing.isEmpty(), "every id a row names must exist in the dump: " + missing);
        assertTrue(maps > 60 && mobs > 20, "the pass really walked the rows: " + maps + " maps, " + mobs + " mob ids");
        // and the pass can fail: an id nobody has must be reported, or a green run above means nothing
        assertTrue(!mapImage(mapRoot, 12_345_678), "a map id that does not exist must not be found");
        assertTrue(!Files.exists(mobRoot.resolve("12345678.img.xml")), "nor a mob id");
    }

    /** {@code Map.wz/Map/Map<first digit>/<id>.img.xml}, which is how the client lays the maps out. */
    private static boolean mapImage(Path mapRoot, int mapId) throws IOException {
        String name = mapId + ".img.xml";
        try (Stream<Path> subs = Files.list(mapRoot)) {
            return subs.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.exists(dir.resolve(name)));
        }
    }
}
