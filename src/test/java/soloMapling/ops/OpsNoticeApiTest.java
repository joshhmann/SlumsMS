package soloMapling.ops;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b377: deploy.sh's in-game "restarting in 1 minute" notice. Only a caller on this host holding the ops secret can send
 * one, the text keeps to one short line without product or company names, and one notice reaches every channel once.
 */
class OpsNoticeApiTest {

    private static final String SECRET = "ops-secret-for-tests-0123456789";
    private static final String TEXT = "The server restarts in 1 minute for an update. You'll be back in about a minute.";

    /** Two worlds of three channels: each send records one line per channel it reached. */
    private static final class FakeWorlds implements OpsNoticeApi.Broadcaster {
        final List<String> perChannel = new ArrayList<>();
        int sends;

        @Override
        public int send(String text) {
            sends++;
            for (int world = 0; world < 2; world++) {
                for (int ch = 1; ch <= 3; ch++) {
                    perChannel.add(world + "/" + ch + ": " + text);
                }
            }
            return 6;
        }
    }

    private final FakeWorlds worlds = new FakeWorlds();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final OpsNoticeApi api = new OpsNoticeApi(SECRET.getBytes(StandardCharsets.UTF_8), worlds, now::get);

    @Test
    void aNoticeWithoutTheSecretIsRefusedAndNothingIsSent() {
        assertEquals(403, api.handle(true, "POST", "/notice", null, TEXT).status());
        assertEquals(403, api.handle(true, "POST", "/notice", "not-the-secret-at-all", TEXT).status());
        assertEquals(0, worlds.sends);
    }

    @Test
    void aCallerNotOnThisHostIsRefusedEvenWithTheSecret() {
        assertEquals(403, api.handle(false, "POST", "/notice", SECRET, TEXT).status());
        assertEquals(0, worlds.sends);
    }

    @Test
    void onlyPostToNoticeIsServed() {
        assertEquals(405, api.handle(true, "GET", "/notice", SECRET, TEXT).status());
        assertEquals(404, api.handle(true, "POST", "/bots/1", SECRET, TEXT).status());
        assertEquals(0, worlds.sends);
    }

    @Test
    void textThatBreaksTheRulesIsRefused() {
        assertEquals("EMPTY", OpsNoticeApi.refusal(""));
        assertEquals("TOO_LONG", OpsNoticeApi.refusal("x".repeat(OpsNoticeApi.MAX_TEXT + 1)));
        assertEquals("NOT_ONE_LINE", OpsNoticeApi.refusal("restarting\nnow"));
        assertEquals("NAME_RULE", OpsNoticeApi.refusal("MapleStory is restarting"));
        assertEquals("NAME_RULE", OpsNoticeApi.refusal("back to v83 in a minute"));
        assertNull(OpsNoticeApi.refusal(TEXT));
        assertEquals(400, api.handle(true, "POST", "/notice", SECRET, "  ").status());
        assertEquals(0, worlds.sends);
    }

    @Test
    void oneNoticeReachesEveryChannelOnce() {
        OpsNoticeApi.Response r = api.handle(true, "POST", "/notice", SECRET, "  " + TEXT + "\n");

        assertEquals(200, r.status());
        assertEquals("{\"ok\":true,\"channels\":6}", r.json());
        assertEquals(1, worlds.sends, "one request, one send");
        assertEquals(6, worlds.perChannel.size(), "every channel of every world, once");
        assertTrue(worlds.perChannel.stream().allMatch(l -> l.endsWith(": " + TEXT)), "the text, trimmed");
    }

    @Test
    void aSecondNoticeWithinFiveSecondsIsHeldBack() {
        assertEquals(200, api.handle(true, "POST", "/notice", SECRET, TEXT).status());
        now.addAndGet(OpsNoticeApi.MIN_GAP_MS - 1);
        assertEquals(429, api.handle(true, "POST", "/notice", SECRET, "Restarting now, see you in a minute.").status());
        now.addAndGet(1);
        assertEquals(200, api.handle(true, "POST", "/notice", SECRET, "Restarting now, see you in a minute.").status());
        assertEquals(2, worlds.sends);
    }

    @Test
    void aShortOrMissingSecretFileKeepsTheEndpointOff(@TempDir Path dir) throws Exception {
        Path shortOne = Files.writeString(dir.resolve("short"), "too-short");
        assertNull(OpsNoticeApi.readSecret(shortOne));
        assertNull(OpsNoticeApi.readSecret(dir.resolve("missing")));
        Path good = Files.writeString(dir.resolve("good"), SECRET + "\n");
        assertEquals(SECRET, new String(OpsNoticeApi.readSecret(good), StandardCharsets.UTF_8));
    }

    @Test
    void theRealListenerServesALoopbackPost() throws Exception {
        HttpServer server = OpsNoticeApi.start(0, api);
        try {
            int port = server.getAddress().getPort();
            assertTrue(server.getAddress().getAddress().isLoopbackAddress(), "bound to loopback only");
            HttpClient http = HttpClient.newHttpClient();
            HttpRequest post = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/notice"))
                    .header(OpsNoticeApi.HEADER, SECRET)
                    .POST(HttpRequest.BodyPublishers.ofString(TEXT)).build();
            HttpResponse<String> res = http.send(post, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, res.statusCode());
            assertEquals(6, worlds.perChannel.size());

            HttpRequest noSecret = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/notice"))
                    .POST(HttpRequest.BodyPublishers.ofString(TEXT)).build();
            assertEquals(403, http.send(noSecret, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            server.stop(0);
        }
    }
}
