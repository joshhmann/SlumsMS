package soloMapling.ArtificialPlayer.BotTypes;

import client.BuffStat;
import client.Character;
import client.Job;
import client.inventory.InventoryType;
import constants.skills.Cleric;
import net.server.world.Party;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotPartySupport;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 followerfix (TEST 2026-10-30, Zakum altar 280030000, ch 2): a player partied a Lv200 Dark Knight and a Lv200
 * Bishop and told both to follow ({@code !bot followbot}). Through the whole fight neither swung on its own (only a
 * forced {@code !bot attack} did) and the Bishop never healed him under 60%: a follower fought only on a dojo floor,
 * in an expedition it was registered for or in its leader's party quest, and never ran the party support.
 */
class FollowerPartyFightTest {

    private static final int ZAKUM_ALTAR = 280030000;
    private static final int EL_NATH = 211000000;
    private static final int LEADER = 4242;
    private static final int BOT = 1_000_000_344;
    private static final int PARTY = 77;

    private final List<Integer> healed = new ArrayList<>();
    private final List<Character> partyInReach = new ArrayList<>();
    private Character botChr;

    @BeforeEach
    void seams() {
        System.setProperty("slums.bots.partysupport", "true");
        BotAttackDriver.ledges = (map, a, b) -> false;
        BotPartySupport.useForTest(new BotPartySupport.Effects() {
            @Override public List<Character> partyInReach(Character bot) { return partyInReach; }
            @Override public boolean partiedWithPlayer(Character bot) { return true; }
            @Override public boolean fighting(Character bot) { return true; }
            @Override public int mpCost(Character bot, int skillId, int level) { return 40; }
            @Override public boolean pay(Character bot, int mp) { return true; }
            @Override public void heal(Character bot, Character target, int skillId, int level) { healed.add(target.getId()); }
            @Override public List<BotPartySupport.Pot> pots(Character bot) { return List.of(); }
            @Override public Set<BuffStat> active(Character bot) { return Set.of(); }
            @Override public boolean drink(Character bot, BotPartySupport.Pot pot) { return false; }
        });
    }

    @AfterEach
    void clean() {
        System.clearProperty("slums.bots.partysupport");
        BotPartySupport.useForTest(null);
        BotAttackDriver.ledges = BotAttackDriver.NAV_LEDGES;
        if (botChr != null) {
            GCMovement.stop(botChr);
            FollowerFight.standDown(botChr);
        }
    }

    private static MapleMap map(int id, List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        return m;
    }

    private static Character at(MapleMap map, int id, int x, int party) {
        int mapId = map.getId();
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(mapId);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        when(c.getPartyId()).thenReturn(party);
        return c;
    }

    /** A Zakum arm (a boss part) the leader hit {@code agoMs} ago, or never (agoMs < 0). */
    private static Monster arm(int oid, int x, long agoMs) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(8800003);
        when(m.getObjectId()).thenReturn(oid);
        when(m.isAlive()).thenReturn(true);
        when(m.isBoss()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, -120));
        when(m.getStats()).thenReturn(new MonsterStats());
        long now = System.currentTimeMillis();
        if (agoMs >= 0) {
            when(m.lastHitBy(LEADER)).thenReturn(now - agoMs);
            when(m.damageSnapshot()).thenReturn(Map.of(LEADER, 50_000L));
        } else {
            when(m.damageSnapshot()).thenReturn(Map.of());
        }
        return m;
    }

    @Test
    void aFollowerInTheLeadersPartyGoesForTheMobTheLeaderIsFighting() {
        Monster fought = arm(11, 200, 1_000);
        MapleMap altar = map(ZAKUM_ALTAR, List.of(fought));
        Character leader = at(altar, LEADER, 0, PARTY);
        Character bot = at(altar, BOT, -30, PARTY);

        assertSame(fought, FollowerFight.target(bot, leader), "the party's fight is its fight - no forced command");
    }

    @Test
    void itNeverStartsOnAQuietMobNorFightsOutsideTheParty() {
        Monster quiet = arm(12, 150, -1);
        MapleMap altar = map(ZAKUM_ALTAR, List.of(quiet));
        Character leader = at(altar, LEADER, 0, PARTY);
        assertNull(FollowerFight.target(at(altar, BOT, -30, PARTY), leader), "nobody in the party hit it");

        Monster fought = arm(13, 150, 1_000);
        MapleMap altar2 = map(ZAKUM_ALTAR, List.of(fought));
        Character leader2 = at(altar2, LEADER, 0, PARTY);
        assertNull(FollowerFight.target(at(altar2, BOT, -30, -1), leader2), "not partied: it only follows");
        assertNull(FollowerFight.target(at(altar2, BOT, -30, PARTY + 1), leader2), "another party");
    }

    @Test
    void aBossPartWithinReachAcrossIsCloseEnoughToSwing() {
        int[] reach = {100, 90};
        assertTrue(FollowerFight.closeEnough(new Point(0, 0), new Point(80, -250), reach, true), "Zakum's arm overhead");
        assertFalse(FollowerFight.closeEnough(new Point(0, 0), new Point(80, -250), reach, false), "a plain mob up there");
        assertFalse(FollowerFight.closeEnough(new Point(0, 0), new Point(300, 0), reach, true), "too far across");
    }

    // ---- FollowerBot's own tick ----

    private FollowerBot follower(Character bot, Character leader) {
        when(bot.getClient().getChannelServer().getPlayerStorage().getCharacterById(LEADER)).thenReturn(leader);
        // not in the channel's storage: GCMovement leaves a test bot's movement driver off (hasLeftWorld)
        when(bot.getClient().getChannelServer().getPlayerStorage().getCharacterById(BOT)).thenReturn(null);
        when(bot.getParty()).thenReturn(mock(Party.class));
        FollowerBot f = new FollowerBot(bot);
        f.followForTest(LEADER);
        return f;
    }

    private Character deepBot(MapleMap map, Job job) {
        int mapId = map.getId();
        Character bot = mock(Character.class, RETURNS_DEEP_STUBS);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("pianuselnath");
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(mapId);
        when(bot.getPosition()).thenReturn(new Point(-30, 0));
        when(bot.getPartyId()).thenReturn(PARTY);
        when(bot.getEventInstance()).thenReturn(null);
        when(bot.getJob()).thenReturn(job);
        when(bot.getSkillLevel(anyInt())).thenReturn(0);
        when(bot.getSkillLevel(Cleric.HEAL)).thenReturn(30);
        when(bot.getInventory(InventoryType.EQUIPPED).getItem(anyShort())).thenReturn(null); // no item data in tests
        botChr = bot;
        return bot;
    }

    @Test
    void aFollowingHealerHealsAPartyMemberUnderSixtyPercent() {
        MapleMap altar = map(ZAKUM_ALTAR, List.of());
        Character leader = at(altar, LEADER, 0, PARTY);
        when(leader.getHp()).thenReturn(10_000);
        when(leader.getCurrentMaxHp()).thenReturn(30_000);
        when(leader.isAlive()).thenReturn(true);
        partyInReach.add(leader);
        FollowerBot f = follower(deepBot(altar, Job.BISHOP), leader);

        f.doFollow();

        assertEquals(List.of(LEADER), healed, "the Bishop heals its player at 33% HP");
    }

    @Test
    void afterTheLeaderDiesAndRevivesInTownTheFollowerStopsFightingAndFollowsHim() {
        Monster fought = arm(14, 20, 1_000);
        MapleMap altar = map(ZAKUM_ALTAR, List.of(fought));
        Character leader = at(altar, LEADER, 0, PARTY);
        Character bot = deepBot(altar, Job.BISHOP);
        when(bot.getSkillLevel(Cleric.HEAL)).thenReturn(0);
        FollowerBot f = follower(bot, leader);

        f.doFollow();
        assertTrue(f.isFighting(), "it fights the arm its leader is on");
        assertFalse(GCMovement.isFollowing(bot), "the follow session is down while it fights");

        // he dies at the altar and revives in El Nath (the altar's return map)
        MapleMap town = map(EL_NATH, List.of());
        when(leader.getMap()).thenReturn(town);
        when(leader.getMapId()).thenReturn(EL_NATH);
        when(leader.getHp()).thenReturn(50);
        f.doFollow();

        assertFalse(f.isFighting(), "nothing to fight once he left");
        assertTrue(GCMovement.isFollowing(bot), "the follow session is back up, so it travels after him");
    }
}
