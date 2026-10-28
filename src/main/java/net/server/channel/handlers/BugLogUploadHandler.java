package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.reports.BugClientLogs;
import server.reports.ReportStore;

import java.util.Map;

/**
 * CP 0x373B BUG_LOG_UPLOAD (ticket 131): the client's answer to LP 0x373A after an @bug. Only a client whose build
 * stamp has caps bit 1 is ever asked, and only an unused, fresh nonce is accepted ({@link BugClientLogs}); the text is
 * scrubbed again and merged into the report's context file under "clientLogs". No reply.
 */
public final class BugLogUploadHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(BugLogUploadHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        handle(p, c, ReportStore.getInstance(), BugClientLogs.get(), System.currentTimeMillis());
    }

    static void handle(InPacket p, Client c, ReportStore store, BugClientLogs logs, long now) {
        Character chr = c.getPlayer();
        if (chr == null || chr.isBot() || !BugClientLogs.supports(c)) {
            return;
        }
        BugClientLogs.Upload u = BugClientLogs.parse(p);
        Map.Entry<BugClientLogs.Verdict, String> v = logs.accept(c, chr.getId(), u, now);
        if (v.getKey() != BugClientLogs.Verdict.OK) {
            log.info("Bug log upload from {} refused: {} {}", chr.getName(), v.getKey(), v.getValue());
            return;
        }
        Map<String, Object> merged = BugClientLogs.toContext(u, c.getAccountName(), now);
        store.updateContext(v.getValue(), ctx -> {
            ctx.put("clientLogs", merged);
            return ctx;
        });
    }
}
