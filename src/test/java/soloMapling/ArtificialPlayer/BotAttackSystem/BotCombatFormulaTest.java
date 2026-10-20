package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Attacker;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Crit;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Defender;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Hit;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Kind;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Line;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotCombatFormula.Range;

import java.lang.reflect.Field;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real bots 07: a bot hits as hard as a player of the same build would. The physical ceiling is the server's own
// (Character.calculateMaxBaseDamage times the skill's %, AbstractDealDamageHandler.parseDamage), the rest of the
// client formula stays under parseDamage's checks, so a bot never hits harder than a player could.
class BotCombatFormulaTest {

    private static final Defender DUMMY = new Defender(1, 0, 0, 1.0);

    // A representative build: level, job, weapon, total stats, weapon attack, mastery.
    private record Build(String name, int level, Job job, WeaponType weapon, int str, int dex, int int_, int luk,
                         int watk, double mastery, int skillId, int damagePct) {
    }

    private static final Build[] BUILDS = {
            new Build("lv10 beginner, sword", 10, Job.BEGINNER, WeaponType.SWORD1H, 35, 25, 4, 4, 17, 0.1, 0, 100),
            new Build("lv30 fighter, Power Strike", 30, Job.FIGHTER, WeaponType.SWORD2H, 130, 40, 4, 4, 60, 0.35, 1001004, 260),
            new Build("lv75 crusader, Raging Blow", 75, Job.CRUSADER, WeaponType.SWORD2H, 330, 60, 4, 4, 110, 0.6, 1121008, 400),
            new Build("lv120 dark knight, Spear Crusher", 120, Job.DARKKNIGHT, WeaponType.SPEAR_STAB, 560, 70, 4, 4, 150, 0.6, 1311001, 170),
            new Build("lv50 assassin, claw basic", 50, Job.ASSASSIN, WeaponType.CLAW, 25, 70, 4, 240, 75, 0.45, 0, 100),
            new Build("lv140 shadower, Savage Blow", 140, Job.SHADOWER, WeaponType.DAGGER_OTHER, 40, 90, 4, 600, 160, 0.6, 4201005, 120),
            new Build("lv90 ranger, Strafe", 90, Job.RANGER, WeaponType.BOW, 40, 400, 4, 4, 110, 0.6, 3111006, 150),
            new Build("lv70 sniper, crossbow", 70, Job.SNIPER, WeaponType.CROSSBOW, 35, 320, 4, 4, 100, 0.6, 3211006, 135),
            new Build("lv100 outlaw, gun", 100, Job.OUTLAW, WeaponType.GUN, 30, 420, 4, 4, 120, 0.6, 5211004, 200),
            new Build("lv60 marauder, knuckle", 60, Job.MARAUDER, WeaponType.KNUCKLE, 280, 60, 4, 4, 90, 0.6, 5111006, 300),
    };

    private static Attacker attacker(Build b) {
        return attacker(b.level(), b.job(), b.weapon(), b.str(), b.dex(), b.int_(), b.luk(), b.watk(), 0, b.mastery());
    }

    private static Attacker attacker(int level, Job job, WeaponType weapon, int str, int dex, int int_, int luk,
                                     int watk, int matk, double mastery) {
        return new Attacker(level, str, dex, int_, luk, watk, matk, weapon, job.isA(Job.THIEF),
                job.isA(Job.PIRATE), job.isA(Job.PIRATE) && job.getId() % 100 != 0, mastery, 0, 0, 0, 0, 0, 100);
    }

    // A character whose server totals are the build's, for Character.calculateMaxBaseDamage itself.
    private static Character player(Build b) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setJob(b.job());
        chr.setLevel(b.level());
        setInt(chr, "localstr", b.str());
        setInt(chr, "localdex", b.dex());
        setInt(chr, "localint_", b.int_());
        setInt(chr, "localluk", b.luk());
        setInt(chr, "localwatk", b.watk());
        return chr;
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void thePhysicalCeilingIsTheServersForEveryBuild() throws Exception {
        for (Build b : BUILDS) {
            Character chr = player(b);
            // parseDamage: calcDmgMax = calculateMaxBaseDamage(totalWatk); with a skill, * damage / 100.
            long server = chr.calculateMaxBaseDamage(chr.getTotalWatk(), b.weapon());
            if (b.skillId() != 0) {
                server = server * b.damagePct() / 100;
            }
            Range bot = BotCombatFormula.base(attacker(b), Hit.physical(b.skillId(), b.damagePct()));
            assertEquals(server, bot.max(), b.name());
            assertTrue(bot.min() >= 1 && bot.min() <= bot.max(), b.name() + " floor " + bot);
        }
    }

    @Test
    void masteryRaisesTheFloorButNotTheCeiling() {
        Build b = BUILDS[2];
        Range low = BotCombatFormula.base(attacker(b.level(), b.job(), b.weapon(), b.str(), b.dex(), 4, 4, b.watk(), 0, 0.1),
                Hit.physical(0, 100));
        Range high = BotCombatFormula.base(attacker(b.level(), b.job(), b.weapon(), b.str(), b.dex(), 4, 4, b.watk(), 0, 0.6),
                Hit.physical(0, 100));
        assertEquals(low.max(), high.max());
        assertTrue(high.min() > low.min() * 3, low + " vs " + high);
    }

    @Test
    void moreWeaponAttackAndMoreLevelsHitHarder() {
        Range basic = BotCombatFormula.base(attacker(30, Job.FIGHTER, WeaponType.SWORD2H, 130, 40, 4, 4, 60, 0, 0.35),
                Hit.physical(0, 100));
        Range betterSword = BotCombatFormula.base(attacker(30, Job.FIGHTER, WeaponType.SWORD2H, 130, 40, 4, 4, 80, 0, 0.35),
                Hit.physical(0, 100));
        Range tenLevelsOn = BotCombatFormula.base(attacker(40, Job.FIGHTER, WeaponType.SWORD2H, 180, 40, 4, 4, 60, 0, 0.35),
                Hit.physical(0, 100));
        assertTrue(betterSword.max() > basic.max());
        assertTrue(tenLevelsOn.max() > basic.max());
    }

    @Test
    void spellsStayUnderTheServersMagicCeiling() {
        int[][] mages = {{8, 25, 40, 64}, {35, 160, 230, 140}, {75, 330, 520, 260}, {130, 600, 1100, 500}, {180, 900, 1900, 570}};
        for (int[] m : mages) {   // level, INT, total magic attack, spell magic attack
            int totalInt = m[1];
            int matk = m[2];
            int spell = m[3];
            Attacker a = attacker(m[0], Job.FP_MAGE, WeaponType.WAND, 4, 4, totalInt, 30, 0, matk, 0.1);
            Range bot = BotCombatFormula.base(a, Hit.magic(2001005, spell, 0.6));
            // AbstractDealDamageHandler.parseDamage, magic with a skill:
            long server = (long) (Math.ceil((matk * Math.ceil(matk / 1000.0) + matk) / 30.0) + Math.ceil(totalInt / 200.0));
            server *= spell;
            assertTrue(bot.max() <= server, "INT " + totalInt + ": " + bot.max() + " > " + server);
            if (matk >= 200) {   // the check rounds each term up, which only matters at the lowest levels
                assertTrue(bot.max() >= server / 2, "INT " + totalInt + ": " + bot.max() + " far under " + server);
            }
            assertTrue(bot.min() < bot.max());
        }
    }

    @Test
    void elementAmplificationMultipliesTheSpell() {
        Attacker plain = attacker(80, Job.FP_MAGE, WeaponType.WAND, 4, 4, 350, 30, 0, 550, 0.1);
        Attacker amplified = new Attacker(80, 4, 4, 350, 30, 0, 550, WeaponType.WAND, false, false, false, 0.1,
                0, 0, 0, 0, 0, 140);
        long before = BotCombatFormula.base(plain, Hit.magic(2111002, 180, 0.6)).max();
        long after = BotCombatFormula.base(amplified, Hit.magic(2111002, 180, 0.6)).max();
        assertEquals(before * 140 / 100.0, after, before * 0.02);
    }

    @Test
    void luckySevenAndTripleThrowUseTheirOwnFormula() {
        Attacker a = attacker(70, Job.HERMIT, WeaponType.CLAW, 25, 80, 4, 300, 90, 0, 0.6);
        Range lucky = BotCombatFormula.base(a, Hit.physical(4001344, 150));
        assertEquals((long) Math.ceil(300 * 5.0 * 90 / 100.0) * 150 / 100, lucky.max());
        // parseDamage's check for it: (LUK * 5) * ceil(WATK / 100)
        long server = (long) (300 * 5 * Math.ceil(90 / 100.0)) * 150 / 100;
        assertTrue(lucky.max() <= server);
    }

    @Test
    void aBuffsDamageBonusScalesTheWholeSwing() {
        Build b = BUILDS[2];
        Attacker plain = attacker(b);
        Attacker buffed = new Attacker(b.level(), b.str(), b.dex(), 4, 4, b.watk(), 0, b.weapon(), false, false, false,
                b.mastery(), 0, 0, 0, 0, 20, 100);
        long before = BotCombatFormula.base(plain, Hit.physical(b.skillId(), b.damagePct())).max();
        long after = BotCombatFormula.base(buffed, Hit.physical(b.skillId(), b.damagePct())).max();
        assertEquals((long) Math.ceil(before * 1.2), after);
    }

    @Test
    void theMobsDefenceAndLevelTakeTheirShare() {
        Range r = new Range(800, 1000);
        Range evenLevel = BotCombatFormula.against(r, Kind.PHYSICAL, 50, new Defender(50, 0, 0, 1.0));
        Range tenAbove = BotCombatFormula.against(r, Kind.PHYSICAL, 50, new Defender(60, 0, 0, 1.0));
        Range armoured = BotCombatFormula.against(r, Kind.PHYSICAL, 50, new Defender(50, 200, 0, 1.0));
        Range belowBot = BotCombatFormula.against(r, Kind.PHYSICAL, 50, new Defender(20, 0, 0, 1.0));
        assertEquals(r, evenLevel);
        assertEquals(belowBot, evenLevel);   // only a mob above the bot resists more
        assertEquals(new Range(720, 900), tenAbove);
        assertEquals(new Range(680, 900), armoured);

        Range spell = BotCombatFormula.against(r, Kind.MAGIC, 50, new Defender(60, 0, 100, 1.0));
        assertEquals(new Range(800 - 66, 1000 - 55), spell);

        Range weak = BotCombatFormula.against(r, Kind.MAGIC, 50, new Defender(50, 0, 0, 1.5));
        assertEquals(new Range(1200, 1500), weak);

        Range wall = BotCombatFormula.against(r, Kind.PHYSICAL, 1, new Defender(200, 5000, 0, 1.0));
        assertEquals(new Range(1, 1), wall);   // never under 1, like the client's miss-free floor
    }

    @Test
    void critsStayInsideTheServersCritAllowance() {
        // A Hermit with Critical Throw (40%, 200%) and Sharp Eyes (20%, 130%).
        Attacker a = new Attacker(110, 25, 90, 4, 450, 140, 0, WeaponType.CLAW, true, false, false, 0.6,
                0.40, 200, 0.20, 130, 0, 100);
        Hit hit = Hit.physical(4111005, 150);
        Crit crit = BotCombatFormula.crit(a, hit);
        assertEquals(0.60, crit.chance(), 1e-9);
        assertEquals((150 + 100 + 30) / 150.0, crit.factor(), 1e-9);

        Range r = BotCombatFormula.base(a, hit);
        Random rng = new Random(7);
        // parseDamage: canCrit doubles the ceiling, Sharp Eyes adds 40%, and it only warns past 1.5x of that.
        double allowance = r.max() * 2 * 1.4 * 1.5;
        int crits = 0;
        for (int i = 0; i < 5_000; i++) {
            Line line = BotCombatFormula.roll(r, crit, rng);
            assertTrue(line.damage() >= r.min() && line.damage() <= allowance, line + " vs " + r);
            if (line.crit()) {
                crits++;
            } else {
                assertTrue(line.damage() <= r.max());
            }
        }
        assertEquals(0.60, crits / 5_000.0, 0.03);
    }

    @Test
    void spellsCritOnlyFromACritBuffAndSwingsNever() {
        Attacker withPassive = new Attacker(50, 4, 90, 4, 200, 80, 300, WeaponType.CLAW, true, false, false, 0.5,
                0.4, 200, 0, 0, 0, 100);
        assertEquals(Crit.NONE, BotCombatFormula.crit(withPassive, Hit.magic(2001005, 100, 0.6)));
        assertEquals(Crit.NONE, BotCombatFormula.crit(withPassive,
                new Hit(Kind.SWING, 0, 100, 0, 0, 0, 0, 0, 1.0)));

        Attacker fireRush = new Attacker(80, 4, 4, 400, 30, 0, 600, WeaponType.WAND, false, false, false, 0.1,
                0, 0, 0.6, 120, 0, 100);
        Crit crit = BotCombatFormula.crit(fireRush, Hit.magic(2111002, 180, 0.6));
        assertEquals(0.6, crit.chance(), 1e-9);
        assertEquals(1.2, crit.factor(), 1e-9);
    }

    @Test
    void aShadowRepeatsItsLineAtItsShare() {
        assertEquals(new Line(500, true), BotCombatFormula.shadow(new Line(1000, true), 50));
        assertEquals(new Line(1, false), BotCombatFormula.shadow(new Line(1, false), 50));
    }

    @Test
    void theAverageLineMatchesTheRolledOnes() {
        Range r = new Range(1000, 2000);
        Crit crit = new Crit(0.3, 2.0);
        Random rng = new Random(11);
        double sum = 0;
        int n = 50_000;
        for (int i = 0; i < n; i++) {
            sum += BotCombatFormula.roll(r, crit, rng).damage();
        }
        assertEquals(BotCombatFormula.expected(r, crit), sum / n, BotCombatFormula.expected(r, crit) * 0.01);
    }

    @Test
    void linesNeverPassWhatAPacketCarries() {
        Attacker huge = attacker(250, Job.HERO, WeaponType.SWORD2H, 30_000, 30_000, 4, 4, 30_000, 0, 0.9);
        Range r = BotCombatFormula.base(huge, Hit.physical(1121008, 60_000));
        assertEquals(BotCombatFormula.MAX_LINE, r.max());
        Line crit = BotCombatFormula.roll(r, new Crit(1.0, 3.0), new Random(1));
        assertTrue(BotAttackData.encodeCritLine(crit.damage()) < 0, "a crit line still encodes negative");
        assertTrue(BotAttackData.decodeDamageLine(BotAttackData.encodeCritLine(crit.damage())) > 0);
    }

    @Test
    void healHurtsTheUndeadByTheClientsFormula() {
        Attacker cleric = attacker(40, Job.CLERIC, WeaponType.WAND, 4, 4, 200, 40, 0, 300, 0.1);
        Range solo = BotCombatFormula.base(cleric, new Hit(Kind.HEAL, 2301002, 100, 0, 0, 300, 2, 0, 1.0));
        Range crowd = BotCombatFormula.base(cleric, new Hit(Kind.HEAL, 2301002, 100, 0, 0, 300, 6, 0, 1.0));
        // (INT x 1.2 + LUK) x MATK / 1000 x (1.5 + 5 / 2) x 300%
        assertEquals(Math.round((200 * 1.2 + 40) * 0.3 * 4.0) * 3, solo.max());
        assertTrue(crowd.max() < solo.max(), "Heal spreads thinner over more targets");
    }

    @Test
    void aRangedWeaponSwungInMeleeHitsSoftly() {
        Attacker bowman = attacker(40, Job.HUNTER, WeaponType.BOW, 25, 200, 4, 4, 70, 0, 0.5);
        Range shot = BotCombatFormula.base(bowman, Hit.physical(0, 100));
        Range swing = BotCombatFormula.base(bowman, new Hit(Kind.SWING, 0, 100, 0, 0, 0, 0, 0, 1.0));
        assertEquals((long) Math.ceil((200 * 3.4 + 25) * 70 / 150.0), swing.max());
        assertTrue(swing.max() < shot.max());
    }
}
