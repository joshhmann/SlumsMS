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
        when(m.lastHitBy(1)).thenReturn(System.currentTimeMillis() - 1_000); // hitting it now
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

    /**
     * Owner 2026-10-30: a !botshowcase bot keeps no KS manners toward the GM who summoned it, so a visible GM standing
     * by the mobs does not stop the showcase. Only that bot, only that GM, until the bot is despawned.
     */
    @Test
    void aShowcaseBotFightsByItsOwnGmOnly() {
        Character showcase = at(1_000_009_101, 0, 100);
        Character other = at(1_000_009_102, 0, 100);
        Character gm = at(1, 100, 100);
        Character player = at(2, 100, 100);
        assertTrue(KsManners.respects(showcase, gm), "before: the summoning GM is respected like anyone");
        KsManners.showcaseSummonedBy(showcase.getId(), gm.getId());
        try {
            assertFalse(KsManners.respects(showcase, gm), "its own GM's mobs are fair game");
            assertTrue(KsManners.respects(showcase, player), "another player is still respected");
            assertTrue(KsManners.respects(other, gm), "another bot still respects the GM");
        } finally {
            KsManners.forget(showcase.getId());
        }
        assertTrue(KsManners.respects(showcase, gm), "despawned (forget): the slot's next bot has manners again");
    }

    /**
     * TEST 2026-10-30 (t-ev-3): a visible player standing by invasion mobs made them "theirs" at every swing, so the
     * wave never cleared. A world event's mob is everyone's; a normal field mob by the player still is the player's.
     */
    @Test
    void aPlayerByAnInvasionMobDoesNotStopABotsSwingButByAFieldMobItDoes() {
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        Character bot = at(1_000_009_201, 0, 100);
        Character player = at(3, 300, 100);
        when(bot.getMap()).thenReturn(map);
        when(player.getMap()).thenReturn(map);
        when(map.getCharacters()).thenReturn(List.of(bot, player));
        Monster invader = mob(350, 100);
        Monster field = mob(360, 100);
        KsManners.useEventMobsForTest((m, mob) -> m == map && mob == invader);
        try {
            long now = System.currentTimeMillis();
            assertTrue(KsManners.mayHit(bot, invader, now), "an invasion mob by the player is still everyone's");
            assertFalse(KsManners.mayHit(bot, field, now), "a field mob by the player is the player's");
            assertNull(KsManners.ownerOf(map, List.of(player), invader, now));
            assertEquals(player, KsManners.ownerOf(map, List.of(player), field, now));
        } finally {
            KsManners.useEventMobsForTest(null);
            KsManners.forget(bot.getId());
        }
    }

    /** Audit 163 (B21): a player's hit claims a mob while they keep at it, not for the rest of its life. */
    @Test
    void aPlayersOldHitNoLongerClaimsTheMob() {
        Character bot = at(9001, 0, 100);
        Character player = at(1, 900, 100); // far from the mob: only a hit could make it theirs
        Monster m = mob(0, 100);
        long now = System.currentTimeMillis();
        when(m.isAttackedBy(player)).thenReturn(true); // the vanilla record never forgets
        when(m.lastHitBy(1)).thenReturn(now - KsManners.CLAIM_MS - 1_000);
        assertNull(KsManners.ownerOf(bot.getMap(), List.of(player), m, now), "they stopped hitting it 16 s ago");
        when(m.lastHitBy(1)).thenReturn(now - 2_000);
        assertEquals(player, KsManners.ownerOf(bot.getMap(), List.of(player), m, now), "still at it");
    }

    /** Audit 163: the earlier claim is the one that counts (a run of hits restarts after a long gap). */
    @Test
    void aClaimStartsAtTheCurrentRunOfHits() {
        Monster m = mob(0, 100);
        long now = 1_000_000L;
        when(m.lastHitBy(7)).thenReturn(now - 1_000);
        when(m.hitStreakStart(7)).thenReturn(now - 9_000);
        assertEquals(now - 9_000, KsManners.claimSince(m, 7, now));
        when(m.lastHitBy(7)).thenReturn(now - KsManners.CLAIM_MS - 1);
        assertEquals(0, KsManners.claimSince(m, 7, now), "lapsed: no claim");
    }
}
