package soloMapling.companions;

import client.Character;
import client.Job;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.5 b311: an owned alt does not lock onto a boss part it can never get in reach of. H1 run 2b on TEST (6 Nov,
 * switch on): the Hero alt walked 315 times and swung 0 times for four minutes while two Red Wyverns (8810019, boss
 * flag, flying) that the party was fighting were the nearest engaged mobs; it attacked Horntail's head only once they
 * died. {@code OwnedAltCombat.target} ranked by straight-line distance with no reach test, so it walked at a wyvern,
 * gave up after STALL_MS, skipped it for GIVE_UP_MS, and took the other one. Population followers already pass over
 * such a part ({@code FollowerFight}, ticket 130 fix 2, {@code -Dslums.bossfight.reach}); the alt now uses the same
 * gate, behind the same switch.
 */
class OwnedAltBossReachTest {

    private static final long NOW = 50_000_000L;
    private static final int OWNER = 1, ALT = 2;
    private static final int RED_WYVERN = 8810019;
    private static final int HEAD_A = 8810000;
    private static final AtomicInteger oids = new AtomicInteger(500);
    private static final scripting.event.EventInstanceManager INSTANCE =
            mock(scripting.event.EventInstanceManager.class);

    @BeforeEach
    void gateOn() {
        System.setProperty("slums.bossfight.reach", "true");
        System.setProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH, "true"); // as on TEST for H1 run 2b
        BotAttackDriver.ledges = (m, a, b) -> false;
    }

    @AfterEach
    void restore() {
        System.clearProperty("slums.bossfight.reach");
        System.clearProperty(OwnedAltCombat.INSTANCE_LEASH_SWITCH);
        BotAttackDriver.pathCost = BotAttackDriver.NAV_PATH_COST;
        BotAttackDriver.ledges = BotAttackDriver.NAV_LEDGES;
        OwnedAltCombat.forget(OWNER);
        OwnedAltCombat.forget(ALT);
    }

    private static MapleMap map(List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(240060200);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        when(m.getCharacters()).thenReturn(new ArrayList<>());
        return m;
    }

    /** Deep stubs, a warrior with no skills and no weapon: the reach test resolves the alt's own attack profile. */
    private static Character at(MapleMap map, int id, int x, boolean alt) {
        Character c = mock(Character.class, RETURNS_DEEP_STUBS);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.isOwnedAlt()).thenReturn(alt);
        when(c.getParty()).thenReturn(null);
        when(c.getEventInstance()).thenReturn(INSTANCE); // both in the Horntail run
        when(c.getJob()).thenReturn(Job.WARRIOR);
        when(c.getSkillLevel(anyInt())).thenReturn(0);
        when(c.getInventory(InventoryType.EQUIPPED).getItem(anyShort())).thenReturn(null);
        return c;
    }

    /** A boss part the owner has just hit (so the party's fight). */
    private static Monster part(int id, int x, int y) {
        Monster m = mock(Monster.class);
        MonsterStats stats = new MonsterStats();
        stats.setFriendly(false);
        when(m.getId()).thenReturn(id);
        when(m.isBoss()).thenReturn(true);
        when(m.isAlive()).thenReturn(true);
        when(m.getStats()).thenReturn(stats);
        when(m.getPosition()).thenReturn(new Point(x, y));
        when(m.getObjectId()).thenReturn(oids.incrementAndGet());
        when(m.getName()).thenReturn("part " + id);
        Map<Integer, Long> dealt = new HashMap<>();
        dealt.put(OWNER, 10L);
        when(m.damageSnapshot()).thenReturn(dealt);
        when(m.lastHitBy(OWNER)).thenReturn(NOW - 1_000);
        return m;
    }

    @Test
    void aFlyingPartItCanNeverReachIsPassedOverForTheHeadItCan() {
        Monster wyvern = part(RED_WYVERN, 40, -300); // nearest by straight line, in the air
        Monster head = part(HEAD_A, 420, 0);
        MapleMap room = map(List.of(wyvern, head));
        BotAttackDriver.pathCost = (m, a, b) -> b.y == -300 ? GCMovement.UNREACHABLE : 1;
        Character owner = at(room, OWNER, 0, false), alt = at(room, ALT, 10, true);

        assertSame(head, OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW),
                "the alt goes for the part it can hit, not the wyvern it would walk under for STALL_MS");
    }

    @Test
    void aReachablePartIsStillTheNearestChoice() {
        Monster near = part(RED_WYVERN, 40, 0);
        Monster head = part(HEAD_A, 420, 0);
        MapleMap room = map(List.of(near, head));
        BotAttackDriver.pathCost = (m, a, b) -> 1;
        Character owner = at(room, OWNER, 0, false), alt = at(room, ALT, 10, true);

        assertSame(near, OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW));
    }

    @Test
    void withTheSwitchOffNothingChanges() {
        System.clearProperty("slums.bossfight.reach");
        AtomicInteger walkCostAsked = new AtomicInteger();
        BotAttackDriver.pathCost = (m, a, b) -> {
            walkCostAsked.incrementAndGet();
            return GCMovement.UNREACHABLE;
        };
        Monster wyvern = part(RED_WYVERN, 40, -300);
        Monster head = part(HEAD_A, 420, 0);
        MapleMap room = map(List.of(wyvern, head));
        Character owner = at(room, OWNER, 0, false), alt = at(room, ALT, 10, true);

        assertSame(wyvern, OwnedAltCombat.target(owner, alt, OwnedAltCombat.partyIds(alt, owner), NOW),
                "off is today's behaviour: the nearest engaged part");
        assertEquals(0, walkCostAsked.get(), "and no walk cost is asked for");
    }
}
