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
 * Ticket 81 (draft): max HP and EXP come from soloMapling/mob-stat-overrides.tsv, applied after LifeFactory's own
 * values (the WZ value, the lv 160+ halving and the hard-coded boss HP). Runs on a tiny fake WZ tree holding copies of
 * Sun Spirit (8644001, lv 200, 264,616,000 HP in the WZ: HP and EXP rows), Skelegon (8190003, Leafre: EXP x1.4 only)
 * and a mob with no row.
 */
class MobStatOverridesTest {

    private static final int SUN_SPIRIT = 8644001;
    private static final int NO_ROW = 9999002;
    private static final int SKELEGON = 8190003;

    @TempDir
    static Path wzRoot;

    @BeforeAll
    static void fakeWz() throws IOException {
        Path wz = wzRoot.resolve("wz");
        mob(wz, SUN_SPIRIT, 200, 264616000, 401055);
        mob(wz, NO_ROW, 50, 1000, 70);
        mob(wz, SKELEGON, 110, 80000, 4500);
        write(wz.resolve("String.wz/Mob.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="Mob.img">
                  <imgdir name="%d"><string name="name" value="Sun Spirit"/></imgdir>
                  <imgdir name="%d"><string name="name" value="No Row"/></imgdir>
                  <imgdir name="%d"><string name="name" value="Skelegon"/></imgdir>
                </imgdir>
                """.formatted(SUN_SPIRIT, NO_ROW, SKELEGON));
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
    void theTableReplacesHpAndExp() {
        Monster sun;
        Monster skelegon;
        Monster other;
        try (var db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenThrow(new SQLException("no database in this test"));
            sun = LifeFactory.getMonster(SUN_SPIRIT);
            skelegon = LifeFactory.getMonster(SKELEGON);
            other = LifeFactory.getMonster(NO_ROW);
        }
        assertNotNull(sun);
        MobStatOverrides.Row row = MobStatOverrides.table().get(SUN_SPIRIT);
        // without the row LifeFactory would give 132,308,000 (lv 160+ non-boss over 30M: half the WZ value)
        assertEquals(row.hp(), sun.getStats().getHp());
        assertEquals(row.hp(), sun.getMaxHp());
        assertTrue(row.hp() < 1_000_000, "on the classic curve: " + row);
        assertEquals(row.exp(), sun.getStats().getExp());
        assertTrue(row.exp() < 401055, "EXP scaled to about 2x a classic spot per hour: " + row);
        assertEquals(200, sun.getStats().getLevel());

        assertEquals(80000, skelegon.getStats().getHp(), "a classic spot keeps its HP");
        assertEquals(6300, skelegon.getStats().getExp(), "and gets EXP x1.4");

        assertEquals(1000, other.getStats().getHp(), "a mob with no row keeps its WZ values");
        assertEquals(70, other.getStats().getExp());
    }

    @Test
    void theTableMatchesTheReviewedStatList() throws IOException {
        Map<Integer, MobStatOverrides.Row> reviewed = new HashMap<>();
        List<String> lines = Files.readAllLines(Path.of("docs/economy/bb-drops/stats.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            int n = f.length; // ..., hp_after, exp_before, exp_after, exp_per_hour_before, exp_per_hour_after
            String hp = f[n - 5];
            String exp = f[n - 3];
            reviewed.put(Integer.parseInt(f[0]), new MobStatOverrides.Row(hp.isEmpty() ? null : Long.parseLong(hp),
                    exp.isEmpty() ? null : Integer.parseInt(exp)));
        }
        assertEquals(reviewed, MobStatOverrides.table(), "mob-stat-overrides.tsv and stats.csv come from one run");
        for (Map.Entry<Integer, MobStatOverrides.Row> e : MobStatOverrides.table().entrySet()) {
            Long hp = e.getValue().hp();
            assertTrue(hp == null || (hp > 0 && hp <= 2_100_000_000L), "HP " + e);
        }
    }

    @Test
    void parseRejectsBadRows() {
        assertThrows(IllegalArgumentException.class,
                () -> MobStatOverrides.parse(new BufferedReader(new StringReader("1\t5\t-\tx\n1\t6\t-\ty\n"))));
        assertThrows(IllegalArgumentException.class,
                () -> MobStatOverrides.parse(new BufferedReader(new StringReader("1\t0\t-\tx\n"))));
        assertThrows(IllegalArgumentException.class,
                () -> MobStatOverrides.parse(new BufferedReader(new StringReader("1\t-\t-\tx\n"))));
        assertThrows(NumberFormatException.class,
                () -> MobStatOverrides.parse(new BufferedReader(new StringReader("1\tlots\t-\tx\n"))));
    }
}
