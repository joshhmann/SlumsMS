package server.maps;

import client.Character;
import client.Client;
import client.command.commands.gm3.SpawnCommand;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 (Will, mob 8880302, "!spawn does nothing"): a mob placed with spawnMonsterOnGroundBelow (the !spawn path and
 * every script spawn) went out with foothold 0, while the revive path that brings Will's phases in sets the foothold
 * under the corpse. The spawn now carries the foothold it landed on. And !spawn no longer returns without a word
 * when the mob will not load.
 */
class GmSpawnFootholdTest {

    @TempDir
    static Path wzRoot;

    @BeforeAll
    static void fakeWz() throws IOException {
        Path wz = wzRoot.resolve("wz");
        write(wz.resolve("Mob.wz/9999001.img.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<imgdir name=\"9999001.img\"/>\n");
        write(wz.resolve("String.wz/Mob.img.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<imgdir name=\"Mob.img\"/>\n");
        write(wz.resolve("String.wz/Npc.img.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<imgdir name=\"Npc.img\"/>\n");
        write(wz.resolve("UI.wz/UIWindow.img.xml"), "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<imgdir name=\"UIWindow.img\"><imgdir name=\"MobGage\"><imgdir name=\"Mob\"/></imgdir></imgdir>\n");
        System.setProperty("wz-path", wz.toString());
    }

    private static void write(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    @Test
    void aGroundSpawnCarriesTheFootholdItLandedOn() {
        MapleMap map = new MapleMap(910000998, 0, 0, 910000998, 1.0f);
        FootholdTree tree = new FootholdTree(new Point(-30000, -30000), new Point(30000, 30000));
        tree.insert(new Foothold(new Point(-400, 400), new Point(400, 400), 77));
        map.setFootholds(tree);

        MonsterStats stats = new MonsterStats();
        stats.setHp(1000L);
        Monster mob = new Monster(8880302, stats);
        map.spawnMonsterOnGroundBelow(mob, new Point(0, 300));

        assertEquals(77, mob.getFh());
        assertEquals(new Point(0, 399), mob.getPosition());
        assertEquals(1, map.getAllMonsters().size());
    }

    @Test
    void spawnTellsTheGmWhenTheMobWillNotLoad() {
        Character gm = mock(Character.class);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(gm);
        try (var db = mockStatic(tools.DatabaseConnection.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenThrow(new java.sql.SQLException("no database in this test"));
            new SpawnCommand().execute(c, new String[]{"9999777"});   // no such mob in the fake Mob.wz
        }
        verify(gm).yellowMessage(contains("9999777 could not be loaded"));
    }
}
