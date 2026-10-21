import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Release rehearsal probe: log real characters into a server copy end to end (login, char select, channel enter,
 * SET_FIELD) with the headless v83 client from LoadGen.java. Proves a release loads the players' characters.
 * Only for a scratch copy whose accounts were given a known plaintext password (the server accepts and re-hashes it).
 *
 *   java -cp bin:Cosmic.jar CharLoadProbe <host> <loginPort> <world> <password> <account:charId:expectedMap> ...
 *
 * The channel is reached on <host> at the port SERVER_IP names (a copy of live advertises live's WAN address, which
 * does not hairpin from the LAN). Prints one line per character: OK/FAIL, account, char id, the map in SET_FIELD and the time to it. Exit 0 = all OK.
 */
public class CharLoadProbe {
    public static void main(String[] a) throws Exception {
        if (a.length < 5) { System.err.println("usage: CharLoadProbe <host> <loginPort> <world> <password> <account:charId:map> ..."); System.exit(2); }
        String host = a[0]; int loginPort = Integer.parseInt(a[1]); int world = Integer.parseInt(a[2]); String pwd = a[3];
        int fails = 0;
        for (int i = 4; i < a.length; i++) {
            String[] t = a[i].split(":");
            String account = t[0]; int charId = Integer.parseInt(t[1]); int wantMap = Integer.parseInt(t[2]);
            String r = "";
            boolean ok = false;
            for (int attempt = 1; attempt <= 6 && !ok; attempt++) {
                try {
                    r = probe(host, loginPort, world, pwd, account, charId, wantMap, i);
                    ok = r.startsWith("OK");
                } catch (Exception e) {
                    r = "FAIL " + e.getMessage();
                }
                if (!ok && r.contains("reason 7")) Thread.sleep(5_000);   // still logged in from the previous char
                else if (!ok) break;
            }
            System.out.println(r.replaceFirst("^(OK|FAIL)", "$1 " + account + " char " + charId));
            if (!ok) fails++;
            Thread.sleep(3_000);   // let the logout save before the same account logs in again
        }
        System.out.println(fails == 0 ? "ALL OK" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static String probe(String host, int loginPort, int world, String pwd, String account, int charId, int wantMap, int n)
            throws Exception {
        long t0 = System.nanoTime();
        String chHost; int chPort;
        try (LoadGen.Conn c = new LoadGen.Conn(host, loginPort)) {
            c.sendPacket(new LoadGen.W().s(0x01).str(account).str(pwd).raw(new byte[6])
                    .raw(new byte[]{(byte) 0xDE, (byte) 0xAD, 0x14, (byte) n}).get());
            byte[] st = c.await(LoadGen.OP_LOGIN_STATUS, 15_000, null);
            if (st == null) throw new IOException("no LOGIN_STATUS");
            if (st[2] == 23) {     // terms of service never accepted (e.g. the bot template's account): accept them
                c.sendPacket(new LoadGen.W().s(0x07).by(1).get());
                st = c.await(LoadGen.OP_LOGIN_STATUS, 15_000, null);
                if (st == null) throw new IOException("no LOGIN_STATUS after ToS");
            }
            if (st[2] != 0) throw new IOException("login refused, reason " + st[2]);
            c.sendPacket(new LoadGen.W().s(0x05).by(2).by(world).by(0).get());
            byte[] list = c.await(LoadGen.OP_CHARLIST, 15_000, null);
            if (list == null) throw new IOException("no CHARLIST");
            int count = list[3] & 0xFF;
            c.sendPacket(new LoadGen.W().s(0x13).i(charId).str("00-11-22-33-44-14")
                    .str("0011223344AA_DEAD14" + String.format("%02X", n)).get());
            byte[] ip = c.await(LoadGen.OP_SERVER_IP, 15_000, null);
            if (ip == null) throw new IOException("no SERVER_IP (charlist had " + count + ")");
            if (ip.length > 3 && ip[2] != 0) throw new IOException("char select refused, code " + ip[2]);
            ByteBuffer bb = LoadGen.le(ip); bb.position(4);
            String advertised = (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF) + "." + (bb.get() & 0xFF);
            chPort = bb.getShort() & 0xFFFF;
            chHost = host;   // not `advertised`
            if (advertised.isEmpty()) throw new IOException("bad SERVER_IP");
        }
        try (LoadGen.Conn ch = new LoadGen.Conn(chHost, chPort)) {
            ch.myId = charId;
            ch.sendPacket(new LoadGen.W().s(0x14).i(charId).raw(new byte[18]).get());
            byte[] f = ch.await(LoadGen.OP_SET_FIELD, 30_000, null);
            if (f == null) throw new IOException("no SET_FIELD on " + chHost + ":" + chPort);
            double ms = (System.nanoTime() - t0) / 1e6;
            ch.sendPacket(new LoadGen.W().s(0xCF).get());
            ch.drainBurst(400, 5_000);
            return String.format("OK channel %d, SET_FIELD after %.0f ms (expected map %d)", chPort, ms, wantMap);
        }
    }
}
