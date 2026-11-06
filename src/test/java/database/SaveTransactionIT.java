package database;

import client.Character;
import client.inventory.Pet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 1.6.5 P0-5, real SQL ({@code -Dslums.family.it.url}, a scratch schema, never a game database): the pet and cooldown
 * writes run on the caller's connection and throw on failure, so a character save's transaction rolls back with them.
 * A trigger makes the pet write fail for a pet named 'boom' and the cooldown write fail for skill 666.
 */
class SaveTransactionIT {

    static final String URL = System.getProperty("slums.family.it.url");

    @Test
    void aFailingPetWriteThrowsOnTheCallersConnectionAndTheEarlierWriteRollsBack() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
            exec(db, "INSERT INTO characters (id, meso) VALUES (1, 100)");
            exec(db, "INSERT INTO pets (petid, name) VALUES (9, 'ok')");
        }
        Pet pet = org.mockito.Mockito.mock(Pet.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn("boom").when(pet).getName();
        org.mockito.Mockito.doReturn(9).when(pet).getUniqueId();
        try (Connection con = DriverManager.getConnection(URL)) {
            con.setAutoCommit(false);
            exec(con, "UPDATE characters SET meso = 999 WHERE id = 1");   // stands for the rest of the character save
            assertThrows(SQLException.class, () -> pet.saveToDb(con));
            con.rollback();
        }
        try (Connection db = DriverManager.getConnection(URL)) {
            assertEquals(100, intOf(db, "SELECT meso FROM characters WHERE id = 1"), "the character write rolled back");
        }
    }

    @Test
    void aFailingCooldownWriteThrowsOnTheCallersConnectionAndTheEarlierWriteRollsBack() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no scratch MySQL given");
        try (Connection db = DriverManager.getConnection(URL)) {
            schema(db);
            exec(db, "INSERT INTO characters (id, meso) VALUES (1, 100)");
        }
        Character chr = org.mockito.Mockito.mock(Character.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.doReturn(1).when(chr).getId();
        org.mockito.Mockito.doReturn(java.util.List.of(new net.server.PlayerCoolDownValueHolder(666, 1L, 1000L)))
                .when(chr).getAllCooldowns();
        org.mockito.Mockito.doReturn(java.util.Map.of()).when(chr).getAllDiseases();
        Character.SaveExtra cooldowns = chr.cooldownsExtra();
        try (Connection con = DriverManager.getConnection(URL)) {
            con.setAutoCommit(false);
            exec(con, "UPDATE characters SET meso = 999 WHERE id = 1");
            assertThrows(SQLException.class, () -> cooldowns.run(con));
            con.rollback();
        }
        try (Connection db = DriverManager.getConnection(URL)) {
            assertEquals(100, intOf(db, "SELECT meso FROM characters WHERE id = 1"), "the character write rolled back");
        }
    }

    private static void schema(Connection db) throws SQLException {
        exec(db, "DROP TABLE IF EXISTS characters, pets, cooldowns, playerdiseases");
        exec(db, "CREATE TABLE characters (id int PRIMARY KEY, meso int NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TABLE pets (petid int PRIMARY KEY, name varchar(13) NOT NULL DEFAULT '', level int NOT NULL DEFAULT 1,"
                + " closeness int NOT NULL DEFAULT 0, fullness int NOT NULL DEFAULT 100, summoned tinyint NOT NULL DEFAULT 0,"
                + " flag int NOT NULL DEFAULT 0) ENGINE=InnoDB");
        exec(db, "CREATE TABLE cooldowns (id int AUTO_INCREMENT PRIMARY KEY, charid int NOT NULL, SkillID int NOT NULL,"
                + " StartTime bigint NOT NULL, length bigint NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TABLE playerdiseases (id int AUTO_INCREMENT PRIMARY KEY, charid int NOT NULL, disease int NOT NULL,"
                + " mobskillid int NOT NULL, mobskilllv int NOT NULL, length int NOT NULL) ENGINE=InnoDB");
        exec(db, "CREATE TRIGGER pet_boom BEFORE UPDATE ON pets FOR EACH ROW BEGIN IF NEW.name = 'boom' THEN"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected pet failure'; END IF; END");
        exec(db, "CREATE TRIGGER cd_boom BEFORE INSERT ON cooldowns FOR EACH ROW BEGIN IF NEW.SkillID = 666 THEN"
                + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'injected cooldown failure'; END IF; END");
    }

    private static void exec(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute(sql);
        }
    }

    private static int intOf(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
