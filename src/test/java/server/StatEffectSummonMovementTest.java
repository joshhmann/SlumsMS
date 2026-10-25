package server;

import constants.skills.Ranger;
import constants.skills.Sniper;
import org.junit.jupiter.api.Test;
import server.maps.SummonMovementType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.1: MapleRoot dropped Ranger.SILVER_HAWK from getSummonMovementType, so casting Silver Hawk spawned no summon
 * (applyTo only summons when the movement type is set). Cosmic lists it with Golden Eagle as CIRCLE_FOLLOW.
 */
class StatEffectSummonMovementTest {

    private static SummonMovementType movementOf(int skillId) throws Exception {
        Constructor<StatEffect> ctor = StatEffect.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        StatEffect effect = ctor.newInstance();
        set(effect, "skill", true);
        set(effect, "sourceid", skillId);
        Method m = StatEffect.class.getDeclaredMethod("getSummonMovementType");
        m.setAccessible(true);
        return (SummonMovementType) m.invoke(effect);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = StatEffect.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void silverHawkCirclesItsOwnerLikeGoldenEagle() throws Exception {
        assertEquals(SummonMovementType.CIRCLE_FOLLOW, movementOf(Ranger.SILVER_HAWK));
        assertEquals(SummonMovementType.CIRCLE_FOLLOW, movementOf(Sniper.GOLDEN_EAGLE));
    }
}
