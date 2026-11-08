package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionClimb;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionLines;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSpots;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStuck;
import soloMapling.companions.OwnedAltCompanion;

import java.awt.Point;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 follow-plus, wave 2 audit: the four helpers are only worth anything if the follow loops call them. Removing
 * the GCFollow same-map hook or the OwnedAltCompanion.keepFollowing hooks left every other test green; these fail
 * then.
 */
class FollowPlusWiringTest {

    private static Character chr(int id, MapleMap map) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100_000_000);
        when(c.getPosition()).thenReturn(new Point(0, 0));
        when(c.getName()).thenReturn("c" + id);
        when(c.isOwnedAlt()).thenReturn(id == 2);
        return c;
    }

    @Test
    void theSameMapFollowPollRunsTheStuckWatchAndTheLines() {
        MapleMap map = mock(MapleMap.class);
        Character owner = chr(1, map);
        Character alt = chr(2, map);
        try (MockedStatic<GCMovement> gc = mockStatic(GCMovement.class);
             MockedStatic<CompanionStuck> stuck = mockStatic(CompanionStuck.class);
             MockedStatic<CompanionLines> lines = mockStatic(CompanionLines.class)) {
            GCFollow.tick(new GCFollow.Session(alt, owner));
            stuck.verify(() -> CompanionStuck.tick(eq(alt), eq(owner), anyLong(), anyBoolean()), times(1));
            lines.verify(() -> CompanionLines.tick(eq(owner), eq(alt), anyLong()), times(1));
            gc.verify(() -> GCMovement.armSameMapFollow(alt, owner), times(1));
        }
    }

    @Test
    void keepFollowingAsksTheClimbThenTheSpotBeforeThePlainFollow() throws Exception {
        MapleMap map = mock(MapleMap.class);
        Character owner = chr(1, map);
        Character alt = chr(2, map);
        Method keep = OwnedAltCompanion.class.getDeclaredMethod("keepFollowing", Character.class, Character.class,
                long.class);
        keep.setAccessible(true);
        try (MockedStatic<GCMovement> gc = mockStatic(GCMovement.class);
             MockedStatic<CompanionClimb> climb = mockStatic(CompanionClimb.class);
             MockedStatic<CompanionSpots> spots = mockStatic(CompanionSpots.class)) {
            climb.when(() -> CompanionClimb.tick(alt, owner, 5L)).thenReturn(true);
            keep.invoke(null, owner, alt, 5L);
            gc.verify(() -> GCMovement.follow(any(Character.class), any(Character.class)), never());
            spots.verify(() -> CompanionSpots.tick(any(), any(), anyInt(), anyLong()), never());

            climb.when(() -> CompanionClimb.tick(alt, owner, 6L)).thenReturn(false);
            spots.when(() -> CompanionSpots.tick(eq(owner), eq(alt), anyInt(), eq(6L))).thenReturn(true);
            keep.invoke(null, owner, alt, 6L);
            gc.verify(() -> GCMovement.follow(any(Character.class), any(Character.class)), never());

            keep.invoke(null, owner, alt, 7L); // neither: the plain follow, as before
            gc.verify(() -> GCMovement.follow(alt, owner), times(1));
        }
    }
}
