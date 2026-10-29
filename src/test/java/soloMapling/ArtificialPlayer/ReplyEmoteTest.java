package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.ArtificialPlayer.BotChatActions.Outcome;
import static soloMapling.ArtificialPlayer.ReplyEmote.ANGRY;
import static soloMapling.ArtificialPlayer.ReplyEmote.CRY;
import static soloMapling.ArtificialPlayer.ReplyEmote.NONE;
import static soloMapling.ArtificialPlayer.ReplyEmote.SMILE;
import static soloMapling.ArtificialPlayer.ReplyEmote.SURPRISED;
import static soloMapling.ArtificialPlayer.ReplyEmote.TROUBLED;

class ReplyEmoteTest {

    @AfterEach
    void reset() {
        System.clearProperty("solomapling.chat.replyEmoteChance");
    }

    private static int face(String asked, Outcome outcome, String reply) {
        return ReplyEmote.face(ChatTriage.classify(asked, null), outcome, asked, reply);
    }

    @Test
    void greetingsAndThanksGetASmile() {
        assertEquals(SMILE, face("hi", Outcome.NONE, "hey"));
        assertEquals(SMILE, face("ty", Outcome.NONE, "anytime"));
        assertEquals(SMILE, face("cya", Outcome.NONE, "later"));
    }

    @Test
    void consGetAnAngryOrPuzzledFace() {
        assertEquals(ANGRY, face("lend me 10m", Outcome.NONE, "nah"));
        assertEquals(ANGRY, face("go die noob", Outcome.NONE, "ok"), "harassment by rule (a death wish)");
        assertEquals(TROUBLED, face("ill pay u 5 dollars for ur account", Outcome.NONE, "no thx"));
        assertEquals(SURPRISED, face("are you a bot", Outcome.NONE, "lol what"));
    }

    @Test
    void theConFlagBeatsTheReplysWords() {
        assertEquals(ANGRY, face("give me mesos", Outcome.NONE, "lol no"));
    }

    @Test
    void whatTheGameDidSetsTheFace() {
        assertEquals(SMILE, face("party?", Outcome.PARTY_ACCEPTED, "sure inv me"));
        assertEquals(TROUBLED, face("party?", Outcome.PARTY_DECLINED, "cant rn"));
        assertEquals(TROUBLED, face("party?", Outcome.PARTY_ALREADY, "im in a party rn"));
        assertEquals(ANGRY, face("party?", Outcome.PARTY_WARY, "no"));
        assertEquals(SMILE, face("where do u train?", Outcome.WHERE_TRAIN, "ant tunnel mostly"));
    }

    @Test
    void theReplysOwnToneCounts() {
        assertEquals(SMILE, face("how is it going", Outcome.NONE, "pretty good lol"));
        assertEquals(CRY, face("i died", Outcome.NONE, "rip :("));
        assertEquals(SURPRISED, face("i got a zakum helm", Outcome.NONE, "omg no way"));
        assertEquals(TROUBLED, face("whats ur fav job", Outcome.NONE, "hmm not sure"));
        assertEquals(ANGRY, face("whats up", Outcome.NONE, "ugh this map is so crowded"));
    }

    @Test
    void aPlainAnswerToAQuestionIsThoughtful_plainTalkHasNoFace() {
        assertEquals(TROUBLED, face("what lvl r u?", Outcome.NONE, "34"));
        assertEquals(NONE, face("im going to ellinia", Outcome.NONE, "same here"));
    }

    @Test
    void onlyTheSevenFreeFacesAreUsed() {
        String[] replies = {"lol", "rip", "omg", "ugh", "idk", "hey", "34", ":)", "T_T", "wow!?"};
        for (String reply : replies) {
            for (Outcome outcome : Outcome.values()) {
                int face = face("what?", outcome, reply);
                assertTrue(face >= NONE && face <= 7, reply + " " + outcome + " -> " + face);
            }
        }
    }

    @Test
    void fillerFacesFollowTheLine() {
        assertEquals(SMILE, ReplyEmote.forFiller("lol"));
        assertEquals(SURPRISED, ReplyEmote.forFiller("wow"));
        assertEquals(CRY, ReplyEmote.forFiller("t_t"));
        assertEquals(SMILE, ReplyEmote.forFiller("ok"));
    }

    @Test
    void theChanceIsTunable() {
        System.setProperty("solomapling.chat.replyEmoteChance", "0");
        assertEquals(NONE, ReplyEmote.pick(ChatTriage.classify("hi", null), Outcome.NONE, "hi", "hey", new Random(1)));
        System.setProperty("solomapling.chat.replyEmoteChance", "1");
        assertEquals(SMILE, ReplyEmote.pick(ChatTriage.classify("hi", null), Outcome.NONE, "hi", "hey", new Random(1)));
        System.setProperty("solomapling.chat.replyEmoteChance", "0.55");
        Random rng = new Random(7);
        int shown = 0;
        for (int i = 0; i < 2000; i++) {
            if (ReplyEmote.pick(ChatTriage.classify("hi", null), Outcome.NONE, "hi", "hey", rng) != NONE) {
                shown++;
            }
        }
        assertTrue(shown > 1000 && shown < 1200, "shown " + shown);
    }

    @Test
    void noFaceForAPlainLineEvenAtFullChance() {
        System.setProperty("solomapling.chat.replyEmoteChance", "1");
        assertEquals(NONE, ReplyEmote.pick(ChatTriage.classify("im going to ellinia", null), Outcome.NONE,
                "im going to ellinia", "same here", new Random(1)));
    }
}
