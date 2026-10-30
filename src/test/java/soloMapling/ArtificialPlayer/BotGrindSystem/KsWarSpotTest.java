package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotSpotClaims;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 auto-QA t-rv-2 (runs 3, 4 and the 10-30 00:00 rerun): the called helpers arrived on the player's ledge
 * (ARRIVE WALK ... (arrived)), then the grind brain picked a spot on a ledge of its own (y -85 / -325 / -565 while the
 * player fought at y 215 / -85), so every KS check said "N mob(s) in reach, 0 hit by AutoQaOne" and the rally walks
 * back were undone by the spot's anchor. A helper on a live war spree now camps the player's platform.
 */
class KsWarSpotTest {

    private static final int MAP = 990_000_161;
    private static final int BOT = 1_000_161_001;
    private static final int PLAYER = 161;

    @AfterEach
    void reset() {
        AffinityReactions.stopSpree(BOT);
        BotSpotClaims.releaseAllFor(BOT);
    }

    private static Character at(int id, int x, int y, MapleMap map) {
        Character c = testutil.Mocks.chr("c" + id);
        when(c.getId()).thenReturn(id);
        when(c.getPosition()).thenReturn(new Point(x, y));
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(MAP);
        return c;
    }

    private static MapGrindProfile profile(List<Spot> spots) {
        MapGrindProfile p = mock(MapGrindProfile.class);
        when(p.spots()).thenReturn(spots);
        return p;
    }

    @Test
    void aWarHelperCampsThePlayersPlatformNotItsOwnLedge() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        Character bot = at(BOT, 650, -85, map);         // where clockhunter stood all war
        Character player = at(PLAYER, 1126, 215, map);  // AutoQaOne fighting on the ground
        when(map.getCharacterById(PLAYER)).thenReturn(player);
        Spot upper = new Spot(new Point(650, -85), -1, 150, 10, 10, 600, 3);
        Spot ground = new Spot(new Point(1100, 215), -1, 150, 6, 6, 600, 3);
        MapGrindProfile p = profile(List.of(upper, ground));

        // The old pick: the helper's own ledge scores best (near, more feed), so it never had the player's mobs.
        assertSame(upper, SpotFinder.pickBest(bot, p, -1, 0L, List.of()), "pickBest alone keeps its own ledge");
        assertNull(SpotFinder.warSpot(bot, p), "no war: no war spot");

        AffinityReactions.startWarSpree(bot, player, 60_000, 5);
        assertEquals(PLAYER, AffinityReactions.warTargetOf(BOT, MAP));
        assertSame(ground, SpotFinder.warSpot(bot, p), "a war helper camps the player's platform");

        AffinityReactions.stopSpree(BOT);
        assertNull(SpotFinder.warSpot(bot, p), "the war over: its own pick again");
    }

    @Test
    void onlyALiveWarSpreeOnTheBotsMapCounts() {
        MapleMap map = mock(MapleMap.class);
        Character bot = at(BOT, 0, 0, map);
        Character player = at(PLAYER, 0, 0, map);
        AffinityReactions.startWarSpree(bot, player, 60_000, 5);
        assertEquals(-1, AffinityReactions.warTargetOf(BOT, MAP + 1), "another map");
        AffinityReactions.startWarSpree(bot, player, 60_000, 0);
        assertEquals(-1, AffinityReactions.warTargetOf(BOT, MAP), "no mobs left to take");
    }

    @Test
    void noSpotOnThePlayersPlatformMeansNoWarSpot() {
        MapleMap map = mock(MapleMap.class);
        Character bot = at(BOT, 650, -85, map);
        Character player = at(PLAYER, 1126, 215, map);
        Spot upper = new Spot(new Point(650, -85), -1, 150, 6, 6, 600, 3);
        assertNull(SpotFinder.warSpot(bot, profile(List.of(upper)), player));
    }
}
