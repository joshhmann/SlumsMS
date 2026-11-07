package scripting.npc;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.6 kpq1-questions: Cloto's script runs in the server's own Graal engine. The owner's six questions load, the
 * fixed answers come back as numbers, and the two live ones are the KpqQuiz keys a string check routes to the world.
 */
class ClotoStage1QuestionsTest {

    @Test
    void clotosSixQuestionsLoadAndTheFixedAnswersComeBackAsNumbers() throws Exception {
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostAccess", true);
        engine.getBindings(javax.script.ScriptContext.ENGINE_SCOPE).put("polyglot.js.allowHostClassLookup",
                (java.util.function.Predicate<String>) s -> true);
        engine.eval(Files.readString(Path.of("scripts/npc/9020001.js")));

        assertEquals(6, ((Number) engine.eval("stage1Questions.length")).intValue());
        assertEquals(6, ((Number) engine.eval("stage1Answers.length")).intValue());
        Invocable js = (Invocable) engine;
        assertEquals(3, ((Number) js.invokeFunction("stage1Answer", 3)).intValue());
        assertEquals(10, ((Number) js.invokeFunction("stage1Answer", 4)).intValue());
        assertEquals(6, ((Number) js.invokeFunction("stage1Answer", 5)).intValue());
        assertEquals(5, ((Number) js.invokeFunction("stage1Answer", 6)).intValue());
        assertEquals(Boolean.TRUE, engine.eval("typeof stage1Answers[0] === 'string' && stage1Answers[0] === 'channels'"),
                "question 1 is the live channel count");
        assertEquals(Boolean.TRUE, engine.eval("typeof stage1Answers[1] === 'string' && stage1Answers[1] === 'expRate'"),
                "question 2 is the live EXP rate");
        for (int i = 0; i < 6; i++) {
            String q = String.valueOf(engine.eval("stage1Questions[" + i + "]"));
            assertTrue(q.startsWith("Collect as many coupons as "), q);
        }
    }
}
