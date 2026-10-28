package net.packet;

import net.opcodes.SendOpcode;

/**
 * A packet that names an NPC template (SPAWN_NPC, SPAWN_NPC_REQUEST_CONTROLLER, NPC_TALK). Tagged so the client
 * session can hold back custom NPCs from a client without their data ({@link server.life.CustomNpcGate}): a v83
 * client that is sent an NPC template it has no Npc/&lt;id&gt;.img for hits STG_E_FILENOTFOUND and ends the session.
 * Same bytes as any other packet; the tag costs nothing on the wire.
 */
public final class NpcOutPacket extends ByteBufOutPacket {
    /** For a packet with no safe stand-in (a spawn): the gate drops it. */
    public static final int NO_SUBSTITUTE = -1;

    private final int npcTemplateId;
    private final int templateOffset;

    /**
     * @param templateOffset where the template id sits, counted from the first opcode byte, if another NPC may stand
     *                       in for it (a talk window); {@link #NO_SUBSTITUTE} if the packet must be dropped instead
     */
    public NpcOutPacket(SendOpcode op, int npcTemplateId, int templateOffset) {
        super(op);
        this.npcTemplateId = npcTemplateId;
        this.templateOffset = templateOffset;
    }

    public int npcTemplateId() {
        return npcTemplateId;
    }

    /** The same packet with another NPC template in it, or null when it has no stand-in (drop it). */
    public Packet withTemplate(int otherTemplateId) {
        if (templateOffset < 0) {
            return null;
        }
        byte[] bytes = getBytes();
        if (bytes.length < templateOffset + 4) {
            return null;
        }
        bytes[templateOffset] = (byte) otherTemplateId;
        bytes[templateOffset + 1] = (byte) (otherTemplateId >>> 8);
        bytes[templateOffset + 2] = (byte) (otherTemplateId >>> 16);
        bytes[templateOffset + 3] = (byte) (otherTemplateId >>> 24);
        return new RawPacket(bytes);
    }

    private record RawPacket(byte[] bytes) implements Packet {
        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }
    }
}
