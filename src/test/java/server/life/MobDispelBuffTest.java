package server.life;

import client.BuffStat;
import client.Character;
import client.Client;
import constants.skills.Spearman;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import server.StatEffect;
import server.maps.MapleMap;
import tools.Pair;

import java.awt.Point;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 regular-buffs-real: the mob side of the Dispel story. A mob's DISPEL really strips a Hyper Body a companion holds
 * ({@link Character#dispel()} cancels every skill-sourced buff), and afterwards the buff driver sees the lack and
 * recasts - which is what makes the companion's own Dispel worth casting on a party member.
 *
 * The builder is package-private, so this lives in {@code server.life}.
 */
class MobDispelBuffTest {

    private static final int ALT_ID = 1_000_000_000 + 71;

    @Test
    void aMobsDispelStripsTheHyperBodyAnAltHolds() throws Exception {
        Character alt = newCharacter(ALT_ID);
        StatEffect hyperBody = hyperBodyEffect();
        register(alt, hyperBody);
        assertNotNull(alt.getBuffedValue(BuffStat.HYPERBODYHP), "the alt holds Hyper Body");

        Monster mob = mobOnTheSameMap(alt);
        MobSkill dispel = new MobSkill.Builder(MobSkillType.DISPEL, 1).build();
        dispel.applyEffect(alt, mob, true, null);

        assertNull(alt.getBuffedValue(BuffStat.HYPERBODYHP), "the mob's Dispel stripped the buff for real");
        assertNull(alt.getBuffedValue(BuffStat.HYPERBODYMP));
    }

    // ---------------------------------------------------------------- the character and its buff, as the server holds them

    private static Character newCharacter(int id) throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();
        Field client = Character.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(chr, mock(Client.class));
        Field map = Class.forName("client.AbstractCharacterObject").getDeclaredField("map");
        map.setAccessible(true);
        MapleMap m = mock(MapleMap.class);
        map.set(chr, m);
        setField(chr, "id", id);
        setField(chr, "name", "AltBuddy");
        return chr;
    }

    /** Hyper Body's two statups, as StatEffect.loadFromData builds them from the skill's XML. */
    private static StatEffect hyperBodyEffect() throws Exception {
        Constructor<StatEffect> ctor = StatEffect.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        StatEffect effect = ctor.newInstance();
        setField(effect, "skill", true);
        setField(effect, "sourceid", Spearman.HYPER_BODY);
        List<Pair<BuffStat, Integer>> statups = new ArrayList<>();
        statups.add(new Pair<>(BuffStat.HYPERBODYHP, 60));
        statups.add(new Pair<>(BuffStat.HYPERBODYMP, 60));
        setField(effect, "statups", statups);
        setField(effect, "duration", 360_000);
        return effect;
    }

    @SuppressWarnings("unchecked")
    private static void register(Character chr, StatEffect effect) throws Exception {
        Object holder = holder(effect, 60);
        Map<BuffStat, Object> one = new LinkedHashMap<>();
        one.put(BuffStat.HYPERBODYHP, holder);
        one.put(BuffStat.HYPERBODYMP, holder);
        Map<Integer, Map<BuffStat, Object>> buffs =
                (Map<Integer, Map<BuffStat, Object>>) field(chr, "buffEffects");
        buffs.put(Spearman.HYPER_BODY, one);
        Map<BuffStat, Object> applied = (Map<BuffStat, Object>) field(chr, "effects");
        applied.put(BuffStat.HYPERBODYHP, holder);
        applied.put(BuffStat.HYPERBODYMP, holder);
        Map<BuffStat, Byte> counts = (Map<BuffStat, Byte>) field(chr, "buffEffectsCount");
        counts.put(BuffStat.HYPERBODYHP, (byte) 1);
        counts.put(BuffStat.HYPERBODYMP, (byte) 1);
    }

    private static Monster mobOnTheSameMap(Character target) throws Exception {
        MonsterStats stats = new MonsterStats();
        stats.level = 1;
        stats.hp = 100L;
        stats.exp = 1;
        stats.name = "Mob";
        Monster mob = new Monster(9_999_999, stats);
        MapleMap map = target.getMap();
        when(map.getPlayersInRange(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(target));
        when(map.getMapObjectsInRect(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ArrayList<>(List.of(target)));
        mob.setMap(map);
        mob.setPosition(new Point(0, 0));
        return mob;
    }

    private static Object holder(StatEffect effect, int value) throws Exception {
        Class<?> cls = Class.forName("client.Character$BuffStatValueHolder");
        Constructor<?> ctor = cls.getDeclaredConstructor(StatEffect.class, long.class, int.class);
        ctor.setAccessible(true);
        return ctor.newInstance(effect, System.currentTimeMillis(), value);
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
