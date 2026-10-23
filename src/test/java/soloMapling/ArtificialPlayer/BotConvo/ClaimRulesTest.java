package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.ArtificialPlayer.BotConvo.ClaimRules.Kind.*;

/** rc6 say-do: the claim rules, on lines from the bank, the dialogue packs and the test server's transcripts. */
class ClaimRulesTest {

    private static List<ClaimRules.Kind> strong(String line) {
        return ClaimRules.classify(line).stream().filter(c -> !c.weak()).map(ClaimRules.Claim::kind).toList();
    }

    private static void claims(String line, ClaimRules.Kind... kinds) {
        assertEquals(List.of(kinds), strong(line), line);
    }

    @Test
    void theOwnersExamplesAreClaims() {
        claims("brb buy pots", RESUPPLY);
        claims("brb pots", RESUPPLY);
        claims("gotta restock on pots before I head out", RESUPPLY);
        claims("omw", COME);
        claims("be there in a sec", COME);
        claims("going to orbis anyone?", GO_TO);
        claims("heading to the fm", GO_TO);
        claims("follow me", FOLLOW);
        claims("ill invite u", PARTY_OFFER);
        claims("inv me", PARTY_ASK);
        claims("LFG kpq", PARTY_ASK);
        claims("healing u", BUFF);
        claims("S> Ilbi 4.5m per set cheap!!! pm me", SELL);
        claims("B> 60% scroll for claw att paying 15m ea @@@", BUY);
        claims("trade me", TRADE);
        claims("brb dinner", AWAY);
        claims("my mom's calling me hold on", AWAY);
        claims("gtg", LEAVING);
        claims("night all", LEAVING);
        claims("training at ant tunnel", TRAIN_AT);
        claims("here take this", GIFT);
        claims("leaving pt, ty all", LEAVE_GROUP);
    }

    @Test
    void notClaims() {
        claims("gl selling tho");
        claims("someone in henesys is selling ilbis for half the going rate");
        claims("the dudes spamming wts in all caps every 5 seconds");
        claims("I AM GOING TO LOSE MY MIND");
        claims("no buff for u sry");
        claims("not giving you my dagger, nice try");
        claims("she quit the guild after the breakup, total mess");
        claims("quit ksing me you already died twice");
        claims("has anyone else noticed how many people are afk in this map");
        claims("yeah sorry was afk");
        claims("he said \"brb 5 min\" 30 minutes ago in my buddy chat lol");
        claims("the worst part is I knew it was coming");
        claims("honestly been stalking this shop waiting for you to restock");
        claims("Can't buff you right now, I'm out of it");
    }

    @Test
    void weakOnesAreMarked() {
        assertTrue(ClaimRules.classify("im out").get(0).weak());
        assertTrue(ClaimRules.classify("this way, dont get lost").get(0).weak());
        assertTrue(ClaimRules.classify("thinking about heading to ellinia after this").get(0).weak());
        assertFalse(ClaimRules.classify("k coming").get(0).weak());
    }

    @Test
    void placesAndItemsAreRead() {
        ClaimRules.Claim go = ClaimRules.classify("heading to ellinia, need to grab some stuff").get(0);
        assertEquals(101000000, go.mapId());
        ClaimRules.Claim sell = ClaimRules.classify("S> steelys 1.8m/set @@ henesys ch1").get(0);
        assertEquals("steelys", sell.item());
        assertTrue(ClaimRules.sameArea(910000003, 910000000), "an FM room is the FM");
        assertFalse(ClaimRules.sameArea(100000000, 101000000));
    }
}
