package net.server.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * 1.5.2: World.updateParty threw IllegalArgumentException("no party with the specified partyid exists") when the party
 * had been dissolved before the update arrived. That race is normal - a leader disbands while a member logs out - and
 * the throw made callers deal with it: the biggest line in the test server's log was a bot whose leader had disbanded
 * its party, throwing on every logout attempt (BotPartyCommands.botLeaveParty), and a mass warp inside an event script
 * aborted with "ScriptException: java.lang.IllegalArgumentException: no party with the specified partyid exists". A
 * missing party means the operation changes nothing, which is what the method already reports as false.
 */
class UpdatePartyMissingPartyTest {

    @Test
    void updateForAPartyTheWorldNoLongerHoldsChangesNothing() {
        World world = mock(World.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(null).when(world).getParty(4242);

        PartyCharacter target = new PartyCharacter();

        assertFalse(world.updateParty(4242, PartyOperation.LEAVE, target));
        assertFalse(world.updateParty(4242, PartyOperation.DISBAND, target));
        assertFalse(world.updateParty(4242, PartyOperation.SILENT_UPDATE, target));
    }
}
