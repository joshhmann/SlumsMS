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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
        MazeWalk maze = new MazeWalk(map, graph);
        for (Portal p : starts(map)) {
            Point from = new Point(p.getPosition());
            int fromRegion = graph == null ? -1 : graph.findRegionId(map, from);
            for (NavProbe.Spot t : targets) {
                NavProbe.Result r = NavProbe.probe(map, graph, fromRegion, from, t.at(), 0);
                if (!r.reachable() && !maze.reaches(p, t.at())) {
                    out.put(mapId + " from " + p.getName() + " to " + t.what() + " @(" + t.at().x + "," + t.at().y + ")",
                            r.reason());
                }
            }
        }
        return out;
    }

    /**
     * 1.6.6 nav-cages-triage: the scripted in-map portals a walker can take. A maze room (Ellin PQ 930000300's 46
     * {@code party6_stage5xx} doors, OPQ 920010700's 64 {@code party3_r6pt} doors) is joined only by portals whose
     * script warps within the map, so the plain graph has no edge for them and every room past the first read as a cage.
     * A portal counts when its script ({@code scripts/portal/<name>.js}) calls {@code warp(<this map or
     * pi.getMapId()>, <portal>)}: a quoted name or a number is that portal, anything computed is any of the
     * map's same-script doors and in-map landings (it may send you to any of them). A target is reached when some chain walk - door - walk gets there.
     */
    static final class MazeWalk {
        private static final Pattern WARP = Pattern.compile("warp\\(\\s*([^,()]+(?:\\(\\))?)\\s*,\\s*([^;]+?)\\)\\s*;");

        private final MapleMap map;
        private final BotNavigationGraph graph;
        /** scripted in-map door -> the portals it can put you on */
        private final Map<Portal, Set<Portal>> doors = new HashMap<>();
        private final Map<Portal, Set<Portal>> reach = new HashMap<>();
        /** (portal, point) -> walks; the door legs are shared by every start, so each is probed once */
        private final Map<String, Boolean> legs = new HashMap<>();

        MazeWalk(MapleMap map, BotNavigationGraph graph) {
            this.map = map;
            this.graph = graph;
            for (Portal p : starts(map)) {
                Set<Portal> to = destinations(map, p);
                if (!to.isEmpty()) {
                    doors.put(p, to);
                }
            }
        }

        boolean hasDoors() {
            return !doors.isEmpty();
        }

        /** Where the portal's script can warp you on this map; empty when it has no script or leaves the map. */
        static Set<Portal> destinations(MapleMap map, Portal p) {
            String script = p.getScriptName();
            Set<Portal> out = new LinkedHashSet<>();
            if (script == null || script.isBlank()) {
                return out;
            }
            Path file = Path.of("scripts", "portal", script + ".js");
            String src;
            try {
                src = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException missing) {
                return out;
            }
            Matcher m = WARP.matcher(src);
            while (m.find()) {
                String mapArg = m.group(1).strip();
                if (!mapArg.equals("pi.getMapId()") && !mapArg.equals(String.valueOf(map.getId()))) {
                    continue;
                }
                String to = m.group(2).strip();
                Portal dest = null;
                if (to.matches("\"[^\"]*\"")) {
                    dest = map.getPortal(to.substring(1, to.length() - 1));
                } else if (to.matches("\\d+")) {
                    dest = map.getPortal(Integer.parseInt(to));
                } else {
                    // computed (OPQ room 6: the next row's door, or back to a landing): the doors of this script and
                    // the map's in-map landings, never a door out of the map or the spawn point
                    for (Portal q : starts(map)) {
                        boolean sameScript = script.equals(q.getScriptName());
                        boolean landing = q.getType() != 0 && (q.getScriptName() == null || q.getScriptName().isBlank())
                                && (q.getTargetMapId() == 999999999 || q.getTargetMapId() == map.getId());
                        if (sameScript || landing) {
                            out.add(q);
                        }
                    }
                    continue;
                }
                if (dest != null && dest.getPosition() != null) {
                    out.add(dest);
                }
            }
            out.remove(p);
            return out;
        }

        private boolean walks(Portal from, Point to) {
            return legs.computeIfAbsent(from.getId() + "->" + to.x + "," + to.y, k -> walk(from, to));
        }

        private boolean walk(Portal from, Point to) {
            Point at = new Point(from.getPosition());
            int region = graph == null ? -1 : graph.findRegionId(map, at);
            return NavProbe.probe(map, graph, region, at, to, 0).reachable();
        }

        /** The portals a walker standing on {@code start} can end up on, taking scripted doors as often as it likes. */
        private Set<Portal> closure(Portal start) {
            Set<Portal> seen = reach.get(start);
            if (seen != null) {
                return seen;
            }
            seen = new HashSet<>();
            ArrayDeque<Portal> todo = new ArrayDeque<>();
            seen.add(start);
            todo.add(start);
            while (!todo.isEmpty()) {
                Portal at = todo.poll();
                for (var d : doors.entrySet()) {
                    Portal door = d.getKey();
                    if (door == at || walks(at, door.getPosition())) {
                        for (Portal next : d.getValue()) {
                            if (seen.add(next)) {
                                todo.add(next);
                            }
                        }
                    }
                }
            }
            reach.put(start, seen);
            return seen;
        }

        /** A target the plain walk missed: reached by walking from somewhere the scripted doors put you. */
        boolean reaches(Portal start, Point target) {
            if (doors.isEmpty() || graph == null) {
                return false;
            }
            for (Portal at : closure(start)) {
                if (at != start && walks(at, target)) {
                    return true;
                }
            }
            return false;
        }
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

    /**
     * 1.6.6 nav-cages-triage: a maze room behind a scripted in-map door is not a cage. Ellin PQ's maze (930000300) is
     * sixteen rooms joined only by {@code party6_stage5xx} doors, OPQ's tower (920010700) rows by {@code party3_r6pt};
     * the plain probe counted every room past the start as walled off (1,334 of the 1,846 listed cages).
     */
    @Test
    void roomsBehindScriptedInMapDoorsAreNotCages() {
        WzTestData.assumePresent("Map.wz");
        assertFalse(cages(930000300).containsKey("930000300 from 001E to npc 2133001 @(1772,1612)"),
                "Ellin maze: the far room is three doors from 001E");
        assertFalse(cages(920010700).containsKey("920010700 from pt00 to reactor 2008007 \"1\" @(-283,-4980)"),
                "OPQ tower: the top row is reached by the right door on each row");
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
