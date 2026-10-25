package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSense;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DecideBenchmarkBridgeTest {
    private Map<String, Object> replay(String line, String intent, double confidence, boolean timely) {
        return DecideBenchmarkBridge.evaluate(Map.of("id", "test", "line", line, "within_budget", timely,
                "response", Json.write(Map.of("answers", Map.of("convo_intent",
                        Map.of("choice", intent, "confidence", confidence))))));
    }

    @Test void usesTheCurrentThreeQuestionRequestAndCleaning() {
        Map<?, ?> request = (Map<?, ?>) replay("hello :) <3", "party", 0.9, true).get("request");
        assertEquals(ChatDecide.request("hello :) <3", List.of(ConvoSense.INTENT, ConvoSense.TONE, ChatTriage.CON)), request);
        assertEquals("decide", request.get("model"));
        assertEquals(3, ((Map<?, ?>) request.get("questions")).size());
    }

    @Test void slowResponsesFallBackEvenWhenTheyWouldHaveFoundARequest() {
        String line = "these things r too strong";
        assertEquals("help_kill", ((Map<?, ?>) replay(line, "help_kill", 0.9, true).get("combined")).get("intent"));
        var slow = replay(line, "help_kill", 0.9, false);
        assertEquals(slow.get("rules"), slow.get("combined"));
        assertFalse(((Map<?, ?>) slow.get("parsed")).isEmpty(), "retain raw quality separately from deadline");
    }

    @Test void rulesWinAndProtectedIntentsAndLowConfidenceStayBlocked() {
        assertEquals("party", ((Map<?, ?>) replay("wanna party?", "follow", 0.99, true).get("combined")).get("intent"));
        for (String intent : new String[]{"trade", "haggle", "date", "flirt", "insult", "nonsense"}) {
            assertEquals("talk", ((Map<?, ?>) replay("these things r too strong", intent, 0.99, true)
                    .get("combined")).get("intent"), intent);
        }
        assertEquals("talk", ((Map<?, ?>) replay("these things r too strong", "help_kill", 0.399, true)
                .get("combined")).get("intent"));
    }

    @Test void fillerSkipsTheModelAndMalformedResponsesFallBack() {
        var filler = replay("hi", "party", 0.99, true);
        assertEquals(false, filler.get("would_ask"));
        assertEquals(filler.get("rules"), filler.get("combined"));
        for (String response : new String[]{"not json", "{}", "{\"answers\":{\"convo_intent\":{\"choice\":\"party\"}}}"}) {
            var row = DecideBenchmarkBridge.evaluate(Map.of("id", "bad", "line", "these things r too strong",
                    "response", response, "within_budget", true));
            assertEquals(row.get("rules"), row.get("combined"));
        }
    }
}
