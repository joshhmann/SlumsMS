package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 boss lane (ticket 130, fix 2): a follower in an event instance does not choose a boss part it cannot get into
 * reach of. It used to rank by straight-line distance, so next to a big part it walked at it, landed nothing for
 * {@code STALL_MS}, then skipped it for {@code GIVE_UP_MS} - the stand-and-give-up loop. Small mobs are ranked exactly
 * as before: the reach check runs for boss parts only.
 */
class FollowerFightReachTest {

    private static final int ZAKUM_FAKE_BODY = 8800000; // a boss part by MapleMap.hasBossMechanics
    private static final int LIGATOR = 9300001;

    /** Round 9: the reach gate is a switch like any other, so a test that wants it turns it on. */
    @BeforeEach
    void turnTheGateOn() {
        System.setProperty(FollowerFight.REACH_SWITCH, "true");
    }

    @AfterEach
    void restore() {
        BotAttackDriver.pathCost = BotAttackDriver.NAV_PATH_COST;
        BotAttackDriver.ledges = BotAttackDriver.NAV_LEDGES;
        System.clearProperty(FollowerFight.REACH_SWITCH);
    }

    private static MapleMap map(List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(103000801);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        return m;
    }

    private static Character in(MapleMap map, int x, EventInstanceManager eim, int party) {
        // deep stubs + no equipped item: the reach check resolves the bot's own attack profile (job, weapon, skills)
        Character c = mock(Character.class, RETURNS_DEEP_STUBS);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.getEventInstance()).thenReturn(eim);
        when(c.getPartyId()).thenReturn(party);
        when(c.getJob()).thenReturn(Job.WARRIOR);
        when(c.getSkillLevel(anyInt())).thenReturn(0);
        when(c.getInventory(InventoryType.EQUIPPED).getItem(anyShort())).thenReturn(null);
        return c;
    }

    private static Monster mob(int id, int x, int y) {
        Monster m = mock(Monster.class);
        MonsterStats stats = new MonsterStats();
        stats.setFriendly(false);
        when(m.getId()).thenReturn(id);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, y));
        when(m.getStats()).thenReturn(stats);
        return m;
    }

    /** Round 9: off unless a server turns it on (the release conf carries the on line). */
    @Test
    void theReachGateIsOffUnlessAServerTurnsItOn() {
        System.clearProperty(FollowerFight.REACH_SWITCH);
        assertFalse(FollowerFight.reachGateOn(), "-Dslums.bossfight.reach is off by default");
        System.setProperty(FollowerFight.REACH_SWITCH, "true");
        assertTrue(FollowerFight.reachGateOn());
    }

    @Test
    void aBossPartTheFollowerCannotGetInReachOfIsSkipped() {
        Monster hovering = mob(ZAKUM_FAKE_BODY, 10, -400); // nearest by straight line, no walk reaches it
        Monster ligator = mob(LIGATOR, 700, 0);
        MapleMap stage = map(List.of(hovering, ligator));
        BotAttackDriver.ledges = (m, a, b) -> false;
        BotAttackDriver.pathCost = (m, a, b) -> b.x == 10 && b.y == -400
                ? GCMovement.UNREACHABLE : 1;
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = in(stage, 0, eim, 7);
        Character bot = in(stage, 0, eim, 7);

        assertSame(ligator, FollowerFight.target(bot, leader),
                "the part it can never hit is passed over for the mob it can");
    }

    @Test
    void anOrdinaryMobNeverPaysTheWalkCost() {
        // the measurement behind shipping the switch on: the gate is two cheap tests for a non-boss candidate, and the
        // walk cost (the only expensive part) is never asked for one
        AtomicInteger walkCostAsked = new AtomicInteger();
        BotAttackDriver.ledges = (m, a, b) -> false;
        BotAttackDriver.pathCost = (m, a, b) -> {
            walkCostAsked.incrementAndGet();
            return 1;
        };
        Monster ligator = mob(LIGATOR, 300, 0);
        MapleMap stage = map(List.of(ligator));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = in(stage, 0, eim, 7);
        Character bot = in(stage, 0, eim, 7);

        assertSame(ligator, FollowerFight.target(bot, leader), "an ordinary mob is picked as before");
        assertEquals(0, walkCostAsked.get(), "a bot fighting ordinary mobs pays no walk cost for the reach gate");
    }

    @Test
    void aReachableBossPartIsStillChosen() {
        Monster part = mob(ZAKUM_FAKE_BODY, 10, 0);
        Monster ligator = mob(LIGATOR, 300, 0);
        MapleMap stage = map(List.of(part, ligator));
        BotAttackDriver.ledges = (m, a, b) -> false;
        BotAttackDriver.pathCost = (m, a, b) -> 1;
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = in(stage, 0, eim, 7);
        Character bot = in(stage, 0, eim, 7);

        assertSame(part, FollowerFight.target(bot, leader), "a reachable part is picked as before");
    }
}
