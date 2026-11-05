package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.4 feature-health finding: two in three social INVITE acts on TEST found "nothing to invite them to" after the
 * act was picked. The INVITE precondition now asks {@link BotTalk#anInviteToMake} first, which reads the same offers
 * {@code WorldSense.offers} makes without reserving a PQ slot or a duo-budget slot.
 */
class BotTalkInviteToMakeTest {

    static final LfgParty.State FREE = new LfgParty.State(30, false, false, false, false, false, true);
    static final LfgParty.State FREE_NO_TRIP = new LfgParty.State(30, false, false, false, false, false, false);
    static final LfgParty.State PARTIED = new LfgParty.State(30, true, false, false, false, false, true);

    @BeforeEach
    void setUp() {
        BotTalk.resetForTest();
    }

    @AfterEach
    void tearDown() {
        BotTalk.resetForTest();
    }

    @Test
    void aFullDuoBudgetWithNoLobbyAndNoTripIsNothingToInviteThemTo() {
        assertFalse(BotTalk.anInviteToMake(FREE, false, false, false));
        assertFalse(BotTalk.anInviteToMake(FREE_NO_TRIP, true, false, false), "a lobby needs a bot that can take the trip");
    }

    @Test
    void aLobbyADuoSlotOrATownTripIsSomethingToInviteThemTo() {
        assertTrue(BotTalk.anInviteToMake(FREE, true, false, false));
        assertTrue(BotTalk.anInviteToMake(FREE_NO_TRIP, false, true, false));
        assertTrue(BotTalk.anInviteToMake(FREE_NO_TRIP, false, false, true));
        assertTrue(BotTalk.anInviteToMake(PARTIED, false, false, true), "a come-along needs no free party state");
        assertFalse(BotTalk.anInviteToMake(PARTIED, true, true, false), "a partied bot offers no party");
    }

    @Test
    void theDuoBudgetPeekTakesNothingAndSaysNoOnceSixPartiesStartedInTenMinutes() {
        long now = 1_000_000_000L;
        for (int i = 0; i < 5; i++) {
            assertTrue(BotTalk.duoPartyRoom(now));
            BotTalk.took(BotTalk.plainPartiesForTest(), now);
        }
        assertTrue(BotTalk.duoPartyRoom(now), "peeking five times took nothing");
        BotTalk.took(BotTalk.plainPartiesForTest(), now);
        assertFalse(BotTalk.duoPartyRoom(now));
        assertTrue(BotTalk.duoPartyRoom(now + 10 * 60_000L + 1), "the window rolls on");
    }
}
