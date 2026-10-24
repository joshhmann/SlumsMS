package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An LLM-judge expectation sends capture group 1 of its pattern to the judge as the bot's reply. rc6 auto-QA
 * triage (t-rm-3, 2026-10-23): "CHAT ({staged}): (.+)" captured the bot's NAME in group 1, so the judge graded
 * "iiKillerx" ("just a username, no response") and failed the check. Every judged pattern must have exactly one
 * capturing group: the reply.
 */
class JudgedPatternGroupTest {

    @Test
    void everyJudgedPatternCapturesOnlyTheReply() {
        List<String> bad = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            for (Check.Expect e : c.expects()) {
                String d = e.describe();
                if (!d.startsWith("LLM judge: /")) {
                    continue;
                }
                String regex = d.substring("LLM judge: /".length(), d.lastIndexOf("/ ("));
                String filled = regex.replaceAll("\\{[a-z0-9]+}", "Name");
                int groups = Pattern.compile(filled).matcher("").groupCount();
                if (groups != 1) {
                    bad.add(c.id() + " /" + regex + "/ has " + groups + " capturing groups");
                }
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void rm3SendsTheLineNotTheName() {
        String regex = null;
        for (Check.Expect e : AutoQaCatalog.find("t-rm-3").expects()) {
            if (e.describe().startsWith("LLM judge: /")) {
                regex = e.describe().substring("LLM judge: /".length(), e.describe().lastIndexOf("/ ("));
            }
        }
        var m = Pattern.compile(regex.replace("{staged}", Pattern.quote("iiKillerx") + "|" + Pattern.quote("iichibi")))
                .matcher("CHAT iiKillerx: hey AutoQaOne. i still think about us sometimes");
        assertTrue(m.find());
        assertTrue(m.group(1).startsWith("hey AutoQaOne"), m.group(1));
    }
}
