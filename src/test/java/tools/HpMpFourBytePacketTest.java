package tools;

import client.BotClient;
import client.Character;
import client.Stat;
import constants.game.GameConstants;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SlumsMS 1.6.0: slums.dll 10600+ reads HP, MaxHP, MP and MaxMP with Decode4 in GW_CharacterStat, the HP of a plain
 * map change and the STAT_CHANGED HP/MaxHP/MP/MaxMP masks (0x400, 0x800, 0x1000, 0x2000). Stock v83 reads 2 bytes. These
 * decode the packets the way the 1.6.0 client does, at the exact offsets, with values past 32,767 that a 2-byte field
 * could not carry, and check the HP/MP cap is 99,999.
 */
class HpMpFourBytePacketTest {

    private static final int HP = 50_000, MAX_HP = 60_000, MP = 40_000, MAX_MP = 45_000;

    private Character chr;

    @BeforeEach
    void setUp() {
        chr = Character.getDefault(new BotClient(0, 0));
        chr.setName("HpTester");
        chr.updateMaxHpMaxMp(MAX_HP, MAX_MP);
        chr.updateHpMp(HP, MP);
    }

    private static InPacket in(byte[] bytes) {
        return new ByteBufInPacket(Unpooled.wrappedBuffer(bytes));
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    @Test
    void characterHoldsHpMpPastTheOld16BitPool() {
        assertEquals(MAX_HP, chr.getMaxHp());
        assertEquals(MAX_MP, chr.getMaxMp());
        assertEquals(MAX_HP, chr.getClientMaxHp());
        assertEquals(MAX_MP, chr.getClientMaxMp());
        assertEquals(HP, chr.getHp());
        assertEquals(MP, chr.getMp());
    }

    /** GW_CharacterStat: ... level(1) job(2) STR DEX INT LUK (2 each) HP MaxHP MP MaxMP (4 each) AP(2) SP ... */
    @Test
    void characterStatBlockWritesHpMpAsFourBytes() throws Exception {
        Method m = PacketCreator.class.getDeclaredMethod("addCharStats", OutPacket.class, Character.class);
        m.setAccessible(true);
        OutPacket p = new ByteBufOutPacket();
        m.invoke(null, p, chr);
        byte[] b = p.getBytes();

        // id 4, name 13, gender 1, skin 1, face 4, hair 4, 3 pet serials 24, level 1, job 2, 4 stats 8
        int hpAt = 4 + 13 + 1 + 1 + 4 + 4 + 24 + 1 + 2 + 8;
        assertEquals(HP, le32(b, hpAt), "HP");
        assertEquals(MAX_HP, le32(b, hpAt + 4), "MaxHP");
        assertEquals(MP, le32(b, hpAt + 8), "MP");
        assertEquals(MAX_MP, le32(b, hpAt + 12), "MaxMP");

        InPacket in = in(b);
        in.skip(hpAt - 2 - 8);
        assertEquals(chr.getJob().getId(), in.readShort(), "job sits right before the stats");
        assertEquals(chr.getStr(), in.readShort());
        assertEquals(chr.getDex(), in.readShort());
        assertEquals(chr.getInt(), in.readShort());
        assertEquals(chr.getLuk(), in.readShort());
        assertEquals(HP, in.readInt());
        assertEquals(MAX_HP, in.readInt());
        assertEquals(MP, in.readInt());
        assertEquals(MAX_MP, in.readInt());
        assertEquals(chr.getRemainingAp(), in.readShort(), "AP follows MaxMP");
        assertEquals(chr.getRemainingSp(), in.readShort(), "beginner SP is one short");
        assertEquals(chr.getExp(), in.readLong(), "EXP");
        assertEquals(chr.getFame(), in.readShort(), "fame");
        assertEquals(chr.getGachaExp(), in.readInt(), "gacha EXP");
        assertEquals(chr.getMapId(), in.readInt(), "map");
        assertEquals(chr.getInitialSpawnpoint(), in.readByte(), "portal");
        assertEquals(0, in.readInt(), "playtime");
        assertEquals(0, in.available(), "the block ends at playtime");
        // 4 + 13 + 1 + 1 + 4 + 4 + 24 + 1 + 2 + 8 + 16 + 2 + 2 + 8 + 2 + 4 + 4 + 1 + 4
        assertEquals(105, b.length, "8 bytes longer than the stock 2-byte block");
    }

    @Test
    void plainMapChangeWritesHpAsFourBytes() {
        MapleMap to = mock(MapleMap.class);
        when(to.getId()).thenReturn(100000000);

        for (Packet packet : List.of(PacketCreator.getWarpToMap(to, 3, chr),
                PacketCreator.getWarpToMap(to, 0x80, new Point(12, -34), chr))) {
            InPacket in = in(packet.getBytes());
            assertEquals(SendOpcode.SET_FIELD.getValue(), in.readShort(), "opcode");
            in.readInt();                                    // channel
            in.readInt();
            in.readByte();
            assertEquals(100000000, in.readInt(), "map");
            in.readByte();                                   // portal
            assertEquals(HP, in.readInt(), "HP is 4 bytes");
            boolean chase = in.readByte() != 0;
            if (chase) {
                assertEquals(12, in.readInt());
                assertEquals(-34, in.readInt());
            }
            in.readLong();                                   // server time
            assertEquals(0, in.available(), "nothing left over");
        }
    }

    @Test
    void statChangedWritesTheFourHpMpMasksAsFourBytes() {
        List<Pair<Stat, Integer>> stats = new ArrayList<>();
        stats.add(new Pair<>(Stat.MAXMP, MAX_MP));
        stats.add(new Pair<>(Stat.HP, HP));
        stats.add(new Pair<>(Stat.LUK, 77));
        stats.add(new Pair<>(Stat.MP, MP));
        stats.add(new Pair<>(Stat.AVAILABLEAP, 5));
        stats.add(new Pair<>(Stat.MAXHP, MAX_HP));
        stats.add(new Pair<>(Stat.FAME, 12));

        InPacket in = in(PacketCreator.updatePlayerStats(stats, true, chr).getBytes());
        assertEquals(SendOpcode.STAT_CHANGED.getValue(), in.readShort(), "opcode");
        assertEquals(1, in.readByte(), "enable actions");
        assertEquals(0x200 | 0x400 | 0x800 | 0x1000 | 0x2000 | 0x4000 | 0x20000, in.readInt(), "mask");
        // sorted by mask bit, as the client reads them
        assertEquals(77, in.readShort(), "LUK stays 2 bytes");
        assertEquals(HP, in.readInt(), "HP 0x400");
        assertEquals(MAX_HP, in.readInt(), "MaxHP 0x800");
        assertEquals(MP, in.readInt(), "MP 0x1000");
        assertEquals(MAX_MP, in.readInt(), "MaxMP 0x2000");
        assertEquals(5, in.readShort(), "AP stays 2 bytes");
        assertEquals(12, in.readShort(), "fame stays 2 bytes");
        assertEquals(0, in.available(), "nothing left over");
    }

    @Test
    void onlyTheFourHpMpStatsAreFourBytes() {
        for (Stat s : Stat.values()) {
            boolean four = s == Stat.HP || s == Stat.MAXHP || s == Stat.MP || s == Stat.MAXMP;
            assertEquals(four, PacketCreator.isFourByteHpMpStat(s), s.name());
        }
    }

    @Test
    void hpMpCapIs99999() {
        assertEquals(99_999, GameConstants.MAX_HPMP);

        chr.updateMaxHpMaxMp(99_999, 99_999);
        chr.updateHpMp(99_999);
        assertEquals(99_999, chr.getMaxHp(), "99,999 MaxHP allowed");
        assertEquals(99_999, chr.getMaxMp(), "99,999 MaxMP allowed");
        assertEquals(99_999, chr.getHp(), "99,999 HP allowed");
        assertEquals(99_999, chr.getMp(), "99,999 MP allowed");

        chr.updateMaxHpMaxMp(100_000, 100_000);
        chr.addMaxHP(5);
        assertEquals(99_999, chr.getMaxHp(), "100,000 MaxHP clamped");
        assertEquals(99_999, chr.getMaxMp(), "100,000 MaxMP clamped");
        assertEquals(99_999, chr.getClientMaxHp());
        chr.updateHpMp(100_000);
        assertEquals(99_999, chr.getHp(), "HP never passes the cap");
        assertTrue(chr.getCurrentMaxHp() <= GameConstants.MAX_HPMP, "gear/buff max stays capped");
    }

    @Test
    void apIntoMaxHpStopsAtTheCapNotAt30000() {
        chr.updateMaxHpMaxMp(40_000, 40_000);
        chr.changeRemainingAp(10, true);
        assertTrue(chr.assignHP(20, 1), "MaxHP 40,000 can still take AP");
        assertEquals(40_020, chr.getMaxHp());

        chr.updateMaxHpMaxMp(99_999, 99_999);
        assertFalse(chr.assignHP(20, 1), "MaxHP at the cap takes no more AP");
        assertFalse(chr.assignMP(20, 1), "MaxMP at the cap takes no more AP");
    }

    @Test
    void lowValuesStillHitTheFloor() {
        chr.updateMaxHpMaxMp(10, 1);
        assertEquals(50, chr.getMaxHp(), "MaxHP floor");
        assertEquals(5, chr.getMaxMp(), "MaxMP floor");
        chr.updateMp(-10);
        assertEquals(0, chr.getMp());
    }
}
