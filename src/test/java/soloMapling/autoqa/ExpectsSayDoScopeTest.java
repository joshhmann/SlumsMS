package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rc7 t-cv-4 (deployed build bbd1ac0b1): the say-do expectation counted every CONVO claim line on the map, so an
 * ambient bot's "S&gt; 10% glove att" (unrelated to the four voices the check stages, and a real unbacked ad in its own
 * right) failed the check. Only the staged bots' claims are the check's verdict now; the ambient one stays on the
 * CONVO topic and in the daily report.
 */
class ExpectsSayDoScopeTest {

    /** The exact line the deployed run failed on (SayDo's CONVO format, with the map). */
    private static final String AMBIENT = "SAYDO UNBACKED sell ZhelmRamen98 [scripted]"
            + " map 100000000 \"S> 10% glove att\" (does not hold '10% glove att')";

    /** The same lie from one of the bots cv-4 stages: still a violation of the check's own conversation. */
    private static final String STAGED_LIE = "SAYDO UNBACKED sell VoiceLeet99 [reply-llm] map 100000000"
            + " \"S> 10% glove att\" (does not hold '10% glove att')";

    private static final Set<String> VOICES = Set.of("VoiceLower1", "VoiceProper2", "VoiceLeet99", "VoiceKorean4");

    private static AutoQaLine line(String message) {
        return new AutoQaLine(0, "qa", "CONVO", message);
    }

    @Test
    void theAmbientShopLineOfAnotherBotDoesNotFailTheCheck() {
        List<AutoQaLine> unbacked = Expects.bySpeaker(List.of(line(AMBIENT)), VOICES);
        assertTrue(unbacked.isEmpty(), "an ambient bot's ad is not this check's claim");
        Check.Verdict v = Expects.sayDoVerdict(0, List.of(), unbacked, 1, false);
        assertEquals(Check.Status.PASS, v.status(), v.evidence().toString());
        assertTrue(v.evidence().get(0).contains("1 claim(s) by other bots (not judged here)"),
                "the ambient finding is named in the evidence, not silently dropped: " + v.evidence());
    }

    @Test
    void theSameLineFromAStagedBotStillFails() {
        List<AutoQaLine> unbacked = Expects.bySpeaker(List.of(line(AMBIENT), line(STAGED_LIE)), VOICES);
        assertEquals(1, unbacked.size(), "only the staged bot's claim is judged");
        Check.Verdict v = Expects.sayDoVerdict(0, List.of(), unbacked, 1, false);
        assertEquals(Check.Status.FAIL, v.status());
        assertTrue(v.evidence().stream().anyMatch(e -> e.contains("VoiceLeet99")), v.evidence().toString());
        assertTrue(v.evidence().stream().noneMatch(e -> e.contains("ZhelmRamen98")),
                "the ambient bot is not evidence of the check's failure: " + v.evidence());
    }

    @Test
    void aStagedBotsWeakClaimIsListedNotFailed() {
        String weak = STAGED_LIE.replace("sell ", "sell (weak) ");
        Check.Verdict v = Expects.sayDoVerdict(0, List.of(), List.of(line(weak)), 0, false);
        assertEquals(Check.Status.PASS, v.status());
        assertTrue(v.evidence().stream().anyMatch(e -> e.startsWith("weak: ")), v.evidence().toString());
    }

    @Test
    void noStagedBotMeansNothingToScopeToAndTheOldBehaviourStands() {
        List<AutoQaLine> unbacked = Expects.bySpeaker(List.of(line(AMBIENT)), Set.of());
        assertEquals(1, unbacked.size());
        Check.Verdict v = Expects.sayDoVerdict(0, List.of(), unbacked, 0, true);
        assertEquals(Check.Status.FAIL, v.status());
        assertTrue(v.evidence().get(0).contains("no staged bot known"), v.evidence().toString());
    }

    @Test
    void theSpeakerAndTheMapAreBothReadFromTheLine() {
        var m = Expects.SAYDO_SPEAKER.matcher(AMBIENT);
        assertTrue(m.find());
        assertEquals("ZhelmRamen98", m.group(1));
        var w = Expects.SAYDO_SPEAKER.matcher("SAYDO BACKED away (weak) VoiceLower1 [bank] map 100000000 \"brb\" (stayed put)");
        assertTrue(w.find());
        assertEquals("VoiceLower1", w.group(1));
    }
}
