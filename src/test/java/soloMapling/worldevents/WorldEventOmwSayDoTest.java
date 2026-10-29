package soloMapling.worldevents;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.ClaimRules;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b068 follow-up (TEST 2026-10-29, since the say-do build): 9 of 18 "omw to the cake!!" / "invasion?? omw" lines were
 * said by bots already standing on the event's map (they walked nowhere), and 7 "brb getting cake" claimed a brb the
 * bot never took (it went for the cake). A bot on the event map claims no trip; no event line claims a brb.
 */
class WorldEventOmwSayDoTest {

    @Test
    void aBotAlreadyAtTheEventSaysNoOmwAndNoLineSaysBrb() {
        Random rng = new Random(7);
        for (EventTemplate.Kind kind : EventTemplate.Kind.values()) {
            for (boolean here : new boolean[]{true, false}) {
                for (int i = 0; i < 40; i++) {
                    String line = WorldEventReactions.eventLine(kind, "Henesys", "Mano", here, rng);
                    assertNotNull(line, kind + " here=" + here + " has a line it can back");
                    for (ClaimRules.Claim c : ClaimRules.classify(line)) {
                        assertTrue(c.kind() != ClaimRules.Kind.AWAY, "no brb: " + line);
                        assertTrue(!here, "a bot on the event map claims nothing: " + line + " " + c);
                        assertTrue(WorldEventReactions.eventClaims(false).contains(c.kind()), line + " " + c);
                    }
                }
            }
        }
    }

    @Test
    void aBotFarAwayStillSaysItIsOnItsWay() {
        Random rng = new Random(8);
        boolean claimed = false;
        for (int i = 0; i < 20; i++) {
            String line = WorldEventReactions.eventLine(EventTemplate.Kind.GIANT_CAKE, "Henesys", null, false, rng);
            claimed |= !ClaimRules.classify(line).isEmpty();
        }
        assertTrue(claimed, "the trip it takes backs an omw");
    }
}
