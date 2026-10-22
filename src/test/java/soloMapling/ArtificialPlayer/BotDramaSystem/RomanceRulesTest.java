package soloMapling.ArtificialPlayer.BotDramaSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RomanceRulesTest {

    static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void nothingRomanticWithoutTheOptIn() {
        assertFalse(RomanceRules.mayFlirt(false, AffinityState.FRIEND, 90, false), "romance off: no flirt");
        assertFalse(RomanceRules.mayAskOut(false, AffinityState.FRIEND, 90, false, false), "romance off: no asking out");
        assertFalse(RomanceRules.saysYes(false, AffinityState.FRIEND, 90, false), "romance off: no yes");
        Romance dating = new Romance(1, 5, Romance.Stage.DATING, 0, 0, 0, 0, 0, false);
        assertFalse(RomanceRules.acceptsRing(false, dating, AffinityState.FRIEND), "romance off: no ring");
        assertTrue(RomanceRules.acceptsRing(true, dating, AffinityState.FRIEND));
    }

    @Test
    void flirtingNeedsAFriendItLikesALotAndASingleBot() {
        assertTrue(RomanceRules.mayFlirt(true, AffinityState.FRIEND, 50, false));
        assertFalse(RomanceRules.mayFlirt(true, AffinityState.FRIEND, 30, false), "not close enough");
        assertFalse(RomanceRules.mayFlirt(true, AffinityState.NEUTRAL, 90, false), "not a friend");
        assertFalse(RomanceRules.mayFlirt(true, AffinityState.FRIEND, 90, true), "taken");
        assertFalse(RomanceRules.mayAskOut(true, AffinityState.FRIEND, 50, false, false), "flirts, but not asking yet");
        assertTrue(RomanceRules.mayAskOut(true, AffinityState.FRIEND, 60, false, false));
        assertFalse(RomanceRules.mayAskOut(true, AffinityState.FRIEND, 60, false, true), "already together");
        assertTrue(RomanceRules.saysYes(true, AffinityState.FRIEND, 40, false));
        assertFalse(RomanceRules.saysYes(true, AffinityState.RIVAL, 40, false));
    }

    @Test
    void neglectEndsDatingButNeverAMarriage() {
        long now = 100 * DAY;
        Romance fresh = new Romance(1, 5, Romance.Stage.DATING, now - 10 * DAY, now - DAY, 0, 0, 0, false);
        Romance stale = new Romance(1, 5, Romance.Stage.DATING, now - 10 * DAY, now - 5 * DAY, 0, 0, 0, false);
        Romance married = new Romance(1, 5, Romance.Stage.MARRIED, now - 10 * DAY, now - 9 * DAY, 0, 1112803, 7, false);
        assertFalse(RomanceRules.neglected(fresh, now));
        assertTrue(RomanceRules.neglected(stale, now));
        assertFalse(RomanceRules.neglected(married, now), "divorce is the player's call");
        Romance ex = new Romance(1, 5, Romance.Stage.EX, 0, 0, now - 61 * DAY, 0, 0, false);
        assertTrue(RomanceRules.oldFlame(ex, now));
        assertFalse(RomanceRules.oldFlame(new Romance(1, 5, Romance.Stage.EX, 0, 0, now - DAY, 0, 0, false), now));
        assertTrue(RomanceRules.readyToPropose(stale, now));
    }

    @Test
    void answersAreRead() {
        assertTrue(RomanceRules.yesNo("yes!!"));
        assertTrue(RomanceRules.yesNo("sure <3"));
        assertTrue(RomanceRules.yesNo("i do"));
        assertFalse(RomanceRules.yesNo("no thanks"));
        assertFalse(RomanceRules.yesNo("nah"));
        assertNull(RomanceRules.yesNo("where is ant tunnel"));
        assertTrue(RomanceRules.asksOut("Amy wanna go out with me?"));
        assertTrue(RomanceRules.asksOut("be my gf"));
        assertFalse(RomanceRules.asksOut("wanna party?"));
        assertTrue(RomanceRules.apology("sorry about that"));
        assertTrue(RomanceRules.apology("my bad"));
        assertFalse(RomanceRules.apology("sorry not sorry"));
        assertTrue(RomanceRules.mocking("lol cry more"));
        assertTrue(RomanceRules.mocking("sorry not sorry"));
        assertFalse(RomanceRules.mocking("sorry about that"));
    }

    @Test
    void ringBoxesMapLikeTheStockCode() {
        assertEquals(4031357, RomanceRules.engagementTokenOf(2240000));
        assertEquals(4031363, RomanceRules.engagementTokenOf(2240003));
        assertEquals(1112803, RomanceRules.weddingRingOf(2240000));
        assertEquals(1112809, RomanceRules.weddingRingOf(2240003));
        assertEquals(1112807, RomanceRules.weddingRingOf(4031362));
        assertTrue(RomanceRules.engagementToken(4031360));
        assertFalse(RomanceRules.engagementToken(2240000));
    }
}
