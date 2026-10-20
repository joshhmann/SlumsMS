package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.skills.FPMage;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.Magician;
import constants.skills.NightLord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real bots 08: bots drink their real potions and fire their real ammo, out of the inventory and the ledger, on
// screen and off it; running out sends them to town, where the resupply hook gets what they need.
class BotSuppliesTest {

    private static final int RED = 2000000;      // 50 HP
    private static final int WHITE = 2000002;    // 300 HP
    private static final int BLUE = 2000003;     // 100 MP
    private static final int ELIXIR = 2000004;   // 50% HP and MP
    private static final int SUBI = 2070000;
    private static final int CRYSTAL_ILBI = 2070016;
    private static final int ARROW = 2060000;
    private static final int BULLET = 2330000;

    private static final Map<Integer, SupplyData.Recovery> POTIONS = Map.of(
            RED, new SupplyData.Recovery(50, 0, 0, 0),
            WHITE, new SupplyData.Recovery(300, 0, 0, 0),
            BLUE, new SupplyData.Recovery(0, 100, 0, 0),
            ELIXIR, new SupplyData.Recovery(0, 0, 0.5, 0.5));

    // skillId -> {bulletCount, bulletConsume, mpCon, hpCon, x, duration}
    private static final Map<Integer, int[]> SKILLS = Map.of(
            Magician.MAGIC_CLAW, new int[]{0, 0, 10, 0, 0, 0},
            4001344, new int[]{2, 0, 8, 0, 0, 0},                 // Lucky Seven: 2 stars
            FPMage.ELEMENT_AMPLIFICATION, new int[]{0, 0, 0, 0, 140, 0},
            NightLord.SHADOW_STARS, new int[]{0, 200, 0, 0, 0, 120_000});

    private static final AtomicInteger ids = new AtomicInteger(1_000_000_000 + 800);
    private Map<Integer, Skill> previousSkills;

    @BeforeEach
    void data() throws Exception {
        Ledgers.clear();
        SupplyData.useForTest(new SupplyData.Source() {
            @Override
            public SupplyData.Recovery recovery(int itemId) {
                return POTIONS.get(itemId);
            }

            @Override
            public int bulletCount(int skillId, int level) {
                return n(skillId)[0] > 0 ? n(skillId)[0] : 1;
            }

            @Override
            public int bulletConsume(int skillId, int level) {
                return n(skillId)[1];
            }

            @Override
            public int mpCon(int skillId, int level) {
                return n(skillId)[2];
            }

            @Override
            public int hpCon(int skillId, int level) {
                return n(skillId)[3];
            }

            @Override
            public int x(int skillId, int level) {
                return n(skillId)[4];
            }

            @Override
            public int duration(int skillId, int level) {
                return n(skillId)[5];
            }

            @Override
            public int projectileWatk(int itemId) {
                return itemId == CRYSTAL_ILBI ? 30 : 15;
            }

            private int[] n(int skillId) {
                return SKILLS.getOrDefault(skillId, new int[6]);
            }
        });
        Map<Integer, Skill> skills = new HashMap<>();
        for (int id : new int[]{Magician.MAGIC_CLAW, 4001344, FPMage.ELEMENT_AMPLIFICATION, Hermit.SHADOW_PARTNER,
                Hunter.SOUL_ARROW, NightLord.SHADOW_STARS, Hermit.ALCHEMIST}) {
            skills.put(id, new Skill(id));
        }
        previousSkills = swapSkillFactory(skills);
    }

    @AfterEach
    void restore() throws Exception {
        SupplyData.useForTest(null);
        BotResupply.install(null);
        swapSkillFactory(previousSkills);
    }

    // The ledger is static; start every test from an empty one.
    private static final class Ledgers {
        static void clear() throws Exception {
            java.lang.reflect.Method clear = BotHoldings.class.getDeclaredMethod("clearForTest");
            clear.setAccessible(true);
            clear.invoke(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swapSkillFactory(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> previous = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return previous;
    }

    private static Character bot(Job job, int level, int maxHp, int maxMp) throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(ids.incrementAndGet());
        chr.setJob(job);
        chr.setLevel(level);
        for (String f : new String[]{"maxhp", "localmaxhp", "clientmaxhp"}) {
            setInt(chr, f, maxHp);
        }
        for (String f : new String[]{"maxmp", "localmaxmp", "clientmaxmp"}) {
            setInt(chr, f, maxMp);
        }
        chr.updateHpMp(maxHp, maxMp);
        return chr;
    }

    private static Item give(Character bot, int itemId, int quantity) {
        Item item = new Item(itemId, (short) 0, (short) quantity, -1);
        Inventory use = bot.getInventory(InventoryType.USE);
        assertTrue(use.addItem(item) > 0);
        return item;
    }

    private static int count(Character bot, int itemId) {
        int n = 0;
        for (Item item : bot.getInventory(InventoryType.USE).listById(itemId)) {
            n += item.getQuantity();
        }
        return n;
    }

    private static void hold(Character bot, int skillId, int level) {
        bot.changeSkillLevel(SkillFactory.getSkill(skillId), (byte) level, level, -1);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ---------------------------------------------------------------- HP, MP and potions

    @Test
    void aHitComesOffHpAndTheBotDrinksTheRightPotionOutOfItsLedger() throws Exception {
        Character bot = bot(Job.WARRIOR, 30, 1000, 200);
        give(bot, RED, 10);
        give(bot, WHITE, 5);

        BotVitals.hurt(bot, 600);   // 400 HP: under half, so it pots; no potion covers 600, the strongest goes

        assertEquals(700, bot.getHp());
        assertEquals(4, count(bot, WHITE));
        assertEquals(10, count(bot, RED));
        assertTrue(BotHoldings.hasUnpersistedDebit(bot), "the drunk potion is a debit the store must write");

        BotVitals.hurt(bot, 160);   // 540: still over half, no potion
        assertEquals(540, bot.getHp());
        assertEquals(4, count(bot, WHITE));
    }

    @Test
    void botsDoNotDieAndStopDrinkingWhenOut() throws Exception {
        Character bot = bot(Job.WARRIOR, 30, 1000, 200);
        give(bot, RED, 2);
        BotVitals.hurt(bot, 50_000);
        assertEquals(1 + 100, bot.getHp(), "two reds drunk on top of the 1 HP floor");
        assertEquals(0, count(bot, RED));
        assertTrue(bot.getInventory(InventoryType.USE).listById(RED).isEmpty(), "an empty potion stack leaves its slot");
    }

    @Test
    void anElixirIsNotWastedOnMpWhenABluePotionDoes() throws Exception {
        Character bot = bot(Job.MAGICIAN, 30, 500, 1000);
        give(bot, ELIXIR, 3);
        give(bot, BLUE, 3);
        BotVitals.spend(bot, 800, 0);   // 200 MP: under 30%
        assertEquals(3, count(bot, ELIXIR));
        assertEquals(2, count(bot, BLUE));
        assertEquals(300, bot.getMp());
    }

    @Test
    void elementAmplificationRaisesASpellsMpCostAsTheServerDoes() throws Exception {
        Character mage = bot(Job.FP_MAGE, 75, 2000, 4000);
        assertEquals(10, BotVitals.mpCost(mage, Magician.MAGIC_CLAW, 20));
        hold(mage, FPMage.ELEMENT_AMPLIFICATION, 30);
        assertEquals(14, BotVitals.mpCost(mage, Magician.MAGIC_CLAW, 20));
        assertEquals(0, BotVitals.mpCost(mage, 0, 0), "a plain swing costs nothing");
    }

    @Test
    void aCastTheBotCannotPayForIsAPlainAttack() throws Exception {
        Character mage = bot(Job.MAGICIAN, 20, 300, 100);
        hold(mage, Magician.MAGIC_CLAW, 20);
        mage.updateMp(5);
        SwingSupply supply = SwingSupply.prepare(mage, BotAttackProfile.magic(Magician.MAGIC_CLAW, 2), null, 20);
        assertEquals(0, supply.profile().skillId);
        assertEquals(BotAttackProfile.Route.CLOSE, supply.profile().route);
        assertEquals("no MP", supply.fallback());

        give(mage, BLUE, 1);
        supply = SwingSupply.prepare(mage, BotAttackProfile.magic(Magician.MAGIC_CLAW, 2), null, 20);
        assertEquals(Magician.MAGIC_CLAW, supply.profile().skillId, "an MP potion pays for the cast");
        assertEquals(0, count(mage, BLUE));
        assertEquals(100, mage.getMp());
        supply.commit(mage, BotAttackProfile.Route.MAGIC, Magician.MAGIC_CLAW);
        assertEquals(90, mage.getMp());
    }

    @Test
    void recoveryTicksAndTownRestRefill() throws Exception {
        Character bot = bot(Job.WARRIOR, 30, 1000, 200);
        bot.updateHpMp(100, 0);
        BotVitals.regen(bot, 25_000);   // 2.5 ticks of 10 HP and 3 + INT/10 MP
        assertEquals(125, bot.getHp());
        BotVitals.regen(bot, 5_000);    // the half tick carried over
        assertEquals(130, bot.getHp());
        BotVitals.rest(bot);
        assertEquals(1000, bot.getHp());
        assertEquals(200, bot.getMp());
    }

    // ---------------------------------------------------------------- ammo

    @Test
    void ammoFollowsTheRangedHandlersRules() {
        assertFalse(BotAmmo.fits(WeaponType.CLAW, CRYSTAL_ILBI, 49), "crystal ilbis from level 50");
        assertTrue(BotAmmo.fits(WeaponType.CLAW, CRYSTAL_ILBI, 50));
        assertTrue(BotAmmo.fits(WeaponType.CLAW, SUBI, 10));
        assertFalse(BotAmmo.fits(WeaponType.CLAW, ARROW, 10));
        assertTrue(BotAmmo.fits(WeaponType.BOW, ARROW, 10));
        assertFalse(BotAmmo.fits(WeaponType.CROSSBOW, ARROW, 10));
        assertTrue(BotAmmo.fits(WeaponType.GUN, BULLET, 10));
        assertFalse(BotAmmo.fits(WeaponType.GUN, BULLET + 2, 49), "a tier-2 bullet from level 50");
        assertTrue(BotAmmo.fits(WeaponType.GUN, BULLET + 2, 50));
    }

    @Test
    void aSwingFiresFromTheFirstStackThatHoldsEnoughAndDrawsIt() throws Exception {
        Character thief = bot(Job.ASSASSIN, 40, 800, 300);
        give(thief, CRYSTAL_ILBI, 500);   // not allowed below 50
        Item single = give(thief, SUBI, 1); // too few for Lucky Seven's 2
        Item stack = give(thief, SUBI + 1, 100);

        SwingSupply supply = SwingSupply.prepare(thief, BotAttackProfile.ranged(4001344, 2), WeaponType.CLAW, 20);
        assertEquals(SUBI + 1, supply.projectile());
        supply.commit(thief, BotAttackProfile.Route.RANGED, 4001344);
        assertEquals(98, stack.getQuantity());
        assertEquals(1, single.getQuantity());
        assertEquals(300 - 8, thief.getMp());
        assertEquals(500, count(thief, CRYSTAL_ILBI));
    }

    @Test
    void starsStayInTheirSlotAtZeroAndArrowsLeaveIt() throws Exception {
        Character thief = bot(Job.ASSASSIN, 40, 800, 300);
        Item stars = give(thief, SUBI, 3);
        assertEquals(3, BotAmmo.fire(thief, WeaponType.CLAW, 5));
        assertEquals(0, stars.getQuantity());
        assertSame(stars, thief.getInventory(InventoryType.USE).getItem(stars.getPosition()), "kept to be recharged");
        assertNull(BotAmmo.usable(thief, WeaponType.CLAW, 0, 0));

        Character archer = bot(Job.BOWMAN, 20, 600, 200);
        Item arrows = give(archer, ARROW, 2);
        give(archer, ARROW, 4);
        assertEquals(4, BotAmmo.fire(archer, WeaponType.BOW, 4));
        assertNull(archer.getInventory(InventoryType.USE).getItem(arrows.getPosition()));
        assertEquals(2, count(archer, ARROW));
    }

    @Test
    void outOfAmmoTheBotSwingsItsRangedWeaponInMelee() throws Exception {
        Character archer = bot(Job.BOWMAN, 20, 600, 200);
        SwingSupply supply = SwingSupply.prepare(archer, BotAttackProfile.ranged(0, 1), WeaponType.BOW, 0);
        assertEquals(BotAttackProfile.Route.CLOSE, supply.profile().route);
        assertEquals(0, supply.projectile());
        assertEquals("no ammo", supply.fallback());
    }

    @Test
    void shadowPartnerDoublesTheStarsAndSoulArrowNeedsNoArrows() throws Exception {
        Character hermit = bot(Job.HERMIT, 80, 3000, 1000);
        assertEquals(2, BotAmmo.usedPerSwing(hermit, WeaponType.CLAW, 4001344, 20));
        hold(hermit, Hermit.SHADOW_PARTNER, 30);
        assertEquals(4, BotAmmo.usedPerSwing(hermit, WeaponType.CLAW, 4001344, 20));

        Character hunter = bot(Job.HUNTER, 40, 1500, 500);
        assertTrue(BotAmmo.needsAmmo(hunter, WeaponType.BOW, 0));
        hold(hunter, Hunter.SOUL_ARROW, 20);
        assertFalse(BotAmmo.needsAmmo(hunter, WeaponType.BOW, 0));
        SwingSupply supply = SwingSupply.prepare(hunter, BotAttackProfile.ranged(0, 1), WeaponType.BOW, 0);
        assertEquals(BotAttackProfile.Route.RANGED, supply.profile().route, "fires with no arrows held");
        assertEquals(0, supply.projectile());
    }

    @Test
    void shadowStarsCostsItsStarsPerCastAndThrowsNone() throws Exception {
        Character nl = bot(Job.NIGHTLORD, 125, 6000, 2000);
        hold(nl, NightLord.SHADOW_STARS, 30);
        Item stars = give(nl, SUBI, 800);
        SwingSupply supply = SwingSupply.prepare(nl, BotAttackProfile.ranged(0, 1), WeaponType.CLAW, 0);
        assertEquals(600, stars.getQuantity(), "the cast took its 200");
        supply.commit(nl, BotAttackProfile.Route.RANGED, 0);
        SwingSupply.prepare(nl, BotAttackProfile.ranged(0, 1), WeaponType.CLAW, 0)
                .commit(nl, BotAttackProfile.Route.RANGED, 0);
        assertEquals(600, stars.getQuantity(), "none thrown while it lasts");
    }

    // ---------------------------------------------------------------- offscreen

    private static OffscreenCombat.Rate rate(double swingsPerMinute, double killsPerMinute, int skillId,
                                             BotAttackProfile.Route route, int mobAttack) {
        return new OffscreenCombat.Rate(killsPerMinute, 10, 30, swingsPerMinute, skillId, 20, route, mobAttack);
    }

    @Test
    void offscreenUsesAmmoAtTheSwingRateAndStopsWhenItRunsOut() throws Exception {
        Character archer = bot(Job.BOWMAN, 30, 100_000, 1000);   // HP to spare: only the ammo limits
        give(archer, ARROW, 30);
        OffscreenCombat.Rate r = rate(20, 5, 0, BotAttackProfile.Route.RANGED, 50);

        assertEquals(1.0, OffscreenSupplies.accrue(archer, r, WeaponType.BOW, 60), 1e-9);
        assertEquals(10, count(archer, ARROW));
        assertEquals(0.5, OffscreenSupplies.accrue(archer, r, WeaponType.BOW, 60), 1e-9);
        assertEquals(0, count(archer, ARROW));
    }

    @Test
    void offscreenTakesTheAverageTouchAndDrinksAsItGoes() throws Exception {
        Character warrior = bot(Job.WARRIOR, 30, 1000, 200);
        give(warrior, WHITE, 20);
        // 60 kills a minute: 6 touches a minute of 0.9 x 100 (half a 200-attack mob, 10% missed) = 540, less 60
        // recovered in the minute.
        OffscreenCombat.Rate r = rate(30, 60, 0, BotAttackProfile.Route.CLOSE, 200);
        OffscreenSupplies.Bill bill = OffscreenSupplies.bill(warrior, r, null, 60);
        assertEquals(6 * 90 - 60, bill.hp(), 1e-9);

        for (int minute = 0; minute < 10; minute++) {
            assertEquals(1.0, OffscreenSupplies.accrue(warrior, r, null, 60), 1e-9);
            assertTrue(warrior.getHp() >= 500 - 300, "it pots under half, as on screen");
        }
        // 10 minutes x 480 HP = 4800 HP, about 16 whites.
        int drunk = 20 - count(warrior, WHITE);
        assertTrue(drunk >= 13 && drunk <= 17, "drunk " + drunk);
    }

    @Test
    void offscreenWithoutPotionsGrindsUntilAQuarterHpAndThenRetreats() throws Exception {
        Character warrior = bot(Job.WARRIOR, 30, 1000, 200);
        OffscreenCombat.Rate r = rate(30, 60, 0, BotAttackProfile.Route.CLOSE, 200);
        double share = OffscreenSupplies.accrue(warrior, r, null, 120);
        assertEquals(750.0 / 960.0, share, 1e-9);
        assertEquals(249, warrior.getHp());
        assertEquals(BotSupplies.Shortage.HP, BotSupplies.shortage(warrior));
    }

    // ---------------------------------------------------------------- shortages and the town stop

    @Test
    void runningOutSendsTheBotToTownWhereTheHookGetsItsNeeds() throws Exception {
        Character mage = bot(Job.MAGICIAN, 20, 400, 100);
        hold(mage, Magician.MAGIC_CLAW, 20);
        assertEquals(BotSupplies.Shortage.NONE, BotSupplies.shortage(mage));
        mage.updateMp(5);
        assertEquals(BotSupplies.Shortage.MP, BotSupplies.shortage(mage));
        give(mage, BLUE, 1);
        assertEquals(BotSupplies.Shortage.NONE, BotSupplies.shortage(mage), "an MP potion left: keeps going");

        mage.updateHp(50);
        give(mage, RED, 1);
        assertEquals(BotSupplies.Shortage.NONE, BotSupplies.shortage(mage));
        BotVitals.drink(mage, true);
        mage.updateHp(50);
        assertEquals(BotSupplies.Shortage.HP, BotSupplies.shortage(mage));

        AtomicReference<SupplyNeeds> asked = new AtomicReference<>();
        BotResupply.install((bot, needs) -> {
            asked.set(needs);
            return false;
        });
        BotSupplies.visitTown(mage);
        assertEquals(400, mage.getHp(), "rested to full");
        assertNotNull(asked.get());
        assertEquals(0, asked.get().hpPotions());
        assertEquals(1, asked.get().mpPotions());
        assertTrue(asked.get().usesMp());
        assertEquals(List.of(), asked.get().toRecharge());
    }

    @Test
    void theDefaultHookBuysNothing() throws Exception {
        Character bot = bot(Job.WARRIOR, 30, 1000, 200);
        assertFalse(BotResupply.request(bot, BotSupplies.needs(bot)));
        assertSame(BotResupply.NONE, BotResupply.installed());
    }
}
