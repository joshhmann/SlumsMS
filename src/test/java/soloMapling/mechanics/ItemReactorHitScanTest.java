package soloMapling.mechanics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.ReactorFactory;
import server.maps.ReactorStats;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 b365: no row or solver sends a regular to hit an item reactor. A reactor whose starting state is an item trigger
 * (Reactor.wz type 100) is opened by its item dropped inside its box; a client never hits it, and a server-side hit
 * runs its script without the item (b365's scan found eleven rows doing it, and OPQ's garden pot on top). This reads
 * every place a hit can be asked for and checks the target against Reactor.wz, like nav-reach-test checks the map
 * graph, so a future row cannot bring it back:
 * <ul>
 *   <li>every {@code HIT_REACTOR} objective with a numeric reactor;</li>
 *   <li>the {@code hitReactors} of a {@code reactor-state} / {@code reactor-count} stage with no {@code PLACE_ITEM}
 *   objective - those solvers hit the first one as it is;</li>
 *   <li>every {@code new SolverStep.HitReactor(<literal or static final int>)} in a solver's source.</li>
 * </ul>
 * A target is allowed only on a stage whose row writes the exception down ({@code opensByHit}); the boss-phase solver
 * checks the item first and is covered by its own tests.
 */
class ItemReactorHitScanTest {

    private static final Path SOLVERS = Path.of("src/main/java/soloMapling/mechanics");
    private static final Pattern HIT = Pattern.compile("new SolverStep\\.HitReactor\\(\\s*([A-Za-z_][A-Za-z0-9_]*|\\d+)\\s*\\)");
    private static final Pattern CONST = Pattern.compile("static final int\\s+([A-Z_][A-Z0-9_]*)\\s*=\\s*(\\d+)\\s*;");

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    /** Whether the reactor's state 0 waits for an item (type 100), read from Reactor.wz. */
    static boolean takesAnItem(int reactorId) {
        ReactorStats stats = ReactorFactory.getReactor(reactorId);
        return stats != null && stats.getType((byte) 0) == 100;
    }

    /** Every hit target a row asks for, with where it is asked, skipping stages with a written exception. */
    static Map<String, Integer> rowTargets() {
        Map<String, Integer> out = new HashMap<>();
        for (MechanicsModel.Content c : StageData.load().content()) {
            for (MechanicsModel.Stage s : c.stages()) {
                if (s.opensByHit() != null) {
                    continue;
                }
                String at = c.id() + " stage " + s.index() + " (" + s.mapId() + ")";
                for (MechanicsModel.Objective o : s.objectives()) {
                    if (o.kind() == MechanicsModel.ObjectiveKind.HIT_REACTOR && o.hasReactor()
                            && o.reactor().trim().chars().allMatch(Character::isDigit)) {
                        out.put(at + " objective " + o.id(), Integer.parseInt(o.reactor().trim()));
                    }
                }
                boolean blind = (ReactorStateSolver.ID.equals(s.solver()) || ReactorCountSolver.ID.equals(s.solver()))
                        && s.objectives().stream().noneMatch(o -> o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM);
                if (blind && !s.hitReactors().isEmpty()) {
                    out.put(at + " hitReactors (" + s.solver() + ")", s.hitReactors().get(0));
                }
            }
        }
        return out;
    }

    /** Every constant reactor a solver's source hits. */
    static Map<String, Integer> solverTargets() throws IOException {
        Map<String, Integer> out = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(SOLVERS)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        }
        for (Path f : files) {
            String src = Files.readString(f);
            Map<String, Integer> consts = new HashMap<>();
            Matcher c = CONST.matcher(src);
            while (c.find()) {
                consts.put(c.group(1), Integer.parseInt(c.group(2)));
            }
            Matcher h = HIT.matcher(src);
            while (h.find()) {
                String arg = h.group(1);
                Integer id = arg.chars().allMatch(Character::isDigit) ? Integer.valueOf(arg) : consts.get(arg);
                if (id != null && id > 0) {
                    out.put(f.getFileName() + " HitReactor(" + arg + ")", id);
                }
            }
        }
        return out;
    }

    @Test
    void noRowOrSolverHitsAReactorThatWaitsForAnItem() throws IOException {
        WzTestData.assumePresent("Reactor.wz");
        Map<String, Integer> targets = new HashMap<>(rowTargets());
        targets.putAll(solverTargets());
        assertTrue(targets.size() > 5, "the scan finds the hit targets it is meant to check: " + targets);
        List<String> bad = new ArrayList<>();
        for (var e : new java.util.TreeMap<>(targets).entrySet()) {
            if (takesAnItem(e.getValue())) {
                bad.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertTrue(bad.isEmpty(), bad.size() + " hit(s) on reactors that wait for an item (Reactor.wz state 0 type 100);"
                + " place the item instead, or write opensByHit on the row: " + new TreeSet<>(bad));
    }
}
