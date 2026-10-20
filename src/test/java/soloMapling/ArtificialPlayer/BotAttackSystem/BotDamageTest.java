package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import constants.skills.Assassin;
import constants.skills.Crusader;
import constants.skills.Fighter;
import constants.skills.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.life.Element;
import server.life.ElementalEffectiveness;
import server.life.Monster;
import server.life.MonsterStats;
import client.status.MonsterStatus;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Attacker;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Kind;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamage.SkillNumbers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Real bots 07: BotDamage reads the bot's real numbers - the gear it wears, the skills and buffs it holds - and its
// swing hits as hard as the server's formula says a player of that build would.
class BotDamageTest {

    private static final int RAGING_BLOW = 1121008;
    private static final int MAGIC_CLAW = 2001005;
    private static final int TWO_HANDED_SWORD = 1402001;

    // The WZ numbers the tests need, as MapleRoot's Skill.wz has them.
    private static final Map<Integer, SkillNumbers> NUMBERS = Map.of(
            Fighter.SWORD_MASTERY, numbers(100, 0, 0, 10, 0, 20, 0),       // lv20: grade 10 = 60%
            Fighter.RAGE, numbers(100, 50, 0, 0, 0, 0, 0),                 // lv20: +50 weapon attack
            Crusader.COMBO, numbers(120, 0, 0, 0, 0, 5, 0),                // lv30: +20% damage
            Hero.MAPLE_WARRIOR, numbers(100, 0, 0, 0, 0, 15, 0),           // lv30: +15% stats
            RAGING_BLOW, numbers(600, 0, 0, 0, 0, 0, 0),                   // lv30: 600%
            Assassin.CRITICAL_THROW, new SkillNumbers(200, 0, 0, 0, 0.40, 0, 0, 0, 0, Element.NEUTRAL),
            MAGIC_CLAW, numbers(100, 0, 140, 10, 0, 0, 0));                 // lv20: 140 magic attack, 60%

    private static SkillNumbers numbers(int damage, int watk, int matk, int mastery, double prop, int x, int y) {
        return new SkillNumbers(damage, watk, matk, mastery, prop, x, y, 0, 0, Element.NEUTRAL);
    }

    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void skillData() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (int id : new int[]{Fighter.SWORD_MASTERY, Fighter.RAGE, Fighter.POWER_GUARD, Crusader.COMBO,
                Hero.MAPLE_WARRIOR, Hero.ENRAGE, RAGING_BLOW, Assassin.CRITICAL_THROW, Assassin.CLAW_MASTERY,
                Assassin.HASTE, MAGIC_CLAW, 1000006}) {
            skills.put(id, new Skill(id));
        }
        previousSkills = swapSkillFactory(skills);
        BotDamage.useSkillDataForTest((id, level) -> NUMBERS.getOrDefault(id, SkillNumbers.NONE));
    }

    @AfterEach
    void restore() throws Exception {
        swapSkillFactory(previousSkills);
        BotDamage.useSkillDataForTest(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character warrior(Job job, int level, int str, int dex, int weaponAttack) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_000_000 + 707);
        chr.setJob(job);
        chr.setLevel(level);
        setInt(chr, "str", str);
        setInt(chr, "dex", dex);
        wield(chr, weaponAttack);
        return chr;
    }

    // Wear a two-handed sword of this attack and let the server work out the totals (Character.reapplyLocalStats).
    private static void wield(Character chr, int weaponAttack) throws Exception {
        var worn = chr.getInventory(InventoryType.EQUIPPED);
        if (worn.getItem((short) -11) != null) {
            worn.removeSlot((short) -11);
        }
        Equip sword = mock(Equip.class);   // a real Equip loads the item data the tests do not have
        when(sword.getItemId()).thenReturn(TWO_HANDED_SWORD);
        when(sword.getPosition()).thenReturn((short) -11);
        when(sword.getWatk()).thenReturn((short) weaponAttack);
        worn.addItemFromDB(sword);
        setBoolean(chr, "equipchanged", true);
        Method reapply = Character.class.getDeclaredMethod("reapplyLocalStats");
        reapply.setAccessible(true);
        reapply.invoke(chr);
    }

    private static void hold(Character chr, int skillId, int level) {
        chr.changeSkillLevel(SkillFactory.getSkill(skillId), (byte) level, level, -1);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        field(target, name).setInt(target, value);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        field(target, name).setBoolean(target, value);
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void theWeaponItWearsSetsItsAttack() throws Exception {
        Character bot = warrior(Job.CRUSADER, 75, 330, 60, 80);
        Attacker a = BotDamage.attacker(bot, WeaponType.SWORD2H);
        assertEquals(80, a.watk());
        assertEquals(330, a.str());
        assertEquals(BotCombatFormula.BASE_MASTERY, a.mastery());

        long before = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.CLOSE, WeaponType.SWORD2H, 1, false).base().max();
        wield(bot, 100);   // a better sword
        long after = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.CLOSE, WeaponType.SWORD2H, 1, false).base().max();
        assertEquals(bot.calculateMaxBaseDamage(80, WeaponType.SWORD2H), before);
        assertEquals(bot.calculateMaxBaseDamage(100, WeaponType.SWORD2H), after);
    }

    @Test
    void itsSkillsAndShownBuffsCountLikeAPlayersWould() throws Exception {
        Character bot = warrior(Job.CRUSADER, 75, 330, 60, 80);
        hold(bot, Fighter.SWORD_MASTERY, 20);
        hold(bot, Fighter.RAGE, 20);
        hold(bot, Crusader.COMBO, 30);
        hold(bot, RAGING_BLOW, 30);

        Attacker a = BotDamage.attacker(bot, WeaponType.SWORD2H);
        assertEquals(80 + 50, a.watk(), "Rage, though only shown, adds its attack");
        assertEquals(0.6, a.mastery(), 1e-9);
        assertEquals(20, a.damageBonusPct(), "Combo Attack's damage, as parseDamage sums the buffs");

        BotDamage.Swing swing = BotDamage.swing(bot, RAGING_BLOW, 30, BotAttackProfile.Route.CLOSE,
                WeaponType.SWORD2H, 1, false);
        // A player holding the same: calculateMaxBaseDamage(total attack) x 600%, then the +20% buff bonus.
        long server = (long) Math.ceil(bot.calculateMaxBaseDamage(130, WeaponType.SWORD2H) * 600L / 100 * 1.2);
        assertEquals(server, swing.base().max());
        assertEquals(Kind.PHYSICAL, swing.hit.kind());
    }

    @Test
    void aBuffItDoesNotHoldCountsForNothing() throws Exception {
        Character bot = warrior(Job.CRUSADER, 75, 330, 60, 80);
        assertEquals(80, BotDamage.attacker(bot, WeaponType.SWORD2H).watk());
        assertEquals(0, BotDamage.attacker(bot, WeaponType.SWORD2H).damageBonusPct());
    }

    @Test
    void mapleWarriorRaisesTheBaseStats() throws Exception {
        Character bot = warrior(Job.HERO, 130, 600, 80, 120);
        hold(bot, Hero.MAPLE_WARRIOR, 30);
        Attacker a = BotDamage.attacker(bot, WeaponType.SWORD2H);
        assertEquals(600 + 90, a.str());
        assertEquals(80 + 12, a.dex());
    }

    @Test
    void aClawThiefCritsFromCriticalThrowWithTheAttackItsTotalsCarry() throws Exception {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(1_000_000_000 + 708);
        bot.setJob(Job.ASSASSIN);
        bot.setLevel(50);
        setInt(bot, "localluk", 240);
        setInt(bot, "localdex", 70);
        setInt(bot, "localstr", 25);
        setInt(bot, "localwatk", 60);
        hold(bot, Assassin.CRITICAL_THROW, 20);

        Attacker a = BotDamage.attacker(bot, WeaponType.CLAW);
        // Real bots 08: no projectile is made up for a bot without stars - the totals carry whatever stars it holds.
        assertEquals(60, a.watk(), "no stars carried: no star's attack counts");
        BotDamage.Swing swing = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.RANGED, WeaponType.CLAW, 1, false);
        assertEquals(0.40, swing.crit().chance(), 1e-9);
        assertEquals(2.0, swing.crit().factor(), 1e-9);
    }

    @Test
    void aSpellUsesItsMagicAttackAndMastery() throws Exception {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setJob(Job.MAGICIAN);
        bot.setLevel(25);
        setInt(bot, "localint_", 130);
        setInt(bot, "localmagic", 190);
        BotDamage.Swing swing = BotDamage.swing(bot, MAGIC_CLAW, 20, BotAttackProfile.Route.MAGIC, WeaponType.WAND, 1, false);
        assertEquals(Kind.MAGIC, swing.hit.kind());
        assertEquals(0.6, swing.hit.spellMastery(), 1e-9);
        assertEquals(140, swing.hit.spellAttack());
        long server = (long) (Math.ceil((190 * Math.ceil(190 / 1000.0) + 190) / 30.0) + Math.ceil(130 / 200.0)) * 140;
        assertTrue(swing.base().max() <= server);
    }

    @Test
    void theMobsDefenceAndElementShapeTheAverage() throws Exception {
        Character bot = warrior(Job.CRUSADER, 75, 330, 60, 80);
        BotDamage.Swing swing = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.CLOSE, WeaponType.SWORD2H, 1, false);
        MonsterStats soft = mob(70, 0);
        MonsterStats armoured = mob(70, 400);
        MonsterStats above = mob(95, 0);
        double vsSoft = swing.expectedPerMob(soft, 1);
        assertEquals(swing.base().average(), vsSoft, 1.0);
        assertTrue(swing.expectedPerMob(armoured, 1) < vsSoft);
        assertTrue(swing.expectedPerMob(above, 1) < vsSoft);
        assertEquals(vsSoft * 3, swing.expectedPerMob(soft, 3), 1e-6);
    }

    @Test
    void aShadowPartnerSwingIsHalfAgainNotDouble() throws Exception {
        Character bot = warrior(Job.CRUSADER, 75, 330, 60, 80);
        BotDamage.Swing plain = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.CLOSE, WeaponType.SWORD2H, 1, false);
        BotDamage.Swing shadow = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.CLOSE, WeaponType.SWORD2H, 1, true);
        MonsterStats mob = mob(70, 0);
        assertEquals(plain.expectedPerMob(mob, 2) * 1.5, shadow.expectedPerMob(mob, 4), 1.0);
    }

    @Test
    void elementMultipliers() {
        assertEquals(1.5, BotDamage.elementMultiplier(ElementalEffectiveness.WEAK));
        assertEquals(0.5, BotDamage.elementMultiplier(ElementalEffectiveness.STRONG));
        assertEquals(0.0, BotDamage.elementMultiplier(ElementalEffectiveness.IMMUNE));
        assertEquals(1.0, BotDamage.elementMultiplier(ElementalEffectiveness.NORMAL));
    }

    @Test
    void theOffscreenKillRateFollowsTheDamageUpToTheSpawnCeiling() {
        // 1,000 HP mob, a swing every 720 ms.
        double weak = OffscreenCombat.killsPerMinute(100, 1_000, 720, 1);      // 10 swings a kill
        double strong = OffscreenCombat.killsPerMinute(500, 1_000, 720, 1);    // 2 swings a kill
        double pack = OffscreenCombat.killsPerMinute(500, 1_000, 720, 3);
        assertEquals(60.0 / (10 * 0.72 + OffscreenCombat.SECONDS_BETWEEN_KILLS), weak, 1e-9);
        assertTrue(strong > weak);
        assertEquals(OffscreenCombat.MAX_KILLS_PER_MIN, pack);
        assertEquals(0.0, OffscreenCombat.killsPerMinute(0, 1_000, 720, 1));
    }

    @Test
    void offscreenKillsAreWorthAThirdOfTheirRealExpUnlessThePropertySaysOtherwise() {
        String previous = System.getProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY);
        try {
            System.clearProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY);
            double third = OffscreenCombat.expPerKill(300, 2, 2);   // 300 EXP mob, 2x rate, 2x map
            assertEquals(1200.0 / 3.0, third, 1e-9);
            // The pace scales the EXP a kill is worth, never the kill rate: the spawn cap still binds.
            OffscreenCombat.Rate rate = new OffscreenCombat.Rate(
                    OffscreenCombat.killsPerMinute(500, 1_000, 720, 3), third, 38);
            assertEquals(OffscreenCombat.MAX_KILLS_PER_MIN, rate.killsPerMinute());
            assertEquals(30.0 / 60.0 * 400.0, rate.expPerSecond(), 1e-9);

            System.setProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY, "1");
            assertEquals(1200.0, OffscreenCombat.expPerKill(300, 2, 2), 1e-9);
            System.setProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY, "0.5");
            assertEquals(600.0, OffscreenCombat.expPerKill(300, 2, 2), 1e-9);

            for (String bad : new String[]{"nonsense", "-2", "0", "NaN"}) {
                System.setProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY, bad);
                assertEquals(OffscreenCombat.DEFAULT_OFFSCREEN_EXP_PACE, OffscreenCombat.expPace());
            }
        } finally {
            if (previous == null) {
                System.clearProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY);
            } else {
                System.setProperty(OffscreenCombat.OFFSCREEN_EXP_PACE_PROPERTY, previous);
            }
        }
    }

    @Test
    void theLinesItSendsStayInTheRangeAndCritsEncodeNegative() throws Exception {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setJob(Job.ASSASSIN);
        bot.setLevel(50);
        setInt(bot, "localluk", 240);
        setInt(bot, "localdex", 70);
        setInt(bot, "localstr", 25);
        setInt(bot, "localwatk", 60);
        hold(bot, Assassin.CRITICAL_THROW, 20);
        BotDamage.Swing swing = BotDamage.swing(bot, 0, 0, BotAttackProfile.Route.RANGED, WeaponType.CLAW, 1, true);

        Monster mob = mock(Monster.class);
        MonsterStats stats = mob(50, 0);
        when(mob.getLevel()).thenReturn(50);
        when(mob.getStats()).thenReturn(stats);
        when(mob.getElementalEffectiveness(org.mockito.ArgumentMatchers.any())).thenReturn(ElementalEffectiveness.NORMAL);

        int crits = 0;
        for (int i = 0; i < 500; i++) {
            List<Integer> lines = swing.roll(mob, 4);
            assertEquals(4, lines.size());
            for (int j = 0; j < 2; j++) {
                int main = BotAttackData.decodeDamageLine(lines.get(j));
                int shadow = BotAttackData.decodeDamageLine(lines.get(j + 2));
                assertTrue(main >= swing.base().min() - 1 && main <= swing.base().max() * 2, "line " + main);
                assertEquals(lines.get(j) < 0, lines.get(j + 2) < 0, "the shadow repeats the crit");
                assertTrue(shadow <= main / 2 + 1);
                if (lines.get(j) < 0) {
                    crits++;
                }
            }
        }
        assertEquals(0.40, crits / 1000.0, 0.06);

        when(mob.isBuffed(MonsterStatus.WEAPON_IMMUNITY)).thenReturn(true);
        assertEquals(List.of(1, 1, 1, 1), swing.roll(mob, 4), "an immune mob takes 1 a line, as parseDamage makes it");
    }

    private static MonsterStats mob(int level, int weaponDefence) {
        MonsterStats stats = new MonsterStats();
        stats.setLevel(level);
        stats.setHp(10_000);
        stats.setPDDamage(weaponDefence);
        return stats;
    }
}
