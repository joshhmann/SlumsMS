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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private static final int RAMPART_RESIDENCES = 410000350;
    private static final int STORAGE_CAVE = 410000310;

    private static final Pattern IMGDIR = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL);
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
    void theMapManagerServesTheMapTheLoginFallbackLandsOn() throws Exception {
        WzTestData.assumePresent("Map.wz");
        // a map image exists but its spawn tables are in the database: an empty one is enough for the geometry
        try (MockedStatic<DatabaseConnection> db = Mockito.mockStatic(DatabaseConnection.class)) {
            Connection con = mock(Connection.class, RETURNS_DEEP_STUBS);
            db.when(DatabaseConnection::getConnection).thenReturn(con);

            MapManager maps = new MapManager(null, 1, 1);

            assertEquals(FOUNTAIN_SQUARE, maps.getMap(STORAGE_CELLAR).getId(),
                    "a script asking for the Storage Cellar gets Fountain Square, never null");
            assertEquals(FOUNTAIN_SQUARE, maps.getMap(RAMPART_RESIDENCES).getId(),
                    "a character saved inside Rampart Residences logs in at Fountain Square");
            assertEquals(STORAGE_CAVE, maps.getMap(STORAGE_CAVE).getId(), "an ordinary map is untouched");
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

    @Test
    void theFountainSquareDoorTheTableRoutesIsTheOneInTheData() throws IOException {
        WzTestData.assumePresent("Map.wz");
        String tm = portalField(FOUNTAIN_SQUARE, "in00", "tm");
        assertEquals(String.valueOf(STORAGE_CELLAR), tm, "410000300 in00 targets the Storage Cellar");

        MapRouting.Route route = MapRouting.route(FOUNTAIN_SQUARE, "in00", STORAGE_CELLAR);
        assertNotNull(route);
        assertEquals(FOUNTAIN_SQUARE, route.map());
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
