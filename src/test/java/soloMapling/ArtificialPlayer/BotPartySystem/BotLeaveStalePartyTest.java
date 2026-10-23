package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.World;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.5.0 rc5: a bot holding a party the world already dropped logged "could not log out" every 20 s forever. */
class BotLeaveStalePartyTest {

    @Test
    void aStalePartyIsClearedInsteadOfThrowing() {
        Character bot = mock(Character.class);
        Party party = mock(Party.class);
        World world = mock(World.class);
        when(party.getId()).thenReturn(4242);
        when(bot.getParty()).thenReturn(party);
        when(bot.getWorldServer()).thenReturn(world);
        when(bot.getName()).thenReturn("Narutard");
        when(world.getParty(4242)).thenReturn(null);

        BotPartyCommands.botLeaveParty(bot);

        verify(world, never()).updateParty(anyInt(), any(), any());
        verify(bot).setParty(null);
    }
}
