package soloMapling.qa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 157: nv-1 logs each town map's unreachable portals and NPCs and a DONE line auto-QA t-nv-1 reads. */
class NavProbeScenariosTest {

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        NavProbeScenarios.Nv1Probe.prober = NavProbe::probeMap;
        NavProbeScenarios.Nv1Probe.spotCount = m -> NavProbe.spots(m).size();
    }

    @Test
    void theRunLogsEveryMapItsLeadsAndADoneLine() throws Exception {
        QaRun run = new QaRun("nv-1", 1, "Gm", Instant.parse("2026-10-30T10:00:00Z"), dir, Set.of(), Set.of(), Set.of());
        run.open(List.of("header"));
        NavProbeScenarios.Nv1Probe.prober = m -> m.getId() == 100000000
                ? List.of("map 100000000 npc 1012000 at (1,2): no route from r1 to r9 walking") : List.of();
        NavProbeScenarios.Nv1Probe.spotCount = m -> 10;
        NavProbeScenarios.Nv1Probe.probe(run, id -> {
            if (id == 105040300) {
                return null; // a map that does not load
            }
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            return map;
        });
        String log = Files.readString(dir.resolve(QaRun.fileName("nv-1", Instant.parse("2026-10-30T10:00:00Z"))));
        assertTrue(log.contains("NV1 MAP 100000000 spots=10 unreachable=1"), log);
        assertTrue(log.contains("NV1 UNREACHABLE map 100000000 npc 1012000 at (1,2): no route"), log);
        assertTrue(log.contains("NV1 ERROR map 105040300 did not load"), log);
        assertTrue(log.contains("NV1 DONE maps=5 spots=50 unreachable=1"), log);
    }
}
