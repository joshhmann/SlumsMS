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
 * Before this table a move into one of the 30-odd maps Map.wz has no image for loaded null and threw inside
 * MapFactory (mapData.getChildByPath("info") on null), and the client died walking into Ristonia Rampart Residences.
 *
 * The real-WZ half (that every route lands on a map that exists, and that every blocked map has a fallback that
 * loads) is {@link MapRoutingWzTest}; the bots' half is GCWorldGraphRoutingTest.
 */
class MapRoutingTableTest {

    private static final int FOUNTAIN_SQUARE = 410000300;      // Ristonia
    private static final int STORAGE_CELLAR = 993162016;       // Ristonia, no Map.wz image
    private static final int RAMPART_RESIDENCES = 410000350;   // Ristonia, the client crash on TEST
    private static final int RAMPART_RESIDENCES_2 = 410000351;
    private static final int STORAGE_CAVE = 410000310;

    @Test
    void theTableIsInTheJar() {
        assertTrue(MapRouting.enabled(), "the switch defaults to on so TEST has routing without a config edit");
        assertTrue(MapRouting.routeCount() > 50, "routes loaded: " + MapRouting.routeCount());
        assertTrue(MapRouting.blockedCount() > 30, "blocked maps loaded: " + MapRouting.blockedCount());
        assertTrue(MapRouting.source().contains("map-routing.tsv"), MapRouting.source());
    }

    @Test
    void rampartResidencesIsBlockedAsAClientCrash() {
        assertTrue(MapRouting.blocked(RAMPART_RESIDENCES));
        assertEquals("client-crash", MapRouting.kind(RAMPART_RESIDENCES));
        assertEquals(FOUNTAIN_SQUARE, MapRouting.fallback(RAMPART_RESIDENCES),
                "a character saved inside it logs in at Fountain Square");
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
        MapRouting.Route route = MapRouting.route(410000325, "east00", RAMPART_RESIDENCES);
        assertNotNull(route);
        assertEquals(FOUNTAIN_SQUARE, route.map());
        assertEquals("return", route.reason(), "Rampart Residences' own returnMap");
        assertEquals(FOUNTAIN_SQUARE, MapRouting.route(RAMPART_RESIDENCES_2, "east00", RAMPART_RESIDENCES).map());
    }

    @Test
    void aMoveOfUnknownOriginIntoABlockedMapGetsItsFallback() {
        MapRouting.Route route = MapRouting.route(123456789, null, RAMPART_RESIDENCES);
        assertNotNull(route, "a saved map id or a direct factory load has no source portal and must still route");
        assertEquals(FOUNTAIN_SQUARE, route.map());
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
