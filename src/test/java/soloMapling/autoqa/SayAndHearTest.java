package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** TEST 2026-10-27 18:41 t-oa-1: two @botme answers in one millisecond; the second call must read its own. */
class SayAndHearTest {

    @Test
    void anAnswerHeardBeforeTheCommandIsNotItsAnswer() {
        Pattern want = Pattern.compile("(?i)already online|has joined you");
        AutoQaLine joined = new AutoQaLine(1000, "heard:1", "YELLOW", "AutoQaAlt has joined you.");
        AutoQaLine again = new AutoQaLine(1000, "heard:1", "YELLOW", "AutoQaAlt is already online or still being saved.");
        assertEquals("AutoQaAlt is already online or still being saved.",
                Probes160.firstNewAnswer(List.of(joined), List.of(joined, again), want));
        assertNull(Probes160.firstNewAnswer(List.of(joined), List.of(joined), want));
        assertEquals("AutoQaAlt has joined you.", Probes160.firstNewAnswer(List.of(), List.of(joined), want));
    }
}
