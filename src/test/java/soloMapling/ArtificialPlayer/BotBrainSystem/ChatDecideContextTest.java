package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSense;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1: earlier lines go to the intent+con endpoint only, in an exact state format. */
class ChatDecideContextTest {

    private static final String PREFIX = "A MapleStory player typed this to you in chat: ";
    private static final String ANSWER = "{\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":0.9}}}";

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    private static String state(String line, List<ChatDecide.Earlier> earlier) {
        return (String) ChatDecide.request(line, List.of(ChatTriage.INTENT), "jev-latest", ChatDecide.contextBlock(earlier))
                .get("state");
    }

    @Test
    void noEarlierLinesIsByteIdenticalToToday() {
        assertEquals(PREFIX + "wanna pt", state("wanna pt", List.of()));
        assertEquals(ChatDecide.request("wanna pt", List.of(ChatTriage.INTENT), "jev-latest"),
                ChatDecide.request("wanna pt", List.of(ChatTriage.INTENT), "jev-latest", ChatDecide.contextBlock(List.of())));
    }

    @Test
    void earlierLinesAreOldestFirstOnePerLineWithRoleLabels() {
        var earlier = List.of(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "hi there"),
                new ChatDecide.Earlier(ChatDecide.Role.YOU, "hey, whats up"),
                new ChatDecide.Earlier(ChatDecide.Role.NEARBY, "anyone selling scrolls"));
        assertEquals("Earlier in this chat:\nplayer: hi there\nyou: hey, whats up\nsomeone nearby: anyone selling scrolls\n"
                + PREFIX + "send me mesos", state("send me mesos", earlier));
    }

    @Test
    void onlyTheLastFiveAreKept() {
        List<ChatDecide.Earlier> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            many.add(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "line " + i));
        }
        assertEquals("Earlier in this chat:\nplayer: line 4\nplayer: line 5\nplayer: line 6\nplayer: line 7\nplayer: line 8\n"
                + PREFIX + "x", state("x", many));
    }

    @Test
    void mapLinesBecomeRolesAndTheCurrentLineIsLeftOut() {
        var lines = List.of(new MapChatLog.Line(7, "Ann", "hi", 1, false), new MapChatLog.Line(99, "Botty", "hello", 2, true),
                new MapChatLog.Line(8, "Bob", "wts scroll", 3, false), new MapChatLog.Line(7, "Ann", "Give me mesos", 4, false));
        var earlier = ConvoSense.earlier(lines, 99, 7, "give me mesos");
        assertEquals(List.of(ChatDecide.Role.PLAYER, ChatDecide.Role.YOU, ChatDecide.Role.NEARBY),
                earlier.stream().map(ChatDecide.Earlier::role).toList());
        assertEquals("hi", earlier.get(0).text());
    }

    @Test
    void theCacheKeyIncludesTheContext() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        ChatDecide d = new ChatDecide((b, t) -> {
            bodies.add(b);
            return ANSWER;
        }, 400, 50);
        var a = List.of(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "i am a friend"));
        var b2 = List.of(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "i hate you"));
        d.askNow("give me stuff", List.of(ChatTriage.INTENT), a);
        d.askNow("give me stuff", List.of(ChatTriage.INTENT), a);
        assertEquals(1, bodies.size(), "same line, same context: cached");
        d.askNow("give me stuff", List.of(ChatTriage.INTENT), b2);
        d.askNow("give me stuff", List.of(ChatTriage.INTENT));
        assertEquals(3, bodies.size(), "another context or none: asked again");
    }

    @Test
    void theToneEndpointKeepsThePlainLine() {
        List<String> mainBodies = new CopyOnWriteArrayList<>(), toneBodies = new CopyOnWriteArrayList<>();
        ChatDecide main = new ChatDecide((b, t) -> {
            mainBodies.add(b);
            return "{\"answers\":{\"convo_intent\":{\"choice\":\"talk\",\"confidence\":0.9},\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}";
        }, 400, 50);
        ChatDecide tone = new ChatDecide((b, t) -> {
            toneBodies.add(b);
            return "{\"answers\":{\"convo_tone\":{\"choice\":\"neutral\",\"confidence\":0.9}}}";
        }, 400, 50);
        ConvoSense.read("what is the meaning of the weather today zq", main, tone, null,
                List.of(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "hello")));
        assertEquals(1, mainBodies.size());
        assertEquals(1, toneBodies.size());
        assertFalse(toneBodies.get(0).contains("Earlier in this chat"));
        assertTrue(mainBodies.get(0).contains("Earlier in this chat:\\nplayer: hello\\nA MapleStory"));
    }
}
