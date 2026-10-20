package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.awt.Point;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real bots 09: bots buy potions and ammo at the NPC shops of their town, with their own mesos, at the server's prices
// (Shop.buy and Shop.recharge), and every stored bot gets a one-time starter stash.
class BotShoppingTest {

    private static final int RED = 2000000;          // 50 HP, 50 mesos
    private static final int ORANGE = 2000001;       // 150 HP, 160
    private static final int WHITE = 2000002;        // 300 HP, 320
    private static final int BLUE = 2000003;         // 100 MP, 200
    private static final int ELIXIR = 2000004;       // 50% HP and MP: never bought
    private static final int MANA_ELIXIR = 2000006;  // 300 MP, 620
    private static final int SUBI = 2070000;         // shop star: 500 a stack
    private static final int ILBI = 2070006;         // looted, better
    private static final int ARROW = 2060000;
    private static final int BRONZE_ARROW = 2060001;
    private static final int CROSSBOW_ARROW = 2061000;
    private static final int BULLET = 2330000;

    private static final Map<Integer, SupplyData.Recovery> POTIONS = Map.of(
            RED, new SupplyData.Recovery(50, 0, 0, 0),
            ORANGE, new SupplyData.Recovery(150, 0, 0, 0),
            WHITE, new SupplyData.Recovery(300, 0, 0, 0),
            BLUE, new SupplyData.Recovery(0, 100, 0, 0),
            ELIXIR, new SupplyData.Recovery(0, 0, 0.5, 0.5),
            MANA_ELIXIR, new SupplyData.Recovery(0, 300, 0, 0));

    // A general store as the shops/shopitems tables list one, with the recharge-only star entry every shop gets.
    private static final ShopCatalog.Site GROCER = new ShopCatalog.Site(100000102, 1012002, 1, new Point(300, 150),
            List.of(listing(0, RED, 50), listing(1, ORANGE, 160), listing(2, WHITE, 320), listing(3, BLUE, 200),
                    listing(4, ELIXIR, 0), listing(5, MANA_ELIXIR, 620), listing(6, SUBI, 500),
                    listing(7, ARROW, 1), listing(8, BRONZE_ARROW, 10), listing(9, CROSSBOW_ARROW, 1),
                    listing(10, BULLET, 600), listing(11, ILBI, 0)));

    private static final AtomicInteger ids = new AtomicInteger(1_000_000_000 + 900);
    private WeaponType weapon;

    private static ShopCatalog.Listing listing(int slot, int itemId, int price) {
        return new ShopCatalog.Listing(slot, itemId, price, 0);
    }

    @BeforeEach
    void data() throws Exception {
        Method clear = BotHoldings.class.getDeclaredMethod("clearForTest");
        clear.setAccessible(true);
        clear.invoke(null);
        BotShopping.resetForTest();
        weapon = null;
        BotShopping.useWeaponForTest(bot -> weapon);
        SupplyData.useForTest(new SupplyData.Source() {
            @Override
            public SupplyData.Recovery recovery(int itemId) {
                return POTIONS.get(itemId);
            }

            @Override
            public int bulletCount(int skillId, int level) {
                return 1;
            }

            @Override
            public int bulletConsume(int skillId, int level) {
                return 0;
            }

            @Override
            public int mpCon(int skillId, int level) {
                return 0;
            }

            @Override
            public int hpCon(int skillId, int level) {
                return 0;
            }

            @Override
            public int x(int skillId, int level) {
                return 0;
            }

            @Override
            public int duration(int skillId, int level) {
                return 0;
            }

            @Override
            public int projectileWatk(int itemId) {
                return switch (itemId) {
                    case ILBI -> 27;
                    case SUBI -> 15;
                    case BRONZE_ARROW -> 1;
                    case BULLET -> 10;
                    default -> 0;
                };
            }

            @Override
            public int slotMax(Character bot, int itemId) {
                if (itemId / 10000 == 207 || itemId / 10000 == 233) {
                    return 800;   // a star or bullet stack with Claw Mastery
                }
                return itemId / 10000 == 206 ? 1000 : 100;
            }

            @Override
            public double unitPrice(int itemId) {
                return itemId == ILBI ? 1.0 : 0.5;
            }
        });
    }

    @AfterEach
    void restore() {
        SupplyData.useForTest(null);
        ShopCatalog.useForTest(null);
        BotShopping.useWeaponForTest(null);
        BotResupply.install(null);
    }

    private static Character bot(Job job, int level, int maxHp, int maxMp, int meso) throws Exception {
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
        if (meso > 0) {
            chr.gainMeso(meso, false, false, false);
        }
        chr.getInventory(InventoryType.USE).setSlotLimit(48);
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

    private static SupplyNeeds needs(int ammoItemId, int ammoHeld, List<Item> recharge, int hp, int mp, boolean usesMp) {
        return new SupplyNeeds(BotSupplies.Shortage.NONE, ammoItemId, ammoHeld, recharge, hp, mp, usesMp);
    }

    // ---------------------------------------------------------------- what it picks

    @Test
    void potionsFollowTheBotsSizeRedOrangeWhiteBlueManaElixir() throws Exception {
        assertEquals(RED, BotShopping.pickPotion(bot(Job.MAGICIAN, 10, 200, 300, 0), GROCER, true).itemId());
        assertEquals(ORANGE, BotShopping.pickPotion(bot(Job.WARRIOR, 15, 500, 50, 0), GROCER, true).itemId());
        assertEquals(WHITE, BotShopping.pickPotion(bot(Job.WARRIOR, 35, 1600, 150, 0), GROCER, true).itemId());
        assertEquals(BLUE, BotShopping.pickPotion(bot(Job.MAGICIAN, 15, 250, 350, 0), GROCER, false).itemId());
        assertEquals(MANA_ELIXIR, BotShopping.pickPotion(bot(Job.FP_WIZARD, 35, 600, 1200, 0), GROCER, false).itemId());
    }

    @Test
    void theBestAmmoTheShopSellsThatTheWeaponFiresAtItsLevel() throws Exception {
        Character archer = bot(Job.BOWMAN, 20, 600, 200, 0);
        assertEquals(BRONZE_ARROW, BotShopping.bestAmmo(archer, WeaponType.BOW, GROCER).itemId(), "more attack wins");
        assertEquals(CROSSBOW_ARROW, BotShopping.bestAmmo(archer, WeaponType.CROSSBOW, GROCER).itemId());
        Character thief = bot(Job.ASSASSIN, 40, 800, 300, 0);
        assertEquals(SUBI, BotShopping.bestAmmo(thief, WeaponType.CLAW, GROCER).itemId(),
                "ilbis are only recharged here, not sold");
        assertNull(BotShopping.bestAmmo(bot(Job.PIRATE, 9, 300, 100, 0), WeaponType.GUN, GROCER),
                "no bullets below level 10");
    }

    // ---------------------------------------------------------------- buying

    @Test
    void potionsAreBoughtAtTheListedPriceInStacksAndLedgered() throws Exception {
        Character warrior = bot(Job.WARRIOR, 35, 1600, 150, 100_000);
        BotShopping.Receipt r = BotShopping.shop(warrior, GROCER, needs(0, 0, List.of(), 0, 0, false));

        int goal = BotShopping.hpGoal(35);   // 145
        assertEquals(goal, count(warrior, WHITE));
        assertEquals(0, count(warrior, BLUE), "its attack costs no MP");
        assertEquals(goal * 320L, r.spent);
        assertEquals(100_000 - goal * 320, warrior.getMeso());
        assertEquals(2, warrior.getInventory(InventoryType.USE).listById(WHITE).size(), "100 a stack");
        assertTrue(BotHoldings.hasUnpersistedDebit(warrior), "the mesos paid are a debit the store must write");
    }

    @Test
    void itKeepsAReserveAndBuysEssentialsFirst() throws Exception {
        Character mage = bot(Job.MAGICIAN, 20, 300, 400, 20_000);
        long budget = BotShopping.budget(mage);
        assertEquals(15_000, budget, "a quarter of its mesos stays (at least 200 a level)");
        BotShopping.shop(mage, GROCER, needs(0, 0, List.of(), 0, 0, true));
        assertTrue(mage.getMeso() >= 5_000, "never below the reserve");
        int mp = count(mage, BLUE);
        int hp = count(mage, RED);
        assertTrue(mp >= Math.ceil(BotShopping.mpGoal(mage, true) / 3.0), "MP essentials: " + mp);
        assertTrue(hp >= Math.ceil(BotShopping.hpGoal(20) / 3.0), "HP essentials: " + hp);
        assertTrue(mp < BotShopping.mpGoal(mage, true), "not enough for the full kit");
    }

    @Test
    void starsComeAWholeStackForTheListedPriceAndOwnStacksAreRecharged() throws Exception {
        weapon = WeaponType.CLAW;
        Character thief = bot(Job.ASSASSIN, 40, 900, 300, 200_000);
        Item ilbis = give(thief, ILBI, 100);   // looted, better than anything sold: recharged
        Item subis = give(thief, SUBI, 0);     // same as sold: recharged too

        BotShopping.Receipt r = BotShopping.shop(thief, GROCER,
                needs(SUBI, 100, List.of(ilbis, subis), 200, 0, true));

        assertEquals(800, ilbis.getQuantity());
        assertEquals(800, subis.getQuantity());
        int stacks = BotShopping.ammoStacks(40);   // 3 stacks = 2400
        assertEquals(800 * (stacks - 2), count(thief, SUBI) - 800, "then whole stacks of Subi");
        long recharge = (long) Math.ceil(1.0 * 700) + (long) Math.ceil(0.5 * 800);
        long stars = 500L * (stacks - 2);
        assertEquals(recharge + stars, r.spent - potionSpend(thief), "recharge at unitPrice, a stack at its price");
        assertEquals(1500, r.recharged);
    }

    // What the receipt spent on potions for this bot (they are bought after the essentials of ammo).
    private static long potionSpend(Character bot) {
        return count(bot, RED) * 50L + count(bot, ORANGE) * 160L + count(bot, WHITE) * 320L + count(bot, BLUE) * 200L
                + count(bot, MANA_ELIXIR) * 620L;
    }

    @Test
    void arrowsAreBoughtByTheUnitUpToTheKit() throws Exception {
        weapon = WeaponType.BOW;
        Character archer = bot(Job.HUNTER, 40, 1200, 400, 400_000);
        BotShopping.shop(archer, GROCER, needs(ARROW, 0, List.of(), 500, 500, true));
        assertEquals(BotShopping.arrowGoal(40), count(archer, BRONZE_ARROW));
        assertEquals(0, count(archer, ARROW));
    }

    @Test
    void aBotThatCannotAffordAmmoGoesWithoutAndFightsInMelee() throws Exception {
        weapon = WeaponType.CLAW;
        Character thief = bot(Job.ASSASSIN, 30, 700, 200, 6_000);   // reserve 6,000: nothing to spend
        assertEquals(0, BotShopping.budget(thief));
        BotShopping.Receipt r = BotShopping.shop(thief, GROCER, needs(SUBI, 0, List.of(), 0, 0, true));
        assertTrue(r.isEmpty());
        assertEquals(6_000, thief.getMeso());
        assertEquals(0, count(thief, SUBI));
    }

    @Test
    void aFullUseTabBuysNothingAndKeepsTheMesos() throws Exception {
        Character warrior = bot(Job.WARRIOR, 35, 1600, 150, 100_000);
        warrior.getInventory(InventoryType.USE).setSlotLimit(2);
        give(warrior, 2010000, 1);
        give(warrior, 2010001, 1);
        BotShopping.Receipt r = BotShopping.shop(warrior, GROCER, needs(0, 0, List.of(), 0, 0, false));
        assertTrue(r.isEmpty());
        assertEquals(100_000, warrior.getMeso());
    }

    // ---------------------------------------------------------------- where

    private static final class Town implements ShopCatalog.Source {
        final Map<Integer, List<ShopCatalog.Site>> sites = new HashMap<>();
        final Map<Integer, List<Integer>> portals = new HashMap<>();

        @Override
        public List<ShopCatalog.Site> sitesOnMap(Character bot, int mapId) {
            return sites.getOrDefault(mapId, List.of());
        }

        @Override
        public List<Integer> neighbours(Character bot, int mapId) {
            return portals.getOrDefault(mapId, List.of());
        }
    }

    @Test
    void aTownsShopsAreItsOwnMapsUpToTwoPortalsDeep() throws Exception {
        Town town = new Town();
        ShopCatalog.Site weapons = new ShopCatalog.Site(100000101, 1011000, 2, new Point(0, 0),
                List.of(listing(0, 1302000, 1000), listing(1, SUBI, 0)));
        ShopCatalog.Site field = new ShopCatalog.Site(100010000, 1012100, 3, new Point(0, 0), GROCER.items());
        town.sites.put(100000101, List.of(weapons));
        town.sites.put(100000102, List.of(GROCER));
        town.sites.put(100010000, List.of(field));
        town.portals.put(100000000, List.of(100000100, 100010000));   // the market, and a hunting field
        town.portals.put(100000100, List.of(100000000, 100000101, 100000102));
        ShopCatalog.useForTest(town);

        List<ShopCatalog.Site> sites = ShopCatalog.town(bot(Job.WARRIOR, 10, 300, 50, 0), 100000000);
        assertEquals(List.of(weapons, GROCER), sites, "the field's NPC is not the town's");

        weapon = WeaponType.CLAW;
        Character thief = bot(Job.ASSASSIN, 30, 700, 200, 50_000);
        SupplyNeeds needs = needs(SUBI, 0, List.of(), 0, 0, true);
        assertEquals(GROCER, BotShopping.siteFor(thief, 100000000, needs), "the shop that sells stars and potions");
        assertNull(BotShopping.siteFor(thief, 100000000, needs(SUBI, 900, List.of(), 400, 400, true)),
                "nothing it needs");
        assertNull(BotShopping.siteFor(thief, 101000000, needs), "a town without shops");
    }

    @Test
    void theHookBuysOnlyAtAShopOnTheMapTheBotStandsOn() throws Exception {
        Town town = new Town();
        town.sites.put(100000102, List.of(GROCER));
        ShopCatalog.useForTest(town);
        BotShopping.install();
        Character warrior = bot(Job.WARRIOR, 35, 1600, 150, 100_000);

        warrior.setMap(100000000);   // in town, not in the store
        BotShopping.headFor(warrior, GROCER);
        assertFalse(BotSupplies.restock(warrior));
        assertEquals(100_000, warrior.getMeso());

        warrior.setMap(100000102);
        assertTrue(BotSupplies.restock(warrior));
        assertEquals(BotShopping.hpGoal(35), count(warrior, WHITE));
        assertTrue(BotShopping.report().contains("1 visits"), BotShopping.report());
    }

    // ---------------------------------------------------------------- the starter stash

    @Test
    void theStarterStashIsAPlayersKitForTheLevelAndJob() throws Exception {
        weapon = WeaponType.CLAW;
        Character thief = bot(Job.ASSASSIN, 40, 900, 300, 0);
        int units = StarterStash.grant(thief);
        assertEquals(1600, count(thief, SUBI), "two stacks of shop stars");
        assertEquals(2, thief.getInventory(InventoryType.USE).listById(SUBI).size());
        assertEquals(BotShopping.hpGoal(40), count(thief, WHITE));
        assertEquals(0, thief.getMeso(), "a gift, not a purchase");
        assertEquals(1600 + BotShopping.hpGoal(40), units);

        weapon = WeaponType.BOW;
        Character archer = bot(Job.BOWMAN, 15, 400, 150, 0);
        StarterStash.grant(archer);
        assertEquals(2000, count(archer, ARROW));
        assertEquals(BotShopping.hpGoal(15), count(archer, ORANGE));

        weapon = WeaponType.WAND;
        Character mage = bot(Job.FP_WIZARD, 35, 600, 1200, 0);
        StarterStash.grant(mage);
        assertEquals(BotShopping.mpGoal(mage, true), count(mage, MANA_ELIXIR), "a mage's MP potions");

        weapon = WeaponType.GUN;
        Character pirate = bot(Job.PIRATE, 9, 300, 100, 0);
        StarterStash.grant(pirate);
        assertEquals(0, count(pirate, BULLET), "no bullet it may fire yet");
    }

    @Test
    void theStashIsGrantedOnceAndRecordedWithTheItems() throws Exception {
        String apply = Files.readString(Path.of("database/migrations/007-bot-starter-stash.sql"));
        String rollback = Files.readString(Path.of("database/migrations/007-bot-starter-stash.rollback.sql"));
        assertTrue(apply.contains("ADD COLUMN StockedAt BIGINT NULL"));
        assertTrue(apply.contains("'007-bot-starter-stash'"));
        assertTrue(rollback.contains("DROP COLUMN StockedAt"));
        assertTrue(rollback.contains("DELETE FROM mapleroot_integration_migrations WHERE migration_id='007-bot-starter-stash'"));
        assertNotNull(StarterStash.GENERAL_STORE.listing(RED));
        List<Integer> ids = new ArrayList<>();
        for (int[] e : StarterStash.contents(bot(Job.WARRIOR, 5, 100, 20, 0))) {
            ids.add(e[0]);
        }
        assertEquals(List.of(RED), ids, "a beginner warrior: red potions only");
    }
}
