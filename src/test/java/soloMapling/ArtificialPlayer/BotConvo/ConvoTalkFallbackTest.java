package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TEST cv-7-20261024T222923Z: bank mode answered "wanna grind together" with "that drop rate is awful" and "lol ok"
 * with "ur right about hnesys being crowded tbh" - two of four lines judged BAD for ignoring the player.
 */
class ConvoTalkFallbackTest {

    @AfterEach
    void clearBank() {
        ChatBank.useForTest(null);
        ConvoLines.useWeights(null);
    }

    private ChatBank bank(String... rows) throws java.io.IOException {
        ChatBank b = ChatBank.read(new ByteArrayInputStream(String.join("\n", rows).getBytes(StandardCharsets.UTF_8)));
        ChatBank.useForTest(b);
        return b;
    }

    /** id, intent, text; any tone, affinity and voice; no situation tags. */
    private static String row(int id, String intent, String text) {
        return id + "\t" + intent + "\tany\tany\tany\t-\t" + text;
    }

    private static ChatBank.Query query(String key) {
        return new ChatBank.Query(key, "neutral", "neutral", null, 0, java.util.Map.of(), 0);
    }

    @Test
    void anInviteTheGameDidNotActOnGetsADeclineNotTheGenericTalkLine() {
        assertEquals("come_no", ConvoLines.key(ChatTriage.Kind.TALK, ChatTriage.Intent.COME_WITH_ME, "wanna grind together"));
        assertEquals("party_no", ConvoLines.key(ChatTriage.Kind.TALK, ChatTriage.Intent.PARTY, "party?"));
        assertEquals("greeting", ConvoLines.key(ChatTriage.Kind.GREETING, ChatTriage.Intent.COME_WITH_ME, "hey come"),
                "a greeting is still a greeting");
        assertEquals("talk", ConvoLines.key(ChatTriage.Kind.TALK, ChatTriage.Intent.NONE, "lol ok"));
        assertEquals("talk_q", ConvoLines.key(ChatTriage.Kind.TALK, ChatTriage.Intent.NONE, "where u at?"));
    }

    @Test
    void theGenericFallbackIsAnAcknowledgementNotAClaimAboutSomeTopic() throws Exception {
        ChatBank b = bank(row(1, "talk", "that drop rate is awful"),
                row(2, "talk", "ur right about hnesys being crowded tbh"),
                row(3, "talk", "ya same"),
                row(4, "talk", "lol true"));
        ChatBank.Query q = query("talk");
        // the selection the fallback used before the fix: any talk line, so a claim about drops answers an invite
        Random oldRng = new Random(1); // one generator: new Random(seed) draws the same first double for near seeds
        boolean oldSaidATopic = false;
        for (int draw = 0; draw < 200; draw++) {
            ChatBank.Pick old = b.pick(q, null, null, oldRng);
            oldSaidATopic |= old != null && old.id() <= 2;
        }
        assertTrue(oldSaidATopic, "the old selection hands back a claim about a topic");
        Random rng = new Random(1);
        for (int draw = 0; draw < 200; draw++) {
            ChatBank.Pick pick = ConvoLines.pickAcknowledgement(b, q, null, rng);
            assertNotNull(pick, "an acknowledgement answers instead, draw " + draw);
            assertTrue(pick.id() == 3 || pick.id() == 4, "draw " + draw + " picked " + pick.text());
        }
    }

    @Test
    void aBankWithNoAcknowledgementLeavesTheScriptedNeutralLineToAnswer() throws Exception {
        ChatBank b = bank(row(1, "talk", "that drop rate is awful"));
        assertNull(ConvoLines.pickAcknowledgement(b, query("talk"), null, new Random(1)),
                "no claim answers: the caller's neutral script does");
    }

    @Test
    void anAcknowledgementIsShortAndSaysNothingAboutATopic() {
        assertTrue(ConvoLines.acknowledgement("ya same"));
        assertTrue(ConvoLines.acknowledgement("lol ok tbh"));
        assertTrue(ConvoLines.acknowledgement("..."));
        assertFalse(ConvoLines.acknowledgement("that drop rate is awful"));
        assertFalse(ConvoLines.acknowledgement("ur right about hnesys being crowded tbh"));
        assertFalse(ConvoLines.acknowledgement("ya same tbh lol wait what now"),
                "more than four words is a sentence, not an acknowledgement");
    }
}
