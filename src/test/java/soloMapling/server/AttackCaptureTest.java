package soloMapling.server;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.server.channel.handlers.AbstractDealDamageHandler.AttackInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AttackCaptureTest {

    @TempDir
    Path dir;

    @AfterEach
    void turnOff() {
        AttackCapture.useFile(Path.of("logs", "attack-capture.tsv"), false);
    }

    @Test
    void aLineHasEveryColumnInOrderAndNoStrayTabs() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("hex", "2C00");
        row.put("event", "close");
        row.put("skillName", "Two\tWords\nhere");
        String[] f = AttackCapture.line(row).split("\t", -1);
        assertEquals(AttackCapture.COLUMNS.size(), f.length);
        assertEquals("close", f[AttackCapture.COLUMNS.indexOf("event")]);
        assertEquals("Two Words here", f[AttackCapture.COLUMNS.indexOf("skillName")]);
        assertEquals("2C00", f[f.length - 1]);
        assertEquals("", f[0]);
    }

    @Test
    void theRawPacketIsReadWithoutMovingTheReader() {
        InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{0x2C, 0x00, 0x01, (byte) 0xFF}));
        p.readShort();
        assertEquals("2C0001FF", AttackCapture.hex(AttackCapture.raw(p)));
        assertEquals(2, p.getPosition());
        assertEquals(1, p.readByte());
    }

    @Test
    void aRangedTailNamesItsUseAndCashSlots() {
        byte[] raw = HexFormat.of().parseHex("AABB" + "0102" + "0300" + "FFFF" + "00");
        assertArrayEquals(new int[]{3, -1}, AttackCapture.bulletSlots(raw, 2));
        assertNull(AttackCapture.bulletSlots(raw, 4), "a tail past the end");
        assertNull(AttackCapture.bulletSlots(raw, -1), "not a parsed ranged attack");
    }

    @Test
    void theBodyActionIsNamedFromTheClientsTable() {
        assertEquals("blast", AttackCapture.actionName(71));
        assertEquals("swingO1", AttackCapture.actionName(5));
        assertEquals("fullSwingDouble", AttackCapture.actionName(127), "Aran poses run past 127");
        assertEquals("", AttackCapture.actionName(250));
    }

    @Test
    void onlyARealGmIsCaptured() {
        AttackCapture.useFile(dir.resolve("c.tsv"), true);
        Character gm = player(3);
        assertTrue(AttackCapture.captures(gm));
        assertFalse(AttackCapture.captures(player(1)), "a player below GM level 2");
        Character bot = player(6);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        assertFalse(AttackCapture.captures(bot), "a bot");
        AttackCapture.useFile(dir.resolve("c.tsv"), false);
        assertFalse(AttackCapture.captures(gm), "capture off");
    }

    @Test
    void aRangedAttackIsWrittenWithItsHeaderOnce() throws IOException {
        Path file = dir.resolve("logs").resolve("attack-capture.tsv");
        AttackCapture.useFile(file, true);
        Character gm = player(3);
        Inventory use = mock(Inventory.class);
        Item stars = mock(Item.class);
        when(stars.getItemId()).thenReturn(2070006);
        when(use.getItem((short) 3)).thenReturn(stars);
        when(gm.getInventory(InventoryType.USE)).thenReturn(use);
        when(gm.getInventory(InventoryType.CASH)).thenReturn(mock(Inventory.class));

        // opcode, then a tail at 2: two time bytes, USE slot 3, CASH slot 0, one byte
        InPacket p = new ByteBufInPacket(Unpooled.wrappedBuffer(HexFormat.of().parseHex("2D00" + "1122" + "0300" + "0000" + "07")));
        AttackInfo attack = new AttackInfo();
        attack.ranged = true;
        attack.direction = 24;
        attack.stance = (byte) 0x80;
        attack.speed = 5;
        attack.numAttacked = 2;
        attack.numDamage = 3;
        attack.rangedTailPos = 2;
        AttackCapture.attack(gm, "ranged", attack, p);
        AttackCapture.attack(gm, "ranged", attack, p);

        List<String> lines = Files.readAllLines(file);
        assertEquals(3, lines.size(), "the header once, then a line per attack");
        assertEquals(String.join("\t", AttackCapture.COLUMNS), lines.get(0));
        String[] f = lines.get(1).split("\t", -1);
        assertEquals("ranged", col(f, "event"));
        assertEquals("Tester", col(f, "char"));
        assertEquals("411", col(f, "jobId"));
        assertEquals("24", col(f, "bodyActionId"));
        assertEquals("swingO1", col(f, "actionName"));
        assertEquals("128", col(f, "facingMask"));
        assertEquals("5", col(f, "speed"));
        assertEquals("2", col(f, "numAttacked"));
        assertEquals("3", col(f, "numDamage"));
        assertEquals("3", col(f, "bulletSlot"));
        assertEquals("2070006", col(f, "projectile"));
        assertEquals("", col(f, "cashProjectile"));
        assertEquals("rangedTail=11220300000007", col(f, "extra"));
        assertEquals("2D0011220300000007", col(f, "hex"));
    }

    @Test
    void nothingIsWrittenWhenCaptureIsOff() {
        Path file = dir.resolve("off.tsv");
        AttackCapture.useFile(file, false);
        AttackCapture.cast(player(3), 0, 1, new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{1, 2})));
        assertFalse(Files.exists(file));
    }

    private static String col(String[] fields, String column) {
        return fields[AttackCapture.COLUMNS.indexOf(column)];
    }

    private static Character player(int gmLevel) {
        Character chr = mock(Character.class);
        when(chr.gmLevel()).thenReturn(gmLevel);
        when(chr.getName()).thenReturn("Tester");
        when(chr.getJob()).thenReturn(Job.HERMIT);
        when(chr.getLevel()).thenReturn(120);
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        return chr;
    }
}
