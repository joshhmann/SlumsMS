package soloMapling.mechanics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.ReactorFactory;
import server.maps.ReactorStats;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 item-reactor-place-scan, the reverse of {@link ItemReactorHitScanTest}: an item reactor (Reactor.wz state 0
 * type 100) standing on a stage's map, whose script's {@code act()} does something, is something a party may need to
 * open with its item - Ellin's altar was one, and its row never placed the Magic Stone, so the room stayed empty. Every
 * such reactor must be placed by the stage's row (a PLACE_ITEM objective, by id or WZ name), named by the stage
 * ({@code hitReactors}, which the boss solver opens with its item), or named by a solver's source; otherwise it is
 * listed in {@link #NOT_THE_REGULARS_JOB} with a reason.
 */
class ItemReactorPlaceScanTest {

    private static final Path SOLVERS = Path.of("src/main/java/soloMapling/mechanics");

    /** Item reactors on a stage's map that the stage does not need placed, each with why. */
    static final Map<Integer, String> NOT_THE_REGULARS_JOB = Map.ofEntries(
            Map.entry(2001002, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001003, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001004, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001005, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001006, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001007, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001008, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001009, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001011, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001012, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001013, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001014, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2001015, "OPQ storage: Eak clears the room once it is empty (2013001.js:102-109); the box chain is the other route"),
            Map.entry(2201002, "LPQ 7: a Ratz's own drop lands in its box and opens it (b363)"),
            Map.entry(2201003, "LPQ 9: 9300006's own drop lands in it and summons Alishar (b359)"),
            Map.entry(2516000, "Pirate PQ: PiratePQ.js:365 spawns Wu Yang when the Lord Pirate dies; this reactor is the other route"),
            Map.entry(2618001, "KNOWN GAP magatia-card-keys: Card Key (4001133) opens side room 2's exit; regulars warp out, a player in it cannot leave"),
            Map.entry(2618002, "KNOWN GAP magatia-card-keys: Card Key (4001133) opens side room 1's exit; regulars warp out, a player in it cannot leave"));

    @BeforeAll
    static void setWzPath() {
        if (System.getProperty("wz-path") == null) {
            System.setProperty("wz-path", Path.of("wz").toAbsolutePath().toString());
        }
    }

    record Placed(int id, String name) {
    }

    static List<Placed> reactorsOn(int mapId) {
        DataProvider source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data map = source.getData("Map/Map" + (mapId / 100000000) + "/" + String.format("%09d", mapId) + ".img");
        List<Placed> out = new ArrayList<>();
        if (map == null || map.getChildByPath("reactor") == null) {
            return out;
        }
        for (Data r : map.getChildByPath("reactor").getChildren()) {
            out.add(new Placed(Integer.parseInt(DataTool.getString("id", r, "0")), DataTool.getString("name", r, "")));
        }
        return out;
    }

    /** Whether the reactor's script does something on act() (an empty or missing act() opens nothing). */
    static boolean actDoesSomething(int id) throws IOException {
        Path js = Path.of("scripts/reactor/" + id + ".js");
        if (!Files.exists(js)) {
            return false;
        }
        String src = Files.readString(js);
        Matcher m = Pattern.compile("function\\s+act\\s*\\(\\s*\\)\\s*\\{(.*?)\\n\\}", Pattern.DOTALL).matcher(src);
        return m.find() && !m.group(1).replaceAll("//[^\\n]*", "").isBlank();
    }

    static boolean takesAnItem(int id) {
        ReactorStats stats = ReactorFactory.getReactor(id);
        return stats != null && stats.getType((byte) 0) == 100;
    }

    static String solverSources() throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> s = Files.list(SOLVERS)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                sb.append(Files.readString(f)).append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void everyItemReactorAStageNeedsIsPlacedByItsRow() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Reactor.wz");
        String solvers = solverSources();
        TreeMap<String, String> missing = new TreeMap<>();
        Set<Integer> checked = new HashSet<>();
        for (MechanicsModel.Content c : StageData.load().content()) {
            for (MechanicsModel.Stage s : c.stages()) {
                for (int mapId : s.mapSet()) {
                    for (Placed r : reactorsOn(mapId)) {
                        if (!takesAnItem(r.id()) || !actDoesSomething(r.id()) || NOT_THE_REGULARS_JOB.containsKey(r.id())) {
                            continue;
                        }
                        checked.add(r.id());
                        boolean placed = s.objectives().stream().anyMatch(o -> o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM
                                && o.hasReactor() && (o.reactor().trim().equals(String.valueOf(r.id()))
                                || (!r.name().isEmpty() && o.reactor().trim().equals(r.name()))));
                        boolean named = s.hitReactors().contains(r.id())
                                || solvers.contains(String.valueOf(r.id()))
                                || (!r.name().isEmpty() && solvers.contains("\"" + r.name() + "\""));
                        if (!placed && !named) {
                            missing.put(c.id() + " stage " + s.index() + " (" + mapId + ") reactor " + r.id()
                                    + (r.name().isEmpty() ? "" : " \"" + r.name() + "\""), "");
                        }
                    }
                }
            }
        }
        assertTrue(!checked.isEmpty(), "the scan finds item reactors on stage maps");
        assertTrue(missing.isEmpty(), missing.size() + " item reactor(s) on a stage's map that nothing places"
                + " (place it in the row, or list it in NOT_THE_REGULARS_JOB with why): " + missing.keySet());
    }
}
