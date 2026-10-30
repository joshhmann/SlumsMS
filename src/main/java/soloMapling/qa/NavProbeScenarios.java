package soloMapling.qa;

import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavProbe;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Ticket 157: nv-1 probes every portal and NPC on the Victoria town maps from each map's spawn point with the bots'
 * planner ({@link NavProbe#probeMap}) and logs what nothing reaches, one line each, for the nav lab (auto-QA
 * {@code t-nv-1}, nightly). It stages nothing and moves nobody. An unreachable spot is a lead, not a failure: scripted
 * portals and NPCs on decoration are expected; the check fails only when a map cannot be probed at all.
 */
final class NavProbeScenarios {
    private NavProbeScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Nv1Probe());
    }

    static final class Nv1Probe implements QaScenario {
        /** Henesys, Ellinia, Perion, Kerning City, Lith Harbor, Sleepywood. */
        static final List<Integer> MAPS = List.of(100000000, 101000000, 102000000, 103000000, 104000000, 105040300);
        static final int MAX_LINES_PER_MAP = 20;

        /** How a map is probed; tests substitute it. */
        static volatile Function<MapleMap, List<String>> prober = NavProbe::probeMap;
        static volatile Function<MapleMap, Integer> spotCount = m -> NavProbe.spots(m).size();

        public String id() { return "nv-1"; }
        public String title() { return "Nav probe: every portal and NPC on the town maps, from the spawn point"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 5; }

        public List<String> stage(QaContext ctx) {
            QaRun run = ctx.run();
            IntFunction<MapleMap> maps = id -> ctx.gm().getClient().getChannelServer().getMapFactory().getMap(id);
            ExecutorServiceManager.getExecutorService().execute(() -> probe(run, maps));
            return List.of("Probing " + MAPS.size() + " town maps with the bots' planner; nothing is staged.",
                    "The spots nothing reaches go to this run's log for the nav lab.");
        }

        static void probe(QaRun run, IntFunction<MapleMap> maps) {
            int probed = 0;
            int spots = 0;
            int unreachable = 0;
            for (int mapId : MAPS) {
                try {
                    MapleMap map = maps.apply(mapId);
                    if (map == null) {
                        run.note("NV1", "ERROR map " + mapId + " did not load");
                        continue;
                    }
                    List<String> failures = prober.apply(map);
                    int n = spotCount.apply(map);
                    probed++;
                    spots += n;
                    unreachable += failures.size();
                    run.note("NV1", "MAP " + mapId + " spots=" + n + " unreachable=" + failures.size());
                    for (String f : failures.subList(0, Math.min(MAX_LINES_PER_MAP, failures.size()))) {
                        run.note("NV1", "UNREACHABLE " + f);
                    }
                } catch (RuntimeException e) {
                    run.note("NV1", "ERROR map " + mapId + ": " + e);
                }
            }
            run.note("NV1", "DONE maps=" + probed + " spots=" + spots + " unreachable=" + unreachable);
        }
    }
}
