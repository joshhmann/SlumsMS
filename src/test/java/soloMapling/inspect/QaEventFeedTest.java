package soloMapling.inspect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaLog;
import soloMapling.qa.QaLogTestAccess;
import soloMapling.qa.QaRegistryTestAccess;
import soloMapling.server.Json;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 170: the TEST-only QA event feed (secret, filters, sequence numbers, long-poll, the QA log sink, live guard). */
class QaEventFeedTest {

    private static final String SECRET = "0123456789abcdef-feed-test";
    private final AtomicLong now = new AtomicLong(1_793_400_000_000L);
    private final Map<Integer, Integer> maps = new HashMap<>();
    private QaEventFeed feed;

    private QaEventFeed feed(boolean realClock) {
        feed = new QaEventFeed(SECRET.getBytes(StandardCharsets.UTF_8),
                realClock ? System::currentTimeMillis : now::get, cid -> maps.getOrDefault(cid, -1));
        return feed;
    }

    @AfterEach
    void tearDown() {
        if (feed != null) {
            feed.close();
        }
        QaLogTestAccess.reset();
    }

    private QaEventFeed.Response get(String query) {
        return feed.handle("GET", QaEventFeed.PATH, query, SECRET);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(QaEventFeed.Response r) {
        assertEquals(200, r.status(), r.json());
        return (List<Map<String, Object>>) ((Map<String, Object>) Json.parse(r.json())).get("lines");
    }

    @SuppressWarnings("unchecked")
    private static Object field(QaEventFeed.Response r, String key) {
        return ((Map<String, Object>) Json.parse(r.json())).get(key);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── access ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void refusesWithoutTheSecretAndOtherMethodsAndPaths() {
        feed(false);
        assertEquals(403, feed.handle("GET", QaEventFeed.PATH, "", null).status());
        assertEquals(403, feed.handle("GET", QaEventFeed.PATH, "", "wrong-secret-wrong-secret").status());
        assertEquals(403, feed.handle("POST", QaEventFeed.PATH, "", null).status(), "the secret is checked first");
        assertEquals(405, feed.handle("POST", QaEventFeed.PATH, "", SECRET).status());
        assertEquals(404, feed.handle("GET", "/qa/other", "", SECRET).status());
        assertFalse(feed.handle("GET", QaEventFeed.PATH, "", null).json().contains(SECRET));
    }

    @Test
    void refusesBadQueries() {
        feed(false);
        assertEquals(400, get("since=-1&wait=0").status());
        assertEquals(400, get("wait=21").status());
        assertEquals(400, get("topics=NOPE&wait=0").status());
        assertEquals(400, get("match=" + enc("(") + "&wait=0").status());
        assertEquals(400, get("password=x&wait=0").status());
        assertEquals(400, get("chars=abc&wait=0").status());
        assertEquals(400, get("match=" + "a".repeat(QaEventFeed.MAX_MATCH + 1) + "&wait=0").status());
    }

    @Test
    void offUnlessSwitchedOnExplicitlyAndNeverOnLive() {
        assertFalse(QaEventFeed.enabledFor(QaRegistryTestAccess.live()::get));
        assertFalse(QaEventFeed.enabledFor(QaRegistryTestAccess.test()::get), "QA on is not enough");
        assertTrue(QaEventFeed.enabledFor(
                QaRegistryTestAccess.props("java -Dslums.qa=true -Dslums.qa.feed=true -jar x.jar")::get));
        assertFalse(QaEventFeed.enabled());
    }

    @Test
    void liveGuardSpotsTheFolderThePortAndTheDatabase() {
        String test = "/opt/mapleroot-dev/persistence-test/Server";
        assertNull(QaEventFeed.liveReason(test, "8489", "jdbc:mysql://127.0.0.1:3307/mapleroot_persist"));
        assertNotNull(QaEventFeed.liveReason("/opt/mapleroot/MapleRoot Full Repack/Server", "8489", null));
        assertNotNull(QaEventFeed.liveReason(test, "8485", null));
        assertNotNull(QaEventFeed.liveReason(test, null, null), "no port given = the default 8485");
        assertNotNull(QaEventFeed.liveReason(test, "x", null));
        assertNotNull(QaEventFeed.liveReason(test, "8489", "jdbc:mysql://127.0.0.1:3309/mapleroot"));
    }

    // ── filters and sequence ────────────────────────────────────────────────────────────────────

    @Test
    void filtersByTopicCharAndRegexWithSequenceNumbersAndUtcTimes() {
        feed(false);
        feed.record("COMBAT", "COMBAT", null, 0, new int[]{7}, "PARTY HEAL Bish -> Me skill 2301002 lv 30 hp 400->900");
        feed.record("COMBAT", "COMBAT", null, 0, new int[]{8}, "PARTY HEAL Bish -> Other");
        feed.record("CHAT", "CHAT", null, 100000000, new int[]{7}, "CHAT Me: hi");
        feed.record("MECHANICS", "MECHANICS", null, 0, new int[0], "RUN start kpq stage 1");

        List<Map<String, Object>> heal = lines(get("topics=COMBAT&chars=7&wait=0"));
        assertEquals(1, heal.size());
        assertEquals(1L, ((Number) heal.get(0).get("seq")).longValue());
        assertEquals("2026-10-30T22:40:00Z", heal.get(0).get("utc"));
        assertEquals("COMBAT", heal.get(0).get("topic"));

        assertEquals(2, lines(get("topics=COMBAT&wait=0")).size());
        assertEquals(1, lines(get("topics=COMBAT&match=" + enc("-> Other$") + "&wait=0")).size());
        // a server-wide line (no char, no map) reaches every char filter
        List<Map<String, Object>> both = lines(get("topics=CHAT,MECHANICS&chars=7&wait=0"));
        assertEquals(2, both.size());
        assertTrue(((Number) both.get(0).get("seq")).longValue() < ((Number) both.get(1).get("seq")).longValue());
        // since: only what came after
        assertEquals(1, lines(get("topics=CHAT,MECHANICS&chars=7&since=3&wait=0")).size());
        QaEventFeed.Response r = get("topics=COMBAT&since=4&wait=0");
        assertEquals(0, lines(r).size());
        assertEquals(4L, ((Number) field(r, "last")).longValue());
        assertEquals(4L, ((Number) field(r, "next")).longValue());
    }

    @Test
    void mapLinesGoToWatchedCharactersOnThatMapAndAreDroppedOtherwise() {
        feed(false);
        maps.put(7, 100000000);
        maps.put(9, 104040000);
        feed.record("COMBAT", "COMBAT", null, 100000000, new int[0], "FOLLOWER FIGHT before anyone watched");
        lines(get("topics=COMBAT&chars=7,9&wait=0"));   // the poll watches 7 and 9
        feed.record("COMBAT", "COMBAT", null, 100000000, new int[0], "FOLLOWER FIGHT Bish swings");
        feed.record("COMBAT", "COMBAT", null, 230000000, new int[0], "FOLLOWER FIGHT on a map nobody watches");
        List<Map<String, Object>> got = lines(get("topics=COMBAT&chars=7&wait=0"));
        assertEquals(1, got.size());
        assertEquals("FOLLOWER FIGHT Bish swings", got.get(0).get("line"));
        assertEquals(List.of(7L), ((List<?>) got.get(0).get("chars")).stream().map(n -> ((Number) n).longValue()).toList());
        assertEquals(0, lines(get("topics=COMBAT&chars=9&wait=0")).size());
        now.addAndGet(QaEventFeed.ARM_MS + 1);   // nobody polled for a while: 7 is no longer watched
        feed.record("COMBAT", "COMBAT", null, 100000000, new int[0], "FOLLOWER FIGHT later");
        assertEquals(1, lines(get("topics=COMBAT&chars=7&wait=0")).size());
    }

    @Test
    void theRingKeepsTheNewestLinesAndSaysWhenAPollMissedSome() {
        feed(false);
        for (int i = 0; i < QaEventFeed.RING + 5; i++) {
            feed.record("CHAT", "CHAT", null, 0, new int[]{7}, "CHAT Me: " + i);
        }
        QaEventFeed.Response r = get("topics=CHAT&since=1&limit=3&wait=0");
        List<Map<String, Object>> got = lines(r);
        assertEquals(3, got.size());
        assertEquals(6L, ((Number) got.get(0).get("seq")).longValue());
        assertEquals(Boolean.TRUE, field(r, "gap"));
        assertEquals(8L, ((Number) field(r, "next")).longValue(), "cut short: the next poll starts after the last hit");
    }

    // ── long-poll ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aLongPollReturnsAsSoonAsAMatchingLineArrives() throws Exception {
        feed(true);
        long t0 = System.currentTimeMillis();
        CompletableFuture<QaEventFeed.Response> poll = CompletableFuture.supplyAsync(
                () -> get("topics=COMBAT&chars=7&match=" + enc("PARTY HEAL") + "&wait=10"));
        Thread.sleep(300);
        feed.record("COMBAT", "COMBAT", null, 0, new int[]{7}, "FOLLOWER FIGHT not this one");
        Thread.sleep(200);
        assertFalse(poll.isDone(), "a line that does not match keeps it waiting");
        feed.record("COMBAT", "COMBAT", null, 0, new int[]{7}, "PARTY HEAL Bish -> Me");
        List<Map<String, Object>> got = lines(poll.get(5, TimeUnit.SECONDS));
        assertEquals(1, got.size());
        assertEquals("PARTY HEAL Bish -> Me", got.get(0).get("line"));
        assertTrue(System.currentTimeMillis() - t0 < 5_000);
    }

    @Test
    void aLongPollWithNothingReturnsEmptyAfterItsWait() {
        feed(true);
        long t0 = System.currentTimeMillis();
        assertEquals(0, lines(get("topics=COMBAT&wait=1")).size());
        long took = System.currentTimeMillis() - t0;
        assertTrue(took >= 900 && took < 8_000, "took " + took + " ms");
    }

    @Test
    void tooManyPollsAtOnceAreTurnedAway() throws Exception {
        feed(true);
        List<CompletableFuture<QaEventFeed.Response>> polls = new java.util.ArrayList<>();
        for (int i = 0; i < QaEventFeed.MAX_POLLERS; i++) {
            polls.add(CompletableFuture.supplyAsync(() -> get("topics=COMBAT&wait=3"),
                    java.util.concurrent.Executors.newSingleThreadExecutor()));
        }
        Thread.sleep(500);
        assertEquals(429, get("topics=COMBAT&wait=0").status());
        for (CompletableFuture<QaEventFeed.Response> p : polls) {
            assertEquals(200, p.get(10, TimeUnit.SECONDS).status());
        }
    }

    // ── the QA log ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aPollArmsItsTopicsInTheQaLogAndTheSinkFeedsHookLines() {
        feed(false);
        feed.open();
        assertFalse(QaLog.on(QaLog.Topic.COMBAT));
        lines(get("topics=COMBAT,MAP_CHANGE&chars=7&wait=0"));
        assertTrue(QaLog.on(QaLog.Topic.COMBAT), "the poll armed COMBAT");
        assertTrue(QaLog.on(QaLog.Topic.MAP_CHANGE));
        assertFalse(QaLog.on(QaLog.Topic.ROPE));
        QaLog.owner(QaLog.Topic.COMBAT, 7, "PARTY HEAL Bish -> Me");
        QaLog.chars(QaLog.Topic.MAP_CHANGE, 100000000, new int[]{7}, "MAP Me -> 100000000 ch 3");
        List<Map<String, Object>> got = lines(get("topics=COMBAT,MAP_CHANGE&chars=7&wait=0"));
        assertEquals(2, got.size());
        assertEquals("PARTY HEAL Bish -> Me", got.get(0).get("line"));
        assertEquals("MAP_CHANGE", got.get(1).get("topic"));
        feed.close();
        assertFalse(QaLog.on(QaLog.Topic.COMBAT), "closing the feed turns its topics off");
    }

    @Test
    void scenarioLinesComeAsQaRunAndTopicLinesAreLeftToTheSink() {
        feed(false);
        lines(get("topics=QA_RUN&wait=0"));   // arms the run lines
        feed.fromRun(7, "mx-1", "MX1", "MX1 PASS cleared stage 1 in 80 s");
        feed.fromRun(7, "mx-1", "COMBAT", "a topic line");
        List<Map<String, Object>> got = lines(get("topics=QA_RUN&chars=7&wait=0"));
        assertEquals(1, got.size());
        assertEquals("mx-1", got.get(0).get("scenario"));
        assertEquals("MX1", got.get(0).get("kind"));
    }
}
