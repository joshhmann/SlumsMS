package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.0: a follower in its leader's party quest (same party, same event instance) fights the stage's monsters next to
 * him, and never the PQ's friendly mob (HPQ's Moon Bunny). Outside the instance, or in someone else's, it only follows.
 */
class FollowerFightPqTest {

    private static final int KPQ_STAGE_2 = 103000801;
    private static final int HPQ_MOON = 910010000;

    private static MapleMap map(int id, List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        return m;
    }

    private static Character in(MapleMap map, int x, EventInstanceManager eim, int party) {
        Character c = mock(Character.class);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.getEventInstance()).thenReturn(eim);
        when(c.getPartyId()).thenReturn(party);
        return c;
    }

    private static Monster mob(int id, int x, boolean friendly) {
        Monster m = mock(Monster.class);
        MonsterStats stats = new MonsterStats();
        stats.setFriendly(friendly);
        when(m.getId()).thenReturn(id);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        when(m.getStats()).thenReturn(stats);
        return m;
    }

    @Test
    void aFollowerInItsLeadersPqTargetsTheStagesMonsters() {
        Monster ligator = mob(9300001, 120, false);
        MapleMap stage = map(KPQ_STAGE_2, List.of(ligator));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = in(stage, 0, eim, 7);
        Character bot = in(stage, -40, eim, 7);
        assertSame(ligator, FollowerFight.target(bot, leader));
    }

    @Test
    void outsideTheLeadersInstanceOrPartyItOnlyFollows() {
        Monster ligator = mob(9300001, 120, false);
        MapleMap stage = map(KPQ_STAGE_2, List.of(ligator));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = in(stage, 0, eim, 7);
        assertNull(FollowerFight.target(in(stage, -40, null, 7), leader), "no instance");
        assertNull(FollowerFight.target(in(stage, -40, mock(EventInstanceManager.class), 7), leader),
                "another instance");
        assertNull(FollowerFight.target(in(stage, -40, eim, 8), leader), "another party");
        assertNull(FollowerFight.target(in(stage, -40, eim, -1), in(stage, 0, eim, -1)), "no party at all");
    }

    @Test
    void theMoonBunnyIsNeverATarget() {
        Monster bunny = mob(9300061, 20, true);
        Monster pig = mob(9300062, 300, false);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        MapleMap moon = map(HPQ_MOON, List.of(bunny, pig));
        assertSame(pig, FollowerFight.target(in(moon, 0, eim, 3), in(moon, 10, eim, 3)),
                "the pig even though the bunny is nearer");
        MapleMap onlyBunny = map(HPQ_MOON, List.of(bunny));
        assertNull(FollowerFight.target(in(onlyBunny, 0, eim, 3), in(onlyBunny, 10, eim, 3)));
        assertFalse(BotAttackDriver.botMayTarget(bunny));
        assertTrue(BotAttackDriver.botMayTarget(pig));
    }
}
