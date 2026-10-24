package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.ArtificialPlayer.BotConvo.BotThreads;
import soloMapling.ArtificialPlayer.BotConvo.ConvoQuality;
import soloMapling.server.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc6: the ROUTE CANDIDATES record and where it goes. */
class RouteLogTest {

    static final int A = 20_001, B = 20_002, JOSH = 7, AMY = 8;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        RouteLog.diskForTest(null);
        System.clearProperty("solomapling.convo.dataDir");
    }

    ChatRoute.Decision decision(List<MapChatLog.Line> lines) {
        Map<Integer, ChatRoute.Bot> bots = Map.of(A, new ChatRoute.Bot(A, "Zephyrr", 100, 0, true),
                B, new ChatRoute.Bot(B, "Mochiko", 200, 0, true));
        BotThreads.View thread = new BotThreads.View(4, List.of(A, B), List.of("Zephyrr", "Mochiko"), B, 0, 5_000, "gossip");
        return ChatRoute.decide(lines, new ChatRoute.Player(JOSH, "Josh", "wait who wiped?", 0, 0, null, -1, 0),
                bots, thread, null, id -> "friend", 9_000);
    }

    @Test
    void oneJsonlRowPerDecisionWithThePlayerHashedAndNoRealNames() throws Exception {
        System.setProperty("solomapling.convo.dataDir", dir.toString());
        RouteLog.diskForTest(true);
        List<MapChatLog.Line> lines = List.of(
                new MapChatLog.Line(A, "Zephyrr", "Josh we wiped at zakum lol", 1_000, true),
                new MapChatLog.Line(AMY, "Amy", "amy here", 2_000, false),
                new MapChatLog.Line(B, "Mochiko", "ya amy saw it", 5_000, true),
                new MapChatLog.Line(JOSH, "Josh", "wait who wiped?", 9_000, false));
        ChatRoute.Decision d = decision(lines);
        RouteLog.log(100000000, JOSH, "Josh", "wait who wiped?", d, d.chosenKey(), d.step().label, lines,
                List.of("Amy"), 9_000);
        RouteLog.flushForTest();
        List<String> rows = Files.readAllLines(RouteLog.file(LocalDate.now()));
        assertEquals(1, rows.size());
        String row = rows.get(0);
        assertFalse(row.contains("Josh") || row.contains("Amy") || row.contains("amy"), row);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) Json.parse(row);
        assertEquals(ConvoQuality.hash(JOSH), m.get("player"));
        assertEquals("3-reply-thread", m.get("step"));
        assertEquals("thread:4", m.get("chosen"));
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) m.get("features");
        assertEquals(Boolean.TRUE, f.get("question"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> cands = (List<Map<String, Object>>) m.get("candidates");
        assertEquals(3, cands.size(), "two speakers and the thread");
        Map<String, Object> zeph = cands.stream().filter(c -> "Zephyrr".equals(c.get("key"))).findFirst().orElseThrow();
        for (String k : List.of("named", "nameScore", "secs", "words", "ents", "question", "addressed", "thread", "dist",
                "dx", "dy", "bubble", "facing", "tier")) {
            assertTrue(zeph.containsKey(k), k);
        }
        assertEquals(Boolean.TRUE, zeph.get("addressed"));
        assertEquals("friend", zeph.get("tier"));
        assertEquals("{player} we wiped at zakum lol", zeph.get("said"));
        assertEquals(4, ((List<?>) m.get("context")).size());
    }

    @Test
    void nothingGoesToDiskUnlessAutoQaOrThePropertyIsOn() throws Exception {
        System.setProperty("solomapling.convo.dataDir", dir.toString());
        RouteLog.diskForTest(null);
        assertFalse(RouteLog.toDisk(), "live: no auto-QA, no property");
        List<MapChatLog.Line> lines = List.of(new MapChatLog.Line(JOSH, "Josh", "wait who wiped?", 9_000, false));
        RouteLog.log(1, JOSH, "Josh", "wait who wiped?", decision(lines), null, "5-none", lines, List.of(), 9_000);
        RouteLog.flushForTest();
        assertFalse(Files.exists(RouteLog.file(LocalDate.now())));
        System.setProperty(RouteLog.PROPERTY, "true");
        try {
            assertTrue(RouteLog.toDisk());
        } finally {
            System.clearProperty(RouteLog.PROPERTY);
        }
    }
}
