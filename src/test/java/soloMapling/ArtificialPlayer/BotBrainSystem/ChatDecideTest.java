package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDecideTest {

    private static final String ANSWER = "{\"model\":\"any\",\"answers\":{\"intent\":{\"choice\":\"party\",\"confidence\":"
            + "0.8,\"probabilities\":{}},\"con\":{\"choice\":\"normal\",\"confidence\":0.9}}}";

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    @Test
    void oneCallCarriesEveryQuestionAndTheAnswersAreParsed() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        ChatDecide decide = new ChatDecide((body, timeout) -> {
            bodies.add(body);
            return ANSWER;
        }, 400, 10);
        Optional<Map<String, ChatDecide.Answer>> a = decide.askNow("wanna pt? :)", List.of(ChatTriage.INTENT, ChatTriage.CON));
        assertEquals(new ChatDecide.Answer("party", 0.8), a.orElseThrow().get("intent"));
        assertEquals(1, bodies.size());
        Map<?, ?> body = (Map<?, ?>) Json.parse(bodies.get(0));
        assertEquals("A MapleStory player typed this to you in chat: wanna pt?", body.get("state"),
                "characters the server rejects are dropped");
        Map<?, ?> questions = (Map<?, ?>) body.get("questions");
        assertEquals(List.of("intent", "con"), List.copyOf(questions.keySet()));
        assertTrue(((Map<?, ?>) questions.get("intent")).get("criteria") instanceof Map<?, ?> c && c.size() == 6);
    }

    @Test
    void theSameLineIsAskedOnceThenCached() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        ChatDecide decide = new ChatDecide((body, timeout) -> {
            bodies.add(body);
            return ANSWER;
        }, 400, 10);
        decide.askNow("Wanna PT?", List.of(ChatTriage.INTENT));
        decide.askNow("wanna pt?!", List.of(ChatTriage.INTENT));
        assertEquals(1, bodies.size());
        assertEquals(1L, ((Map<?, ?>) ChatStats.snapshot().get("decide")).get("cacheHits"));
    }

    @Test
    void pastTheRateLimitTheAnswerIsEmptyAndNoCallIsMade() {
        List<String> bodies = new CopyOnWriteArrayList<>();
        ChatDecide decide = new ChatDecide((body, timeout) -> {
            bodies.add(body);
            return ANSWER;
        }, 400, 2);
        decide.askNow("one", List.of(ChatTriage.INTENT));
        decide.askNow("two", List.of(ChatTriage.INTENT));
        assertEquals(Optional.empty(), decide.askNow("three", List.of(ChatTriage.INTENT)));
        assertEquals(2, bodies.size());
    }

    @Test
    void errorsTimeoutsAndUnknownLabelsAreEmpty() {
        ChatDecide error = new ChatDecide((body, timeout) -> {
            throw new IllegalStateException("HTTP 500");
        }, 300, 10);
        assertEquals(Optional.empty(), error.askNow("hi there friend", List.of(ChatTriage.CON)));
        ChatDecide slow = new ChatDecide((body, timeout) -> {
            Thread.sleep(1_000);
            return ANSWER;
        }, 100, 10);
        assertEquals(Optional.empty(), slow.askNow("hi there friend", List.of(ChatTriage.CON)));
        assertEquals(Optional.empty(), ChatDecide.parse("{\"answers\":{\"con\":{\"choice\":\"evil\",\"confidence\":1}}}",
                List.of(ChatTriage.CON)));
        assertEquals(Optional.empty(), ChatDecide.parse("not json", List.of(ChatTriage.CON)));
    }
}
