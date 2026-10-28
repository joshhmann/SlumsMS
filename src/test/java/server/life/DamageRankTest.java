package server.life;

import net.opcodes.SendOpcode;
import net.packet.ByteBufOutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client ticket 118: LP 0x3739 BOSS_DAMAGE_RANK layout, throttle, recipient filter and sorting. */
class DamageRankTest {
    private static DamageRank.Member m(int id, String name, boolean player, int build, boolean map, boolean dmg) {
        return new DamageRank.Member(id, name, player, build, map, dmg);
    }

    @Test
    void opcodeIs0x3739() {
        assertEquals(0x3739, SendOpcode.BOSS_DAMAGE_RANK.getValue());
    }

    @Test
    void payloadLayout() {
        Packet p = DamageRank.packet(8800000, List.of(new DamageRank.Entry(7, "Ab", 5_000_000_000L),
                new DamageRank.Entry(1000000003, "Bot", 12)));
        byte[] b = ((ByteBufOutPacket) p).getBytes();
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x3739, bb.getShort() & 0xFFFF);
        assertEquals(8800000, bb.getInt());
        assertEquals(2, bb.get());
        assertEquals(7, bb.getInt());
        int len = bb.getShort();
        byte[] s = new byte[len];
        bb.get(s);
        assertEquals("Ab", new String(s, StandardCharsets.US_ASCII));
        assertEquals(5_000_000_000L, bb.getLong());
        assertEquals(1000000003, bb.getInt());
        assertEquals(3, bb.getShort());
        bb.position(bb.position() + 3);
        assertEquals(12L, bb.getLong());
        assertEquals(0, bb.remaining());
    }

    @Test
    void throttleOncePerSecond() {
        assertTrue(DamageRank.due(0, 1_000_000));
        assertFalse(DamageRank.due(1_000_000, 1_000_999));
        assertTrue(DamageRank.due(1_000_000, 1_001_000));
    }

    @Test
    void recipientsAreRealSameMapDamagersOnNewEnoughBuild() {
        List<DamageRank.Member> party = List.of(
                m(1, "ok", true, 10690, true, true),
                m(2, "oldBuild", true, 10600, true, true),
                m(3, "noDamage", true, 10700, true, false),
                m(4, "otherMap", true, 10700, false, true),
                m(1000000001, "bot", false, 0, true, true),
                m(5, "ok2", true, 10700, true, true));
        assertEquals(List.of(1, 5), DamageRank.recipients(party, 10690));
    }

    @Test
    void rankSortsDescCapsAndIncludesBotsButNotNonDamagers() {
        List<DamageRank.Member> party = List.of(m(1, "a", true, 1, true, true), m(2, "b", true, 1, true, true),
                m(1000000001, "BotX", false, 0, true, true), m(4, "idle", true, 1, true, false));
        Map<Integer, Long> snap = Map.of(1, 100L, 2, 300L, 1000000001, 200L, 99, 9999L);
        List<DamageRank.Entry> r = DamageRank.rank(snap, party, 6);
        assertEquals(List.of("b", "BotX", "a"), r.stream().map(DamageRank.Entry::name).toList());
        assertEquals(2, DamageRank.rank(snap, party, 2).size());
    }
}
