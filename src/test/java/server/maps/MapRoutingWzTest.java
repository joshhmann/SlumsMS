package server.maps;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import org.mockito.MockedStatic;
import org.mockito.Mockito;
import tools.DatabaseConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * s-maproute against the real Map.wz (skips without {@code wz/}).
 *
 * Fail-before evidence, from the base commit 2b7a833cb: {@code MapFactory.loadMapFromWz} did
 * {@code Data infoData = mapData.getChildByPath("info")} with no null check, so the 38 map ids Map.wz has no image for
 * threw a NullPointerException on the way in (GenericPortal caught and printed it, a script warp did not), and
 * {@code Character.loadCharFromDB} let {@code MapManager} return null and NPE at {@code ret.map.getPortal(...)}. The
 * two tests below assert the moved behaviour: no exception, and the fallback map the login lands on.
 */
class MapRoutingWzTest {

    private static final int FOUNTAIN_SQUARE = 410000300;
    private static final int STORAGE_CELLAR = 993162016;
    private static final int GOLLUX_ABDOMEN = 863010240;       // client-crash, and Map.wz has its image
    private static final int HEART_TREE = 863010000;           // the abdomen's returnMap: the table's fallback
    private static final int ROSE_GARDEN = 211080000;          // Rose Garden : Gardener's Spot, loads fine
    private static final int ROSE_GARDEN_DEPTH = 211080100;    // client-crash, and Map.wz has its image
    private static final int STORAGE_CAVE = 410000310;

    private static final Pattern IMGDIR = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL);
    private static final String INFO = "<imgdir name=\"info\">";
    private static final Pattern FIELD = Pattern.compile("<(?:string|int) name=\"(\\w+)\" value=\"([^\"]*)\"/>");

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    @Test
    void aMapWithNoImageIsNoMapInsteadOfAnException() {
        WzTestData.assumePresent("Map.wz");
        assertNull(MapFactory.loadMapFromWz(STORAGE_CELLAR, 1, 1, null));
    }

    @Test
    void aDirectGetMapOfABlockedMapIsNoMapAndOnlyTheExplicitFallbackSubstitutes() throws Exception {
        WzTestData.assumePresent("Map.wz");
        // a map image exists but its spawn tables are in the database: an empty one is enough for the geometry
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            Connection con = mock(Connection.class, RETURNS_DEEP_STUBS);
            db.when(DatabaseConnection::getConnection).thenReturn(con);

            MapManager maps = new MapManager(null, 1, 1);

            // The rule: a caller that asked for one map never gets another one's object. Round 1 substituted the
            // fallback here, so a script spawning mobs in the Storage Cellar spawned them in Henesys or Fountain
            // Square and read back an id it never asked for.
            assertNull(maps.getMap(STORAGE_CELLAR), "993162016 has no image, so there is no map to hand out");
            assertNull(maps.getMap(GOLLUX_ABDOMEN), "863010240 crashes the client, so its own image is not handed out");
            assertNull(maps.getMap(ROSE_GARDEN_DEPTH), "211080100 has an image and still crashes the client");
            assertNull(maps.getMap(1234567), "an id Map.wz never had is no map");

            assertEquals(FOUNTAIN_SQUARE, maps.getMapOrFallback(STORAGE_CELLAR).getId(),
                    "the login path asks for the fallback, and gets the table's map for the Storage Cellar");
            assertEquals(HEART_TREE, maps.getMapOrFallback(GOLLUX_ABDOMEN).getId(),
                    "a character saved in Gollux's abdomen logs in at Memories of the Heart Tree");
            assertEquals(ROSE_GARDEN, maps.getMapOrFallback(ROSE_GARDEN_DEPTH).getId(),
                    "a character saved past the Rose Garden entrance logs in at the entrance");
            assertEquals(ROSE_GARDEN, maps.getMap(ROSE_GARDEN).getId(), "the entrance is an ordinary map");
            assertEquals(STORAGE_CAVE, maps.getMap(STORAGE_CAVE).getId(), "an ordinary map is untouched");
            assertEquals(STORAGE_CAVE, maps.getMapOrFallback(STORAGE_CAVE).getId(), "an ordinary map is untouched");
            assertEquals(FOUNTAIN_SQUARE, maps.getMap(FOUNTAIN_SQUARE).getId(), "an ordinary map is untouched");
        }
    }

    @Test
    void everyRouteInTheTableLandsOnAMapThatExists() throws IOException {
        WzTestData.assumePresent("Map.wz");
        List<Integer> blocked = new ArrayList<>();
        for (String[] row : section("blocked_maps")) {
            blocked.add(Integer.parseInt(row[0]));
        }
        assertFalse(blocked.isEmpty());

        for (String[] row : section("routes")) {
            int lands = Integer.parseInt(row[3]);
            assertTrue(imageExists(lands), row[0] + ":" + row[1] + " routes to " + lands + " which has no image");
            assertFalse(blocked.contains(lands), row[0] + ":" + row[1] + " routes into blocked map " + lands);
            String portal = row[4];
            if (!portal.isEmpty() && !portal.equals("0")) {
                assertTrue(portalNames(lands).contains(portal),
                        "map " + lands + " has no portal " + portal + " (row " + row[0] + ":" + row[1] + ")");
            }
        }
    }

    @Test
    void everyBlockedMapLandsOnAMapThatExists() throws IOException {
        WzTestData.assumePresent("Map.wz");
        for (String[] row : section("blocked_maps")) {
            int map = Integer.parseInt(row[0]);
            String kind = row[1];
            int fallback = Integer.parseInt(row[3]);
            if (kind.equals("missing")) {
                assertFalse(imageExists(map), "map " + map + " is blocked as missing but Map.wz has an image");
            } else {
                assertEquals("client-crash", kind, "unknown block kind on " + map);
                assertTrue(imageExists(map), "a client-crash map is one the server can load: " + map);
            }
            assertTrue(imageExists(fallback), "fallback " + fallback + " of " + map + " has no image");
        }
    }

    /**
     * s-maproute round 4 (b253): an image and a portal are not enough. The QA probe loads every fallback for real, and
     * on TEST two of them threw, so t-mp-1 failed twice with {@code NoSuchElementException: No value present}: a
     * destination the routing picks is a map a character actually gets put in, and the table can only be trusted if
     * each one loads. Two causes, both fixed in this round: mob 3501008 (map 867120900) and 3401011 (map 867120100)
     * name mob skill 201, which this server does not implement ({@code MobSkillToleranceTest}), and map 706030000's
     * reactor 7492024 waits for an item its data never names ({@code ReactorLoadTest}). This test keeps the table
     * honest against whatever the load path does next, so a new route cannot add a crash only TEST would find.
     *
     * <p>Only destinations are loaded. A row's source map is where the character already stands, so the routing cannot
     * put anyone in it, and 229 maps (five of them sources in the table: 811000001, 863010210, 863010230, 863010300,
     * 863010400) spawn a later-client mob that has no Mob.wz image at all - a load failure of its own that
     * {@code tools/data-align/map_routing.py} keeps out of the destination column.
     */
    @Test
    void everyMapTheTableSendsACharacterToLoadsOnThisServer() throws Exception {
        WzTestData.assumePresent("Map.wz");
        Set<Integer> ids = new TreeSet<>();
        for (String[] row : section("routes")) {
            ids.add(Integer.parseInt(row[3]));       // where the routing puts the character, portal and all
        }
        for (String[] row : section("blocked_maps")) {
            ids.add(Integer.parseInt(row[3]));       // the fallback of a blocked map
        }
        assertFalse(ids.isEmpty());

        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            Connection con = mock(Connection.class, RETURNS_DEEP_STUBS);
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            for (int id : ids) {
                assertLoads(id);
            }
        }
    }

    private static void assertLoads(int map) {
        MapleMap loaded;
        try {
            loaded = MapFactory.loadMapFromWz(map, 1, 1, null);
        } catch (RuntimeException e) {
            throw new AssertionError("the routing table moves a character into " + map + ", which cannot load", e);
        }
        assertNotNull(loaded, "the routing table moves a character into " + map + ", which has no image");
    }

    @Test
    void theFountainSquareDoorTheTableRoutesIsTheOneInTheData() throws IOException {
        WzTestData.assumePresent("Map.wz");
        String tm = portalField(FOUNTAIN_SQUARE, "in00", "tm");
        assertEquals(String.valueOf(STORAGE_CELLAR), tm, "410000300 in00 targets the Storage Cellar");

        MapRouting.Route route = MapRouting.route(FOUNTAIN_SQUARE, "in00", STORAGE_CELLAR);
        assertNotNull(route);
        assertEquals(FOUNTAIN_SQUARE, route.map());
    }

    @Test
    void theRoseGardenDoorTheTableRoutesIsTheOneInTheData() throws IOException {
        WzTestData.assumePresent("Map.wz");
        assertEquals(String.valueOf(ROSE_GARDEN_DEPTH), portalField(ROSE_GARDEN, "east00", "tm"),
                "211080000 east00 targets the first map past the entrance");

        MapRouting.Route route = MapRouting.route(ROSE_GARDEN, "east00", ROSE_GARDEN_DEPTH);
        assertNotNull(route);
        assertEquals(ROSE_GARDEN, route.map(), "the only way in is a door the client dies behind");
        assertEquals("return", route.reason());
    }

    /**
     * s-maproute round 2, review item 4: the reason column says which rule picked the row, so it has to be provable
     * from the data and not just from the run that wrote it. Row {@code 100 out03 70 -> 66 source-return} is the case
     * that was questioned: 100's own returnMap is 66 (65 is 66's), so the two rows are a chain out of the same hole.
     */
    @Test
    void everyRowReasonIsTheRuleItNames() throws IOException {
        WzTestData.assumePresent("Map.wz");
        for (String[] row : section("routes")) {
            String reason = row[5].replace(" (script)", "");
            int lands = Integer.parseInt(row[3]);
            String where = row[0] + ":" + row[1] + " -> " + row[2] + " = " + lands + " (" + reason + ")";
            if (row.length > 6) {
                assertEquals("manual", row[6], where + ": a seventh column can only be the manual marker");
                continue;
            }
            switch (reason) {
                case "return": {
                    int target = Integer.parseInt(row[2]);
                    String forced = mapInfoField(target, "forcedReturn");
                    String back = mapInfoField(target, "returnMap");
                    assertTrue(String.valueOf(lands).equals(forced) || String.valueOf(lands).equals(back),
                            where + ": the target returns to " + forced + " / " + back + ", not " + lands);
                    break;
                }
                case "source-return": {
                    assertNotEquals("*", row[0], where + ": a script row has no source map to return to");
                    String back = mapInfoField(Integer.parseInt(row[0]), "returnMap");
                    assertEquals(String.valueOf(lands), back, where + ": the source's returnMap is " + back);
                    break;
                }
                case "henesys":
                    assertEquals(100000000, lands, where + ": the last resort is Henesys");
                    break;
                case "portal-pair":
                case "street-town":
                case "street-nearest":
                    break;   // checked by tools/data-align/map_routing.py --check, which has String.wz and every portal
                default:
                    fail(where + ": unknown reason");
            }
        }
    }

    /**
     * A field of a map's info node ("returnMap", "forcedReturn", "town"), null when absent. The dump carries several
     * {@code info} blocks per image (the WZ's own patch history, nine of them in Map0/000002000.img.xml), and only the
     * last one is the map's real info: 2000's first block holds a single string and its last holds returnMap 2000.
     */
    private static String mapInfoField(int map, String field) throws IOException {
        Path file = imagePath(map);
        if (!Files.exists(file)) {
            return null;
        }
        String xml = Files.readString(file);
        String found = null;
        int at = xml.indexOf(INFO);
        while (at >= 0) {
            int next = xml.indexOf("<imgdir name=", at + 1);
            String info = next < 0 ? xml.substring(at) : xml.substring(at, next);
            Matcher f = FIELD.matcher(info);
            while (f.find()) {
                if (f.group(1).equals(field)) {
                    found = f.group(2);
                }
            }
            at = xml.indexOf(INFO, at + INFO.length());
        }
        return found;
    }

    private static boolean imageExists(int map) {
        return Files.exists(imagePath(map));
    }

    private static Path imagePath(int map) {
        return WzTestData.dir().resolve("Map.wz/Map/Map" + (map / 100000000) + "/"
                + String.format("%09d", map) + ".img.xml");
    }

    /** Portal names of a map, empty when the image is not there. */
    private static List<String> portalNames(int map) {
        List<String> out = new ArrayList<>();
        String xml = portalSection(map);
        Matcher p = IMGDIR.matcher(xml);
        while (p.find()) {
            Matcher f = FIELD.matcher(p.group(2));
            while (f.find()) {
                if (f.group(1).equals("pn")) {
                    out.add(f.group(2));
                }
            }
        }
        return out;
    }

    private static String portalField(int map, String portalName, String field) throws IOException {
        String xml = portalSection(map);
        Matcher p = IMGDIR.matcher(xml);
        while (p.find()) {
            Matcher f = FIELD.matcher(p.group(2));
            String name = null;
            String value = null;
            while (f.find()) {
                if (f.group(1).equals("pn")) {
                    name = f.group(2);
                }
                if (f.group(1).equals(field)) {
                    value = f.group(2);
                }
            }
            if (portalName.equals(name)) {
                return value;
            }
        }
        return null;
    }

    private static String portalSection(int map) {
        Path file = imagePath(map);
        try {
            String xml = Files.readString(file);
            int at = xml.indexOf("<imgdir name=\"portal\">");
            return at < 0 ? "" : xml.substring(at);
        } catch (IOException e) {
            return "";
        }
    }

    /** Rows of a table section ("routes" or "blocked_maps"), tab split, "# manual" dropped. */
    private static List<String[]> section(String name) throws IOException {
        List<String[]> out = new ArrayList<>();
        boolean inside = false;
        try (InputStream in = MapRouting.class.getResourceAsStream(MapRouting.TABLE)) {
            assertNotNull(in, "the routing table is in the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("# routes:") || line.startsWith("# blocked_maps:")) {
                    inside = line.startsWith("# " + name + ":");
                    continue;
                }
                if (!inside || line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int hash = line.indexOf(" # manual");
                out.add((hash < 0 ? line : line.substring(0, hash)).split("\t"));
            }
        }
        return out;
    }
}
