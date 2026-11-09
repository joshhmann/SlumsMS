package server.life;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.maps.MapleMap;
import soloMapling.qa.BossFeed;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 boss-feed-lines: the real MobSkill.applyEffect paths write one boss line per heal, summon use and immunity
 * for an {@code isBoss()} monster, and nothing for any other monster. The builder is package-private, so this is in
 * {@code server.life}.
 */
class BossSkillFeedTest {

    private final List<String> log = new ArrayList<>();
    private final long[] clock = {5_000_000};

    @BeforeEach
    void setUp() {
        BossFeed.useForTests(log::add, () -> clock[0]);
    }

    @AfterEach
    void tearDown() {
        BossFeed.useForTests(null, null);
    }

    private static Monster mob(int id, boolean boss, long hpBefore, long hpAfter) {
        Monster m = mock(Monster.class);
        MapleMap map = mock(MapleMap.class);
        when(m.getId()).thenReturn(id);
        when(m.isBoss()).thenReturn(boss);
        when(m.getMap()).thenReturn(map);
        when(m.getMaxHp()).thenReturn(1_000_000L);
        when(m.getHp()).thenReturn(hpBefore, hpAfter);
        when(m.getPosition()).thenReturn(new Point(0, 0));
        return m;
    }

    @Test
    void aBossHealWritesOneLineWithTheHpItActuallyGot() {
        Monster boss = mob(8510000, true, 400_000L, 450_000L);
        MobSkill heal = new MobSkill.Builder(MobSkillType.HEAL_M, 1).x(50_000).y(0).build();

        heal.applyEffect(null, boss, false, null);

        assertEquals(List.of("BOSS heal pianus R +50000 hp (now 45%)"), log);
    }

    @Test
    void aNonBossMobWritesNoSkillLineForAnyOfTheThree() {
        Monster plain = mob(100100, false, 400_000L, 450_000L);
        when(plain.isBuffed(any())).thenReturn(false);
        MobSkill heal = new MobSkill.Builder(MobSkillType.HEAL_M, 1).x(50_000).build();
        MobSkill weapon = new MobSkill.Builder(MobSkillType.PHYSICAL_IMMUNE, 1).x(1).prop(1.0f).duration(35_000).build();
        MobSkill magic = new MobSkill.Builder(MobSkillType.MAGIC_IMMUNE, 1).x(1).prop(1.0f).duration(35_000).build();

        heal.applyEffect(null, plain, false, null);
        weapon.applyEffect(null, plain, false, null);
        magic.applyEffect(null, plain, false, null);

        assertEquals(List.of(), log);
    }

    @Test
    void aBossImmunityNamesWhichKindAndForHowLong() {
        Monster boss = mob(8510000, true, 1L, 1L);
        when(boss.isBuffed(any())).thenReturn(false);
        MobSkill weapon = new MobSkill.Builder(MobSkillType.PHYSICAL_IMMUNE, 1).x(1).prop(1.0f).duration(35_000).build();
        MobSkill magic = new MobSkill.Builder(MobSkillType.MAGIC_IMMUNE, 1).x(1).prop(1.0f).duration(35_000).build();

        weapon.applyEffect(null, boss, false, null);
        clock[0] += 31_000;
        magic.applyEffect(null, boss, false, null);

        assertEquals(List.of("BOSS immune pianus R weapon 35 s", "BOSS immune pianus R magic 35 s"), log);
    }

    @Test
    void anImmunityThatDidNotApplyWritesNothing() {
        Monster boss = mob(8510000, true, 1L, 1L);
        when(boss.isBuffed(any())).thenReturn(true);   // the other immunity is up: the skill does nothing
        MobSkill weapon = new MobSkill.Builder(MobSkillType.PHYSICAL_IMMUNE, 1).x(1).prop(1.0f).duration(35_000).build();

        weapon.applyEffect(null, boss, false, null);

        assertEquals(List.of(), log);
    }

    @Test
    void aBloodyBoomUseIsOneLineWhateverTheMobCount() {
        Monster boss = mob(8510000, true, 1L, 1L);
        MapleMap map = boss.getMap();
        when(map.getId()).thenReturn(230040420);
        when(map.getSpawnedMonstersOnMap()).thenReturn(1);
        when(boss.countAvailableMobSummons(anyInt(), anyInt())).thenReturn(5);
        MobSkill summon = new MobSkill.Builder(MobSkillType.SUMMON, 41).limit(5000)
                .toSummon(List.of(8510100, 8510100, 8510100, 8510100, 8510100)).build();
        try (MockedStatic<LifeFactory> factory = Mockito.mockStatic(LifeFactory.class)) {
            factory.when(() -> LifeFactory.getMonster(anyInt())).thenAnswer(a -> {
                Monster boom = mock(Monster.class);
                when(boom.getId()).thenReturn(a.getArgument(0));
                when(boom.getPosition()).thenReturn(new Point(0, 0));
                return boom;
            });

            summon.applyEffect(null, boss, false, null);
        }

        assertEquals(List.of("BOSS summon pianus R bloody-boom 5"), log);
    }
}
