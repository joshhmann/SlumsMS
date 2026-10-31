package tools;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * b105: the Dimensional Mirror's slide menu ("#0# Ariant Coliseum#1# Mu Lung Dojo") is parsed by the v83 client one
 * "#n# name" entry at a time, each read up to the next '#'. Stock Cosmic sent the list without a closing '#', so the
 * client read past the string (crash at 0x76B7C9). The server now closes the last entry.
 */
class DimensionalMirrorPacketTest {

    private static String menuText(String talk) {
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(PacketCreator.getDimensionalMirror(talk).getBytes()));
        assertEquals(SendOpcode.NPC_TALK.getValue(), in.readShort(), "opcode");
        in.readByte();
        in.readInt();
        in.readByte();
        in.readByte();
        in.readInt();
        return in.readString();
    }

    @Test
    void theMenuEndsWithAClosingHash() {
        assertEquals("#w#0# Ariant Coliseum#1# Mu Lung Dojo#", menuText("#0# Ariant Coliseum#1# Mu Lung Dojo"));
    }

    @Test
    void aMenuThatAlreadyEndsWithAHashIsNotDoubled() {
        assertEquals("#w#-1# There is no place for you to transport to from here.#",
                menuText("#-1# There is no place for you to transport to from here.#"));
    }
}
