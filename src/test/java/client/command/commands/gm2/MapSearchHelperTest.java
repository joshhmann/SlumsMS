package client.command.commands.gm2;

import client.command.commands.gm2.MapSearchHelper.MapMatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Map-name lookup behind !warp <name> and !search map. Ported from nutnnut/Cosmic 8799c9ef0 (nutnnut). */
class MapSearchHelperTest {
    private static final List<MapMatch> MAPS = List.of(
            new MapMatch(100000000, "Victoria Road", "Henesys"),
            new MapMatch(100000200, "Henesys", "Henesys Park"),
            new MapMatch(101000000, "Victoria Road", "Ellinia"),
            new MapMatch(221000000, "Ludibrium", "Ludibrium"));

    @Test
    void substringMatchesMapOrStreetNameCaseInsensitive() {
        assertEquals(List.of(100000000, 100000200), ids(MapSearchHelper.filter(MAPS, "HENESYS")));
        assertEquals(List.of(100000000, 101000000), ids(MapSearchHelper.filter(MAPS, "victoria")));
        assertTrue(MapSearchHelper.filter(MAPS, "nowhere").isEmpty());
    }

    @Test
    void exactMapNameBeatsLongerSubstringHits() {
        List<MapMatch> hits = MapSearchHelper.filter(MAPS, "henesys");
        assertEquals(List.of(100000000), ids(MapSearchHelper.resolve(hits, "henesys")));
        assertEquals(List.of(100000200), ids(MapSearchHelper.resolve(hits, "Henesys - Henesys Park")));
    }

    @Test
    void ambiguousNameStaysAList() {
        assertEquals(2, MapSearchHelper.resolve(MapSearchHelper.filter(MAPS, "victoria"), "victoria").size());
        assertEquals(List.of(221000000), ids(MapSearchHelper.resolve(MapSearchHelper.filter(MAPS, "ludi"), "ludi")));
    }

    private static List<Integer> ids(List<MapMatch> l) {
        return l.stream().map(MapMatch::mapId).toList();
    }
}
