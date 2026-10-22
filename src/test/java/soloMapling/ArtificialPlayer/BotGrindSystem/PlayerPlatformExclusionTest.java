package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSpotClaims;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.0 rc3 review (P2): a real player's platform was only a 400-point cost, which the over-cap penalty dwarfed, so
 * once every other spot was full a neutral bot claimed and walked onto the player's platform. It is now never picked.
 */
class PlayerPlatformExclusionTest {

    private static final int MAP = 990_000_123;
    private static final int BOT = 1_000_888_001;

    @AfterEach
    void reset() {
        BotSpotClaims.releaseAllFor(BOT);
        for (int i = 1; i <= 8; i++) {
            BotSpotClaims.releaseAllFor(BOT + i);
        }
    }

    private static Character at(int id, int x, int y, MapleMap map) {
        Character c = testutil.Mocks.chr("c" + id);
        when(c.getId()).thenReturn(id);
        when(c.getPosition()).thenReturn(new Point(x, y));
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(MAP);
        return c;
    }

    private static Spot spot(int x) {
        return new Spot(new Point(x, 100), -1, 150, 6, 6, 400, 1);
    }

    private static MapGrindProfile profile(List<Spot> spots) {
        MapGrindProfile p = mock(MapGrindProfile.class);
        when(p.spots()).thenReturn(spots);
        return p;
    }

    @Test
    void aPlayersPlatformIsNeverPickedEvenWhenEveryOtherSpotIsFull() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(MAP);
        Character bot = at(BOT, 0, 100, map);
        Character player = at(7, 1_500, 100, map);
        Spot free = spot(0);
        Spot players = spot(1_500);
        List<Spot> spots = List.of(free, players);

        // Over cap on the free spot: the player's platform used to win here.
        for (int i = 1; i <= 3; i++) {
            BotSpotClaims.claim(SpotFinder.claimKey(bot), 0, Integer.MAX_VALUE, BOT + i);
        }
        List<Character> respected = List.of(player);
        assertTrue(SpotFinder.playersPlatform(bot, respected, players), "the player stands on the second spot");
        for (int round = 0; round < 20; round++) {
            Spot picked = SpotFinder.pickBest(bot, profile(spots), -1, 0L, respected);
            assertSame(free, picked, "sharing a full spot beats the player's platform");
        }
        // Only the player's platform left: nothing is picked (CampStrategy then yields the map).
        assertNull(SpotFinder.pickBest(bot, profile(List.of(players)), -1, 0L, respected));
    }
}
