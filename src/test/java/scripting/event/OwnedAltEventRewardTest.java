package scripting.event;

import client.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/* Owner 2026-10-26: in a PQ an owned alt is a full member and gets the PQ's rewards. An alt cannot talk to the reward
 * NPC or walk into the reward portal, so it gets its reward when its owner claims theirs. */
class OwnedAltEventRewardTest {

    private static Character player(int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private static Character altOf(Character owner, int id) {
        Character alt = player(id);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        return alt;
    }

    @Test
    void onlyTheClaimingPlayersOwnAltsShareTheReward() {
        Character owner = player(1);
        Character other = player(2);
        Character mine = altOf(owner, 10);
        Character theirs = altOf(other, 11);
        assertEquals(List.of(mine), EventInstanceManager.ownedAltsOf(owner, List.of(owner, other, mine, theirs)));
        assertTrue(EventInstanceManager.ownedAltsOf(mine, List.of(owner, mine)).isEmpty(), "an alt has no alts");
    }

    @Test
    void theOwnersClaimRewardsTheirAltsInTheInstance() {
        Character owner = player(1);
        Character alt = altOf(owner, 10);
        Character stranger = player(3);
        EventInstanceManager eim = mock(EventInstanceManager.class, CALLS_REAL_METHODS);
        doReturn(List.of(owner, alt, stranger)).when(eim).getPlayers();
        doReturn(true).when(eim).giveOwnEventReward(any(), anyInt());

        assertTrue(eim.giveEventReward(owner, 2));
        verify(eim).giveOwnEventReward(owner, 2);
        verify(eim).giveOwnEventReward(alt, 2);
        verify(eim, never()).giveOwnEventReward(stranger, 2);
    }

    @Test
    void noClaimNoAltReward() {
        Character owner = player(1);
        Character alt = altOf(owner, 10);
        EventInstanceManager eim = mock(EventInstanceManager.class, CALLS_REAL_METHODS);
        doReturn(List.of(owner, alt)).when(eim).getPlayers();
        doReturn(false).when(eim).giveOwnEventReward(owner, 1);

        assertEquals(false, eim.giveEventReward(owner, 1), "the owner's bag is full: they try again");
        verify(eim, never()).giveOwnEventReward(alt, 1);
    }
}
