package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 companion-shared-picker: a party regular inside a run (a dojo floor, an expedition, its leader's party quest)
 * picks through the one shared picker when {@code -Dslums.companion.picker.regulars=true}, the ranking later cards
 * (assist, boss kill order) add sort keys to, and takes the nearest mob exactly as before while that switch is off
 * (the default). The instance is the party's fight, so the in-run mode keeps the alive/targetable/surface/reach-gate/
 * skip filters of {@code FollowerFight.nearest} and drops only the owned alt's leash, engaged and KS rules.
 */
class FollowerFightSharedPickerTest {

    private static final int KPQ_STAGE_2 = 103000801;
    private static final int PARTY = 7;
    private static final AtomicInteger oids = new AtomicInteger(700);

    private BotAttackDriver.Ledges ledgesBefore;
    private Comparator<Monster> priorityBefore;

    @BeforeEach
    void keepLedges() {
        ledgesBefore = BotAttackDriver.ledges;
        priorityBefore = soloMapling.companions.OwnedAltCombat.priority;
        BotAttackDriver.ledges = (map, a, b) -> false; // a mocked map has no nav graph
    }

    @AfterEach
    void restore() {
        BotAttackDriver.ledges = ledgesBefore;
        soloMapling.companions.OwnedAltCombat.priority = priorityBefore;
        System.clearProperty(Feature.SHARED_PICKER.property(false));
    }

    private static void switchOn() {
        System.setProperty(Feature.SHARED_PICKER.property(false), "true");
    }

    private static MapleMap map(int id, List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        return m;
    }

    /** A real player: the follower's leader. */
    private static Character player(MapleMap map, int x, EventInstanceManager eim, int party) {
        Character c = in(map, x, eim, party);
        when(c.getId()).thenReturn(1_000);
        when(c.getName()).thenReturn("Josh");
        return c;
    }

    /** A regular: a bot with a bot client and no owner, so the policy treats it as a party regular. */
    private static Character regular(MapleMap map, int x, EventInstanceManager eim, int party) {
        Character c = in(map, x, eim, party);
        when(c.getId()).thenReturn(1_000_000_303);
        when(c.getName()).thenReturn("Clock4");
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.isOwnedAlt()).thenReturn(false);
        return c;
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
        when(m.getObjectId()).thenReturn(oids.incrementAndGet());
        when(m.getName()).thenReturn(friendly ? "Moon Bunny" : "Ligator");
        when(m.getStats()).thenReturn(stats);
        return m;
    }

    /** Its leader's party quest: the stage's monsters are the fight (FollowerFight.instanceFight). */
    @Test
    void aRegularInARunTakesTheNearestMobByDefault() {
        Monster near = mob(9300001, 30, false);
        Monster far = mob(9300002, 300, false);
        MapleMap stage = map(KPQ_STAGE_2, List.of(far, near));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = player(stage, 0, eim, PARTY);
        Character bot = regular(stage, -40, eim, PARTY);

        assertFalse(PartyCompanionPolicy.on(Feature.SHARED_PICKER, bot), "a regular is off unless a server says so");
        assertSame(near, FollowerFight.target(bot, leader), "the nearest mob, as every run before this card");
    }

    /**
     * Fail-before: {@code nearest()} takes the near mob whatever surface it stands on; the shared picker rejects a mob
     * the attack driver will not face ({@code onAttackableSurface}) and takes the farther one instead.
     */
    @Test
    void withTheSwitchOnAMobOnAnotherLedgeIsNotPicked() {
        switchOn();
        Monster otherLedge = mob(9300001, 40, false);
        Monster reachable = mob(9300002, 300, false);
        BotAttackDriver.ledges = (map, a, b) -> b.x == 40; // the near mob's ledge is not one the driver faces
        MapleMap stage = map(KPQ_STAGE_2, List.of(otherLedge, reachable));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = player(stage, 0, eim, PARTY);
        Character bot = regular(stage, -40, eim, PARTY);

        assertTrue(PartyCompanionPolicy.on(Feature.SHARED_PICKER, bot), "the switch this card adds");
        assertSame(reachable, FollowerFight.target(bot, leader),
                "the shared in-run picker never picks a mob the driver will not face");
    }

    /**
     * Fail-before: the hook does not exist for the run path until this card, so {@code nearest()} takes the near mob.
     * After it, the ranking method both pickers share asks the hook first - the seam the assist and boss kill-order
     * cards add their sort keys to.
     */
    @Test
    void withTheSwitchOnThePriorityHookRanksTheFarMobFirst() {
        switchOn();
        Monster near = mob(9300001, 40, false);
        Monster far = mob(9300002, 300, false);
        soloMapling.companions.OwnedAltCombat.priority =
                (a, b) -> Integer.compare(b.getPosition().x, a.getPosition().x); // farther first
        MapleMap stage = map(KPQ_STAGE_2, List.of(near, far));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = player(stage, 0, eim, PARTY);
        Character bot = regular(stage, -40, eim, PARTY);

        assertSame(far, FollowerFight.target(bot, leader), "the shared ranking's sort key, not the nearest mob");
    }

    @Test
    void withTheSwitchOnAMobBotMayTargetRefusesIsNeverPicked() {
        switchOn();
        Monster bunny = mob(9300061, 20, true);
        Monster pig = mob(9300062, 300, false);
        MapleMap stage = map(KPQ_STAGE_2, List.of(bunny, pig));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        Character leader = player(stage, 0, eim, PARTY);
        Character bot = regular(stage, -40, eim, PARTY);

        assertSame(pig, FollowerFight.target(bot, leader), "never the nearer friendly mob");
    }
}
