package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmJudgeTest {

    @Test
    void aCleanAnswerPassesAndAnyBadCriterionFails() {
        assertTrue(LlmJudge.read("{\"on_topic\":true,\"coherent\":true,\"in_character\":true,\"pg13\":true,"
                + "\"reveals_ai\":false,\"reason\":\"fine\"}").pass());
        LlmJudge.Grade g = LlmJudge.read("sure! {\"on_topic\":false,\"coherent\":true,\"in_character\":true,\"pg13\":true,"
                + "\"reveals_ai\":true,\"reason\":\"off\"} hope that helps");
        assertFalse(g.pass());
        assertTrue(g.decided());
        assertTrue(g.reason().contains("not on_topic") && g.reason().contains("reveals AI"), g.reason());
    }

    @Test
    void anUnreadableOrMissingAnswerIsUndecidedNotAFail() {
        assertFalse(LlmJudge.read(null).decided());
        assertFalse(LlmJudge.read("I think it is fine").decided());
        assertTrue(LlmJudge.read("{broken").pass());
    }

    @Test
    void sayingItIsAnAiFailsWithoutAskingTheModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmJudge j = LlmJudge.of((s, u) -> {
            calls.incrementAndGet();
            return "{}";
        }, 3);
        assertFalse(j.grade("x", "hi", "as an AI language model i cant").pass());
        assertFalse(j.grade("x", "hi", "lol 😂").pass());
        assertEquals(0, calls.get());
        assertTrue(j.grade("x", "hi", "sup noob").pass());
        assertEquals(1, calls.get());
    }

    @Test
    void noProviderMeansNoJudge() {
        assertFalse(LlmJudge.of(null, 5).available());
        assertFalse(LlmJudge.of((s, u) -> "{}", 0).available());
    }
}
