package soloMapling.menus;

import org.junit.jupiter.api.Test;
import soloMapling.fishing.FishingPolicy;
import soloMapling.fishing.StarterChairs;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Migration 054 hard-codes the keys, the tip's info and the chair ids: keep them in step with the code. */
class StarterChairRepairMigrationTest {

    @Test
    void migrationMatchesTheCode() throws Exception {
        String sql = Files.readString(Path.of("database/migrations/054-starter-chair-repair.sql"));
        assertEquals(29151, StarterChairs.GIVEN_AREA);
        assertTrue(sql.contains("a.area = " + StarterChairs.GIVEN_AREA));
        assertTrue(sql.contains("a.info = '" + GuideMenu.UI_TIP_INFO + "'"));
        assertTrue(sql.contains("SET a.area = " + GuideMenu.UI_TIP_AREA));
        assertTrue(sql.contains("t.area = " + GuideMenu.UI_TIP_AREA));
        assertTrue(sql.contains("(" + StarterChairs.RELAXER + ", " + FishingPolicy.CHAIR + ")"));
        assertTrue(Files.exists(Path.of("database/migrations/054-starter-chair-repair.rollback.sql")));
    }
}
