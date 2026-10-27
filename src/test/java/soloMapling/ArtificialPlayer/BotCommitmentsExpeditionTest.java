package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.ExpeditionBotsFixture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.6.0: a party bot registered for its player's boss expedition is held (no logout, no slow wheel) until it ends. */
class BotCommitmentsExpeditionTest {

    @Test
    void aBotInItsPlayersExpeditionIsCommittedUntilTheExpeditionEnds() {
        MapleMap altar = mock(MapleMap.class);
        net.server.world.World world = mock(net.server.world.World.class);
        when(world.getPlayerStorage()).thenReturn(mock(net.server.PlayerStorage.class));
        when(altar.getWorldServer()).thenReturn(world);
        ExpeditionBotsFixture.Joined j = ExpeditionBotsFixture.join(altar, 1, 1_000_000_123);
        try {
            assertTrue(BotCommitments.inFlight(j.bot(), BotSM.BotState.IDLE));
            j.expedition().dispose(false);
            assertFalse(BotCommitments.inFlight(j.bot(), BotSM.BotState.IDLE));
        } finally {
            j.release();
        }
    }
}
