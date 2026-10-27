package client;

import org.junit.jupiter.api.Test;
import tools.PacketCreator;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 t-hp-1 and t-np-4: what the pretend player reads from the stat packet and from an NPC's number box. */
class SyntheticClientStatTest {

    private static byte[] stats(List<Pair<Stat, Integer>> s) {
        return PacketCreator.updatePlayerStats(new ArrayList<>(s), true, null).getBytes();
    }

    @Test
    void maxHpAndMaxMpPastTheShortLimitReadBackAsFourBytes() {
        String line = SyntheticClient.decodeHpMpStats(stats(List.of(new Pair<>(Stat.MAXHP, 50_000),
                new Pair<>(Stat.HP, 50_000), new Pair<>(Stat.MAXMP, 45_000), new Pair<>(Stat.MP, 45_000))));
        assertEquals("HP=50000 MAXHP=50000 MP=45000 MAXMP=45000", line);
        assertEquals("MAXHP=99999", SyntheticClient.decodeHpMpStats(stats(List.of(new Pair<>(Stat.MAXHP, 99_999)))));
    }

    @Test
    void otherStatsAroundThemAreSkippedByWidth() {
        String line = SyntheticClient.decodeHpMpStats(stats(List.of(new Pair<>(Stat.STR, 300), new Pair<>(Stat.MAXHP, 40_000),
                new Pair<>(Stat.LEVEL, 120), new Pair<>(Stat.MAXMP, 33_000))));
        assertEquals("MAXHP=40000 MAXMP=33000", line);
    }

    @Test
    void regenAloneIsNotKept() {
        assertNull(SyntheticClient.decodeHpMpStats(stats(List.of(new Pair<>(Stat.HP, 1234)))));
        assertNull(SyntheticClient.decodeHpMpStats(new byte[]{0x1F, 0}));
    }

    @Test
    void theStatLineAndTheNumberBoxAreHeard() {
        SyntheticClient c = new SyntheticClient(9, 1, 1);
        long t = System.currentTimeMillis();
        c.sendPacket(PacketCreator.updatePlayerStats(new ArrayList<>(List.of(new Pair<>(Stat.MAXHP, 50_000))), true, null));
        c.sendPacket(PacketCreator.updatePlayerStats(new ArrayList<>(List.of(new Pair<>(Stat.HP, 10))), true, null));
        c.sendPacket(PacketCreator.getNPCTalkNum(9000017, "how many?", 1, 1, 5));
        var heard = c.heardSince(t).stream().map(l -> l.kind() + " " + l.message()).toList();
        assertEquals(1, heard.stream().filter(l -> l.startsWith("STAT")).count(), heard.toString());
        assertTrue(heard.contains("STAT MAXHP=50000"), heard.toString());
        assertTrue(heard.contains("NPC_NUMBER 9000017: default 1 min 1 max 5"), heard.toString());
        assertTrue(heard.stream().anyMatch(l -> l.startsWith("NPC_TALK 9000017: how many?")), heard.toString());
    }
}
