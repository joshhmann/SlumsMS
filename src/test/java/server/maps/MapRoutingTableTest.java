package server.maps;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * s-maproute, no WZ needed: the policy of the checked-in routing table (src/main/resources/soloMapling/map-routing.tsv).
 * Before this table a move into one of the 40-odd maps Map.wz has no image for loaded null and threw inside
 * MapFactory (mapData.getChildByPath("info") on null), and the client died walking into Gollux's abdomen and the
 * whole Rose Garden family past its entrance.
 *
 * The real-WZ half (that every route lands on a map that exists, and that every blocked map has a fallback that
 * loads) is {@link MapRoutingWzTest}; the bots' half is GCWorldGraphRoutingTest.
 */
class MapRoutingTableTest {

    private static final int FOUNTAIN_SQUARE = 410000300;      // Ristonia
    private static final int STORAGE_CELLAR = 993162016;       // Ristonia, no Map.wz image
    private static final int GOLLUX_ABDOMEN = 863010240;       // Phantom Forest, the client crash on TEST
    private static final int HEART_TREE = 863010000;           // Gollux: Abdomen's own returnMap
    private static final int STORAGE_CAVE = 410000310;
    private static final int ROSE_GARDEN = 211080000;          // Rose Garden : Gardener's Spot, loads fine
    private static final int ROOF_OF_THE_FIFTH_TOWER = 211061001; // the map before it, loads fine

    @Test
    void theTableIsInTheJar() {
        assertTrue(MapRouting.enabled(), "the switch defaults to on so TEST has routing without a config edit");
        assertTrue(MapRouting.routeCount() > 50, "routes loaded: " + MapRouting.routeCount());
        assertTrue(MapRouting.blockedCount() > 30, "blocked maps loaded: " + MapRouting.blockedCount());
        assertTrue(MapRouting.source().contains("map-routing.tsv"), MapRouting.source());
    }

    @Test
    void golluxAbdomenIsBlockedAsAClientCrash() {
        assertTrue(MapRouting.blocked(GOLLUX_ABDOMEN));
        assertEquals("client-crash", MapRouting.kind(GOLLUX_ABDOMEN));
        assertEquals(HEART_TREE, MapRouting.fallback(GOLLUX_ABDOMEN),
                "a character saved inside it logs in at Memories of the Heart Tree");
    }

    @Test
    void rampartResidencesLoadsAgainSinceClientBuild10721() {
        assertFalse(MapRouting.blocked(410000350), "rc6 fixes the Rampart Residences crash; MIN_CLIENT_BUILD is 10721");
        assertNull(MapRouting.route(410000325, "east00", 410000350), "an ordinary door is not touched");
    }

    @Test
    void aMissingMapIsBlockedAndHasAFallback() {
        assertTrue(MapRouting.blocked(STORAGE_CELLAR));
        assertEquals("missing", MapRouting.kind(STORAGE_CELLAR));
        assertEquals(FOUNTAIN_SQUARE, MapRouting.fallback(STORAGE_CELLAR));
    }

    @Test
    void fountainSquareInTakesThePlayerNowhere() {
        MapRouting.Route route = MapRouting.route(FOUNTAIN_SQUARE, "in00", STORAGE_CELLAR);
        assertNotNull(route, "the in00 door into the Storage Cellar must be routed");
        assertEquals(FOUNTAIN_SQUARE, route.map(), "there is no Storage Cellar to enter: the player stays");
        assertEquals("source-return", route.reason());
    }

    @Test
    void anOrdinaryDoorIsNotTouched() {
        assertNull(MapRouting.route(FOUNTAIN_SQUARE, "west00", STORAGE_CAVE));
        assertNull(MapRouting.route(STORAGE_CAVE, "east00", FOUNTAIN_SQUARE));
    }

    @Test
    void aMoveIntoTheCrashMapFromASideDoor() {
        // every leg of the Gollux chain whose portal leads into the abdomen: Left1/Right1 are one map back, these four
        // are the legs that point straight at it
        int[] legs = {863010210, 863010230, 863010300, 863010400};
        String[] ways = {"Right2", "Left2", "Right3", "Left3"};
        for (int i = 0; i < legs.length; i++) {
            MapRouting.Route route = MapRouting.route(legs[i], ways[i], GOLLUX_ABDOMEN);
            assertNotNull(route, legs[i] + ":" + ways[i]);
            assertEquals(HEART_TREE, route.map(), legs[i] + ":" + ways[i]);
            assertEquals("return", route.reason(), "the abdomen's own returnMap, " + legs[i] + ":" + ways[i]);
        }
    }

    @Test
    void aMoveOfUnknownOriginIntoABlockedMapGetsItsFallback() {
        MapRouting.Route route = MapRouting.route(123456789, null, GOLLUX_ABDOMEN);
        assertNotNull(route, "a saved map id or a direct factory load has no source portal and must still route");
        assertEquals(HEART_TREE, route.map());
    }

    @Test
    void everyRoseGardenMapPastTheEntranceLeadsBackToTheGardener() {
        // Client lane 2026-10-31: the client dies on entry to every map past 211080000, and no script warps into them
        int[] family = {211080100, 211080200, 211080300, 211080400, 211080500, 211080600, 211080700};
        for (int map : family) {
            assertTrue(MapRouting.blocked(map), "Rose Garden " + map);
            assertEquals("client-crash", MapRouting.kind(map), "Rose Garden " + map);
            assertEquals(ROSE_GARDEN, MapRouting.fallback(map), "Rose Garden " + map + " falls back to the entrance");
        }
        MapRouting.Route east = MapRouting.route(ROSE_GARDEN, "east00", 211080100);
        assertNotNull(east, "Gardener's Spot's east00 door leads into the family");
        assertEquals(ROSE_GARDEN, east.map(), "the door is a dead end: the player stays where the map loads");
        assertEquals("return", east.reason());
        assertFalse(MapRouting.blocked(ROOF_OF_THE_FIFTH_TOWER), "Roof of the Fifth Tower loads fine and stays ordinary");
        assertNull(MapRouting.route(ROOF_OF_THE_FIFTH_TOWER, "next00", ROSE_GARDEN), "the way in is untouched");
    }

    @Test
    void noRouteLandsInABlockedMap() throws IOException {
        for (String[] row : rows()) {
            if (row.length < 6) {
                continue;
            }
            int target = Integer.parseInt(row[2]);
            int lands = Integer.parseInt(row[3]);
            String why = row[0] + ":" + row[1] + " -> " + target + " lands at " + lands;
            assertFalse(MapRouting.blocked(lands), "a route must never land in a blocked map: " + why);
            assertTrue(lands > 0, why);
            assertTrue(!row[5].isBlank(), "every row says why: " + why);
        }
    }

    @Test
    void everyBlockedMapHasAFallbackInTheTable() throws IOException {
        for (String[] row : blockedRows()) {
            int map = Integer.parseInt(row[0]);
            assertTrue(MapRouting.blocked(map), "blocked section lists " + map);
            assertTrue(row[1].equals("missing") || row[1].equals("client-crash"), row[1]);
            assertTrue(MapRouting.fallback(map) > 0, "no fallback for blocked map " + map);
            assertFalse(MapRouting.blocked(MapRouting.fallback(map)), "fallback of " + map + " is itself blocked");
        }
    }

    private static List<String[]> rows() throws IOException {
        return section("# routes:", "# blocked_maps:");
    }

    private static List<String[]> blockedRows() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (String[] row : section("# blocked_maps:", null)) {
            out.add(row);
        }
        return out;
    }

    /** Table lines of one section, tab split, without comments and with a trailing "# manual" dropped. */
    private static List<String[]> section(String start, String end) throws IOException {
        List<String[]> out = new ArrayList<>();
        boolean inside = start == null;
        try (InputStream in = MapRouting.class.getResourceAsStream(MapRouting.TABLE)) {
            assertNotNull(in, "the routing table must be in the jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (end != null && line.startsWith(end)) {
                    break;
                }
                if (line.startsWith(start)) {
                    inside = true;
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
