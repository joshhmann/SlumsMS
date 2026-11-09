package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.WzTestData;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA's Zakum run on TEST 1.6.7 (zakum-sweep-c-20261109T041606Z, 9 Nov 2026): "FIGHT thunder54 gives up on Zakum's Arm
 * n at -9,-216 (no way closer in 3 s)" x12. Every Zakum part reports the same anchor (-9,-216) while its body box
 * (Mob.wz stand/0 lt/rb) lies up to 400 px away, and the executor measured reach and the walk from that anchor. Boss
 * parts are now judged by their body box, as BotStory does (AgentCombatHitboxIntersection, fd953285). Real Mob.wz.
 */
class BossBodyReachTest {

    private static final Point ANCHOR = new Point(-9, -216);
    private static final int REACH_X = 70;
    private static final int REACH_Y = 90;

    private BotAttackDriver.Ledges saved;

    @BeforeEach
    void ledges() {
        saved = BotAttackDriver.ledges;
        BotAttackDriver.ledges = (map, a, b) -> false; // a mocked map has no nav graph
    }

    @AfterEach
    void restore() {
        BotAttackDriver.ledges = saved;
    }

    private static Monster part(int id, boolean boss, Point at) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        when(m.isBoss()).thenReturn(boss);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(at);
        when(m.isFacingLeft()).thenReturn(false);
        return m;
    }

    @Test
    void aMeleeBotOnTheAltarBesideArmNinesBodyReachesIt() {
        WzTestData.assumePresent("Mob.wz");
        Monster arm9 = part(8800009, true, ANCHOR); // body x -270..-105, y -296..-214

        assertTrue(BotAttackDriver.canSwingFrom(mock(MapleMap.class), new Point(-200, -216), arm9, REACH_X, REACH_Y),
                "standing inside arm 9's body box on the altar is in reach, 191 px from the shared anchor or not");
    }

    @Test
    void aMeleeBotOnTheFloorBesideTheAltarReachesArmTenWhereThunderGaveUp() {
        WzTestData.assumePresent("Mob.wz");
        Monster arm10 = part(8800010, true, ANCHOR); // body x 49..305, y -362..-246

        // thunder54 stood here, on the floor right of the altar, for the whole run
        assertTrue(BotAttackDriver.canSwingFrom(mock(MapleMap.class), new Point(215, -175), arm10, REACH_X, REACH_Y),
                "arm 10's body reaches down beside the altar: a melee bot on the floor there can hit it");
    }

    @Test
    void theAnchorStillCountsForAPartWhoseBodyHangsHighAboveAsBefore() {
        WzTestData.assumePresent("Mob.wz");
        Monster arm3 = part(8800003, true, ANCHOR); // body x -153..-57, y -616..-482, far above any swing

        // BotStory's strict rule would refuse this; 1.6.7 keeps today's anchor reach so melee can still clear the
        // upper arms (and Horntail's heads) until boss-positioning-by-job puts them on ledges (1.6.8)
        assertTrue(BotAttackDriver.canSwingFrom(mock(MapleMap.class), new Point(-9, -216), arm3, REACH_X, REACH_Y));
        Character bot = mock(Character.class);
        when(bot.getPosition()).thenReturn(new Point(215, -216));
        when(bot.getMap()).thenReturn(mock(MapleMap.class));
        assertEquals((215 - (-9)) * 8, BotAttackDriver.costTo(bot, arm3),
                "no swing from the ground meets arm 3's body: the walk still goes to the anchor");
    }

    @Test
    void theWalkToArmNineIsMeasuredToItsBodyNotTheAnchor() {
        WzTestData.assumePresent("Mob.wz");
        Monster arm9 = part(8800009, true, ANCHOR);
        Character bot = mock(Character.class);
        when(bot.getPosition()).thenReturn(new Point(215, -216));
        when(bot.getMap()).thenReturn(mock(MapleMap.class));

        int cost = BotAttackDriver.costTo(bot, arm9);

        assertEquals((215 - (-106)) * 8, cost, "the walk ends at arm 9's near edge (x -106), not at the anchor (x -9)");
    }

    @Test
    void anOrdinaryMobIsJudgedFromItsOriginAsBefore() {
        Monster snail = part(100100, false, new Point(0, 0));
        MapleMap map = mock(MapleMap.class);

        assertTrue(BotAttackDriver.canSwingFrom(map, new Point(60, 0), snail, REACH_X, REACH_Y));
        assertFalse(BotAttackDriver.canSwingFrom(map, new Point(80, 0), snail, REACH_X, REACH_Y));
        Character bot = mock(Character.class);
        when(bot.getPosition()).thenReturn(new Point(100, 0));
        when(bot.getMap()).thenReturn(map);
        assertEquals(800, BotAttackDriver.costTo(bot, snail));
    }
}
