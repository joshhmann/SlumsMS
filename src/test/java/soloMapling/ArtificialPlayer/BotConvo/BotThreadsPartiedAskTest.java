package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * b068 (TEST 2026-10-28): thread lines like "lf party for kpq r u in" from a bot already in a party were unbacked claims
 * ("asks for a party while in one"). A partied speaker may not ask for a party; the line loses that part or is dropped.
 */
class BotThreadsPartiedAskTest {

    @Test
    void aBotInAPartyNeverAsksForOne() {
        Set<ClaimRules.Kind> lfgNo = EnumSet.of(ClaimRules.Kind.PARTY_ASK);
        Set<ClaimRules.Kind> partied = BotThreads.forSpeaker(lfgNo, true);
        assertFalse(partied.contains(ClaimRules.Kind.PARTY_ASK));
        assertTrue(lfgNo.contains(ClaimRules.Kind.PARTY_ASK), "the thread's own set is left as it was");
        assertEquals(lfgNo, BotThreads.forSpeaker(lfgNo, false), "a partyless bot may still ask");
        assertEquals(null, ClaimActions.guardReply("lf party for kpq r u in", partied), "the whole line is the ask");
        assertEquals("lf party for kpq r u in", ClaimActions.guardReply("lf party for kpq r u in",
                BotThreads.forSpeaker(lfgNo, false)));
    }
}
