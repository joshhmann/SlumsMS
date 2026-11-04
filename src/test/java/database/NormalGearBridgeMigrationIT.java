package database;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static database.FamilyBotJuniorsMigrationIT.*;
import static org.junit.jupiter.api.Assertions.*;

/** Destructive only in the explicitly named, localhost disposable bridge_061_it schema. */
class NormalGearBridgeMigrationIT {
    private static final String URL = System.getProperty("slums.bridge.it.url");
    private static final String APPLY = "061-normal-gear-bridge.sql";
    private static final String ROLLBACK = "061-normal-gear-bridge.rollback.sql";
    record Pair(int mob, int item, int chance) { }
    static List<Pair> pairs() throws Exception {
        var matcher = Pattern.compile("(?m)^\\((\\d+), (\\d+), (\\d+)\\)[,;]")
                .matcher(Files.readString(Path.of("database/migrations", APPLY)));
        List<Pair> out = new ArrayList<>();
        while (matcher.find()) out.add(new Pair(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
        assertEquals(226, out.size());
        return out;
    }
    Connection db() throws Exception { return DriverManager.getConnection(URL); }
    @BeforeEach void reset() throws Exception {
        Assumptions.assumeTrue(URL != null, "no disposable bridge DB supplied");
        assertTrue(URL.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/bridge_061_it(?:\\?.*)?"),
                "Refuse any schema/host except the dedicated localhost scratch DB");
        try (Connection db = db()) {
            exec(db, "DROP TABLE IF EXISTS drop_data,normal_bridge_061_rows,normal_gear_059_rows,"
                    + "royal_materials_060_rows,mapleroot_integration_migrations");
            exec(db, "CREATE TABLE drop_data (id BIGINT AUTO_INCREMENT PRIMARY KEY,dropperid INT NOT NULL,"
                    + "itemid INT NOT NULL,minimum_quantity INT NOT NULL DEFAULT 1,maximum_quantity INT NOT NULL DEFAULT 1,"
                    + "questid INT NOT NULL DEFAULT 0,chance INT NOT NULL DEFAULT 0,UNIQUE KEY(dropperid,itemid)) ENGINE=MyISAM");
        }
    }
    static String where(Pair p) { return "dropperid=" + p.mob + " AND itemid=" + p.item; }
    static void seed(Connection db, Pair p, int chance, int quest) throws Exception {
        exec(db, "INSERT INTO drop_data(dropperid,itemid,chance,questid) VALUES("
                + p.mob + "," + p.item + "," + chance + "," + quest + ")");
    }
    @Test void applyTwiceRollbackTwicePreservesOlderStrongerQuestAndUnrelatedRows() throws Exception {
        var p = pairs();
        try (Connection db = db()) {
            seed(db,p.get(0),800,0); seed(db,p.get(1),7000,0); seed(db,p.get(2),999999,123);
            exec(db,"INSERT INTO drop_data(dropperid,itemid,chance) VALUES(100100,4000000,600000)");
            for (int i=0;i<2;i++) script(db,APPLY);
            assertEquals(227,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(p.get(0).chance,count(db,"SELECT chance FROM drop_data WHERE " + where(p.get(0))));
            assertEquals(7000,count(db,"SELECT chance FROM drop_data WHERE " + where(p.get(1))));
            assertEquals(123,count(db,"SELECT questid FROM drop_data WHERE " + where(p.get(2))));
            assertEquals(224,count(db,"SELECT COUNT(*) FROM normal_bridge_061_rows"));
            for (int i=0;i<2;i++) script(db,ROLLBACK);
            assertEquals(4,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(800,count(db,"SELECT chance FROM drop_data WHERE " + where(p.get(0))));
            assertEquals(0,count(db,"SELECT COUNT(*) FROM normal_bridge_061_rows"));
            script(db,APPLY);
            assertEquals(227,count(db,"SELECT COUNT(*) FROM drop_data"));
        }
    }
    @Test void rollbackPreservesLaterEditsAndReplacementRowIds() throws Exception {
        var p = pairs();
        try (Connection db = db()) {
            seed(db,p.get(0),800,0);script(db,APPLY);
            exec(db,"UPDATE drop_data SET chance=9000 WHERE " + where(p.get(0)));
            exec(db,"UPDATE drop_data SET maximum_quantity=2 WHERE " + where(p.get(1)));
            exec(db,"DELETE FROM drop_data WHERE " + where(p.get(2)));
            seed(db,p.get(2),p.get(2).chance,0);
            script(db,APPLY); // Must not take ownership of the replacement or reset the old intent.
            script(db,ROLLBACK);script(db,ROLLBACK);
            assertEquals(3,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(9000,count(db,"SELECT chance FROM drop_data WHERE " + where(p.get(0))));
            assertEquals(2,count(db,"SELECT maximum_quantity FROM drop_data WHERE " + where(p.get(1))));
        }
    }
    @Test void rollbackBeforeApplyAndFreshApplyAreIdempotent() throws Exception {
        try (Connection db = db()) {
            script(db,ROLLBACK);script(db,ROLLBACK);script(db,APPLY);script(db,APPLY);
            assertEquals(226,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(226,count(db,"SELECT COUNT(*) FROM normal_bridge_061_rows"));
            script(db,ROLLBACK);script(db,ROLLBACK);
            assertEquals(0,count(db,"SELECT COUNT(*) FROM drop_data"));
        }
    }
    @Test void intentJournalRecoversInterruptionBeforeInsertedIdsWereCaptured() throws Exception {
        try (Connection db = db()) {
            script(db,APPLY);
            exec(db,"UPDATE normal_bridge_061_rows SET drop_id=NULL WHERE operation='I'");
            script(db,APPLY);
            assertEquals(226,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(0,count(db,"SELECT COUNT(*) FROM normal_bridge_061_rows WHERE drop_id IS NULL"));
            script(db,ROLLBACK);
            assertEquals(0,count(db,"SELECT COUNT(*) FROM drop_data"));
        }
    }
    @Test void bridgeRollbackDoesNotTouchTheOtherUnshippedGearMigrations() throws Exception {
        try (Connection db = db()) {
            script(db,"059-normal-gear-through-200.sql");script(db,"060-royal-crafting-materials.sql");
            int baseline=count(db,"SELECT COUNT(*) FROM drop_data");
            script(db,APPLY);script(db,APPLY);
            assertEquals(baseline+226,count(db,"SELECT COUNT(*) FROM drop_data"));
            script(db,ROLLBACK);script(db,ROLLBACK);
            assertEquals(baseline,count(db,"SELECT COUNT(*) FROM drop_data"));
            assertEquals(136,count(db,"SELECT COUNT(*) FROM normal_gear_059_rows"));
            assertEquals(5,count(db,"SELECT COUNT(*) FROM royal_materials_060_rows"));
            script(db,"060-royal-crafting-materials.rollback.sql");
            script(db,"059-normal-gear-through-200.rollback.sql");
            assertEquals(0,count(db,"SELECT COUNT(*) FROM drop_data"));
        }
    }
}
