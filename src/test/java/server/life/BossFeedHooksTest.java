package server.life;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.qa.BossFeed;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 boss-feed-lines: the field hooks are really wired into the real kill path (Monster.killBy) and the real spawn
 * path (MapleMap.spawnMonster), so removing either hook turns these red.
 */
class BossFeedHooksTest {

    private final List<String> log = new ArrayList<>();
    private final long[] clock = {9_000_000};

    @BeforeEach
    void setUp() {
        BossFeed.useForTests(log::add, () -> clock[0]);
    }

    @AfterEach
    void tearDown() {
        BossFeed.useForTests(null, null);
    }

    private static Monster boss(int id, boolean isBoss, MapleMap map) {
        MonsterStats stats = new MonsterStats();
        stats.setHp(80L);
        stats.setExp(900_000);
        stats.setBoss(isBoss);
        Monster mob = new Monster(id, stats);
        mob.setMap(map);
        return mob;
    }

    private static Character attacker(int id, MapleMap map) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getLevel()).thenReturn(120);
        when(c.getMap()).thenReturn(map);
        when(c.getClient()).thenReturn(mock(Client.class));
        when(c.isAlive()).thenReturn(true);
        when(c.getName()).thenReturn("c" + id);
        return c;
    }

    @Test
    void killingPianusLWritesTheKillAndRewardLinesFromTheRealKillPath() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(BossFeed.PIANUS_MAP);
        Monster pianus = boss(BossFeed.PIANUS_L, true, map);
        pianus.damage(attacker(31, map), 40, false);
        pianus.damage(attacker(32, map), 40, false);

        pianus.killBy(null);

        assertEquals(List.of("BOSS kill pianus L after 0 s, damagers 2",
                "BOSS reward pianus L exp 900000 to 2 members"), log);
    }

    @Test
    void killingAnOrdinaryMobWritesNothing() {
        MapleMap map = mock(MapleMap.class);
        Monster plain = boss(100100, false, map);
        plain.damage(attacker(31, map), 80, false);

        plain.killBy(null);

        assertEquals(List.of(), log);
    }

    @Test
    void aBossRespawnOnTheMapWritesTheTimerSpawnLine() {
        MapleMap map = new MapleMap(BossFeed.PIANUS_MAP, 0, 0, BossFeed.PIANUS_MAP, 1.0f);
        Monster pianus = boss(BossFeed.PIANUS_R, true, map);

        map.spawnMonster(pianus);

        assertEquals(List.of("BOSS spawn pianus R map 230040420 source timer"), log);
    }
}
