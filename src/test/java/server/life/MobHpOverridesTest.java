package server.life;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.DatabaseConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Ticket 81 (draft): the post-Big-Bang mobs' max HP comes from soloMapling/mob-hp-overrides.tsv, applied after
 * LifeFactory's own HP (the WZ value, the lv 160+ halving and the hard-coded boss HP); EXP and level stay. Runs on a
 * tiny fake WZ tree holding a copy of Sun Spirit (8644001, lv 200, 264,616,000 HP in the WZ) and a mob with no row.
 */
class MobHpOverridesTest {

    private static final int SUN_SPIRIT = 8644001;
    private static final int NO_ROW = 9999002;

    @TempDir
    static Path wzRoot;

    @BeforeAll
    static void fakeWz() throws IOException {
        Path wz = wzRoot.resolve("wz");
        mob(wz, SUN_SPIRIT, 200, 264616000, 401055);
        mob(wz, NO_ROW, 50, 1000, 70);
        write(wz.resolve("String.wz/Mob.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="Mob.img">
                  <imgdir name="%d"><string name="name" value="Sun Spirit"/></imgdir>
                  <imgdir name="%d"><string name="name" value="No Row"/></imgdir>
                </imgdir>
                """.formatted(SUN_SPIRIT, NO_ROW));
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

    private static void mob(Path wz, int id, int level, int maxHp, int exp) throws IOException {
        write(wz.resolve("Mob.wz/" + id + ".img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="%d.img">
                  <imgdir name="info">
                    <int name="level" value="%d"/>
                    <int name="maxHP" value="%d"/>
                    <int name="exp" value="%d"/>
                    <int name="PADamage" value="10"/>
                    <int name="PDDamage" value="11"/>
                    <int name="MADamage" value="12"/>
                    <int name="MDDamage" value="13"/>
                  </imgdir>
                </imgdir>
                """.formatted(id, level, maxHp, exp));
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    @Test
    void theTableReplacesTheServersHpAndKeepsExp() {
        Monster sun;
        Monster other;
        try (var db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            sun = LifeFactory.getMonster(SUN_SPIRIT);
            other = LifeFactory.getMonster(NO_ROW);
        }
        assertNotNull(sun);
        long row = MobHpOverrides.table().get(SUN_SPIRIT);
        // without the row LifeFactory would give 132,308,000 (lv 160+ non-boss over 30M: half the WZ value)
        assertEquals(row, sun.getStats().getHp());
        assertEquals(row, sun.getMaxHp());
        assertTrue(row < 1_000_000, "on the classic curve: " + row);
        assertEquals(401055, sun.getStats().getExp(), "EXP stays");
        assertEquals(200, sun.getStats().getLevel());
        assertEquals(1000, other.getStats().getHp(), "a mob with no row keeps its WZ HP");
    }

    @Test
    void theTableMatchesTheReviewedHpList() throws IOException {
        Map<Integer, Long> reviewed = new HashMap<>();
        List<String> lines = Files.readAllLines(Path.of("docs/economy/bb-drops/hp.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",");
            reviewed.put(Integer.parseInt(f[0]), Long.parseLong(f[f.length - 2]));
        }
        assertEquals(reviewed, MobHpOverrides.table(), "mob-hp-overrides.tsv and hp.csv come from one generator run");
        for (Map.Entry<Integer, Long> e : MobHpOverrides.table().entrySet()) {
            assertTrue(e.getValue() > 0 && e.getValue() <= 2_100_000_000L, "HP " + e);
        }
    }

    @Test
    void parseRejectsBadRows() {
        assertThrows(IllegalArgumentException.class,
                () -> MobHpOverrides.parse(new BufferedReader(new StringReader("1\t5\tx\n1\t6\ty\n"))));
        assertThrows(IllegalArgumentException.class,
                () -> MobHpOverrides.parse(new BufferedReader(new StringReader("1\t0\tx\n"))));
        assertThrows(NumberFormatException.class,
                () -> MobHpOverrides.parse(new BufferedReader(new StringReader("1\tlots\tx\n"))));
    }
}
