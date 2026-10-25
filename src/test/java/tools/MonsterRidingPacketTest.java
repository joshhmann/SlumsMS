package tools;

import client.BuffStat;
import client.Mount;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GIVE_FOREIGN_BUFF is addressed to other players, so the packet opens with the character id: the observing client
 * reads that id before the stat mask and then applies the buff to that character. Every other writer in PacketCreator
 * (giveForeignBuff :2989 and :3020, giveForeignCardEffect :3103, giveForeignInfusion :3131, cancelForeignBuff :3150 and
 * :5435) writes the id first, and stock Cosmic's showMonsterRiding did too.
 *
 * MapleRoot wrapped it in {@code if (mount.getItemId() == 1932000)}, so the packet for every other mount was four
 * bytes short: the client read the low half of the stat mask as the character id, the mask was misaligned and the
 * mount never showed up for anyone else. Level 2 and up of the battleship itself (1932001+, StatEffect:1245) was
 * short too. This decodes the packet the way the client does and asserts the id is where the client looks for it.
 */
class MonsterRidingPacketTest {

    /** A cash mount any player can ride, the battleship, and the battleship past level 1. */
    private static final int[] MOUNT_ITEMS = {1902036, 1932000, 1932001};

    private static InPacket in(Packet p) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        assertEquals(SendOpcode.GIVE_FOREIGN_BUFF.getValue(), in.readShort(), "opcode");
        return in;
    }

    @Test
    void mountPacketOpensWithTheCharacterIdForEveryMount() {
        for (int itemId : MOUNT_ITEMS) {
            int cid = 0x01020304;
            int skillId = 1004;

            InPacket in = in(PacketCreator.showMonsterRiding(cid, new Mount(null, itemId, skillId)));

            assertEquals(cid, in.readInt(), "character id for mount " + itemId);
            assertEquals(BuffStat.MONSTER_RIDING.getValue(), in.readLong(), "stat mask");
            assertEquals(0L, in.readLong(), "second mask half");
            assertEquals(0, in.readShort(), "short between mask and mount");
            assertEquals(itemId, in.readInt(), "mount item");
            assertEquals(skillId, in.readInt(), "mount skill");
            assertEquals(0, in.readInt(), "server tick");
            assertEquals(0, in.readShort(), "trailing short");
            assertEquals(0, in.readByte(), "times buffed");
            assertEquals(0, in.available(), "nothing may be left over for mount " + itemId);

            // one whole packet: 2 opcode + 4 id + 8 + 8 masks + 2 + 4 + 4 + 4 + 2 + 1
            assertEquals(39, PacketCreator.showMonsterRiding(cid, new Mount(null, itemId, skillId)).getBytes().length,
                    "packet size for mount " + itemId);
        }
    }
}
