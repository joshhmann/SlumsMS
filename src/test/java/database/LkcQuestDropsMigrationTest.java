package database;

import org.junit.jupiter.api.Test;
import server.quest.HiddenQuests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.4 Lion King's Castle questline: migration 065 holds the quest-only drops, the NPC and golem placements. */
class LkcQuestDropsMigrationTest {

    static final Path DIR = Path.of("database/migrations");
    static final String ID = "065-lkc-quest-drops";
    static final Pattern DROP = Pattern.compile("\\((\\d{7}), (\\d{7}), 1, 1, (\\d{4}), (\\d+)\\)");

    static String sql() throws Exception {
        return Files.readString(DIR.resolve(ID + ".sql"));
    }

    @Test
    void everyQuestItemHasAQuestOnlyDropOnAMobOfTheCastle() throws Exception {
        List<int[]> rows = new ArrayList<>();
        Matcher m = DROP.matcher(sql());
        while (m.find()) {
            rows.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))});
        }
        assertEquals(7, rows.size());
        int[][] quests = {{4032831, 3164}, {4032835, 3170}, {4032836, 3174}, {4032838, 3177}};
        for (int[] q : quests) {
            assertTrue(rows.stream().anyMatch(r -> r[1] == q[0] && r[2] == q[1]), "item " + q[0] + " drops for quest " + q[1]);
        }
        for (int[] r : rows) {
            assertTrue(r[0] >= 8210000 && r[0] <= 8210005, "a castle mob: " + r[0]);
            assertTrue(r[2] > 0, "quest-only");
            assertEquals(r[1] == 4032838 ? 200000 : 400000, r[3], "chance of " + r[1] + " on " + r[0]);
        }
    }

    @Test
    void theVonLeonNpcsAndTheGolemArePlacedInBothWorlds() throws Exception {
        String sql = sql();
        assertTrue(sql.contains("SELECT 211070000 AS map, 2161000 AS life, 'n' AS type"));
        assertTrue(sql.contains("SELECT 211070100, 2161008, 'n'"));
        assertTrue(sql.contains("SELECT 211080700, 8211004, 'm'"));
        assertTrue(sql.contains("SELECT 0 AS world UNION ALL SELECT 1"));
    }

    @Test
    void theRollbackRemovesOnlyItsOwnRowsAndTheMigrationIsListed() throws Exception {
        String sql = sql();
        String rollback = Files.readString(DIR.resolve(ID + ".rollback.sql"));
        assertTrue(sql.contains("VALUES ('" + ID + "')"));
        assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM plife p WHERE p.world = w.world AND p.map = m.map AND p.life = m.life)"));
        assertTrue(rollback.contains("JOIN lkc_quest_drops_065_rows"));
        assertTrue(rollback.contains("migration_id = '" + ID + "' AND table_name = 'plife'"));
        assertTrue(Files.readString(Path.of("docs/release/1.6.4.conf")).contains(ID));
    }

    @Test
    void theChainIsNoLongerHiddenAndMurtHasHisFiles() {
        for (int id : new int[]{3164, 3170, 3174, 3177, 3181, 3182, 3168, 3171}) {
            assertFalse(HiddenQuests.ids().contains(id), id + " must be startable");
        }
        assertTrue(Files.exists(Path.of("scripts/npc/2160000.js")));
        assertTrue(Files.exists(Path.of("tools/data-align/murt/Npc.wz-2160000.img.xml")));
    }
}
