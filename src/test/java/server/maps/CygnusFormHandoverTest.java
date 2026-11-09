package server.maps;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterStats;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * cygnus-fight-unreachable part 3 (2026-11-09): Cygnus is a two-form chain on purpose - 8850011 (the phase form, no
 * drop table) hands over to 8850012 (migration 055's table, and the only id the event's isCygnus accepts) - but the
 * hand-over used to run only when the fifth guard died. A 8850011 killed without going through the guards (a GM kill
 * of the full-HP form, a blow that jumps over the last threshold) left the room with no clear, no drops, no bosslog
 * row and the two-hour clock running. {@code MapleMap.afterBossKilled} now hands over on any form 1 death, once.
 *
 * <p>The map is a spy on a real {@link MapleMap} (so the Cygnus fields and the map's object list are real); the kills,
 * spawns and the final form's damage are stubbed. The final form is a real {@link Monster} with a known HP, so the
 * share it comes in with can be checked exactly.
 */
class CygnusFormHandoverTest {

    private static final int MAP = 271040100;
    private static final int FORM_ONE = 8850011;
    private static final int FINAL_FORM = 8850012;
    private static final int LAST_GUARD = 8850004;
    private static final int VON_LEON_LAST_FORM = 8847777;
    private static final long FINAL_HP = 2_000_000_000L;

    private MapleMap map;
    private Monster finalForm;
    private Monster vonLeonLastForm;
    private Character chr;

    @BeforeEach
    void harness() {
        soloMapling.WzTestData.assumePresent("Mob.wz"); // mockStatic(LifeFactory) runs its static init, which reads Mob.wz
        map = spy(new MapleMap(MAP, 0, 0, MAP, 1.0f));
        doNothing().when(map).killMonster(any(Monster.class), any(), anyBoolean(), anyInt());
        doNothing().when(map).killMonster(any(Monster.class), any(), anyBoolean());
        doNothing().when(map).killMonster(any(Monster.class), any(), anyBoolean(), anyBoolean());
        doNothing().when(map).killAllMonsters();
        doNothing().when(map).spawnMonster(any(Monster.class));
        doReturn(true).when(map).damageMonster(any(), any(), anyLong());
        doNothing().when(map).spawnArielMonster(any(), any());
        doNothing().when(map).spawnMist(any(), anyInt(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
        finalForm = mob(FINAL_FORM, FINAL_HP);
        vonLeonLastForm = mob(VON_LEON_LAST_FORM, 1_000_000L);
        chr = mock(Character.class);
    }

    private static Monster mob(int id, long hp) {
        MonsterStats stats = new MonsterStats();
        stats.setHp(hp);
        return new Monster(id, stats);
    }

    /** {@code LifeFactory.getMonster} answered for the two ids {@code afterBossKilled} asks for. */
    private MockedStatic<LifeFactory> lifeFactory() {
        MockedStatic<LifeFactory> life = mockStatic(LifeFactory.class);
        life.when(() -> LifeFactory.getMonster(FINAL_FORM)).thenReturn(finalForm);
        life.when(() -> LifeFactory.getMonster(VON_LEON_LAST_FORM)).thenReturn(vonLeonLastForm);
        return life;
    }

    private void setField(String name, Object value) {
        try {
            Field f = MapleMap.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(map, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set MapleMap." + name, e);
        }
    }

    @Test
    void formOneDyingWithNoGuardBringsTheFinalFormOnce() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            Monster form1 = mob(FORM_ONE, 2_000_000_000L);

            assertTrue(map.afterBossKilled(chr, form1), "the map handles form 1's death");

            InOrder order = inOrder(map);
            order.verify(map).killMonster(form1, chr, false, 0); // form 1 stays dropless (migration 055)
            order.verify(map).killAllMonsters();                 // the field-clear the guard path does
            order.verify(map).spawnMonster(finalForm);           // and the form that has the drops
            verify(map, times(1)).spawnMonster(any(Monster.class));
            // no guard had spawned: the final form comes whole, at full HP and unhit
            verify(map, never()).damageMonster(any(), any(), anyLong());
        }
    }

    @Test
    void formOneDyingAfterTheGuardChainComesInWithTheGuardsShare() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            setField("cygnusSpawnCounter", 5); // all five guards had been through, yet form 1 stood
            Monster form1 = mob(FORM_ONE, 2_000_000_000L);

            assertTrue(map.afterBossKilled(chr, form1));

            verify(map).spawnMonster(finalForm);
            // the guard branches' own formula: 15% of the final form's HP per guard already through
            verify(map, times(1)).damageMonster(eq(chr), eq(finalForm), eq((long) (FINAL_HP * (0.15 * 5))));
        }
    }

    @Test
    void aSecondFormOneDeathSpawnsNothing() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            assertTrue(map.afterBossKilled(chr, mob(FORM_ONE, 2_000_000_000L)));

            Monster second = mob(FORM_ONE, 2_000_000_000L);
            assertTrue(map.afterBossKilled(chr, second));

            verify(map, times(1)).spawnMonster(any(Monster.class));
            verify(map).killMonster(second, chr, false, 0); // the stray form still dies dropless
        }
    }

    @Test
    void formOneDyingWhileTheFinalFormIsUpSpawnsNothing() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            setField("cygnusFinalFormSummoned", true); // 8850012 is already up (or was killed: the room cleared)
            Monster form1 = mob(FORM_ONE, 2_000_000_000L);

            assertTrue(map.afterBossKilled(chr, form1));

            verify(map, never()).spawnMonster(any(Monster.class));
            verify(map, never()).killAllMonsters(); // and the standing final form is not cleared either
            verify(map).killMonster(form1, chr, false, 0);
        }
    }

    @Test
    void theLastGuardStillBringsTheFinalFormOnceAndMarksIt() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            setField("cygnusSpawnCounter", 5);
            Monster guard = mob(LAST_GUARD, 1_000_000_000L);

            assertTrue(map.afterBossKilled(chr, guard));

            verify(map).killMonster(guard, chr, true); // the guard drops its loot (1.6.1)
            verify(map).spawnMonster(finalForm);
            verify(map).damageMonster(eq(chr), eq(finalForm), eq((long) (FINAL_HP * (0.15 * 5))));

            // the guard path marks the room as handed over: a stray form 1 brings no second final form
            Monster stray = mob(FORM_ONE, 2_000_000_000L);
            assertTrue(map.afterBossKilled(chr, stray));
            verify(map, times(1)).spawnMonster(any(Monster.class));
        }
    }

    @Test
    void theThresholdSwapIsNotADeathToHandOver() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            setField("spawningcygnus", true); // the guard spawn took form 1 out and put its guard up
            Monster form1 = mob(FORM_ONE, 2_000_000_000L); // already off the field: the swap removed it

            assertTrue(map.afterBossKilled(chr, form1), "handled: the swap already did the phase's work");

            verify(map, never()).spawnMonster(any(Monster.class));
            verify(map, never()).killMonster(any(Monster.class), any(), anyBoolean(), anyInt());
        }
    }

    @Test
    void aFormOneStillStandingDuringTheGuardPhaseIsRemovedDropless() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            setField("spawningcygnus", true);
            Monster form1 = mob(FORM_ONE, 2_000_000_000L);
            map.addMapObject(form1); // spawned from outside while a guard is out: it really is on the field

            assertTrue(map.afterBossKilled(chr, form1));

            verify(map).killMonster(form1, chr, false, 0);
            verify(map, never()).spawnMonster(any(Monster.class));
        }
    }

    @Test
    void formOnesDeathReachesTheHandOverFromTheRealDamagePath() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            doCallRealMethod().when(map).damageMonster(any(), any(), anyLong());
            Monster form1 = mob(FORM_ONE, 100L); // no guard had been triggered: killed from full HP in one blow
            map.addMapObject(form1);

            assertTrue(map.damageMonster(chr, form1, 500L), "the killing blow is handled by the map");

            verify(map).spawnMonster(finalForm);
            verify(map).killMonster(form1, chr, false, 0);
        }
    }

    @Test
    void theOtherBossHandOversAreUnchanged() {
        try (MockedStatic<LifeFactory> ignored = lifeFactory()) {
            Monster vonLeon = mob(8840000, 1_000L);
            assertTrue(map.afterBossKilled(chr, vonLeon));
            verify(map).killMonster(vonLeon, chr, false, false);
            verify(map).spawnArielMonster(vonLeonLastForm, vonLeon.getPosition());
            verify(map).damageMonster(chr, vonLeonLastForm, vonLeonLastForm.getMaxHp());

            Monster magnus = mob(8880002, 1_000L);
            assertTrue(map.afterBossKilled(chr, magnus));
            verify(map).killMonster(magnus, chr, true);
            verify(map).killAllMonsters();

            Monster papulatus = mob(8240098, 1_000L);
            assertTrue(map.afterBossKilled(chr, papulatus));
            verify(map).killMonster(papulatus, chr, true);
            verify(map).spawnMist(any(), eq(1_000_000), eq(false), eq(true), eq(false), eq(true));

            verify(map, never()).spawnMonster(any(Monster.class)); // no Cygnus form is touched by any of them
        }
    }
}
