package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Goal;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 b042 ("bots greet the bots they know when they run into each other"): before, only the bot that just arrived
 * could say hi, so a busy arrival met nobody even when the friend standing there knew it. Now the one standing there
 * may greet first, or instead.
 */
class BotTalkGreetBackTest {

    static Mind mind(int id, boolean busy) {
        return new Mind(id, "b" + id, 30, 0.8, 0.1, 0.5, Goal.NONE, 0, false, false, false, -1, busy);
    }

    static final Relation FRIEND = new Relation("friend", 0.8, true);

    @Test
    void theFriendStandingThereGreetsWhenTheArrivalIsBusy() {
        Random rng = new Random(1);
        int standing = 0;
        for (int i = 0; i < 100; i++) {
            int o = BotTalk.greetOpener(mind(1, true), FRIEND, mind(2, false), FRIEND, rng);
            assertTrue(o != 0, "a busy arrival never opens");
            standing += o == 1 ? 1 : 0;
        }
        assertTrue(standing >= 85, "the one standing there says hi nearly always: " + standing + "/100");
    }

    @Test
    void eitherMayGoFirstAndStrangersOnBothSidesStaySilent() {
        Random rng = new Random(2);
        int standing = 0, arriving = 0;
        for (int i = 0; i < 400; i++) {
            int o = BotTalk.greetOpener(mind(1, false), FRIEND, mind(2, false), FRIEND, rng);
            standing += o == 1 ? 1 : 0;
            arriving += o == 0 ? 1 : 0;
        }
        assertTrue(standing > 80 && arriving > 160, "both open sometimes: standing " + standing + ", arriving " + arriving);
        assertEquals(-1, BotTalk.greetOpener(mind(1, false), Relation.STRANGER, mind(2, false), Relation.STRANGER,
                new Random(3)), "nobody greets a bot it does not know");
        assertEquals(1, BotTalk.greetOpener(mind(1, false), Relation.STRANGER, mind(2, false), FRIEND, new Random(4)),
                "the one that remembers the other says hi");
    }
}
