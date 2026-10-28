package server.reports;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BugClientLogsTest {

    static Client client(int format, int caps) {
        Client c = mock(Client.class);
        when(c.getClientStampFormat()).thenReturn(format);
        when(c.getClientCaps()).thenReturn(caps);
        return c;
    }

    static InPacket upload(int format, int nonce, int total, boolean truncated, byte[] data, int claimedLength) {
        OutPacket out = new ByteBufOutPacket();
        out.writeShort(format);
        out.writeInt(nonce);
        out.writeInt(total);
        out.writeByte(truncated ? 1 : 0);
        out.writeInt(claimedLength);
        out.writeBytes(data);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    static InPacket upload(int nonce, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return upload(1, nonce, b.length, false, b, b.length);
    }

    @Test
    void onlyClientsWithCapsBit1AreAsked() {
        assertFalse(BugClientLogs.supports(client(1, 2)), "format 1 has no caps");
        assertFalse(BugClientLogs.supports(client(2, 1)), "custom NPC data only");
        assertTrue(BugClientLogs.supports(client(2, 3)));
        assertFalse(BugClientLogs.supports(null));

        BugClientLogs logs = new BugClientLogs();
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(5);
        Client old = client(2, 1);
        logs.request(old, chr, "B-1", 0);
        verify(old, never()).sendPacket(any(Packet.class));
        Client bot = client(2, 2);
        Character botChr = mock(Character.class);
        when(botChr.isBot()).thenReturn(true);
        logs.request(bot, botChr, "B-1", 0);
        verify(bot, never()).sendPacket(any(Packet.class));
        Client neu = client(2, 2);
        logs.request(neu, chr, "B-1", 0);
        verify(neu).sendPacket(any(Packet.class));
    }

    @Test
    void requestPacketLayout() {
        Packet p = BugClientLogs.requestPacket(0x12345678, "B-42");
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        assertEquals(SendOpcode.BUG_LOG_REQUEST.getValue(), in.readShort() & 0xFFFF);
        assertEquals(0x373A, SendOpcode.BUG_LOG_REQUEST.getValue());
        assertEquals(1, in.readShort());
        assertEquals(0x12345678, in.readInt());
        assertEquals("B-42", in.readString());
        assertEquals(32 * 1024, in.readInt());
        assertEquals(0, in.available());
        assertEquals(0x373B, net.opcodes.RecvOpcode.BUG_LOG_UPLOAD.getValue());
    }

    @Test
    void aNonceWorksOnceForItsCharacterWhileFresh() {
        BugClientLogs logs = new BugClientLogs();
        Client c = client(2, 2);
        int nonce = logs.issue(5, "B-7", 1_000);
        assertEquals(BugClientLogs.Verdict.UNKNOWN_NONCE, logs.accept(c, 5, BugClientLogs.parse(upload(nonce + 1, "x")), 2_000).getKey());
        assertEquals(BugClientLogs.Verdict.UNKNOWN_NONCE, logs.accept(c, 6, BugClientLogs.parse(upload(nonce, "x")), 2_000).getKey(),
                "another character cannot use it");
        Map.Entry<BugClientLogs.Verdict, String> ok = logs.accept(c, 5, BugClientLogs.parse(upload(nonce, "x")), 2_000);
        assertEquals(Map.entry(BugClientLogs.Verdict.OK, "B-7"), ok);
        assertEquals(BugClientLogs.Verdict.UNKNOWN_NONCE, logs.accept(c, 5, BugClientLogs.parse(upload(nonce, "x")), 3_000).getKey(),
                "used once");
        assertEquals(BugClientLogs.Verdict.NO_CAPS, logs.accept(client(2, 1), 5, BugClientLogs.parse(upload(nonce, "x")), 3_000).getKey());
    }

    @Test
    void expiryAndOncePerMinute() {
        BugClientLogs logs = new BugClientLogs();
        Client c = client(2, 2);
        int n1 = logs.issue(5, "B-1", 0);
        assertEquals(BugClientLogs.Verdict.EXPIRED,
                logs.accept(c, 5, BugClientLogs.parse(upload(n1, "x")), BugClientLogs.NONCE_TTL_MS + 1).getKey());
        int n2 = logs.issue(5, "B-2", 100_000);
        assertEquals(BugClientLogs.Verdict.OK, logs.accept(c, 5, BugClientLogs.parse(upload(n2, "x")), 100_000).getKey());
        int n3 = logs.issue(5, "B-3", 110_000);
        assertEquals(BugClientLogs.Verdict.TOO_SOON, logs.accept(c, 5, BugClientLogs.parse(upload(n3, "x")), 130_000).getKey());
        assertEquals(BugClientLogs.Verdict.OK, logs.accept(c, 5, BugClientLogs.parse(upload(n3, "x")), 160_001).getKey(),
                "a too-soon answer does not burn the nonce");
    }

    @Test
    void sizeAndShapeChecks() {
        BugClientLogs logs = new BugClientLogs();
        Client c = client(2, 2);
        byte[] big = new byte[BugClientLogs.MAX_BYTES + 1];
        int n = logs.issue(5, "B-1", 0);
        BugClientLogs.Upload tooBig = BugClientLogs.parse(upload(1, n, big.length, false, big, big.length));
        assertNull(tooBig.data());
        assertEquals(BugClientLogs.Verdict.TOO_BIG, logs.accept(c, 5, tooBig, 1).getKey());
        assertEquals(BugClientLogs.Verdict.UNKNOWN_NONCE, logs.accept(c, 5, BugClientLogs.parse(upload(n, "x")), 2).getKey(),
                "an oversized answer uses the nonce up");

        assertNull(BugClientLogs.parse(upload(2, 1, 1, false, new byte[1], 1)), "unknown format");
        assertNull(BugClientLogs.parse(upload(1, 1, 10, false, new byte[3], 10)), "claims more bytes than it has");
        assertNull(BugClientLogs.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{1, 0}))), "short");
        byte[] exact = new byte[BugClientLogs.MAX_BYTES];
        assertEquals(BugClientLogs.MAX_BYTES, BugClientLogs.parse(upload(1, 1, 99_999, true, exact, exact.length)).data().length);
    }

    @Test
    void contextIsScrubbedAndSplitIntoSections() {
        String text = "[build]\nbuild 10700 release 1.6.2 slums.dll sha256 abcdef0123456789abcdef0123456789\n"
                + "[crash]\nException 0xC0000005 at slums.dll+0x1234 in C:\\Users\\Josh\\SlumsMS\\MapleStory.exe\n"
                + "[integration]\nconnect 192.168.0.164:8485 ok\npassword field focused\n[display]\ndgVoodoo D3D11 1280x720";
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        BugClientLogs.Upload u = BugClientLogs.parse(upload(1, 1, 5000, true, b, b.length));
        Map<String, Object> m = BugClientLogs.toContext(u, "acct", 1_793_000_000_000L);
        assertEquals(5000, m.get("totalLength"));
        assertEquals(true, m.get("truncated"));
        Map<?, ?> s = (Map<?, ?>) m.get("sections");
        assertEquals(java.util.List.of("build", "crash", "integration", "display"), new java.util.ArrayList<>(s.keySet()));
        assertTrue(((String) s.get("crash")).contains("C:\\Users\\<user>\\SlumsMS"));
        assertTrue(((String) s.get("integration")).contains("connect <ip> ok"));
        assertFalse(((String) s.get("integration")).contains("password"));
        assertTrue(((String) s.get("build")).contains("sha256 abcdef0123456789abcdef0123456789"));
        assertEquals("dgVoodoo D3D11 1280x720", s.get("display"));
    }
}
