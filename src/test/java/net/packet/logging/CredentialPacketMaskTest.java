package net.packet.logging;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.packet.ByteBufInPacket;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.HexTool;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * b351: the packet loggers (USE_DEBUG_SHOW_PACKET, the monitored-player log) wrote login passwords, PINs and PICs to
 * the log in hex and text. Every opcode that carries one is logged by name and length only.
 */
class CredentialPacketMaskTest {

    static {
        constants.net.OpcodeConstants.generateOpcodeNames(); // the server fills the name table at boot
    }

    private static final String SECRET = "b351secret";

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("b351-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final List<Runnable> undo = new ArrayList<>();

    private Capture attach(Class<?> loggerClass) {
        Logger logger = (Logger) LogManager.getLogger(loggerClass);
        Level level = logger.getLevel();
        Configurator.setLevel(logger.getName(), Level.DEBUG); // the test config turns the root logger off
        Capture capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        undo.add(() -> {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel(logger.getName(), level);
        });
        return capture;
    }

    @AfterEach
    void detach() {
        undo.forEach(Runnable::run);
        undo.clear();
    }

    /** A packet of the given opcode carrying the secret as a maple string, the way the client sends a password or PIC. */
    private static byte[] packet(RecvOpcode op) {
        byte[] s = SECRET.getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[4 + s.length + 4];
        b[0] = (byte) op.getValue();
        b[1] = (byte) (op.getValue() >> 8);
        b[2] = (byte) s.length;
        System.arraycopy(s, 0, b, 4, s.length);
        return b;
    }

    private static void assertNoSecret(List<String> lines) {
        String hex = HexTool.toHexString(SECRET.getBytes(StandardCharsets.US_ASCII));
        for (String l : lines) {
            assertFalse(l.contains(SECRET), "secret text in log: " + l.replace(SECRET, "<SECRET>"));
            assertFalse(l.contains(hex), "secret hex in log");
        }
    }

    @ParameterizedTest
    @EnumSource(value = RecvOpcode.class, names = {"LOGIN_PASSWORD", "AFTER_LOGIN", "REGISTER_PIN", "DELETE_CHAR",
            "REGISTER_PIC", "CHAR_SELECT_WITH_PIC", "VIEW_ALL_PIC_REGISTER", "VIEW_ALL_WITH_PIC"})
    void theDebugLoggerNeverWritesACredential(RecvOpcode op) {
        Capture capture = attach(InPacketLogger.class);

        new InPacketLogger().log(new ByteBufInPacket(Unpooled.wrappedBuffer(packet(op))));

        assertEquals(1, capture.lines.size());
        assertTrue(capture.lines.get(0).contains("ClientSend:" + op.name()), "still names the opcode: " + op);
        assertNoSecret(capture.lines);
    }

    @ParameterizedTest
    @EnumSource(value = RecvOpcode.class, names = {"LOGIN_PASSWORD", "AFTER_LOGIN", "REGISTER_PIN", "DELETE_CHAR",
            "REGISTER_PIC", "CHAR_SELECT_WITH_PIC", "VIEW_ALL_PIC_REGISTER", "VIEW_ALL_WITH_PIC"})
    void theMonitoredPlayerLogNeverWritesACredential(RecvOpcode op) {
        Capture capture = attach(MonitoredChrLogger.class);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(351);
        when(chr.getName()).thenReturn("Watched");
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getAccountName()).thenReturn("acct");
        if (!MonitoredChrLogger.getMonitoredChrIds().contains(351)) {
            MonitoredChrLogger.toggleMonitored(351);
        }
        undo.add(() -> {
            if (MonitoredChrLogger.getMonitoredChrIds().contains(351)) {
                MonitoredChrLogger.toggleMonitored(351);
            }
        });
        byte[] b = packet(op);

        MonitoredChrLogger.logPacketIfMonitored(c, (short) op.getValue(), java.util.Arrays.copyOfRange(b, 2, b.length));

        assertEquals(1, capture.lines.size());
        assertNoSecret(capture.lines);
    }

    @Test
    void ordinaryPacketsAreStillLoggedInFull() {
        Capture capture = attach(InPacketLogger.class);

        new InPacketLogger().log(new ByteBufInPacket(Unpooled.wrappedBuffer(packet(RecvOpcode.GENERAL_CHAT))));

        assertTrue(capture.lines.get(0).contains(SECRET), "a chat line keeps its text");
    }
}
