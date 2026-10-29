package database;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 81 (owner 2026-10-28, draft): the rows migration 050 would add for the post-Big-Bang mobs. Checks the
 * generated SQL itself (no card items, one meso row per mob with a usable range, sane chances) and, where the WZ
 * data is present, that every item exists, is not cash, quest or untradeable, and every mob exists.
 */
class BbDropTablesDataTest {

    static final Path SQL = Path.of("database/migrations/050-bb-drop-tables.sql");
    static final Pattern ROW = Pattern.compile("^\\((\\d+), (\\d+), (\\d+), (\\d+), (\\d+), (\\d+)\\)[,;]$");

    record Row(int mob, int item, int min, int max, int quest, int chance) {
    }

    static List<Row> rows() throws Exception {
        List<Row> out = new ArrayList<>();
        for (String line : Files.readAllLines(SQL)) {
            Matcher m = ROW.matcher(line.strip());
            if (m.matches()) {
                out.add(new Row(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))));
            }
        }
        return out;
    }

    @Test
    void rowsAreWellFormed() throws Exception {
        List<Row> rows = rows();
        assertTrue(rows.size() > 5000, "rows parsed: " + rows.size());
        Set<Long> pairs = new HashSet<>();
        Map<Integer, Integer> mesoRows = new HashMap<>();
        Set<Integer> mobs = new HashSet<>();
        for (Row r : rows) {
            mobs.add(r.mob);
            assertFalse(r.mob >= 2600000 && r.mob < 2700000, "Mirror World stays out for now (owner 2026-10-28): " + r);
            assertTrue(pairs.add(((long) r.mob << 32) | r.item), "duplicate " + r);
            assertFalse(r.item >= 2380000 && r.item < 2390000, "a Monster Book card is 045's, not 050's: " + r);
            assertFalse(r.item / 1000000 == 5, "cash item " + r);
            assertEquals(0, r.quest, "no quest drops " + r);
            assertTrue(r.chance > 0 && r.chance <= 999999, "chance " + r);
            if (r.item == 0) {
                mesoRows.merge(r.mob, 1, Integer::sum);
                // MapleMap draws Randomizer.nextInt(max - min) + min: max must exceed min
                assertTrue(r.min > 0 && r.max > r.min, "meso range " + r);
                assertTrue(r.max <= 25000, "base mesos stay at classic boss scale (x meso rate in game) " + r);
            } else {
                assertTrue(r.min >= 1 && r.max >= r.min, "quantity " + r);
            }
        }
        for (int mob : mobs) {
            assertEquals(1, mesoRows.getOrDefault(mob, 0), "one meso row for mob " + mob);
        }
    }

    @Test
    void rollbackOnlyTouchesItsOwnLedger() throws Exception {
        String rb = Files.readString(Path.of("database/migrations/050-bb-drop-tables.rollback.sql"));
        assertTrue(rb.contains("DELETE d FROM drop_data d JOIN bb_drops_050_rows r"));
        for (String line : rb.split("\n")) {
            if (line.startsWith("DELETE")) {
                assertTrue(line.contains("bb_drops_050_rows") || line.contains("'050-bb-drop-tables'"), line);
            }
        }
        assertTrue(Files.readString(SQL).contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('050-bb-drop-tables')"));
        assertTrue(Files.readString(Path.of("docs/release/1.6.1.conf")).lines()
                        .anyMatch(l -> l.startsWith("MIGRATIONS=(") && l.matches(".*[( ]050-bb-drop-tables[ )].*")),
                "listed in 1.6.1.conf MIGRATIONS");
    }

    @Test
    void everyItemIsARealTradeableNonCashNonQuestItem() throws Exception {
        WzTestData.assumePresent("Item.wz");
        WzTestData.assumePresent("Character.wz");
        WzTestData.assumePresent("Mob.wz");
        DataProvider item = DataProviderFactory.getDataProvider(WZFiles.ITEM);
        DataProvider equip = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
        DataProvider mob = DataProviderFactory.getDataProvider(WZFiles.MOB);
        Map<String, String> equipDir = new HashMap<>();
        for (DataDirectoryEntry dir : equip.getRoot().getSubdirectories()) {
            for (DataFileEntry f : dir.getFiles()) {
                equipDir.put(f.getName(), dir.getName());
            }
        }
        Set<Integer> items = new HashSet<>();
        Set<Integer> mobs = new HashSet<>();
        for (Row r : rows()) {
            mobs.add(r.mob);
            if (r.item != 0) {
                items.add(r.item);
            }
        }
        for (int id : items) {
            Data d;
            if (id / 1000000 == 1) {
                String file = String.format("%08d.img", id);
                String dir = equipDir.get(file);
                assertNotNull(dir, "Character.wz has no " + file);
                d = equip.getData(dir + "/" + file);
            } else {
                String folder = switch (id / 1000000) {
                    case 2 -> "Consume";
                    case 4 -> "Etc";
                    default -> throw new AssertionError("unexpected item type " + id);
                };
                Data img = item.getData(folder + "/" + String.format("%04d", id / 10000) + ".img");
                assertNotNull(img, "Item.wz file for " + id);
                d = img.getChildByPath(String.format("%08d", id));
            }
            assertNotNull(d, "Item.wz/Character.wz has no item " + id);
            for (String flag : List.of("cash", "quest", "tradeBlock", "accountSharable", "equipTradeBlock", "only")) {
                assertEquals(0, DataTool.getIntConvert("info/" + flag, d, 0), "item " + id + " has " + flag);
            }
        }
        for (int id : mobs) {
            assertNotNull(mob.getData(String.format("%07d.img", id)), "Mob.wz has no " + id);
        }
    }

    @Test
    void everyEtcItemHasAUseHereOrAnNpcPrice() throws Exception {
        WzTestData.assumePresent("Item.wz");
        DataProvider item = DataProviderFactory.getDataProvider(WZFiles.ITEM);
        Set<Integer> inScripts = new HashSet<>();
        try (var files = Files.walk(Path.of("scripts"))) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                Matcher m = Pattern.compile("(?<!\\d)(4\\d{6})(?!\\d)").matcher(Files.readString(f,
                        java.nio.charset.StandardCharsets.ISO_8859_1));
                while (m.find()) {
                    inScripts.add(Integer.parseInt(m.group(1)));
                }
            }
        }
        Set<Integer> ores = Set.of(4004, 4010, 4020, 4006, 4007, 4130);
        for (Row r : rows()) {
            if (r.item / 1000000 != 4 || ores.contains(r.item / 1000)) {
                continue;
            }
            Data d = item.getData("Etc/" + String.format("%04d", r.item / 10000) + ".img")
                    .getChildByPath(String.format("%08d", r.item));
            assertTrue(inScripts.contains(r.item) || DataTool.getIntConvert("info/price", d, 0) > 0,
                    "ETC " + r.item + " has no use in scripts/ and no NPC price");
        }
    }
}
