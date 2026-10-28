package net.server.channel.handlers;

import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.reports.BugClientLogs;
import server.reports.ReportStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CP 0x373B end to end: a filed @bug, the client's answer to the nonce, the logs merged into the context file. */
class BugLogUploadHandlerTest {
    @TempDir Path dir;

    static InPacket upload(int nonce, String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        OutPacket out = new ByteBufOutPacket();
        out.writeShort(1);
        out.writeInt(nonce);
        out.writeInt(b.length);
        out.writeByte(0);
        out.writeInt(b.length);
        out.writeBytes(b);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void mergesScrubbedLogsIntoTheReportOnce() throws Exception {
        ReportStore store = new ReportStore(dir.resolve("reports.jsonl"), Clock.systemUTC());
        ReportStore.Context ctx = new ReportStore.Context("Tester", 42, 1, 1, 100000000, "Henesys", 0, 0, null);
        String ref = store.submit("bug", "the game crashed", ctx, () -> new LinkedHashMap<>(Map.of("schema", 1))).get().ref();

        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(5);
        when(chr.getName()).thenReturn("Tester");
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.getClientStampFormat()).thenReturn(2);
        when(c.getClientCaps()).thenReturn(3);
        when(c.getAccountName()).thenReturn("joshacct");

        BugClientLogs logs = BugClientLogs.get();
        long now = System.currentTimeMillis();
        logs.request(c, chr, ref, now);
        var sent = org.mockito.ArgumentCaptor.forClass(net.packet.Packet.class);
        verify(c).sendPacket(sent.capture());
        InPacket req = new ByteBufInPacket(Unpooled.wrappedBuffer(sent.getValue().getBytes()));
        req.readShort();
        req.readShort();
        int nonce = req.readInt();

        BugLogUploadHandler.handle(upload(nonce, "[crash]\nboom in C:\\Users\\Josh\\x.dll for joshacct"), c, store, logs, now + 1000);
        Map<String, Object> merged = null;
        for (int i = 0; i < 50 && (merged == null || !merged.containsKey("clientLogs")); i++) {
            TimeUnit.MILLISECONDS.sleep(20);
            merged = store.readContext(ref);
        }
        Map<?, ?> cl = (Map<?, ?>) merged.get("clientLogs");
        assertNotNull(cl, "merged: " + merged);
        String crash = (String) ((Map<?, ?>) cl.get("sections")).get("crash");
        assertEquals("boom in C:\\Users\\<user>\\x.dll for <account>", crash);

        // a replay of the same nonce changes nothing
        BugLogUploadHandler.handle(upload(nonce, "[crash]\nreplayed"), c, store, logs, now + 120_000);
        TimeUnit.MILLISECONDS.sleep(200);
        assertEquals(crash, ((Map<?, ?>) ((Map<?, ?>) store.readContext(ref).get("clientLogs")).get("sections")).get("crash"));

        // a client without the caps bit is ignored even with a valid-looking packet
        Client old = mock(Client.class);
        when(old.getPlayer()).thenReturn(chr);
        when(old.getClientStampFormat()).thenReturn(2);
        when(old.getClientCaps()).thenReturn(1);
        BugLogUploadHandler.handle(upload(nonce, "[crash]\nold"), old, store, logs, now + 200_000);
        verify(old, never()).getAccountName();
    }
}
