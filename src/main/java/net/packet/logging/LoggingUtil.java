package net.packet.logging;

import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;

import java.util.Set;

public class LoggingUtil {
    private static final Set<Short> ignoredDebugRecvPackets = Set.of(
            (short) RecvOpcode.MOVE_PLAYER.getValue(), // 41
            (short) RecvOpcode.HEAL_OVER_TIME.getValue(), // 89
            (short) RecvOpcode.SPECIAL_MOVE.getValue(), // 91
            (short) RecvOpcode.QUEST_ACTION.getValue(), // 107
            (short) RecvOpcode.MOVE_PET.getValue(), // 167
            (short) RecvOpcode.MOVE_LIFE.getValue(), // 188
            (short) RecvOpcode.NPC_ACTION.getValue() // 197
    );

    // b351: packets that carry a login password, PIN or PIC; the loggers write their name and length, never their bytes
    private static final Set<Short> credentialRecvPackets = Set.of(
            (short) RecvOpcode.LOGIN_PASSWORD.getValue(),
            (short) RecvOpcode.AFTER_LOGIN.getValue(),
            (short) RecvOpcode.REGISTER_PIN.getValue(),
            (short) RecvOpcode.DELETE_CHAR.getValue(),
            (short) RecvOpcode.REGISTER_PIC.getValue(),
            (short) RecvOpcode.CHAR_SELECT_WITH_PIC.getValue(),
            (short) RecvOpcode.VIEW_ALL_PIC_REGISTER.getValue(),
            (short) RecvOpcode.VIEW_ALL_WITH_PIC.getValue()
    );

    public static short readFirstShort(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes).readShortLE();
    }

    public static boolean isIgnoredRecvPacket(short opcode) {
        return ignoredDebugRecvPackets.contains(opcode);
    }

    public static boolean isCredentialRecvPacket(short opcode) {
        return credentialRecvPackets.contains(opcode);
    }
}
