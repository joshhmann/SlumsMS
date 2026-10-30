package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/** 1.6.2: the approach step the grind brain and owned alts share. */
class ApproachTest {

    @Test
    void reachIsEightyPercentOfTheAttackFlooredAtTheMeleeBox() {
        Character c = mock(Character.class);
        try (MockedStatic<BotAttackDriver> a = Mockito.mockStatic(BotAttackDriver.class)) {
            a.when(() -> BotAttackDriver.attackReachX(any())).thenReturn(400);
            a.when(() -> BotAttackDriver.attackReachY(any())).thenReturn(50);
            assertArrayEquals(new int[]{320, Approach.MELEE_Y}, Approach.reach(c));
            a.when(() -> BotAttackDriver.attackReachX(any())).thenReturn(0);
            assertArrayEquals(new int[]{Approach.MELEE_X, Approach.MELEE_Y}, Approach.reach(c));
        }
        assertEquals(Approach.MELEE_X, GrindBrain.APPROACH_X, "the grind brain's numbers are the shared ones");
        assertEquals(Approach.RETARGET_EPS, GrindBrain.ROAM_RETARGET_EPS);
    }

    @Test
    void inReachIsTheBox() {
        int[] r = {80, 90};
        assertTrue(Approach.inReach(new Point(0, 0), new Point(80, -90), r));
        assertFalse(Approach.inReach(new Point(0, 0), new Point(81, 0), r));
        assertFalse(Approach.inReach(new Point(0, 0), new Point(0, 91), r));
    }

    @Test
    void theWalkIsReissuedOnlyWhenTheSpotMovedOrTheWalkStopped() {
        Character c = mock(Character.class);
        try (MockedStatic<GCMovement> m = Mockito.mockStatic(GCMovement.class)) {
            m.when(() -> GCMovement.isMoving(c)).thenReturn(true);
            assertEquals(1000, Approach.walkTo(c, 1005, 0, 1000), "still heading there");
            m.verify(() -> GCMovement.move(c, 1005, 0), never());
            assertEquals(1100, Approach.walkTo(c, 1100, 0, 1000));
            m.verify(() -> GCMovement.move(c, 1100, 0));
            m.when(() -> GCMovement.isMoving(c)).thenReturn(false); // the driver dropped the walk
            assertEquals(1100, Approach.walkTo(c, 1100, 0, 1100));
            m.verify(() -> GCMovement.move(c, 1100, 0), Mockito.times(2));
        }
        assertTrue(RoamStrategy.alreadyHeading(1000, 1004, true), "roam asks the same rule");
    }

    @Test
    void theStandSpotIsTheBotsBandNotTheMobsPixel() {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(77);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        when(c.getMap()).thenReturn(map);
        server.life.Monster mob = mock(server.life.Monster.class);
        when(mob.getPosition()).thenReturn(new Point(500, 0));
        try (MockedStatic<GCMovement> m = Mockito.mockStatic(GCMovement.class)) {
            m.when(() -> GCMovement.groundPointBelow(any(), Mockito.anyInt(), Mockito.anyInt()))
                    .thenAnswer(inv -> new Point(inv.getArgument(1, Integer.class), 0));
            int[] spot = Approach.fightSpot(c, mob, new int[]{100, 90}, true);
            int dx = Math.abs(spot[0] - 500);
            assertTrue(dx >= 42 && dx <= 72, "past a 20 px half body + 22, within 30 of that: " + dx);
            assertEquals(0, spot[1]);
        }
    }
}
