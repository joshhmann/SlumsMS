package server.maps;

import constants.id.MobId;
import org.junit.jupiter.api.Test;
import server.life.Monster;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 Chaos Zakum: the altar's arm shield and the body wake-up read one body-to-arms mapping
 * ({@code MobId.shieldingArms}) instead of a hardcoded normal-Zakum pair, so the normal altar behaves exactly as it
 * did and the chaos altar (8800100 shielded by 8800103-8800110) works without a second branch.
 *
 * <p>The map is a {@link CALLS_REAL_METHODS} mock: the predicates under test are the real ones, only the object list
 * and the packet-sending wake-up are stubbed.
 */
class ZakumArmShieldTest {

    /** A map holding these monsters, each reachable both by object id and by the entry list. */
    private static MapleMap mapHolding(List<Monster> mobs) {
        MapleMap map = mock(MapleMap.class, CALLS_REAL_METHODS);
        List<MapObject> objects = new ArrayList<>();
        for (int i = 0; i < mobs.size(); i++) {
            MapObject object = mock(MapObject.class);
            when(object.getObjectId()).thenReturn(i);
            objects.add(object);
            doReturn(mobs.get(i)).when(map).getMonsterByOid(i); // doReturn: when(...) would run the real method first
        }
        doReturn(objects).when(map).getMapObjects();
        doNothing().when(map).makeMonsterReal(any());
        return map;
    }

    private static Monster mob(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    private static List<Monster> chaosAltar() {
        List<Monster> mobs = new ArrayList<>();
        mobs.add(mob(MobId.CHAOS_ZAKUM_1));
        for (int id = MobId.CHAOS_ZAKUM_ARM_1; id <= MobId.CHAOS_ZAKUM_ARM_8; id++) {
            mobs.add(mob(id));
        }
        return mobs;
    }

    // ---- the id block itself ----------------------------------------------------------------------

    @Test
    void theChaosIdsAreTheWzOnesAndTheArmRangesDoNotTouch() {
        assertEquals(8800100, MobId.CHAOS_ZAKUM_1);
        assertEquals(8800101, MobId.CHAOS_ZAKUM_2);
        assertEquals(8800102, MobId.CHAOS_ZAKUM_3);
        assertTrue(MobId.isChaosZakumArm(8800103));
        assertTrue(MobId.isChaosZakumArm(8800110));
        assertFalse(MobId.isChaosZakumArm(8800102));
        assertFalse(MobId.isChaosZakumArm(8800010), "a normal arm is not a chaos arm");
        assertFalse(MobId.isZakumArm(8800103), "and a chaos arm is not a normal arm");
        assertTrue(MobId.isAnyZakumArm(8800003) && MobId.isAnyZakumArm(8800110));
        assertTrue(MobId.isAnyZakumBody(8800002) && MobId.isAnyZakumBody(8800102));
        assertFalse(MobId.isAnyZakumBody(8800103));
        assertArrayEquals(new int[]{8800003, 8800010}, MobId.shieldingArms(8800000));
        assertArrayEquals(new int[]{8800103, 8800110}, MobId.shieldingArms(8800100));
        assertNull(MobId.shieldingArms(8800002), "only the phase the arms stand in front of is shielded");
        assertEquals(8800000, MobId.shieldedBodyOf(8800008));
        assertEquals(8800100, MobId.shieldedBodyOf(8800105));
        assertEquals(0, MobId.shieldedBodyOf(8800100));
    }

    @Test
    void bothAltarsBodiesAreTheMapsOwnBusiness() {
        assertTrue(MapleMap.hasBossMechanics(8800000));
        assertTrue(MapleMap.hasBossMechanics(MobId.CHAOS_ZAKUM_1), "the chaos body needed this too");
        assertFalse(MapleMap.hasBossMechanics(8800002), "the later phases were never listed, and still are not");
        assertFalse(MapleMap.hasBossMechanics(MobId.CHAOS_ZAKUM_2));
    }

    // ---- the shield ---------------------------------------------------------------------------------

    @Test
    void chaosArmsShieldTheChaosBody() {
        MapleMap chaos = mapHolding(chaosAltar());
        assertTrue(chaos.bossDamageBlocked(mob(MobId.CHAOS_ZAKUM_1)));
    }

    @Test
    void theTwoAltarsDoNotShieldEachOther() {
        MapleMap chaosBodyWithNormalArms = mapHolding(List.of(mob(MobId.CHAOS_ZAKUM_1), mob(8800003)));
        assertFalse(chaosBodyWithNormalArms.bossDamageBlocked(mob(MobId.CHAOS_ZAKUM_1)),
                "a normal arm does not shield the chaos body");

        MapleMap normalBodyWithChaosArms = mapHolding(List.of(mob(8800000), mob(MobId.CHAOS_ZAKUM_ARM_5)));
        assertFalse(normalBodyWithChaosArms.bossDamageBlocked(mob(8800000)),
                "and a chaos arm does not shield the normal body");
    }

    @Test
    void theNormalShieldIsUnchanged() {
        MapleMap map = mapHolding(List.of(mob(8800000), mob(8800003), mob(8800010)));
        assertTrue(map.bossDamageBlocked(mob(8800000)));

        MapleMap noArms = mapHolding(List.of(mob(8800000)));
        assertFalse(noArms.bossDamageBlocked(mob(8800000)));

        MapleMap laterPhase = mapHolding(List.of(mob(8800001), mob(8800003)));
        assertFalse(laterPhase.bossDamageBlocked(mob(8800001)), "only 8800000 is ever shielded");
    }

    // ---- the wake-up --------------------------------------------------------------------------------

    @Test
    void theLastChaosArmWakesTheChaosBody() {
        // the map no longer holds the dying arm by the time killMonster asks (that is why the normal fight works)
        List<Monster> all = chaosAltar();
        Monster lastArm = all.remove(all.size() - 1); // 8800110, the arm whose death is being handled
        List<Monster> mobs = new ArrayList<>(all);    // the other arms are gone too: this was the last one
        mobs.subList(1, mobs.size()).clear();
        MapleMap chaos = mapHolding(mobs);

        chaos.wakeBodyIfLastArm(lastArm, chaos);

        verify(chaos).makeMonsterReal(mobs.get(0));
    }

    @Test
    void anArmWithABrotherStillStandingWakesNothing() {
        List<Monster> mobs = chaosAltar();
        MapleMap chaos = mapHolding(mobs);

        chaos.wakeBodyIfLastArm(mobs.get(3), chaos);

        verify(chaos, never()).makeMonsterReal(any());
    }

    @Test
    void aChaosArmNeverWakesTheNormalBody() {
        MapleMap normalOnly = mapHolding(List.of(mob(8800000)));
        normalOnly.wakeBodyIfLastArm(mob(MobId.CHAOS_ZAKUM_ARM_1), normalOnly);
        verify(normalOnly, never()).makeMonsterReal(any());
    }

    @Test
    void theNormalWakeUpIsUnchanged() {
        List<Monster> mobs = new ArrayList<>();
        mobs.add(mob(8800000));
        Monster lastArm = mob(8800009);
        MapleMap map = mapHolding(mobs);

        map.wakeBodyIfLastArm(lastArm, map);

        verify(map).makeMonsterReal(mobs.get(0));
    }
}
