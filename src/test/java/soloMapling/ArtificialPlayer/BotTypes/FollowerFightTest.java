package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.WzTestData;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.5 hotfix ("Mu Lung Dojo - bots don't register enemies on party climb so they just watch"): a party bot (a
 * FollowerBot) on a dojo floor with its leader takes the floor's script-spawned monster as its target.
 */
class FollowerFightTest {

    private static final int PARTY_FLOOR_1 = 925030100;  // party dojo, stage 1, slot 0
    private static final int SOLO_FLOOR_3 = 925020302;
    private static final int HENESYS_HUNTING = 104010001;

    private static MapleMap map(int id, List<Monster> mobs) {
        MapleMap m = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        return m;
    }

    private static Character on(MapleMap map, int x) {
        Character c = mock(Character.class);
        when(c.getMap()).thenReturn(map);
        when(c.getPosition()).thenReturn(new Point(x, 0));
        return c;
    }

    private static Monster mob(int x, boolean alive) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(alive);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        return m;
    }

    @Test
    void aFollowerOnADojoFloorWithItsLeaderTargetsTheSpawnedMonster() {
        Monster boss = mob(160, true); // dojang_1st -> spawnDojoMonster puts it at x 140..190
        MapleMap floor = map(PARTY_FLOOR_1, List.of(boss));
        Character leader = on(floor, -200);
        Character bot = on(floor, -230);
        assertSame(boss, FollowerFight.target(bot, leader));
    }

    @Test
    void theNearestLiveMonsterIsTheTarget() {
        Monster dead = mob(-220, false);
        Monster near = mob(-100, true);
        Monster far = mob(300, true);
        MapleMap floor = map(SOLO_FLOOR_3, List.of(dead, far, near));
        Character bot = on(floor, -230);
        assertSame(near, FollowerFight.target(bot, on(floor, 0)));
    }

    @Test
    void noTargetWhenTheFloorIsClearOrTheLeaderMovedOn() {
        MapleMap clear = map(PARTY_FLOOR_1, List.of());
        assertNull(FollowerFight.target(on(clear, 0), on(clear, 10)));

        MapleMap floor = map(PARTY_FLOOR_1, List.of(mob(160, true)));
        MapleMap next = map(PARTY_FLOOR_1 + 100, List.of());
        assertNull(FollowerFight.target(on(floor, 0), on(next, 0)), "the leader went up: follow, don't stay to fight");
        assertNull(FollowerFight.target(on(floor, 0), null));
    }

    @Test
    void outsideTheDojoAFollowerOnlyFollows() {
        MapleMap field = map(HENESYS_HUNTING, List.of(mob(10, true)));
        assertNull(FollowerFight.target(on(field, 0), on(field, 0)));
        assertFalse(FollowerFight.fightsOn(HENESYS_HUNTING));
        assertTrue(FollowerFight.fightsOn(PARTY_FLOOR_1));
        assertTrue(FollowerFight.fightsOn(SOLO_FLOOR_3));
    }

    /**
     * The root cause, from the real data: a dojo floor has no Map.wz life mobs (its monster comes from the map script),
     * so every grinding path that reads the static mob index calls it mobless: "train here" says "no mobs here".
     */
    @Test
    void aDojoFloorHasNoStaticMobsInTheIndex() {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        assertEquals(-1, MapMobIndex.level(PARTY_FLOOR_1));
        assertEquals(-1, MapMobIndex.level(SOLO_FLOOR_3));
    }
}
