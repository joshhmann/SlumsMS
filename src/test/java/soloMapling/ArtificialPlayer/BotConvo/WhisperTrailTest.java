package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperTrailTest {

    private static String state(String line, List<ChatDecide.Earlier> earlier) {
        return (String) ChatDecide.request(line, List.of(ChatTriage.INTENT), "jev-latest", ChatDecide.contextBlock(earlier))
                .get("state");
    }

    @Test
    void whisperRequestCarriesThePairsPreviousWhispersNewestLast() {
        var trail = new WhisperTrail();
        trail.add(true, "hey, you play a lot?", 1_000);
        trail.add(false, "yeah most days", 2_000);
        trail.add(true, "cool, trade me your gear to hold", 3_000);
        assertEquals("Earlier in this chat:\nplayer: hey, you play a lot?\nyou: yeah most days\n"
                        + "player: cool, trade me your gear to hold\n"
                        + "A MapleStory player typed this to you in chat: send me the mesos",
                state("send me the mesos", trail.earlier(4_000)));
    }

    @Test
    void anotherPairsTrailIsNeverMixedIn() {
        var withAnn = new WhisperTrail();
        var withBob = new WhisperTrail();
        withAnn.add(true, "ann secret", 1_000);
        withBob.add(true, "bob line", 1_000);
        assertEquals(List.of(new ChatDecide.Earlier(ChatDecide.Role.PLAYER, "bob line")), withBob.earlier(2_000));
        assertTrue(new WhisperTrail().earlier(2_000).isEmpty());
        assertEquals(state("x", List.of()), state("x", new WhisperTrail().earlier(2_000)));
    }

    @Test
    void expiredLinesAreNotSentAndOnlyFiveAreKept() {
        var trail = new WhisperTrail();
        trail.add(true, "old", 0);
        for (int i = 1; i <= 7; i++) {
            trail.add(i % 2 == 0, "l" + i, WhisperTrail.EXPIRY_MS + i);
        }
        var out = trail.earlier(WhisperTrail.EXPIRY_MS + 10);
        assertEquals(5, out.size());
        assertEquals("l3", out.get(0).text());
        assertEquals("l7", out.get(4).text());
        assertTrue(trail.earlier(2 * WhisperTrail.EXPIRY_MS + 100).isEmpty());
    }
}
