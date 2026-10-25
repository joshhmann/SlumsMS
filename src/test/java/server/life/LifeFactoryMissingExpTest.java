package server.life;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;

/**
 * 1.5.1: MapleRoot read info/exp with no default, so a mob without an exp node (Nett's Pyramid, Amoria and Ellin PQ,
 * Golden Pig, ghost-map mobs) threw, getMonster returned null and MapFactory failed the whole map. Cosmic defaults
 * a missing exp to the stats' current value (0). Runs on a tiny fake WZ tree, so it needs no real game data.
 */
class LifeFactoryMissingExpTest {

    private static final int NO_EXP_MOB = 9999001;

    @TempDir
    static Path wzRoot;

    @BeforeAll
    static void fakeWz() throws IOException {
        Path wz = wzRoot.resolve("wz");
        write(wz.resolve("Mob.wz/" + NO_EXP_MOB + ".img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="%d.img">
                  <imgdir name="info">
                    <int name="level" value="50"/>
                    <int name="maxHP" value="1000"/>
                    <int name="PADamage" value="10"/>
                    <int name="PDDamage" value="11"/>
                    <int name="MADamage" value="12"/>
                    <int name="MDDamage" value="13"/>
                  </imgdir>
                </imgdir>
                """.formatted(NO_EXP_MOB));
        write(wz.resolve("String.wz/Mob.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="Mob.img">
                  <imgdir name="%d">
                    <string name="name" value="No Exp Mob"/>
                  </imgdir>
                </imgdir>
                """.formatted(NO_EXP_MOB));
        write(wz.resolve("String.wz/Npc.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="Npc.img"/>
                """);
        write(wz.resolve("UI.wz/UIWindow.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="UIWindow.img">
                  <imgdir name="MobGage">
                    <imgdir name="Mob"/>
                  </imgdir>
                </imgdir>
                """);
        System.setProperty("wz-path", wz.toString());
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    @Test
    void aMobWithoutAnExpNodeStillLoadsWithZeroExp() {
        Monster mob;
        try (var db = mockStatic(DatabaseConnection.class)) {
            // MonsterInformationProvider reads global drops on first use; with no database it just logs and goes on.
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            mob = LifeFactory.getMonster(NO_EXP_MOB);
        }
        assertNotNull(mob, "a mob with no info/exp must still load, or its whole map fails");
        assertEquals(0, mob.getStats().getExp());
        assertEquals(50, mob.getStats().getLevel());
    }
}
