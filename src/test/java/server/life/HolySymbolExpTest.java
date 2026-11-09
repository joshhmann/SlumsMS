package server.life;

import client.BuffStat;
import client.Character;
import client.Client;
import constants.skills.Priest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import provider.Data;
import provider.wz.XMLDomMapleData;
import server.StatEffect;
import server.maps.MapleMap;

import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 1.6.7 regular-buffs-real: a bot's Holy Symbol is a real buff, so the EXP the mob gives goes up - the same
 * {@code Monster.getStatusExpMultiplier} branch a player's own Holy Symbol takes. This is what makes the buff worth
 * handing out for real instead of only drawing it.
 */
class HolySymbolExpTest {

    private static final int ALT_ID = 1_000_000_000 + 72;

    @TempDir
    Path dir;

    /* Holy Symbol level 1: x is the percentage the character's EXP is raised by (WZSkill 231.img 2311003). */
    private StatEffect holySymbol() throws Exception {
        Path f = dir.resolve("1.img.xml");
        Files.writeString(f, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"1\">"
                + "<int name=\"x\" value=\"30\"/><int name=\"time\" value=\"600\"/>"
                + "<vector name=\"lt\" x=\"-300\" y=\"-200\"/><vector name=\"rb\" x=\"300\" y=\"200\"/></imgdir>");
        Data level;
        try (FileInputStream in = new FileInputStream(f.toFile())) {
            level = new XMLDomMapleData(in, dir);
        }
        return StatEffect.loadSkillEffectFromData(level, Priest.HOLY_SYMBOL, true);
    }

    @Test
    void aRegisteredHolySymbolRaisesTheExpTheMobGives() throws Exception {
        Character alt = newCharacter();
        Monster mob = mob();
        assertEquals(1.0f, expMultiplier(mob, alt), 0.0001f, "nothing up: the mob's own EXP");

        StatEffect holySymbol = holySymbol();
        alt.registerEffect(holySymbol, System.currentTimeMillis(), System.currentTimeMillis() + 600_000, false);

        assertNotNull(alt.getBuffedValue(BuffStat.HOLY_SYMBOL), "the registered effect is a real buff on the alt");
        assertEquals(30, alt.getBuffedValue(BuffStat.HOLY_SYMBOL).intValue(), "WZ x = 30%");
        assertEquals(1.30f, expMultiplier(mob, alt), 0.0001f, "+30% EXP from the real Holy Symbol");

        // and a mob's Dispel takes it away again, so the multiplier comes back down
        MobSkill dispel = new MobSkill.Builder(MobSkillType.DISPEL, 1).build();
        dispel.applyEffect(alt, mob, true, null);
        assertEquals(1.0f, expMultiplier(mob, alt), 0.0001f, "the mob's Dispel stripped it");
        assertTrue(expMultiplier(mob, alt) < 1.30f);
    }

    /** Monster.getStatusExpMultiplier(Character, boolean) - the private rule the EXP gain runs through. */
    private static float expMultiplier(Monster mob, Character chr) throws Exception {
        Method m = Monster.class.getDeclaredMethod("getStatusExpMultiplier", Character.class, boolean.class);
        m.setAccessible(true);
        return (float) m.invoke(mob, chr, true);
    }

    private static Character newCharacter() throws Exception {
        Constructor<Character> ctor = Character.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Character chr = ctor.newInstance();
        Field client = Character.class.getDeclaredField("client");
        client.setAccessible(true);
        client.set(chr, mock(Client.class));
        Field map = Class.forName("client.AbstractCharacterObject").getDeclaredField("map");
        map.setAccessible(true);
        map.set(chr, newMap());
        setField(chr, "id", ALT_ID);
        return chr;
    }

    /** A map that answers the few calls registerEffect and getPartyMembersOnSameMap make. */
    private static MapleMap newMap() {
        MapleMap map = mock(MapleMap.class);
        org.mockito.Mockito.when(map.getId()).thenReturn(101_000_000);
        return map;
    }

    private static Monster mob() {
        MonsterStats stats = new MonsterStats();
        stats.level = 1;
        stats.hp = 100L;
        stats.exp = 1;
        stats.name = "Mob";
        Monster mob = new Monster(9_999_998, stats);
        mob.setMap(newMap());
        return mob;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
