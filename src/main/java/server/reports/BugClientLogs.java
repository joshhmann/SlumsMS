package server.reports;

import client.Character;
import client.Client;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 131 part 4: after an @bug, a client that says it can (build stamp format 2, caps bit 1) is asked for the tail
 * of its crash and integration logs, which are merged into the report's context file. Old clients never see the
 * opcode. Design and the client's side: docs/tickets/131-bug-reports.md.
 *
 * <pre>
 * LP 0x373A BUG_LOG_REQUEST (server -> client)
 *   short format (1)  int nonce  string ref ("B-12")  int maxBytes (32768)
 * CP 0x373B BUG_LOG_UPLOAD (client -> server)
 *   short format (1)  int nonce  int totalLength (bytes before the client cut)  byte truncated (0/1)
 *   int length  byte[length] UTF-8 text, sections headed "[name]" on their own line
 * </pre>
 *
 * Rules: one pending nonce per character (a new @bug replaces it), valid {@link #NONCE_TTL_MS}, used once; at most
 * one upload a minute per character; at most {@link #MAX_BYTES} bytes (more is refused, not cut); the text is scrubbed
 * again here ({@link BugPrivacy#scrubClientText}) whatever the client did.
 */
public final class BugClientLogs {
    public static final int CAPS_LOG_UPLOAD = 1 << 1;
    public static final int FORMAT = 1;
    public static final int MAX_BYTES = 32 * 1024;
    public static final long NONCE_TTL_MS = 5 * 60_000L;
    public static final long MIN_INTERVAL_MS = 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SECTION = Pattern.compile("^\\[([A-Za-z0-9_.-]{1,40})]\\s*$");

    record Pending(int nonce, String ref, long issuedAt) {
    }

    /** What the client sent, before any check. */
    public record Upload(int format, int nonce, int totalLength, boolean truncated, byte[] data) {
    }

    public enum Verdict { OK, NO_CAPS, MALFORMED, UNKNOWN_NONCE, EXPIRED, TOO_SOON, TOO_BIG }

    private static final BugClientLogs INSTANCE = new BugClientLogs();

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Integer, Long> lastUpload = new ConcurrentHashMap<>();

    public static BugClientLogs get() {
        return INSTANCE;
    }

    /** True when this session's client said it can upload logs (stamp format 2+, caps bit 1). */
    public static boolean supports(Client c) {
        return c != null && c.getClientStampFormat() >= 2 && (c.getClientCaps() & CAPS_LOG_UPLOAD) != 0;
    }

    /** After a filed @bug: asks a supporting client for its logs. Does nothing for other clients and for bots. */
    public void request(Client c, Character chr, String ref, long now) {
        if (!supports(c) || chr == null || chr.isBot() || ref == null) {
            return;
        }
        c.sendPacket(requestPacket(issue(chr.getId(), ref, now), ref));
    }

    int issue(int charId, String ref, long now) {
        pending.values().removeIf(p -> now - p.issuedAt() > NONCE_TTL_MS);
        lastUpload.values().removeIf(t -> now - t > MIN_INTERVAL_MS);
        int nonce;
        do {
            nonce = RANDOM.nextInt();
        } while (nonce == 0);
        pending.put(charId, new Pending(nonce, ref, now));
        return nonce;
    }

    public static Packet requestPacket(int nonce, String ref) {
        OutPacket p = OutPacket.create(SendOpcode.BUG_LOG_REQUEST);
        p.writeShort(FORMAT);
        p.writeInt(nonce);
        p.writeString(ref);
        p.writeInt(MAX_BYTES);
        return p;
    }

    /** Reads CP 0x373B; null when the packet is short, an unknown format or claims more bytes than it has. */
    public static Upload parse(InPacket p) {
        if (p.available() < 2 + 4 + 4 + 1 + 4) {
            return null;
        }
        int format = p.readShort();
        if (format != FORMAT) {
            return null;
        }
        int nonce = p.readInt();
        int total = p.readInt();
        boolean truncated = p.readByte() != 0;
        int len = p.readInt();
        if (len < 0 || len > MAX_BYTES || len > p.available() || total < 0) {
            return len > MAX_BYTES ? new Upload(format, nonce, total, truncated, null) : null;
        }
        return new Upload(format, nonce, total, truncated, p.readBytes(len));
    }

    /** Checks an upload against the pending nonce and the rate; on OK consumes the nonce and returns its ref. */
    public synchronized Map.Entry<Verdict, String> accept(Client c, int charId, Upload u, long now) {
        if (!supports(c)) {
            return Map.entry(Verdict.NO_CAPS, "");
        }
        if (u == null) {
            return Map.entry(Verdict.MALFORMED, "");
        }
        Pending p = pending.get(charId);
        if (p == null || p.nonce() != u.nonce()) {
            return Map.entry(Verdict.UNKNOWN_NONCE, "");
        }
        if (now - p.issuedAt() > NONCE_TTL_MS) {
            pending.remove(charId);
            return Map.entry(Verdict.EXPIRED, p.ref());
        }
        Long last = lastUpload.get(charId);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return Map.entry(Verdict.TOO_SOON, p.ref());
        }
        pending.remove(charId); // used once, whatever follows
        if (u.data() == null) {
            return Map.entry(Verdict.TOO_BIG, p.ref());
        }
        lastUpload.put(charId, now);
        return Map.entry(Verdict.OK, p.ref());
    }

    /** The "clientLogs" object merged into the context: scrubbed text, its sections, sizes. */
    public static Map<String, Object> toContext(Upload u, String accountName, long now) {
        String raw = new String(u.data(), StandardCharsets.UTF_8);
        String text = BugPrivacy.scrubClientText(raw, accountName);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean cut = false;
        if (bytes.length > MAX_BYTES) { // redaction can grow a line ("<user>"), so cap again
            text = new String(bytes, bytes.length - MAX_BYTES, MAX_BYTES, StandardCharsets.UTF_8);
            cut = true;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receivedAt", Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("format", u.format());
        m.put("totalLength", u.totalLength());
        m.put("truncated", u.truncated() || cut);
        m.put("bytes", text.getBytes(StandardCharsets.UTF_8).length);
        m.put("sections", sections(text));
        return m;
    }

    /** Splits the text at "[name]" header lines; text before the first header goes under "text". */
    static Map<String, Object> sections(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = "text";
        List<String> cur = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher m = SECTION.matcher(line);
            if (m.matches()) {
                put(out, name, cur);
                name = m.group(1);
                cur = new ArrayList<>();
            } else {
                cur.add(line);
            }
        }
        put(out, name, cur);
        return out;
    }

    private static void put(Map<String, Object> out, String name, List<String> lines) {
        String body = String.join("\n", lines).strip();
        if (!body.isEmpty()) {
            out.merge(name, body, (a, b) -> a + "\n" + b);
        }
    }

    /** Tests: forget everything. */
    void clear() {
        pending.clear();
        lastUpload.clear();
    }
}
