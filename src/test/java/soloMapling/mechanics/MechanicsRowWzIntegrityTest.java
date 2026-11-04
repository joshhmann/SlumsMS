package soloMapling.mechanics;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import testutil.PropsRestored;

/**
 * Every row in {@code content/} against the real WZ, skipped without {@code -Dwz-path} (or a {@code wz/} folder).
 *
 * <p>A content row is only as good as the ids in it: a stage on a map the client does not have is a run that warps into
 * nothing, and a {@code mobs:} id with no {@code Mob.wz} entry is a boss that never stands up. Neither shows up in a
 * parser test, which is why this one reads the dump. It covers every row rather than the newest four, so the check keeps
 * working as rows are added: the list comes from {@code index.txt}, not from a hardcoded set.
 */
@ExtendWith(PropsRestored.class)
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

    /**
     * The same class of mistake the map check in the test above cannot see: a stage that names a reactor which is not
     * on the map the stage sends the party to. {@code magatiapq} shipped with its beaker stage on {@code 926110200},
     * where only the door reactors stand - the three {@code 2618000} beakers are next door on {@code 926110100} - so the
     * stage could never clear: {@code ReactorCountSolver} warps to the stage's own map and hits a reactor that is not
     * there until the clock runs out. No per-solver test could catch it, because a fake view answers the reactor by id
     * whatever map it stands on.
     *
     * <p>So this walks every row and asks the dump the question a solver cannot: is the id on this stage's ground? A
     * stage's ground is its own {@code map:} plus its {@code maps:} list, and the reactors it names are
     * {@code hitReactors}, the clear's {@code reactorId} and any objective's numeric {@code reactor:}. Named reactors are
     * skipped (a name is not an id and cannot be looked up in the map image), and a map the dump does not have is left
     * to the test above rather than reported twice.
     */
    @Test
    void everyStageThatNamesAReactorHasItOnItsOwnGround() throws IOException {
        WzTestData.assumePresent("Map.wz");

        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the rows parse: " + r.errors());

        Path mapRoot = WzTestData.dir().resolve("Map.wz").resolve("Map");
        List<String> wrongRoom = new ArrayList<>();
        int checked = 0;
        for (MechanicsModel.Content c : r.content()) {
            for (MechanicsModel.Stage s : c.stages()) {
                java.util.Set<Integer> ground = new java.util.HashSet<>();
                ground.add(s.mapId());
                ground.addAll(s.maps());
                List<Integer> named = new ArrayList<>(s.hitReactors());
                if (s.clear() != null && s.clear().reactorId() > 0) {
                    named.add(s.clear().reactorId());
                }
                for (MechanicsModel.Objective o : s.objectives()) {
                    if (o.hasReactor()) {
                        try {
                            int id = Integer.parseInt(o.reactor().trim());
                            if (id > 0) {
                                named.add(id);
                            }
                        } catch (NumberFormatException ignoredByName) {
                            // a name, not an id: reactorFor resolves it with getReactorByName, and no map image lists names
                        }
                    }
                }
                java.util.Set<Integer> present = new java.util.HashSet<>();
                for (int map : ground) {
                    present.addAll(reactorsOn(mapRoot, map));
                }
                for (int id : named) {
                    checked++;
                    if (!present.contains(id)) {
                        wrongRoom.add(c.id() + " stage " + s.index() + ": reactor " + id + " is named but stands on none of "
                                + ground + " (that ground holds " + new java.util.TreeSet<>(present) + ")");
                    }
                }
            }
        }
        assertTrue(wrongRoom.isEmpty(), "every reactor a stage names must stand on that stage's ground: " + wrongRoom);
        assertTrue(checked > 60, "the pass really walked the rows' reactors: " + checked);
        // and it can fail: an id nobody has must not be found on any map
        assertFalse(reactorsOn(mapRoot, 926110100).contains(12_345_678), "a reactor id that does not exist");
        assertTrue(reactorsOn(mapRoot, 926110100).contains(2618000), "and the beakers are the control that must be found");
    }

    private static final java.util.Map<Integer, java.util.Set<Integer>> REACTORS = new java.util.HashMap<>();

    /** The reactor ids a map image places, read once per map. */
    private static java.util.Set<Integer> reactorsOn(Path mapRoot, int mapId) throws IOException {
        java.util.Set<Integer> cached = REACTORS.get(mapId);
        if (cached != null) {
            return cached;
        }
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        Path image = mapImagePath(mapRoot, mapId);
        if (image != null) {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setValidating(false);
            try {
                org.w3c.dom.Document doc = f.newDocumentBuilder().parse(image.toFile());
                org.w3c.dom.NodeList blocks = doc.getElementsByTagName("imgdir");
                for (int i = 0; i < blocks.getLength(); i++) {
                    org.w3c.dom.Element block = (org.w3c.dom.Element) blocks.item(i);
                    if (!"reactor".equals(block.getAttribute("name"))) {
                        continue;
                    }
                    org.w3c.dom.NodeList entries = block.getChildNodes();
                    for (int j = 0; j < entries.getLength(); j++) {
                        org.w3c.dom.Node entry = entries.item(j);
                        if (entry.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                            continue;
                        }
                        org.w3c.dom.NodeList fields = entry.getChildNodes();
                        for (int k = 0; k < fields.getLength(); k++) {
                            org.w3c.dom.Node field = fields.item(k);
                            if (field.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                                    && "id".equals(((org.w3c.dom.Element) field).getAttribute("name"))) {
                                try {
                                    ids.add(Integer.parseInt(((org.w3c.dom.Element) field).getAttribute("value").trim()));
                                } catch (NumberFormatException ignored) {
                                    // a malformed id is the dump's problem, not this test's
                                }
                            }
                        }
                    }
                }
            } catch (org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
                throw new IOException("cannot read " + image, e);
            }
        }
        REACTORS.put(mapId, ids);
        return ids;
    }

    /** {@code Map.wz/Map/Map<first digit>/<id>.img.xml}, which is how the client lays the maps out. */
    /** The map's image, or null when the dump does not have it. */
    private static Path mapImagePath(Path mapRoot, int mapId) throws IOException {
        String name = mapId + ".img.xml";
        try (Stream<Path> subs = Files.list(mapRoot)) {
            for (Path sub : subs.filter(Files::isDirectory).toList()) {
                Path image = sub.resolve(name);
                if (Files.exists(image)) {
                    return image;
                }
            }
        }
        return null;
    }

    private static boolean mapImage(Path mapRoot, int mapId) throws IOException {
        String name = mapId + ".img.xml";
        try (Stream<Path> subs = Files.list(mapRoot)) {
            return subs.filter(Files::isDirectory)
                    .anyMatch(dir -> Files.exists(dir.resolve(name)));
        }
    }
}
