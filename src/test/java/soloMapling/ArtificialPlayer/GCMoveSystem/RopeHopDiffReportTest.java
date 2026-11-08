package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Rope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * v72's add-only diff over every WZ map (base profile): builds each map's graph without and with the rope-hop pass
 * and lists the maps that gain edges and any edge lost. Only maps with two ropes within ROPE_HOP_MAX_GAP_PX can
 * differ (the pass compares rope pairs and nothing else), so only those are built. A report, not a regular test: run
 * with -Dslums.v72diff=true -Dwz-path=...; it writes target/v72-diff.txt and fails if any edge is lost.
 */
class RopeHopDiffReportTest {
    private static final Pattern MAP_IMG = Pattern.compile("(\\d{9})\\.img(\\.xml)?");

    static List<Integer> wzMapIds() throws IOException {
        Path maps = Path.of(System.getProperty("wz-path", "wz"), "Map.wz", "Map");
        List<Integer> ids = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(maps, 2)) {
            walk.forEach(p -> {
                Matcher m = MAP_IMG.matcher(p.getFileName().toString());
                if (m.matches()) {
                    ids.add(Integer.parseInt(m.group(1)));
                }
            });
        }
        ids.sort(null);
        return ids;
    }

    static boolean hasRopePairInReach(MapleMap map) {
        List<Rope> ropes = map.getRopes();
        for (int i = 0; i < ropes.size(); i++) {
            for (int j = i + 1; j < ropes.size(); j++) {
                int dx = Math.abs(ropes.get(i).x() - ropes.get(j).x());
                if (dx > 0 && dx <= BotPhysicsEngine.ROPE_HOP_MAX_GAP_PX) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void report() throws IOException {
        Assumptions.assumeTrue(Boolean.getBoolean("slums.v72diff"), "report only: -Dslums.v72diff=true");
        List<Integer> ids = wzMapIds();
        int candidates = 0;
        int failed = 0;
        List<String> changed = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int id : ids) {
            MapleMap map;
            try {
                map = BotNavigationMapLoader.loadMapGeometry(id);
            } catch (RuntimeException e) {
                failed++;
                failures.add(id + " load " + e.getClass().getSimpleName());
                continue;
            }
            if (map == null || map.isSwim() || !hasRopePairInReach(map)) {
                continue;
            }
            candidates++;
            Set<String> before = null;
            Set<String> after = null;
            String v71Error = null;
            String v72Error = null;
            try {
                BotNavigationGraphProvider.clientKickRopeHops = false;
                before = RopeHopGraphTest.edgeKeys(BotNavigationGraphProvider.buildUncachedGraph(map, BotMovementProfile.base()));
            } catch (RuntimeException e) {
                v71Error = e.getClass().getSimpleName();
            } finally {
                BotNavigationGraphProvider.clientKickRopeHops = true;
            }
            try {
                after = RopeHopGraphTest.edgeKeys(BotNavigationGraphProvider.buildUncachedGraph(map, BotMovementProfile.base()));
            } catch (RuntimeException e) {
                v72Error = e.getClass().getSimpleName();
            }
            if (v71Error != null || v72Error != null) {
                failed++;
                failures.add(id + " build v71=" + (v71Error == null ? "ok" : v71Error) + " v72=" + (v72Error == null ? "ok" : v72Error));
                if (v71Error == null) {
                    lost.add(id + " v72 build failed where v71 built: " + v72Error);
                }
                continue;
            }
            Set<String> gone = new HashSet<>(before);
            gone.removeAll(after);
            Set<String> added = new HashSet<>(after);
            added.removeAll(before);
            for (String g : gone) {
                lost.add(id + " " + g);
            }
            if (!added.isEmpty()) {
                changed.add(id + " +" + added.size() + " " + new java.util.TreeSet<>(added));
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("# v72 rope-hop diff, base profile: ").append(ids.size()).append(" WZ maps, ").append(candidates)
                .append(" with a rope pair within ").append(BotPhysicsEngine.ROPE_HOP_MAX_GAP_PX).append(" px, ")
                .append(failed).append(" could not load/build\n");
        out.append("# maps gaining edges: ").append(changed.size()).append("; edges lost: ").append(lost.size()).append('\n');
        for (String c : changed) {
            out.append(c).append('\n');
        }
        for (String f : failures) {
            out.append("FAILED ").append(f).append('\n');
        }
        for (String l : lost) {
            out.append("LOST ").append(l).append('\n');
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "v72-diff.txt"), out.toString());
        System.out.println(out);
        assertEquals(List.of(), lost, "v72 must only add edges");
    }
}
