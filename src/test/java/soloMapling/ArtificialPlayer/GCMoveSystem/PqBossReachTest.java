package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.WzTestData;
import soloMapling.mechanics.MechanicsModel;
import soloMapling.mechanics.StageData;
import testutil.PropsRestored;

import java.awt.Point;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.5 nav-reach-test: on every map a party quest or boss row plays ({@link MechanicsModel.Stage#mapSet()}), a walker
 * with no movement skills, on the plain WZ graph (no learned edges), starting at any portal of the map, reaches every
 * reactor and NPC the map image places. It is {@link NavProbe#probeMap} (the nightly nv1 probe) run from every portal
 * instead of the spawn point only, with the WZ's reactors and NPCs as the targets: b344c's ZPQ stage 3 door-to-rock
 * cage (280011005) is the kind of pair it finds.
 *
 * <p>A cage is a (map, portal, target) pair nothing walks. The ones on the WZ today are listed in
 * {@code nav-reach-cages.txt} (reported to the Coordinator, not fixed here); this test fails on any cage not on that
 * list, so a graph change that walls off a reactor or NPC shows up, and on a listed cage that is open now, so the list
 * only shrinks. {@code target/nav-reach-report.txt} has the full run. Needs {@code -Dwz-path}; skipped without it.
 */
@ExtendWith(PropsRestored.class)
class PqBossReachTest {

    private static final String BASELINE = "/nav-reach-cages.txt";

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /** Every map a PQ or boss row's stages cover, with the rows that use it. */
    static TreeMap<Integer, Set<String>> maps() {
        TreeMap<Integer, Set<String>> out = new TreeMap<>();
        for (MechanicsModel.Content c : StageData.load().content()) {
            if (c.kind() != MechanicsModel.Kind.PQ && c.kind() != MechanicsModel.Kind.BOSS) {
                continue;
            }
            for (MechanicsModel.Stage s : c.stages()) {
                for (int mapId : s.mapSet()) {
                    if (mapId > 0) {
                        out.computeIfAbsent(mapId, k -> new TreeSet<>()).add(c.id());
                    }
                }
            }
        }
        return out;
    }

    /** The reactors and NPCs the map image places (its {@code reactor} and {@code life} type n blocks). */
    static List<NavProbe.Spot> targets(int mapId) {
        DataProvider source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data map = source.getData("Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img");
        List<NavProbe.Spot> out = new ArrayList<>();
        if (map == null) {
            return out;
        }
        String link = DataTool.getString("info/link", map, "");
        if (!link.isEmpty()) {
            int to = Integer.parseInt(link);
            map = source.getData("Map/Map" + (to / 100000000) + "/" + String.format("%09d", to) + ".img");
        }
        Data reactors = map.getChildByPath("reactor");
        if (reactors != null) {
            for (Data r : reactors.getChildren()) {
                out.add(new NavProbe.Spot("reactor " + DataTool.getString("id", r, "?")
                        + nameOf(DataTool.getString("name", r, "")),
                        new Point(DataTool.getInt("x", r, 0), DataTool.getInt("y", r, 0))));
            }
        }
        Data life = map.getChildByPath("life");
        if (life != null) {
            for (Data l : life.getChildren()) {
                if ("n".equals(DataTool.getString("type", l, "")) && DataTool.getInt("hide", l, 0) == 0) {
                    out.add(new NavProbe.Spot("npc " + DataTool.getString("id", l, "?"),
                            new Point(DataTool.getInt("x", l, 0), DataTool.getInt("cy", l, DataTool.getInt("y", l, 0)))));
                }
            }
        }
        return out;
    }

    private static String nameOf(String name) {
        return name.isEmpty() ? "" : " \"" + name + "\"";
    }

    /** Every portal with a position: where a member can stand when it comes in or is warped there. */
    static List<Portal> starts(MapleMap map) {
        List<Portal> out = new ArrayList<>();
        for (Portal p : map.getPortals()) {
            if (p != null && p.getPosition() != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** One line per cage on the map: {@code <map> from <portal> to <target> @(x,y)}, sorted, with the probe's reason. */
    static TreeMap<String, String> cages(int mapId) {
        TreeMap<String, String> out = new TreeMap<>();
        MapleMap map = BotNavigationMapLoader.loadMapGeometry(mapId);
        if (map.isSwim()) {
            return out; // swimmers do not use the ground graph (as probeMap)
        }
        BotNavigationGraph graph = BotNavigationGraphProvider.rebuildGraph(map, BotMovementProfile.base());
        List<NavProbe.Spot> targets = targets(mapId);
        for (Portal p : starts(map)) {
            Point from = new Point(p.getPosition());
            int fromRegion = graph == null ? -1 : graph.findRegionId(map, from);
            for (NavProbe.Spot t : targets) {
                NavProbe.Result r = NavProbe.probe(map, graph, fromRegion, from, t.at(), 0);
                if (!r.reachable()) {
                    out.put(mapId + " from " + p.getName() + " to " + t.what() + " @(" + t.at().x + "," + t.at().y + ")",
                            r.reason());
                }
            }
        }
        return out;
    }

    static Set<String> baseline() throws IOException {
        Set<String> out = new TreeSet<>();
        try (InputStream in = PqBossReachTest.class.getResourceAsStream(BASELINE)) {
            if (in == null) {
                return out;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    @Test
    void aWalkerFromAnyPortalReachesEveryReactorAndNpcOnPqAndBossMaps() throws IOException {
        WzTestData.assumePresent("Map.wz");
        TreeMap<Integer, Set<String>> maps = maps();
        assertFalse(maps.isEmpty(), "the rows list their maps");
        TreeMap<String, String> found = new TreeMap<>();
        List<String> report = new ArrayList<>();
        int probed = 0;
        for (var e : maps.entrySet()) {
            TreeMap<String, String> onMap;
            try {
                onMap = cages(e.getKey());
            } catch (IllegalArgumentException missing) {
                report.add("# " + e.getKey() + " " + e.getValue() + ": " + missing.getMessage());
                continue;
            }
            probed++;
            report.add("# " + e.getKey() + " " + e.getValue() + ": " + onMap.size() + " cage(s)");
            onMap.forEach((k, why) -> report.add(k + "    # " + why));
            found.putAll(onMap);
        }
        Path out = Path.of("target", "nav-reach-report.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, report, StandardCharsets.UTF_8);

        Set<String> known = baseline();
        List<String> fresh = found.keySet().stream().filter(k -> !known.contains(k)).toList();
        List<String> opened = known.stream().filter(k -> !found.containsKey(k)).toList();
        assertTrue(probed > 0, "some maps were probed");
        assertTrue(fresh.isEmpty(), fresh.size() + " cage(s) not on " + BASELINE + " (see " + out + "): "
                + fresh.stream().limit(20).map(k -> k + " - " + found.get(k)).toList());
        assertTrue(opened.isEmpty(), opened.size() + " listed cage(s) are open now; take them off " + BASELINE + ": "
                + opened.stream().limit(20).toList());
    }
}
