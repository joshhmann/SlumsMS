package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

/**
 * Every id in the content rows was read out of a WZ dump by hand, and the dump is not version-pinned to this branch
 * (docs/PROVENANCE.md says so per section). This test re-reads the dump the same way a person would and checks that the
 * ids the rows name are really there: the stage map is a map, every {@code mobs} id is a mob, every {@code hitReactors}
 * id is a reactor, every {@code entryItems} id is an item, and no stage declares a negative spawn wait.
 *
 * <p>It reads the real data, so it skips (reported as skipped, never failed) when {@code wz/} is not in the checkout -
 * {@code -Dwz-path}, else {@code wz}, the same switch every other WZ test uses, via {@link WzTestData}. A row that
 * points at an id the dump does not hold is a typo, and a typo is exactly what this catches before a live run finds
 * the room empty.
 *
 * <p>The model's {@code Stage.mapId} is all this branch has; when {@code 164-opq}'s {@code maps:} list lands on merge,
 * the same loop should walk it too. {@code spawnWaitSeconds == 0} means the row declares no expectation, so the check
 * is that it is not negative.
 */
class ContentRowWzIntegrityTest {

    @Test
    void everyRowIdIsInTheWzDump() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        WzTestData.assumePresent("Reactor.wz");
        WzTestData.assumePresent("Item.wz");
        Path wz = WzTestData.dir();

        StageData.Result loaded = StageData.load();
        assertEquals(List.of(), loaded.errors(), "a row that does not load cannot be checked");

        Set<String> mapIds = mapIds(wz);
        assertTrue(mapIds.size() > 1_000, "Map.wz/Map should hold the whole map set, saw " + mapIds.size());

        List<String> problems = new ArrayList<>();
        Set<Integer> checkedMaps = new TreeSet<>();
        Set<Integer> checkedMobs = new TreeSet<>();
        Set<Integer> checkedReactors = new TreeSet<>();
        Set<Integer> checkedItems = new TreeSet<>();

        for (MechanicsModel.Content content : loaded.content()) {
            for (MechanicsModel.Stage stage : content.stages()) {
                String where = content.id() + " stage " + stage.index();

                checkedMaps.add(stage.mapId());
                if (!mapIds.contains(Integer.toString(stage.mapId()))) {
                    problems.add(where + ": map " + stage.mapId() + " is not a Map.wz map");
                }
                for (int mob : stage.mobs()) {
                    checkedMobs.add(mob);
                    if (!Files.isRegularFile(wz.resolve("Mob.wz").resolve(mob + ".img.xml"))) {
                        problems.add(where + ": mob " + mob + " has no Mob.wz/" + mob + ".img.xml");
                    }
                }
                for (int reactor : stage.hitReactors()) {
                    checkedReactors.add(reactor);
                    if (!reactorFile(wz, reactor)) {
                        problems.add(where + ": reactor " + reactor + " has no Reactor.wz entry");
                    }
                }
                for (int item : stage.entryItems()) {
                    checkedItems.add(item);
                    if (!itemInDump(wz, item)) {
                        problems.add(where + ": door item " + item + " is not in Item.wz");
                    }
                }
                if (stage.spawnWaitSeconds() < 0) {
                    problems.add(where + ": spawnWaitSeconds " + stage.spawnWaitSeconds() + " is negative");
                }
            }
        }

        assertEquals(List.of(), problems, "row numbers the WZ dump does not back");
        System.out.println("[rows] " + loaded.content().size() + " rows: maps " + checkedMaps + ", mobs " + checkedMobs
                + ", reactors " + checkedReactors + ", door items " + checkedItems);
    }

    /** Every map id the dump holds, by file name - Map.wz/Map/Map<k>/<id>.img.xml. */
    private static Set<String> mapIds(Path wz) throws IOException {
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.walk(wz.resolve("Map.wz").resolve("Map"))) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".img.xml"))
                    .forEach(n -> ids.add(n.substring(0, n.length() - ".img.xml".length())));
        }
        return ids;
    }

    /** Reactor.wz names seven-digit ids, but a short id is legal too, so both spellings are looked for. */
    private static boolean reactorFile(Path wz, int reactorId) {
        Path dir = wz.resolve("Reactor.wz");
        return Files.isRegularFile(dir.resolve(reactorId + ".img.xml"))
                || Files.isRegularFile(dir.resolve(String.format(Locale.ROOT, "%07d.img.xml", reactorId)));
    }

    /** Item.wz groups items by category directory and 4-digit bag (4000381 -> *&#47;0400.img.xml, entry "04000381"). */
    private static boolean itemInDump(Path wz, int itemId) throws IOException {
        String bag = String.format(Locale.ROOT, "%04d.img.xml", itemId / 10_000);
        String entry = "name=\"" + String.format(Locale.ROOT, "%08d", itemId) + "\"";
        Path itemWz = wz.resolve("Item.wz");
        List<Path> bags = new ArrayList<>();
        try (Stream<Path> categories = Files.list(itemWz)) {
            for (Path category : (Iterable<Path>) categories.filter(Files::isDirectory)::iterator) {
                Path file = category.resolve(bag);
                if (Files.isRegularFile(file)) {
                    bags.add(file);
                }
            }
        }
        for (Path file : bags) {
            if (Files.readString(file).contains(entry)) {
                return true;
            }
        }
        return false;
    }
}
