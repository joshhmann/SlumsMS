package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 follow-plus: while the owner waits by a portal, each companion stands on its own slot beside it - slot 1
 * left, slot 2 right - not on the owner and not on the portal. Party regulars keep the old behaviour by default.
 */
class CompanionSpotsTest {

    private static final int OWNER = 1;
    private static final long T0 = 5_000_000L;

    private Character owner;
    private MapleMap map;
    private MockedStatic<BotHelpers> helpers;
    private MockedStatic<GCMovement> gc;

    @BeforeEach
    void setUp() {
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        when(owner.getMapId()).thenReturn(100_000_000);
        map = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map);
        Portal portal = mock(Portal.class);
        when(portal.getPosition()).thenReturn(new Point(60, 0)); // 60 px right of the owner: inside NEAR_PX
        when(map.getPortals()).thenReturn(List.of(portal));
        when(map.getMapObjects()).thenReturn(List.of());
        helpers = mockStatic(BotHelpers.class);
        gc = mockStatic(GCMovement.class);
        CompanionSpots.forget(OWNER);
    }

    @AfterEach
    void tearDown() {
        gc.close();
        helpers.close();
        System.clearProperty(Feature.FOLLOW_PLUS.property(false));
        CompanionSpots.forget(OWNER);
    }

    private Character companion(int id, boolean ownedAlt, Point at) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isOwnedAlt()).thenReturn(ownedAlt);
        when(c.getPosition()).thenReturn(at);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100_000_000);
        when(c.getName()).thenReturn("alt" + id);
        helpers.when(() -> BotHelpers.isBot(c)).thenReturn(true);
        return c;
    }

    @Test
    void twoCompanionsGetDistinctSpotsBesideTheOwner() {
        Character first = companion(11, true, new Point(500, 0));
        Character second = companion(12, true, new Point(-500, 0));

        assertNull(CompanionSpots.spotFor(owner, first, 1, T0), "the owner just moved: not waiting yet");

        long still = T0 + CompanionSpots.STILL_MS;
        Point one = CompanionSpots.spotFor(owner, first, 1, still);
        Point two = CompanionSpots.spotFor(owner, second, 2, still);
        assertNotNull(one);
        assertNotNull(two);
        assertEquals(-CompanionSpots.SLOT_PX, one.x, "slot 1 stands left of the owner");
        assertEquals(100, two.x, "slot 2's 40 px is 20 off the portal at 60, so it moves out to 60 + CLEAR_PX");
        assertNotEquals(one.x, two.x);
        assertNotEquals(0, one.x, "not on the owner");
        assertNotEquals(0, two.x, "not on the owner");
        assertTrue(Math.abs(one.x - 60) >= CompanionSpots.CLEAR_PX, "not on the portal");
        assertTrue(Math.abs(two.x - 60) >= CompanionSpots.CLEAR_PX, "not on the portal");

        assertTrue(CompanionSpots.tick(owner, first, 1, still), "the spot holds the follow");
        gc.verify(() -> GCMovement.move(first, one.x, one.y), times(1));
    }

    @Test
    void aCompanionAlreadyOnItsSpotJustStands() {
        Character first = companion(11, true, new Point(-CompanionSpots.SLOT_PX, 0));
        gc.when(() -> GCMovement.isFollowing(first)).thenReturn(true);
        CompanionSpots.spotFor(owner, first, 1, T0); // start the still clock
        long still = T0 + CompanionSpots.STILL_MS;

        assertTrue(CompanionSpots.tick(owner, first, 1, still));
        gc.verify(() -> GCMovement.stop(first), times(1));
        gc.verify(() -> GCMovement.move(eq(first), anyInt(), anyInt()), never());
    }

    @Test
    void aRegularIsUnchangedWithTheDefaults() {
        Character regular = companion(13, false, new Point(500, 0));
        assertNull(CompanionSpots.spotFor(owner, regular, 1, T0));
        assertNull(CompanionSpots.spotFor(owner, regular, 1, T0 + CompanionSpots.STILL_MS));
        assertFalse(CompanionSpots.tick(owner, regular, 1, T0 + CompanionSpots.STILL_MS));

        System.setProperty(Feature.FOLLOW_PLUS.property(false), "true");
        assertNull(CompanionSpots.spotFor(owner, regular, 1, T0 + CompanionSpots.STILL_MS),
                "the switch also starts its still clock");
        assertNotNull(CompanionSpots.spotFor(owner, regular, 1, T0 + 2 * CompanionSpots.STILL_MS),
                "the switch turns it on for regulars too");
        CompanionSpots.forget(OWNER);
    }

    @Test
    void nobodyWaitsWhereThereIsNoPortalOrNpc() {
        when(map.getPortals()).thenReturn(List.of());
        Character first = companion(11, true, new Point(500, 0));
        assertNull(CompanionSpots.spotFor(owner, first, 1, T0));
        assertNull(CompanionSpots.spotFor(owner, first, 1, T0 + CompanionSpots.STILL_MS));
        assertFalse(CompanionSpots.tick(owner, first, 1, T0 + CompanionSpots.STILL_MS));
        gc.verify(() -> GCMovement.move(eq(first), anyInt(), anyInt()), never());
        gc.verify(() -> GCMovement.stop(eq(first)), never());
    }
}
