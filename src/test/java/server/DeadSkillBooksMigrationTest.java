package server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Migration 025: the 19 mastery books of skills MapleRoot deleted stop dropping; lists and pools no longer offer them. */
class DeadSkillBooksMigrationTest {
    static final List<Integer> IDS = List.of(2290019, 2290020, 2290021, 2290026, 2290027, 2290080, 2290081, 2290082,
            2290083, 2290092, 2290093, 2290101, 2290102, 2290103, 2290104, 2290105, 2290106, 2290107, 2290123);
    static final Path DIR = Path.of("database/migrations");
    static final String ID = "025-remove-dead-skill-books";

    static TreeSet<Integer> idsIn(String text) {
        TreeSet<Integer> out = new TreeSet<>();
        Matcher m = Pattern.compile("(?<!\\d)2290\\d{3}(?!\\d)").matcher(text);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        return out;
    }

    @Test
    void migrationDeletesExactlyTheNineteenIdsFromBothDropTables() throws Exception {
        String sql = Files.readString(DIR.resolve(ID + ".sql"));
        Matcher in = Pattern.compile("itemid IN \\(([^)]*)\\)").matcher(sql);
        int lists = 0;
        while (in.find()) {
            lists++;
            TreeSet<Integer> listed = new TreeSet<>();
            for (String s : in.group(1).split(",")) {
                listed.add(Integer.parseInt(s.trim()));
            }
            assertEquals(new TreeSet<>(IDS), listed);
        }
        assertEquals(4, lists, "copy and delete for each of drop_data and reactordrops");
        assertTrue(sql.contains("DELETE FROM drop_data WHERE @apply AND itemid IN"));
        assertTrue(sql.contains("DELETE FROM reactordrops WHERE @apply AND itemid IN"));
        assertTrue(sql.contains("migration_id = '" + ID + "'"));
        assertTrue(sql.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('" + ID + "')"));
        assertTrue(sql.indexOf("INSERT IGNORE INTO migration_025_removed_drop_data") < sql.indexOf("DELETE FROM drop_data"),
                "rows are copied before they are deleted");
        assertTrue(sql.indexOf("INSERT IGNORE INTO migration_025_removed_reactordrops") < sql.indexOf("DELETE FROM reactordrops"));
        assertTrue(sql.contains("mapleroot_integration_migrations"), "ledger table");
        assertTrue(sql.toLowerCase().contains("stop the game"));
    }

    @Test
    void rollbackRestoresTheKeptRowsAndClearsTheLedger() throws Exception {
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(rollback.contains("INSERT IGNORE INTO drop_data SELECT * FROM migration_025_removed_drop_data"));
        assertTrue(rollback.contains("INSERT IGNORE INTO reactordrops SELECT * FROM migration_025_removed_reactordrops"));
        assertTrue(rollback.contains("migration_id = '" + ID + "'"));
        assertTrue(rollback.indexOf("INSERT IGNORE INTO reactordrops") < rollback.indexOf("DROP TABLE migration_025_removed_reactordrops"),
                "the kept tables are dropped only after the restore");
    }

    @Test
    void noDeadBookIsOfferedByTheMasteryBookNpcOrBotShopPool() throws Exception {
        for (String f : List.of("scripts/npc/masterybooks.js",
                "src/main/java/soloMapling/itemPool/itemConfig/masteryBook.yaml")) {
            TreeSet<Integer> found = idsIn(Files.readString(Path.of(f)));
            found.retainAll(IDS);
            assertTrue(found.isEmpty(), f + " still lists " + found);
        }
    }
}
