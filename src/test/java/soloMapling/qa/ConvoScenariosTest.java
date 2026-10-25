package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59's scenarios (cv-1 .. cv-7) against a recording world. */
class ConvoScenariosTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    QaSuite suite;

    @AfterEach
    void reset() {
        QaLog.reset();
        MoveTrace.reset();
    }

    private Character start(String id, int mapId, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", mapId);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    private long called(String prefix) {
        return world.calls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    @Test
    void everyChatScenarioIsRegistered() {
        List<String> ids = QaScenarios.all().stream().map(QaScenario::id).toList();
        for (String id : List.of("cv-1", "cv-2", "cv-3", "cv-4", "cv-5", "cv-6", "cv-7", "cv-8", "cv-9", "cv-10", "cv-11")) {
            assertTrue(ids.contains(id), id);
        }
    }

    @Test
    void cv1NamesTheBotAndSaysWhereItIs() throws Exception {
        // rc6 (QA cv-1): "Bot 1000000140 counts you as a friend. Name it..." - an id, and nowhere to look
        start("cv-1", 100000000);
        String text = java.nio.file.Files.readString(suite.runOf(1).logPath());
        assertTrue(text.contains("told GM: Botty"), text);
        assertTrue(text.contains("px to your right) counts you as a friend"), text);
        assertFalse(text.contains("told GM: Bot 9"), "never the id");
        suite.stop(1, "!qa stop");
    }

    @Test
    void cv1StagesAFriendAndLeavesAtStop() {
        start("cv-1", 100000000);
        assertEquals(1, called("convobot friend social"));
        suite.action(world.online, "sense", new String[]{"come", "to", "henesys"});
        assertEquals(1, called("convo sense"));
        suite.stop(1, "!qa stop");
        assertEquals(1, called("unconvobot"));
    }

    @Test
    void cv2GivesTheBotTheItem() {
        start("cv-2", 100000000, "1082002");
        assertTrue(world.calls.stream().anyMatch(c -> c.endsWith("give 1082002 x1")), world.calls.toString());
    }

    @Test
    void cv4StagesFourVoices() {
        start("cv-4", 100000000);
        for (String v : List.of("lower", "proper", "leet", "korean")) {
            assertEquals(1, called("convobot neutral social " + v), v + " " + world.calls);
        }
        suite.stop(1, "!qa stop");
        assertEquals(4, called("unconvobot"));
    }

    @Test
    void cv5StartsAThreadAndAnotherOnRequest() {
        start("cv-5", 100000000);
        assertEquals(1, called("convo thread"));
        suite.action(world.online, "thread", new String[]{"trade"});
        assertEquals(2, called("convo thread"));
    }

    @Test
    void cv8StagesAStrangerThatAsksTheMapAndAsksAgainOnRequest() {
        start("cv-8", 100000000, "anyone", "from", "the", "east", "coast?");
        assertEquals(1, called("convobot neutral social"));
        assertEquals(1, called("convo ask anyone from the east coast?"), world.calls.toString());
        suite.action(world.online, "ask", new String[0]);
        assertEquals(2, called("convo ask"));
        suite.stop(1, "!qa stop");
        assertEquals(1, called("unconvobot"));
    }

    @Test
    void cv7TurnsBankModeOnAndOffAgainAtStop() {
        start("cv-7", 100000000);
        assertEquals(1, called("convo bank on"));
        suite.action(world.online, "llm", new String[0]);
        assertEquals(1, called("convo bank off"));
        suite.stop(1, "!qa stop");
        assertEquals(2, called("convo bank off"), "stop restores the LLM");
    }

    @Test
    void cv9StagesAFriendAndHoldsItNearThePlayer() {
        start("cv-9", 100000000);
        assertEquals(1, called("convobot friend social"));
        suite.action(world.online, "hold", new String[]{"hey", "wait", "here"});
        assertEquals(1, called("convo hold hey wait here"), world.calls.toString());
        suite.action(world.online, "sample", new String[0]);
        assertEquals(1, called("convo sample"));
        suite.stop(1, "!qa stop");
        assertEquals(1, called("unconvobot"));
    }

    @Test
    void cv11StagesAPartyAndSendsAPartyLine() {
        start("cv-11", 100000000);
        assertEquals(1, called("convobot friend social"));
        assertEquals(1, called("convo party"));
        suite.action(world.online, "say", new String[]{"ready?"});
        assertEquals(1, called("convo partysay ready?"), world.calls.toString());
        suite.stop(1, "!qa stop");
        assertEquals(1, called("unconvobot"));
    }
}
