package client.command.commands.gm2;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Map lookup by name over String.wz (Map.img): substring match on the map name or the street name.
 * Ported from nutnnut/Cosmic 8799c9ef0 (nutnnut), with an exact-name preference and a pure matcher for tests.
 */
final class MapSearchHelper {
    private MapSearchHelper() {
    }

    record MapMatch(int mapId, String streetName, String mapName) {
    }

    /** All maps whose map name or street name contains the text (case-insensitive), in String.wz order. */
    static List<MapMatch> findMaps(String search) {
        DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
        Data mapStringData = dataProvider.getData("Map.img");
        List<MapMatch> all = new ArrayList<>();

        for (Data searchDataDir : mapStringData.getChildren()) {
            for (Data searchData : searchDataDir.getChildren()) {
                String mapName = DataTool.getString(searchData.getChildByPath("mapName"), "NO-NAME");
                String streetName = DataTool.getString(searchData.getChildByPath("streetName"), "NO-NAME");
                all.add(new MapMatch(Integer.parseInt(searchData.getName()), streetName, mapName));
            }
        }
        return filter(all, search);
    }

    static List<MapMatch> filter(List<MapMatch> all, String search) {
        String needle = search == null ? "" : search.toLowerCase(Locale.ROOT);
        List<MapMatch> matches = new ArrayList<>();
        for (MapMatch m : all) {
            if (m.mapName().toLowerCase(Locale.ROOT).contains(needle)
                    || m.streetName().toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(m);
            }
        }
        return matches;
    }

    /**
     * The maps a name should resolve to: the exact-name hits (map name, or "street - map") when there are any,
     * otherwise every substring hit. One entry means the name is unambiguous.
     */
    static List<MapMatch> resolve(List<MapMatch> matches, String search) {
        String needle = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<MapMatch> exact = new ArrayList<>();
        for (MapMatch m : matches) {
            String name = m.mapName().toLowerCase(Locale.ROOT);
            String full = m.streetName().toLowerCase(Locale.ROOT) + " - " + name;
            if (name.equals(needle) || full.equals(needle)) {
                exact.add(m);
            }
        }
        return exact.isEmpty() ? matches : exact;
    }
}
