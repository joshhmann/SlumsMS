package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (KPQ pace, b153/b204): on KPQ stage 1 (map 103000800) every Ligator stands on an upper platform; the floor has
 * none. The PQ executor took the nearest Ligator in a straight line and judged reach by |dy| alone, so bots parked on
 * the floor under the y=-345 platform (STALL at -57,104) or hung on the x=-437 rope, while the swing skipped every mob
 * on another ledge. One target rule now: what the swing may hit, ranked by the walk to it, never an unreachable one.
 */
class PickTargetTest {

    static final int LIGATOR = 9300001;

    /* Ledges as on 103000800: the floor (y > 0), the y=-345 platform (x -369..-139), the slope (x 22..542). */
    static int ledge(Point p) {
        if (p.y > 0) {
            return 0;
        }
        if (p.y < -300 && p.x < -100) {
            return 1;
        }
        return 2;
    }

    @BeforeEach
    void stubNav() {
        BotAttackDriver.ledges = (map, a, b) -> ledge(a) != ledge(b);
    }

    @AfterEach
    void restore() {
        BotAttackDriver.ledges = BotAttackDriver.NAV_LEDGES;
        BotAttackDriver.pathCost = BotAttackDriver.NAV_PATH_COST;
    }

    static Monster ligator(MapleMap map, int x, int y) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(LIGATOR);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, y));
        when(m.getMap()).thenReturn(map);
        return m;
    }

    static Character botAt(MapleMap map, int x, int y) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_777);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(x, y));
        when(bot.isOwnedAlt()).thenReturn(true); // no KS manners in this test (own test below)
        return bot;
    }

    @Test
    void aBotUnderAPlatformPicksTheMobItCanWalkToNotTheOneOverhead() {
        MapleMap map = mock(MapleMap.class);
        Monster overhead = ligator(map, -233, -345); // 450 px straight up: the climb is the x=-437 rope, far round
        Monster slope = ligator(map, 300, -60);       // 400 px across, 165 up: the slope walks up from the floor
        when(map.getAllMonsters()).thenReturn(List.of(overhead, slope));
        BotAttackDriver.pathCost = (m, a, b) -> b.x == -233 ? 9_000 : 4_000;
        Character bot = botAt(map, -57, 104);
        assertSame(slope, BotAttackDriver.pickTarget(bot, LIGATOR, null),
                "by the walk, not the straight line (the overhead one is nearer in a line)");
    }

    @Test
    void anUnreachableMobIsNeverPickedAndASkippedOneIsLeft() {
        MapleMap map = mock(MapleMap.class);
        Monster stuck = ligator(map, -233, -345);
        Monster other = ligator(map, 300, -60);
        when(map.getAllMonsters()).thenReturn(List.of(stuck, other));
        BotAttackDriver.pathCost = (m, a, b) -> b.x == -233 ? GCMovement.UNREACHABLE : 4_000;
        Character bot = botAt(map, -57, 104);
        assertSame(other, BotAttackDriver.pickTarget(bot, LIGATOR, null));
        assertNull(BotAttackDriver.pickTarget(bot, LIGATOR, m -> m == other), "only an unreachable one left: none");
        assertNull(BotAttackDriver.pickTarget(bot, 1234, null), "another mob kind");
    }

    @Test
    void aMobOnTheBotsOwnLedgeRanksByItsWalkAcross() {
        MapleMap map = mock(MapleMap.class);
        Monster near = ligator(map, -250, -345);
        Monster far = ligator(map, -360, -347);
        when(map.getAllMonsters()).thenReturn(List.of(far, near));
        BotAttackDriver.pathCost = (m, a, b) -> {
            throw new AssertionError("same ledge: no route search");
        };
        assertSame(near, BotAttackDriver.pickTarget(botAt(map, -200, -345), LIGATOR, null));
    }

    @Test
    void aRangedBotUnderThePlatformIsNotInReachThoughTheMobIsWithinItsReachUpAndDown() {
        MapleMap map = mock(MapleMap.class);
        Monster overhead = ligator(map, -233, -345);
        Point under = new Point(-180, -40); // a ranged bot's reach y covers 305 px; another ledge all the same
        assertFalse(BotAttackDriver.canSwingFrom(map, new Point(-180, 104), overhead, 350, 500),
                "on the floor under it: the swing would skip it");
        assertFalse(BotAttackDriver.canSwingFrom(map, under, overhead, 350, 500));
        assertTrue(BotAttackDriver.canSwingFrom(map, new Point(-300, -347), overhead, 350, 500), "on its platform");
        assertFalse(BotAttackDriver.canSwingFrom(map, new Point(-300, -347), overhead, 50, 500), "too far across");
    }

    @Test
    void aSwingThatSeesNoMobItMayHitIsToldApart() {
        assertTrue(BotAttackDriver.noTargetableMob(BotAttackDriver.AttackResult.miss(
                "no targetable mobs within 700px (none, or all on a separate ledge)")));
        assertFalse(BotAttackDriver.noTargetableMob(BotAttackDriver.AttackResult.miss("on cooldown")));
        assertFalse(BotAttackDriver.noTargetableMob(null));
    }

    @Test
    void aMobAMateGoesForCountsAsMoreWalkSoThePartySpreads() {
        MapleMap map = mock(MapleMap.class);
        Monster mates = ligator(map, -250, -345);
        Monster next = ligator(map, -330, -347);
        when(map.getAllMonsters()).thenReturn(List.of(mates, next));
        Character bot = botAt(map, -230, -345);
        assertSame(mates, BotAttackDriver.pickTarget(bot, LIGATOR, null, m -> 0));
        assertSame(next, BotAttackDriver.pickTarget(bot, LIGATOR, null, m -> m == mates ? 400 * 8 : 0),
                "80 px further but nobody on it");
    }
}
