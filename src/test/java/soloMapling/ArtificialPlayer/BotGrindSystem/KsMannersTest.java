package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.5.0 rc3, owner decision 4: what a neutral bot treats as a player's platform and a player's mob. */
class KsMannersTest {

    private static Character at(int id, int x, int y) {
        Character c = testutil.Mocks.chr("c" + id);
        when(c.getId()).thenReturn(id);
        when(c.getPosition()).thenReturn(new Point(x, y));
        return c;
    }

    private static Monster mob(int x, int y) {
        Monster m = mock(Monster.class);
        when(m.getPosition()).thenReturn(new Point(x, y));
        return m;
    }

    @Test
    void aMobByAPlayerOnItsLedgeIsThePlayers() {
        Character bot = at(9001, 0, 100);
        Character player = at(1, 500, 100);
        assertEquals(player, KsManners.ownerOf(bot, List.of(player), mob(400, 100)));
        assertNull(KsManners.ownerOf(bot, List.of(player), mob(-200, 100)), "far along the ledge: free");
        assertNull(KsManners.ownerOf(bot, List.of(player), mob(450, 400)), "on another ledge below: free");
    }

    @Test
    void aMobAPlayerIsFightingIsThePlayersWhereverItStands() {
        Character bot = at(9001, 0, 100);
        Character player = at(1, 900, 100);
        Monster m = mob(0, 100);
        when(m.isAttackedBy(player)).thenReturn(true);
        assertEquals(player, KsManners.ownerOf(bot, List.of(player), m));
    }

    @Test
    void aSpotOnThePlayersPlatformIsTaken() {
        Character bot = at(9001, 0, 100);
        Character player = at(1, 600, 100);
        assertEquals(player, KsManners.playerOn(bot, List.of(player), new Point(300, 100), 250));
        assertNull(KsManners.playerOn(bot, List.of(player), new Point(-400, 100), 250));
    }

    @Test
    void aNeutralBotRespectsAPlayerButNotItsOwnParty() {
        Character bot = at(9001, 0, 100);
        Character player = at(1, 600, 100);
        assertTrue(KsManners.respects(bot, player));
        Party party = mock(Party.class);
        when(party.getId()).thenReturn(5);
        when(bot.getParty()).thenReturn(party);
        when(player.getParty()).thenReturn(party);
        assertFalse(KsManners.respects(bot, player));
    }

    @Test
    void aKsWarHelperDoesNotRespectThePlayersMobs() {
        // rc6 auto-QA triage (rv-2): the war's called helpers are neutral, so their manners kept them off the player
        Character bot = at(1_000_009_001, 0, 100);
        Character player = at(1, 600, 100);
        when(player.getMapId()).thenReturn(104040000);
        assertTrue(KsManners.respects(bot, player));
        soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.startWarSpree(bot, player, 60_000, 5);
        try {
            assertFalse(KsManners.respects(bot, player), "on a KS spree against this player");
            assertTrue(KsManners.respects(bot, at(2, 600, 100)), "another player is still respected");
        } finally {
            soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions.stopSpree(bot.getId());
        }
        assertTrue(KsManners.respects(bot, player), "the spree over: manners again");
    }
}
