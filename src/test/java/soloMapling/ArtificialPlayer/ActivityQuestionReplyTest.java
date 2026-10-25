package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityQuestionReplyTest {
    @Test void recognizesDirectActivityQuestionsWithoutNeedingAPunctuationMark() {
        for (String line : new String[]{"what u doing", "what are you doing?", "what r u doing rn", "wyd?",
                "WHAT ARE YOU UP TO RN?", "whatcha doing", "what you doing right now!"})
            assertEquals("chatting with u rn", ScriptedReplies.activityQuestion(line), line);
    }

    @Test void storiesRefusalsOtherQuestionsAndCompoundRequestsKeepTheirOwnPath() {
        for (String line : new String[]{"he asked what u doing", "what were you doing", "what u doing yesterday?",
                "what u doing, follow me", "dont ask what u doing", "where u training?", "lol ok", "hey"})
            assertNull(ScriptedReplies.activityQuestion(line), line);
        assertNull(ScriptedReplies.activityQuestion(null));
    }
}
