package server;

import client.BuffStat;
import client.Character;
import constants.skills.Assassin;
import constants.skills.Buccaneer;
import constants.skills.Pirate;
import constants.skills.Rogue;
import org.junit.jupiter.api.Test;
import tools.PacketCreator;
import tools.Pair;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// SoloMapling bots show buffs without StatEffect.applyTo, so foreignBuffPacket has to pick what applyTo would. A raw
// stat list in giveForeignBuff is misread by every client, and overruns the packet for the two-state pirate buffs.
class StatEffectForeignBuffTest {

    private static final int BOT_ID = 1_000_000_123;

    private static StatEffect skillEffect(int skillId, List<Pair<BuffStat, Integer>> statups) throws Exception {
        Constructor<StatEffect> ctor = StatEffect.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        StatEffect effect = ctor.newInstance();
        set(effect, "skill", true);
        set(effect, "sourceid", skillId);
        set(effect, "statups", statups);
        return effect;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = StatEffect.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Character bot() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(BOT_ID);
        return chr;
    }

    @Test
    void buffsWithoutAnAuraShowOthersNothing() throws Exception {
        StatEffect haste = skillEffect(Assassin.HASTE,
                List.of(new Pair<>(BuffStat.SPEED, 40), new Pair<>(BuffStat.JUMP, 20)));

        assertNull(haste.foreignBuffPacket(bot(), 200_000));
        assertFalse(haste.isPirateTwoStateBuff());
    }

    @Test
    void twoStatePirateBuffsUseThePirateLayout() throws Exception {
        List<Pair<BuffStat, Integer>> dashStats = List.of(new Pair<>(BuffStat.DASH2, 30), new Pair<>(BuffStat.DASH, 20));
        StatEffect dash = skillEffect(Pirate.DASH, dashStats);
        StatEffect infusion = skillEffect(Buccaneer.SPEED_INFUSION, List.of(new Pair<>(BuffStat.SPEED_INFUSION, 9)));

        assertArrayEquals(PacketCreator.giveForeignPirateBuff(BOT_ID, Pirate.DASH, 100, dashStats).getBytes(),
                dash.foreignBuffPacket(bot(), 100_000).getBytes());
        assertTrue(dash.isPirateTwoStateBuff());
        assertTrue(infusion.isPirateTwoStateBuff());
    }

    @Test
    void darkSightShowsOnlyTheDarkSightFlag() throws Exception {
        StatEffect darkSight = skillEffect(Rogue.DARK_SIGHT,
                List.of(new Pair<>(BuffStat.DARKSIGHT, 1), new Pair<>(BuffStat.SPEED, -10)));

        assertArrayEquals(PacketCreator.giveForeignBuff(BOT_ID, List.of(new Pair<>(BuffStat.DARKSIGHT, 0))).getBytes(),
                darkSight.foreignBuffPacket(bot(), 60_000).getBytes());
    }
}
