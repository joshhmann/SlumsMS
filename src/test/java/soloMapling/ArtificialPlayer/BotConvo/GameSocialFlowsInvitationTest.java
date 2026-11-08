package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Offer;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 1.6.6 accept-fail-reason step 2: an INVITE never sends an offer the LFG rules already declined (TEST c42a85edf: all
 * 28 failed ACCEPTs were "LFG rules said no"); the come-along stands in when there is one. See
 * docs/bots/accept-fail-reason.md.
 */
class GameSocialFlowsInvitationTest {
    private static final Offer PQ_NO = new Offer(Ask.PARTY, "KPQ", Map.of("pq", "kpq"), null, Feasible.no("too low lol"));
    private static final Offer PQ_YES = new Offer(Ask.PARTY, "KPQ", Map.of("pq", "kpq"), null, Feasible.YES);
    private static final Offer DUO = new Offer(Ask.PARTY, "a duo", Map.of(), null, null);
    private static final Offer TOWN = new Offer(Ask.COME_ALONG, "Henesys", Map.of("town", "Henesys"), 100000000, null);

    private static Map<Ask, Offer> offers(Offer... os) {
        Map<Ask, Offer> m = new EnumMap<>(Ask.class);
        for (Offer o : os) {
            m.put(o.ask(), o);
        }
        return m;
    }

    @Test
    void aDeclinedPqPartyFallsBackToTheComeAlong() {
        assertSame(TOWN, GameSocialFlows.invitation(offers(PQ_NO, TOWN)));
    }

    @Test
    void aDeclinedPqPartyAloneInvitesNothing() {
        assertNull(GameSocialFlows.invitation(offers(PQ_NO)));
    }

    @Test
    void anAgreedPqPartyAndAPlainDuoAreSentAsBefore() {
        assertSame(PQ_YES, GameSocialFlows.invitation(offers(PQ_YES, TOWN)));
        assertSame(DUO, GameSocialFlows.invitation(offers(DUO, TOWN)));
    }

    @Test
    void aComeAlongAloneIsSentAsBefore() {
        assertSame(TOWN, GameSocialFlows.invitation(offers(TOWN)));
        assertNull(GameSocialFlows.invitation(offers()));
    }
}
