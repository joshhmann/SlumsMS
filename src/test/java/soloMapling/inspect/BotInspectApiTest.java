package soloMapling.inspect;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.qa.QaRegistryTestAccess;
import soloMapling.server.DebugTools;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: the bot inspect endpoint is loopback-only, secret-gated, GET-only, cached and off on live. */
class BotInspectApiTest {

    private static final String SECRET = "0123456789abcdef-test-only";

    /** Counts its calls; bot 7 and map 100000000 exist. */
    private static final class Fake implements BotInspectApi.Sources {
        final List<String> calls = new ArrayList<>();

        @Override
        public Map<String, Object> bot(int cid, long now) {
            calls.add("bot " + cid);
            return cid == 7 ? doc("cid", cid, now) : null;
        }

        @Override
        public Map<String, Object> map(int mapId, long now) {
            calls.add("map " + mapId);
            return mapId == 100000000 ? doc("map", mapId, now) : null;
        }

        @Override
        public Map<String, Object> positions(long now) {
            calls.add("positions");
            return doc("count", 0, now);
        }

        private static Map<String, Object> doc(String k, int v, long now) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("at", now);
            m.put(k, v);
            return m;
        }
    }

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final Fake fake = new Fake();
    private final BotInspectApi api = new BotInspectApi(SECRET.getBytes(StandardCharsets.UTF_8), fake, clock::get);

    @Test
    void noOrAWrongSecretIs403AndNothingIsRead() {
        assertEquals(403, api.handle("GET", "/positions", null).status());
        assertEquals(403, api.handle("GET", "/positions", "").status());
        assertEquals(403, api.handle("GET", "/bots/7", "wrong-secret-wrong-secret").status());
        assertEquals(403, api.handle("POST", "/bots/7", null).status(), "the secret comes before anything else");
        assertTrue(fake.calls.isEmpty());
        assertFalse(api.handle("GET", "/positions", null).json().contains(SECRET));
    }

    @Test
    void getOnlyAndKnownRoutesOnly() {
        assertEquals(405, api.handle("POST", "/positions", SECRET).status());
        assertEquals(405, api.handle("DELETE", "/bots/7", SECRET).status());
        assertEquals(404, api.handle("GET", "/", SECRET).status());
        assertEquals(404, api.handle("GET", "/bots/abc", SECRET).status());
        assertEquals(404, api.handle("GET", "/bots/7/extra", SECRET).status());
        assertEquals(404, api.handle("GET", "/bots/99999999999", SECRET).status(), "past an int: no such bot");
        assertEquals(404, api.handle("GET", "/bots/8", SECRET).status(), "no such bot");
        assertEquals(404, api.handle("GET", "/maps/1", SECRET).status());
        BotInspectApi.Response bot = api.handle("GET", "/bots/7", SECRET);
        assertEquals(200, bot.status());
        assertTrue(bot.json().contains("\"cid\":7"), bot.json());
        assertEquals(200, api.handle("GET", "/maps/100000000", SECRET).status());
        assertEquals(200, api.handle("GET", "/positions", " " + SECRET + "\n").status(), "the header is trimmed");
    }

    @Test
    void answersAreCachedForTwoSecondsPerPath() {
        api.handle("GET", "/positions", SECRET);
        api.handle("GET", "/positions", SECRET);
        api.handle("GET", "/bots/7", SECRET);
        assertEquals(List.of("positions", "bot 7"), fake.calls);
        clock.addAndGet(BotInspectApi.CACHE_MS - 1);
        api.handle("GET", "/positions", SECRET);
        assertEquals(2, fake.calls.size(), "still cached");
        clock.addAndGet(1);
        api.handle("GET", "/positions", SECRET);
        assertEquals(List.of("positions", "bot 7", "positions"), fake.calls);
    }

    @Test
    void onlyALoopbackBindIsAccepted() throws Exception {
        assertTrue(BotInspectApi.bindAddress(null).isLoopbackAddress());
        assertTrue(BotInspectApi.bindAddress("127.0.0.1").isLoopbackAddress());
        assertTrue(BotInspectApi.bindAddress("localhost").isLoopbackAddress());
        assertTrue(BotInspectApi.bindAddress("::1").isLoopbackAddress());
        assertNull(BotInspectApi.bindAddress("0.0.0.0"));
        assertNull(BotInspectApi.bindAddress("192.168.0.164"));
        assertNull(BotInspectApi.bindAddress("::"));
        assertNull(BotInspectApi.bindAddress("example.com"), "no lookups");
        assertThrows(IllegalArgumentException.class,
                () -> BotInspectApi.start(InetAddress.getByName("0.0.0.0"), 0, api));
    }

    @Test
    void overHttpTheSecretHeaderGatesEveryRequest() throws Exception {
        HttpServer server = BotInspectApi.start(InetAddress.getLoopbackAddress(), 0, api);
        try {
            assertTrue(server.getAddress().getAddress().isLoopbackAddress());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            assertEquals(403, get(base + "/positions", null).status());
            BotInspectApi.Response ok = get(base + "/bots/7", SECRET);
            assertEquals(200, ok.status());
            assertTrue(ok.json().startsWith("{\"ok\":true"), ok.json());
            HttpURLConnection post = (HttpURLConnection) URI.create(base + "/positions").toURL().openConnection();
            post.setRequestMethod("POST");
            post.setRequestProperty(BotInspectApi.HEADER, SECRET);
            assertEquals(405, post.getResponseCode());
        } finally {
            server.stop(0);
        }
    }

    private static BotInspectApi.Response get(String url, String secret) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (secret != null) {
            c.setRequestProperty(BotInspectApi.HEADER, secret);
        }
        int status = c.getResponseCode();
        var in = status < 400 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new BotInspectApi.Response(status, body);
    }

    @Test
    void theSecretFileMustExistAndBeLongEnough(@TempDir Path dir) throws Exception {
        assertNull(BotInspectApi.readSecret(Path.of("")));
        assertNull(BotInspectApi.readSecret(dir.resolve("missing")));
        Path shortOne = Files.writeString(dir.resolve("short"), "tooshort\n");
        assertNull(BotInspectApi.readSecret(shortOne));
        Path good = Files.writeString(dir.resolve("good"), SECRET + "\n");
        assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), BotInspectApi.readSecret(good));
    }

    @Test
    void offOnLiveAndNotStartedWithoutAPort() {
        assertFalse(DebugTools.featureFor(BotInspectApi.SWITCH, QaRegistryTestAccess.live()::get));
        assertTrue(DebugTools.featureFor(BotInspectApi.SWITCH, QaRegistryTestAccess.test()::get));
        assertFalse(DebugTools.featureFor(BotInspectApi.SWITCH,
                QaRegistryTestAccess.props("java -Dslums.qa=true -Dslums.inspect=false -jar x.jar")::get));
    }

    @Test
    void startsOnlyWithThePortTheSwitchAndTheSecret(@TempDir Path dir) throws Exception {
        String[] keys = {BotInspectApi.PORT, BotInspectApi.SWITCH, BotInspectApi.SECRET_FILE, BotInspectApi.BIND};
        Map<String, String> before = new LinkedHashMap<>();
        for (String k : keys) {
            before.put(k, System.getProperty(k));
            System.clearProperty(k);
        }
        try {
            BotInspectApi.startIfEnabled();
            assertNull(BotInspectApi.running(), "no port");
            System.setProperty(BotInspectApi.PORT, "0");
            System.setProperty(BotInspectApi.SWITCH, "false");
            System.setProperty(BotInspectApi.SECRET_FILE, Files.writeString(dir.resolve("s"), SECRET).toString());
            BotInspectApi.startIfEnabled();
            assertNull(BotInspectApi.running(), "switched off");
            System.setProperty(BotInspectApi.SWITCH, "true");
            System.setProperty(BotInspectApi.BIND, "0.0.0.0");
            BotInspectApi.startIfEnabled();
            assertNull(BotInspectApi.running(), "a non-loopback bind is refused");
            System.clearProperty(BotInspectApi.BIND);
            System.setProperty(BotInspectApi.SECRET_FILE, dir.resolve("missing").toString());
            BotInspectApi.startIfEnabled();
            assertNull(BotInspectApi.running(), "no secret");
            System.setProperty(BotInspectApi.SECRET_FILE, dir.resolve("s").toString());
            BotInspectApi.startIfEnabled();
            assertNotNull(BotInspectApi.running());
            assertTrue(BotInspectApi.running().getAddress().getAddress().isLoopbackAddress());
        } finally {
            BotInspectApi.stop();
            before.forEach((k, v) -> {
                if (v == null) {
                    System.clearProperty(k);
                } else {
                    System.setProperty(k, v);
                }
            });
        }
    }
}
