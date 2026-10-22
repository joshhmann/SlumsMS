package net.server.channel.handlers;

import client.Character;
import client.Job;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A packet-edited negative damage line used to be decoded by applyAttack as a crit ({@code += Integer.MAX_VALUE}),
 * so a -1 hit for ~2.1b. parseDamage now turns it into a miss, while server-side crit marking still works.
 */
class NegativeDamageTest {

    private static final int MOB_OID = 4242;

    @Test
    void aNegativeLineIsAMiss() {
        assertEquals(0, AbstractDealDamageHandler.clientDamage(-1, null, 0));
        assertEquals(0, AbstractDealDamageHandler.clientDamage(Integer.MIN_VALUE, null, 0));
        assertEquals(0, AbstractDealDamageHandler.clientDamage(0, null, 0));
        assertEquals(1234, AbstractDealDamageHandler.clientDamage(1234, null, 0));
    }

    @Test
    void parseDamageDropsNegativeLinesButStillMarksServerCrits() {
        Character chr = thief();
        // close-range basic attack: 1 mob, 3 lines: -1 (edited), 500 (normal), 1500 (above the 1000 estimate: a crit)
        List<Integer> lines = parse(chr, -1, 500, 1500);

        assertEquals(0, lines.get(0), "negative line becomes a miss");
        assertEquals(500, lines.get(1));
        assertEquals(AbstractDealDamageHandler.markCritical(1500), lines.get(2), "server-computed crit is still flagged");
        assertEquals(1499, AbstractDealDamageHandler.lineDamage(lines.get(2)));

        long applied = 0;
        for (int line : lines) {
            applied += AbstractDealDamageHandler.lineDamage(line);
        }
        assertEquals(1999, applied, "nothing near Integer.MAX_VALUE reaches mob HP");
    }

    @Test
    void anEditedMinusOneNoLongerDecodesToBillions() {
        List<Integer> lines = parse(thief(), -1);
        assertEquals(0, AbstractDealDamageHandler.lineDamage(lines.get(0)));
    }

    private static Character thief() {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.getJob()).thenReturn(Job.THIEF);
        when(chr.getMapId()).thenReturn(100000000);
        when(chr.calculateMaxBaseDamage(anyInt())).thenReturn(1000);
        when(chr.getAllBuffs()).thenReturn(Collections.emptyList());
        return chr;
    }

    private static List<Integer> parse(Character chr, int... damages) {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(0);                       // header byte
        b.writeByte(0x10 | damages.length);   // 1 mob, n lines
        b.writeIntLE(0);                      // skill: basic attack
        b.writeZero(8);
        b.writeByte(0);                       // display
        b.writeByte(0);                       // direction
        b.writeByte(0);                       // stance
        b.writeByte(0);
        b.writeByte(4);                       // speed
        b.writeZero(4);
        b.writeIntLE(MOB_OID);
        b.writeZero(14);
        for (int d : damages) {
            b.writeIntLE(d);
        }
        b.writeZero(4);

        AbstractDealDamageHandler.AttackInfo info =
                new CloseRangeDamageHandler().parseDamage(new ByteBufInPacket(b), chr, false, false);
        return info.allDamage.get(MOB_OID);
    }
}
