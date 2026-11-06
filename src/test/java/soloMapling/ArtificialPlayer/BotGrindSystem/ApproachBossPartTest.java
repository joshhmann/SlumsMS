package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b311: a melee fighter stands under the boss part it fights, as a player does. H1 run 3 on TEST (6 Nov):
 * Horntail's head A sat at (960, 228); the Hero alt and both melee fillers parked at x 848-850, y 230 - 111 px from the
 * head's centre, just outside its body - and never swung, because the run's in-reach test (StepExecutor.fight,
 * {@code Approach.reach}: 80% of a 130 px melee box, 104 px) never passed there. The stand spot treated the target's
 * own body as a no-stand zone (the t-cb-2 rule, meant for ANOTHER mob) and the band put the spot past the body's edge,
 * so on a part wider than the reach no spot was ever in reach. The ranged member (167 px) and the mage (11 px) swung.
 */
class ApproachBossPartTest {

    private static final int HEAD_X = 960;
    private static final int[] MELEE_REACH = {104, 90};

    private static Monster mob(int id, boolean boss, Rectangle body) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        when(m.isBoss()).thenReturn(boss);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(HEAD_X, 0));
        return m;
    }

    /** The spot a melee character (no job, so no kept distance) is sent to, with {@code mob}'s body on the map. */
    private static int[] spotFor(Monster mob, Rectangle body) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(106);
        MapleMap map = mock(MapleMap.class);
        when(c.getMap()).thenReturn(map);
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenAnswer(inv -> {
            List<?> types = inv.getArgument(2);
            return types.contains(server.maps.MapObjectType.MONSTER) ? List.of(mob) : List.of();
        });
        try (MockedStatic<GCMovement> g = Mockito.mockStatic(GCMovement.class)) {
            g.when(() -> GCMovement.groundPointBelow(any(), anyInt(), anyInt()))
                    .thenAnswer(inv -> new Point(inv.getArgument(1, Integer.class), 0));
            g.when(() -> GCMovement.mobBounds(mob)).thenReturn(body);
            return Approach.fightSpot(c, mob, MELEE_REACH, true);
        }
    }

    @Test
    void aMeleeFighterStandsWhereItsSwingReachesABossPartWiderThanTheReach() {
        Rectangle body = new Rectangle(HEAD_X - 120, -300, 240, 300); // half width 120 > the 104 px reach
        Monster headA = mob(8810000, true, body);

        int dx = Math.abs(spotFor(headA, body)[0] - HEAD_X);

        // StepExecutor.moveNear stops within reach/3 of the spot, so the spot must leave that much room
        assertTrue(dx <= MELEE_REACH[0] - MELEE_REACH[0] / 3,
                "the spot is " + dx + " px from the head's centre; the run swings only within " + MELEE_REACH[0]);
    }

    /**
     * The run's own gate (StepExecutor.fight): it walks the member to {@code fightSpot}, the walk counts anywhere within
     * reach/3 of the spot as arrived (moveNear), and it swings only when {@code BotAttackDriver.canSwingFrom} passes
     * with {@code Approach.reach}. Wherever the walk stops, the swing must now go out.
     */
    @Test
    void theRunsSwingGatePassesWhereverTheWalkToTheSpotStops() {
        Rectangle body = new Rectangle(HEAD_X - 120, -300, 240, 300);
        Monster headA = mob(8810000, true, body);
        MapleMap map = mock(MapleMap.class);
        int spotX = spotFor(headA, body)[0];
        int tolerance = Math.max(10, MELEE_REACH[0] / 3);
        soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.ledges = (m, a, b) -> false;
        try {
            for (int stop = spotX - tolerance; stop <= spotX + tolerance; stop++) {
                assertTrue(soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.canSwingFrom(map,
                                new Point(stop, 2), headA, MELEE_REACH[0], MELEE_REACH[1]),
                        "a member that stopped at x " + stop + " (head at " + HEAD_X + ") swings");
            }
        } finally {
            soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.ledges =
                    soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver.NAV_LEDGES;
        }
    }

    @Test
    void anOrdinaryBigMobStillHasItsFighterStandPastItsBody() {
        Rectangle body = new Rectangle(HEAD_X - 60, -120, 120, 120);
        Monster pig = mob(1210100, false, body);

        int dx = Math.abs(spotFor(pig, body)[0] - HEAD_X);

        assertTrue(dx >= 60, "not inside an ordinary mob's touch box (t-cb-2): " + dx);
    }
}
