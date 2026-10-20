import net.encryption.InitializationVector;
import net.encryption.MapleAESOFB;
import net.encryption.MapleCustomEncryption;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ticket 48 "player" load generator: headless v83 clients that behave like friends on the server.
 * Builds on the ticket 43 HeadlessClient (crypto from the server jar, so it runs with Cosmic.jar on the classpath).
 *
 * Modes:
 *   create <host> <loginPort> <world> <n> <outdir>    log in soak_NN (auto-registered), make character soakNN if missing
 *   run    <host> <loginPort> <world> <n> <outdir> <wzDir> <seconds> [whisperEverySec=20]
 *
 * In run mode each client logs in (timed: connect -> SET_FIELD, then the map-entry burst), and then, until the end,
 * at random: says a line in map chat and times its own echo (map-lag proxy), walks through a portal next to it and
 * times the warp (ping-pongs between its home town and a neighbour map, never moves otherwise: fake movement would
 * teach the bots' nav learning bogus edges), changes channel (timed), logs out and in again (timed), and - shared by
 * all clients, at most one every whisperEverySec - whispers a bot on its map and times the bot's whispered reply
 * (the real LLM path). PINGs are answered. Every minute each client writes what it received (packets, bytes, bot
 * movement packets, players on screen).
 *
 * The client count follows <outdir>/loadgen.clients when that file holds a number (the live guard writes it to scale
 * the load down); clients above it log out and wait.
 *
 * Output: <outdir>/events.tsv (utc_ms, client, event, ms, ok, detail) and <outdir>/minute.tsv.
 */
public class LoadGen {
    static final short VERSION = 83;
    static final int OP_LOGIN_STATUS = 0x00, OP_CHARLIST = 0x0B, OP_SERVER_IP = 0x0C, OP_CHAR_NAME = 0x0D,
            OP_NEW_CHAR = 0x0E, OP_CHANGE_CHANNEL = 0x10, OP_PING = 0x11, OP_SET_FIELD = 0x7D, OP_WHISPER = 0x87,
            OP_SPAWN_PLAYER = 0xA0, OP_REMOVE_PLAYER = 0xA1, OP_CHATTEXT = 0xA2, OP_MOVE_PLAYER = 0xB9;
    static final String[] CHAT = {"hi", "anyone selling pots?", "lol", "brb", "this town is busy", "nice",
            "where do i train at 30", "gl everyone", "hello", "ty", "anyone want to party", "omg so many people"};
    static final String[] WHISPERS = {"hey whats up", "where are you training?", "how's it going",
            "do you know a good place to level", "hi! what are you up to", "any tips for a new player?"};

    static String host;
    static int loginPort, world;
    static Path out;
    static BufferedWriter events, minutes;
    static final AtomicLong lastWhisper = new AtomicLong();
    static long whisperEveryMs = 20_000;
    static volatile int allowed = Integer.MAX_VALUE;
    /** -Dsoak.timescale=0.1 runs every timer 10x faster (for a functional check). */
    static final double SCALE = Double.parseDouble(System.getProperty("soak.timescale", "1"));
    static long t(long ms) { return (long) (ms * SCALE); }
    static Path wz;
    static final Map<Integer, List<Portal>> portalCache = new ConcurrentHashMap<>();

    record Portal(int index, String name, int x, int y, int pt, int tm, String tn, boolean scripted) {}

    // ── connection ──────────────────────────────────────────────────────────────────────────────

    static final class Conn implements AutoCloseable {
        final Socket s; final DataInputStream in; final OutputStream out; final MapleAESOFB send, recv;
        final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
        final Thread reader;
        volatile boolean closed;
        final AtomicLong packets = new AtomicLong(), bytes = new AtomicLong(), moves = new AtomicLong();
        final Map<Integer, String> players = new ConcurrentHashMap<>();
        volatile int myId;

        Conn(String h, int port) throws IOException {
            s = new Socket(); s.connect(new InetSocketAddress(h, port), 10_000); s.setSoTimeout(0); s.setTcpNoDelay(true);
            in = new DataInputStream(s.getInputStream()); out = s.getOutputStream();
            byte[] hello = new byte[16]; in.readFully(hello);
            send = new MapleAESOFB(iv(Arrays.copyOfRange(hello, 7, 11)), VERSION);
            recv = new MapleAESOFB(iv(Arrays.copyOfRange(hello, 11, 15)), (short) (0xFFFF - VERSION));
            reader = Thread.ofPlatform().daemon().start(this::readLoop);
        }

        static InitializationVector iv(byte[] b) throws IOException {
            try {
                var c = InitializationVector.class.getDeclaredConstructor(byte[].class); c.setAccessible(true);
                return c.newInstance((Object) b);
            } catch (ReflectiveOperationException e) { throw new IOException(e); }
        }

        synchronized void sendPacket(byte[] data) throws IOException {
            byte[] header = send.getPacketHeader(data.length);
            byte[] body = data.clone();
            MapleCustomEncryption.encryptData(body); send.crypt(body);
            out.write(header); out.write(body); out.flush();
        }

        private void readLoop() {
            try {
                while (!closed) {
                    byte[] h = new byte[4]; in.readFully(h);
                    int len = (((h[1] ^ h[3]) & 0xFF) << 8) | ((h[0] ^ h[2]) & 0xFF);
                    byte[] body = new byte[len]; in.readFully(body);
                    recv.crypt(body); MapleCustomEncryption.decryptData(body);
                    packets.incrementAndGet(); bytes.addAndGet(len + 4);
                    int op = op(body);
                    if (op == OP_PING) { sendPacket(new W().s(0x18).get()); continue; }
                    if (op == OP_MOVE_PLAYER) { moves.incrementAndGet(); continue; }
                    if (op == OP_SPAWN_PLAYER) {
                        try {
                            ByteBuffer bb = le(body); bb.position(2);
                            int id = bb.getInt(); bb.get(); players.put(id, str(bb));
                        } catch (RuntimeException ignored) { }
                    } else if (op == OP_REMOVE_PLAYER && body.length >= 6) {
                        players.remove(le(body).getInt(2));
                    } else if (op == OP_SET_FIELD) {
                        players.clear();
                    }
                    q.offer(body);
                }
            } catch (IOException e) {
                closed = true; q.offer(new byte[]{(byte) 0xFF, (byte) 0xFF});
            }
        }

        /** Waits for the first packet with this opcode that passes the test; null on timeout or a closed socket. */
        byte[] await(int opcode, long timeoutMs, Predicate<byte[]> test) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return null;
                byte[] p = q.poll(left, TimeUnit.NANOSECONDS);
                if (p == null) return null;
                if (op(p) == 0xFFFF && closed) return null;
                if (op(p) == opcode && (test == null || test.test(p))) return p;
            }
        }

        /** Packets keep coming after a map entry (players, NPCs, mobs, drops): wait for a quiet gap. */
        int drainBurst(long gapMs, long capMs) throws InterruptedException {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(capMs); int n = 0;
            while (System.nanoTime() < end) {
                byte[] p = q.poll(gapMs, TimeUnit.MILLISECONDS);
                if (p == null) break; n++;
            }
            return n;
        }

        @Override public void close() { closed = true; try { s.close(); } catch (IOException ignored) { } }
    }

    static int op(byte[] p) { return p.length < 2 ? -1 : (p[0] & 0xFF) | ((p[1] & 0xFF) << 8); }
    static ByteBuffer le(byte[] p) { return ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN); }
    static String str(ByteBuffer bb) { int n = bb.getShort() & 0xFFFF; byte[] d = new byte[n]; bb.get(d); return new String(d, StandardCharsets.ISO_8859_1); }

    static final class W {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        W s(int v) { b.write(v); b.write(v >> 8); return this; }
        W i(int v) { s(v); s(v >> 16); return this; }
        W by(int v) { b.write(v); return this; }
        W str(String x) { byte[] d = x.getBytes(StandardCharsets.US_ASCII); s(d.length); b.writeBytes(d); return this; }
        W raw(byte[] d) { b.writeBytes(d); return this; }
        byte[] get() { return b.toByteArray(); }
    }

    // ── output ──────────────────────────────────────────────────────────────────────────────────

    static synchronized void event(String client, String what, double ms, boolean ok, String detail) {
        try {
            events.write(System.currentTimeMillis() + "\t" + client + "\t" + what + "\t" + String.format("%.1f", ms) + "\t"
                    + (ok ? 1 : 0) + "\t" + detail.replace('\t', ' ').replace('\n', ' ') + "\n");
            events.flush();
        } catch (IOException ignored) { }
    }

    static synchronized void minute(String line) {
        try { minutes.write(line + "\n"); minutes.flush(); } catch (IOException ignored) { }
    }

    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    // ── one player ──────────────────────────────────────────────────────────────────────────────

    static final class Player {
        final int n; final String account, charName, tag; final Random rnd;
        int charId, mapId, spawn, channel; Conn ch;
        volatile String stage = "";
        long nextChat, nextWarp, nextCc, nextRelog, nextMinute;
        long pk0, by0, mv0;

        Player(int n) {
            this.n = n; account = String.format("soak_%02d", n); charName = String.format("soak%02d", n);
            tag = account; rnd = new Random(n * 7919L + System.nanoTime());
        }

        /** Login server part: password, (gender), charlist; returns the char id or -1 and leaves the login conn open. */
        Conn loginServer(boolean createIfMissing) throws Exception {
            stage = "login-connect";
            Conn c = new Conn(host, loginPort);
            stage = "login-password";
            c.sendPacket(new W().s(0x01).str(account).str("soakpass48").raw(new byte[6]).raw(new byte[]{(byte) 0xDE, (byte) 0xAD, 0x48, (byte) n}).get());
            byte[] st = c.await(OP_LOGIN_STATUS, 15_000, null);
            if (st == null) throw new IOException("no LOGIN_STATUS");
            if (st[2] == 23) {     // new account: accept the terms of service
                c.sendPacket(new W().s(0x07).by(1).get());
                st = c.await(OP_LOGIN_STATUS, 15_000, null);
                if (st == null) throw new IOException("no LOGIN_STATUS after ToS");
            }
            if (st[2] != 0) throw new IOException("login refused, reason " + st[2]);
            if (st.length > 12 && st[12] == 10) {     // new account: pick a gender first
                c.sendPacket(new W().s(0x08).by(1).by(0).get());
                if (c.await(OP_LOGIN_STATUS, 15_000, null) == null) throw new IOException("no LOGIN_STATUS after gender");
            }
            stage = "charlist";
            c.sendPacket(new W().s(0x05).by(2).by(world).by(channel).get());
            byte[] list = c.await(OP_CHARLIST, 15_000, null);
            if (list == null) throw new IOException("no CHARLIST");
            int count = list[3] & 0xFF;
            charId = count > 0 ? le(list).getInt(4) : -1;
            if (charId < 0 && createIfMissing) {
                c.sendPacket(new W().s(0x15).str(charName).get());
                byte[] r = c.await(OP_CHAR_NAME, 10_000, null);
                if (r == null || r[r.length - 1] != 0) throw new IOException("name " + charName + " not available");
                c.sendPacket(new W().s(0x16).str(charName).i(1).i(20000).i(30000).i(0).i(0)
                        .i(1040002).i(1060002).i(1072001).i(1302000).by(0).get());
                byte[] made = c.await(OP_NEW_CHAR, 15_000, null);
                if (made == null || made[2] != 0) throw new IOException("character not created");
                charId = le(made).getInt(3);
            }
            return c;
        }

        void login() throws Exception {
            channel = rnd.nextInt(4) == 0 ? rnd.nextInt(8) : 0;   // bots live on channel 1, so friends mostly go there
            long t0 = System.nanoTime();
            try (Conn c = loginServer(false)) {
                if (charId < 0) throw new IOException("account has no character (run create first)");
                stage = "char-select";
                c.sendPacket(new W().s(0x13).i(charId).str("00-11-22-33-44-48").str("0011223344AA_DEAD48" + String.format("%02X", n)).get());
                byte[] ip = c.await(OP_SERVER_IP, 15_000, null);
                if (ip == null) throw new IOException("no SERVER_IP");
                ByteBuffer bb = le(ip); bb.position(4);
                String chHost = (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF);
                int chPort = bb.getShort() & 0xFFFF;
                double tLoginSrv = ms(t0);
                enterChannel(chHost, chPort, t0, "login", String.format("login_server_ms=%.0f", tLoginSrv));
            }
        }

        void enterChannel(String h, int port, long t0, String what, String extra) throws Exception {
            if (ch != null) ch.close();
            stage = what + "-channel-connect";
            ch = new Conn(h, port); ch.myId = charId;
            stage = what + "-enter";
            ch.sendPacket(new W().s(0x14).i(charId).raw(new byte[18]).get());
            byte[] f = ch.await(OP_SET_FIELD, 30_000, null);
            if (f == null) throw new IOException(what + ": no SET_FIELD");
            double tField = ms(t0);
            ch.sendPacket(new W().s(0xCF).get());   // map loaded (the real client says so; the server blocks portals until then)
            int burst = ch.drainBurst(400, 15_000);
            double tSettled = ms(t0);
            event(tag, what, tField, true, String.format("ch=%d map=%d settled_ms=%.0f burst_packets=%d players_on_screen=%d %s",
                    channel + 1, mapId, tSettled, burst, ch.players.size(), extra));
            pk0 = ch.packets.get(); by0 = ch.bytes.get(); mv0 = ch.moves.get();
            stage = "in-game";
        }

        void logout() {
            if (ch != null) { ch.close(); ch = null; }
        }

        void chat() throws Exception {
            stage = "chat";
            String line = CHAT[rnd.nextInt(CHAT.length)];
            ch.q.clear();
            long t0 = System.nanoTime();
            ch.sendPacket(new W().s(0x31).str(line).by(0).get());
            byte[] echo = ch.await(OP_CHATTEXT, 10_000, p -> le(p).getInt(2) == charId);
            event(tag, "chat_echo", ms(t0), echo != null, "map=" + mapId + " players_on_screen=" + ch.players.size());
        }

        void warp() throws Exception {
            stage = "warp";
            List<Portal> here = portals(mapId);
            Portal at = here.stream().filter(p -> p.index == spawn).findFirst().orElse(null);
            if (at == null) { nextWarp += 300_000; return; }
            int home = home();
            List<Portal> near = new ArrayList<>();
            for (Portal p : here) {
                if (p.tm == 999999999 || p.tm == mapId || p.scripted || p.pt == 0 || p.tn.isEmpty()) continue;
                if ((long) (p.x - at.x) * (p.x - at.x) + (long) (p.y - at.y) * (p.y - at.y) > 360_000) continue;
                if (mapId != home && p.tm != home) continue;   // away from home: only the way back
                if (mapId == home && portals(p.tm).isEmpty()) continue;
                near.add(p);
            }
            if (near.isEmpty()) { event(tag, "warp_skip", 0, false, "no portal near spawn " + spawn + " on " + mapId); nextWarp += 600_000; return; }
            Portal go = near.get(rnd.nextInt(near.size()));
            ch.q.clear();
            long t0 = System.nanoTime();
            ch.sendPacket(new W().s(0x26).by(0).i(-1).str(go.name).by(0).by(0).by(0).get());
            byte[] f = ch.await(OP_SET_FIELD, 15_000, null);
            if (f == null) { event(tag, "warp", ms(t0), false, mapId + " via " + go.name + " -> " + go.tm + " (no SET_FIELD)"); return; }
            double tField = ms(t0);
            ch.sendPacket(new W().s(0xCF).get());
            int to = le(f).getInt(11), sp = f[15] & 0xFF;
            int burst = ch.drainBurst(400, 15_000);
            event(tag, "warp", tField, to == go.tm, String.format("%d->%d settled_ms=%.0f burst_packets=%d players_on_screen=%d",
                    mapId, to, ms(t0), burst, ch.players.size()));
            mapId = to; spawn = sp;
        }

        void changeChannel() throws Exception {
            stage = "cc";
            int to = channel == 0 ? 1 + rnd.nextInt(7) : 0;   // out to a quiet channel and back to channel 1
            ch.q.clear();
            long t0 = System.nanoTime();
            ch.sendPacket(new W().s(0x27).by(to).i((int) System.currentTimeMillis()).get());
            byte[] cc = ch.await(OP_CHANGE_CHANNEL, 15_000, null);
            if (cc == null) { event(tag, "cc", ms(t0), false, "no CHANGE_CHANNEL"); return; }
            ByteBuffer bb = le(cc); bb.position(3);
            String h = (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF);
            int port = bb.getShort() & 0xFFFF;
            int from = channel; channel = to;
            enterChannel(h, port, t0, "cc", "from_ch=" + (from + 1));
        }

        void whisper() throws Exception {
            List<String> bots = new ArrayList<>();
            for (Map.Entry<Integer, String> e : ch.players.entrySet()) {
                if (e.getKey() != charId && !e.getValue().toLowerCase().startsWith("soak")) bots.add(e.getValue());
            }
            if (bots.isEmpty()) return;
            long now = System.currentTimeMillis(), last = lastWhisper.get();
            if (now - last < whisperEveryMs || !lastWhisper.compareAndSet(last, now)) return;
            String bot = bots.get(rnd.nextInt(bots.size()));
            ch.q.clear();
            long t0 = System.nanoTime();
            ch.sendPacket(new W().s(0x78).by(0x06).str(bot).str(WHISPERS[rnd.nextInt(WHISPERS.length)]).get());
            byte[] reply = ch.await(OP_WHISPER, 90_000, p -> {
                if (p[2] != 0x12) return false;
                ByteBuffer bb = le(p); bb.position(3); return str(bb).equalsIgnoreCase(bot);
            });
            String text = "";
            if (reply != null) { ByteBuffer bb = le(reply); bb.position(3); str(bb); bb.get(); bb.get(); text = str(bb); }
            event(tag, "whisper_reply", ms(t0), reply != null, "bot=" + bot + " map=" + mapId + " reply=" + text);
        }

        int home() { return HOMES[(n - 1) % HOMES.length][0]; }

        void schedule(long now) {
            nextChat = now + t(20_000 + rnd.nextInt(40_000));
            nextWarp = now + t(45_000 + rnd.nextInt(90_000));
            nextCc = now + t(240_000 + rnd.nextInt(360_000));
            nextRelog = now + t(900_000 + rnd.nextInt(900_000));
            nextMinute = now + 60_000;
        }

        void minuteLine(long now) {
            if (ch == null) return;
            long pk = ch.packets.get(), by = ch.bytes.get(), mv = ch.moves.get();
            minute(now + "\t" + tag + "\t" + (channel + 1) + "\t" + mapId + "\t" + ch.players.size() + "\t" + (pk - pk0) + "\t"
                    + (by - by0) + "\t" + (mv - mv0));
            pk0 = pk; by0 = by; mv0 = mv;
        }

        void run(long endAt) {
            mapId = home(); spawn = HOMES[(n - 1) % HOMES.length][1];
            while (System.currentTimeMillis() < endAt) {
                try {
                    if (n > allowed) { logout(); Thread.sleep(10_000); continue; }
                    if (ch == null || ch.closed) {
                        if (ch != null) event(tag, "disconnected", 0, false, "map=" + mapId);
                        Thread.sleep(rnd.nextInt(5_000));
                        login(); schedule(System.currentTimeMillis());
                    }
                    long now = System.currentTimeMillis();
                    if (now >= nextMinute) { minuteLine(now); nextMinute = now + 60_000; }
                    if (now >= nextRelog) {
                        logout(); Thread.sleep(3_000 + rnd.nextInt(10_000));
                        login(); schedule(System.currentTimeMillis()); continue;
                    }
                    if (now >= nextCc) { changeChannel(); nextCc = now + t(240_000 + rnd.nextInt(360_000)); continue; }
                    if (now >= nextWarp) {
                        warp();
                        // away from home (hunting maps have mobs this idle client would control): go back soon
                        nextWarp = now + (mapId == home() ? t(45_000 + rnd.nextInt(90_000)) : t(5_000 + rnd.nextInt(5_000)));
                        continue;
                    }
                    if (now >= nextChat) { chat(); nextChat = now + t(20_000 + rnd.nextInt(40_000)); continue; }
                    whisper();
                    ch.q.clear();
                    Thread.sleep(1_000);
                } catch (Exception e) {
                    event(tag, "error", 0, false, "stage=" + stage + " " + e);
                    logout();
                    try { Thread.sleep(15_000); } catch (InterruptedException ie) { return; }
                }
            }
            logout();
        }
    }

    // home town, spawn portal index (one with a way out within reach: the portal check is 632 px)
    static int[][] HOMES;

    static List<Portal> portals(int mapId) {
        return portalCache.computeIfAbsent(mapId, id -> {
            List<Portal> list = new ArrayList<>();
            Path f = wz.resolve(String.format("Map.wz/Map/Map%d/%09d.img.xml", id / 100_000_000, id));
            if (!Files.exists(f)) return list;
            try {
                String xml = Files.readString(f);
                int a = xml.indexOf("<imgdir name=\"portal\">");
                if (a < 0) return list;
                Matcher m = Pattern.compile("<imgdir name=\"(\\d+)\">(.*?)</imgdir>", Pattern.DOTALL).matcher(xml);
                m.region(a, xml.length());
                while (m.find()) {
                    String body = m.group(2);
                    String pn = attr(body, "string", "pn");
                    if (pn == null) break;
                    list.add(new Portal(Integer.parseInt(m.group(1)), pn, iattr(body, "x"), iattr(body, "y"), iattr(body, "pt"),
                            iattr(body, "tm"), nz(attr(body, "string", "tn")), nz(attr(body, "string", "script")).length() > 0));
                }
            } catch (IOException | RuntimeException e) { list.clear(); }
            return list;
        });
    }

    static String nz(String s) { return s == null ? "" : s; }
    static String attr(String body, String type, String name) {
        Matcher m = Pattern.compile("<" + type + " name=\"" + name + "\" value=\"([^\"]*)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }
    static int iattr(String body, String name) { String v = attr(body, "int", name); return v == null ? 0 : Integer.parseInt(v); }

    /** For each busy town, the spawn point with the most exits within reach. */
    static int[][] homes() {
        int[] towns = {100000000, 910000000, 101000000, 102000000, 103000000, 104000000, 200000000, 220000000, 105040300, 100000100};
        List<int[]> out = new ArrayList<>();
        for (int t : towns) {
            List<Portal> ps = portals(t);
            int best = -1, bestN = 0;
            for (Portal s : ps) {
                if (s.pt != 0) continue;
                int k = 0;
                for (Portal p : ps) {
                    if (p.tm == 999999999 || p.scripted || p.pt == 0 || p.tn.isEmpty() || portals(p.tm).isEmpty()) continue;
                    if ((long) (p.x - s.x) * (p.x - s.x) + (long) (p.y - s.y) * (p.y - s.y) <= 360_000) k++;
                }
                if (k > bestN) { bestN = k; best = s.index; }
            }
            if (best < 0) best = 0;
            out.add(new int[]{t, best, bestN});
        }
        return out.toArray(new int[0][]);
    }

    // ── main ────────────────────────────────────────────────────────────────────────────────────

    public static void main(String[] a) throws Exception {
        String mode = a[0]; host = a[1]; loginPort = Integer.parseInt(a[2]); world = Integer.parseInt(a[3]);
        int count = Integer.parseInt(a[4]); out = Path.of(a[5]); Files.createDirectories(out);
        events = Files.newBufferedWriter(out.resolve("events.tsv"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        minutes = Files.newBufferedWriter(out.resolve("minute.tsv"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (mode.equals("homes")) {
            wz = Path.of(a[6]);
            for (int[] h : homes()) System.out.println(h[0] + " spawn=" + h[1] + " exits=" + h[2]);
            return;
        }
        if (mode.equals("create")) {
            for (int i = 1; i <= count; i++) {
                Player p = new Player(i);
                long t0 = System.nanoTime();
                try (Conn c = p.loginServer(true)) {
                    System.out.println(p.account + " " + p.charName + " id=" + p.charId);
                    event(p.tag, "create", ms(t0), p.charId > 0, "char=" + p.charName + " id=" + p.charId);
                } catch (Exception e) {
                    System.out.println(p.account + " FAILED " + e);
                }
                Thread.sleep(500);
            }
            return;
        }
        wz = Path.of(a[6]);
        long seconds = Long.parseLong(a[7]);
        if (a.length > 8) whisperEveryMs = Long.parseLong(a[8]) * 1000;
        HOMES = homes();
        for (int[] h : HOMES) System.out.println("home " + h[0] + " spawn=" + h[1] + " exits=" + h[2]);
        long endAt = System.currentTimeMillis() + seconds * 1000;
        Path ctl = Path.of(System.getProperty("soak.ctl", out.resolve("loadgen.clients").toString()));
        Thread.ofPlatform().daemon().start(() -> {
            while (true) {
                try {
                    if (Files.exists(ctl)) {
                        String v = Files.readString(ctl).trim();
                        int want = v.matches("\\d+") ? Integer.parseInt(v) : Integer.MAX_VALUE;
                        if (want != allowed) event("loadgen", "clients", want, true, "allowed " + allowed + " -> " + want);
                        allowed = want;
                    }
                    Thread.sleep(10_000);
                } catch (Exception e) { return; }
            }
        });
        List<Thread> threads = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Player p = new Player(i);
            threads.add(Thread.ofPlatform().name(p.tag).start(() -> p.run(endAt)));
            Thread.sleep(3_000);   // stagger logins
        }
        for (Thread t : threads) t.join();
        event("loadgen", "done", 0, true, "");
    }
}
