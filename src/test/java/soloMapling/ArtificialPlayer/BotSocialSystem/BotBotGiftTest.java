package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 Stage 4: a bot hands another bot something out of its own bag (or just a kind word), and the gift is
 * either whole or does not happen. Everything the live path reads from WZ - what a bot can wear, what counts as a
 * potion, what an item is worth - is behind the seams the gear optimizer and the supply code already use, so this
 * test table is the item data: the assertions are about the rules, not about v83 content.
 */
class BotBotGiftTest {

    private static final int MAP = 100_000_000;
    private static final int GIVER_ID = 1_000_000_011;
    private static final int RECEIVER_ID = 1_000_000_012;
    private static final int OTHER_ID = 1_000_000_013;

    private static final int HP_POT = 2000000;
    private static final int SCROLL = 2040000;

    private static final int WARRIOR_ONLY = 1302000;   // reqJob 1 (warrior), level 25
    private static final int UNIVERSAL = 1002000;      // reqJob 0 (every job), level 20
    private static final int TOO_HIGH = 1302001;       // reqJob 1, level 90

    // As BotScrollingTest: the item data loads once with no DB and no WZ behind it, then answers blank.
    @org.junit.jupiter.api.BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = org.mockito.Mockito.mockStatic(tools.DatabaseConnection.class);
             var providers = org.mockito.Mockito.mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private org.mockito.MockedStatic<ItemInformationProvider> itemData;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final FakeMinds minds = new FakeMinds();
    private final JobGear gear = new JobGear();
    private final List<Character> players = new ArrayList<>();
    private MapleMap map;
    private Character giver;
    private Character receiver;

    @BeforeEach
    void setUp() {
        itemData = org.mockito.Mockito.mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
        BotBonds.useForTest(new BondStore.Memory(), Runnable::run, clock::get,
                id -> new double[]{0.25, 0.25}, (id, push) -> { }, id -> null);
        SocialSim.useForTest(clock::get, 7L);
        BotBotGift.useForTest(minds);
        BotBotGift.useQuestItemsForTest(itemId -> minds.quests.contains(itemId));
        BotBotGift.useValueForTest(item -> item.getItemId() >= 1_000_000 ? 10_000 : 1);
        GearUpgrades.install(gear);

        map = mock(MapleMap.class);
        when(map.getAllPlayers()).thenReturn(players);
        when(map.getId()).thenReturn(MAP);

        gear.item(WARRIOR_ONLY, 1, 25);
        gear.item(UNIVERSAL, 0, 20);
        gear.item(TOO_HIGH, 1, 90);

        giver = bot(GIVER_ID, "Giver", Job.WARRIOR, 30, new Point(0, 0));
        gear.satisfied.add(GIVER_ID); // it already wears better than any spare in this bag
        receiver = bot(RECEIVER_ID, "Receiver", Job.MAGICIAN, 30, new Point(40, 0));
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("slums.botgifts");
        System.clearProperty("slums.botgifts.giver.cooldownMin");
        System.clearProperty("slums.botgifts.pair.cooldownMin");
        System.clearProperty("slums.botgifts.receiver.perDay");
        System.clearProperty("slums.botgifts.perHour");
        System.clearProperty("slums.botgifts.valueCap");
        GearUpgrades.install(null);
        BotBotGift.useForTest(null);
        SocialSim.reset();
        itemData.close();
    }

    // ── what may be given ──

    @Test
    void anEquipGoesOnlyToABotThatWouldWearIt() {
        Equip warriorBlade = spare(giver, WARRIOR_ONLY);
        spare(giver, UNIVERSAL);
        assertEquals(0.0, gear.gain(receiver, warriorBlade), 0.0, "a magician cannot wear the warrior blade");

        Optional<BotBotGift.Plan> plan = BotBotGift.choose(giver, receiver, minds, clock.get());

        assertTrue(plan.isPresent(), "a spare the receiver can use should be offered");
        assertEquals(BotBotGift.Kind.EQUIP, plan.get().kind());
        assertEquals(UNIVERSAL, plan.get().itemId(), "...and it should be the one it can actually wear");
    }

    @Test
    void universalGearFitsEveryJobAndJobGearOnlyItsOwn() {
        for (Job job : new Job[]{Job.WARRIOR, Job.MAGICIAN, Job.BOWMAN, Job.THIEF, Job.PIRATE}) {
            Character other = bot(OTHER_ID, "Other", job, 30, new Point(30, 0));

            clearBag(giver, InventoryType.EQUIP);
            spare(giver, UNIVERSAL);
            assertEquals(UNIVERSAL, BotBotGift.choose(giver, other, minds, clock.get())
                    .map(BotBotGift.Plan::itemId).orElse(0), "an every-job piece fits " + job);

            clearBag(giver, InventoryType.EQUIP);
            spare(giver, WARRIOR_ONLY);
            Optional<BotBotGift.Plan> plan = BotBotGift.choose(giver, other, minds, clock.get());
            if (job == Job.WARRIOR) {
                assertEquals(WARRIOR_ONLY, plan.map(BotBotGift.Plan::itemId).orElse(0));
            } else {
                assertTrue(plan.isEmpty(), "a warrior-only piece is not for " + job);
            }
        }
    }

    @Test
    void aPieceAboveTheReceiversLevelIsNotOffered() {
        spare(giver, TOO_HIGH);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
    }

    @Test
    void theGiversOwnUpgradeNeverLeaves() {
        // The giver is level 30 and wears none of these, so both would be upgrades to it: neither is a spare.
        gear.satisfied.remove(GIVER_ID);
        spare(giver, WARRIOR_ONLY);
        spare(giver, UNIVERSAL);
        Character otherWarrior = bot(OTHER_ID, "Blade", Job.WARRIOR, 30, new Point(30, 0));

        assertTrue(BotBotGift.choose(giver, otherWarrior, minds, clock.get()).isEmpty());
    }

    @Test
    void untradeableAndQuestItemsAreNeverGiftable() {
        Equip cursed = spare(giver, UNIVERSAL);
        cursed.setFlag(ItemConstants.UNTRADEABLE);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "untradeable");

        cursed.setFlag((short) 0);
        minds.quests.add(UNIVERSAL);
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "a quest item");
    }

    @Test
    void potionsGoToALowReceiverAndTheGiverKeepsItsOwnTrip() {
        addItem(giver, item(HP_POT, 60));
        addItem(receiver, item(HP_POT, 5));
        minds.hp.add(HP_POT);

        Optional<BotBotGift.Plan> plan = BotBotGift.choose(giver, receiver, minds, clock.get());

        assertTrue(plan.isPresent());
        assertEquals(BotBotGift.Kind.USABLE, plan.get().kind());
        assertEquals(15, plan.get().quantity(), "only what brings the receiver back to the low line");
        assertTrue(BotBotGift.give(plan.get()).ok());
        assertEquals(45, count(giver, HP_POT));
        assertEquals(20, count(receiver, HP_POT));
    }

    @Test
    void aGiverNeverDropsBelowItsOwnSupplies() {
        addItem(giver, item(HP_POT, 24)); // 4 spare, under the smallest gift
        addItem(receiver, item(HP_POT, 2));
        minds.hp.add(HP_POT);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
        assertEquals(24, count(giver, HP_POT));
    }

    @Test
    void scrollsAndQuestItemsAreNeverPotionGifts() {
        addItem(giver, item(SCROLL, 60));
        addItem(receiver, item(HP_POT, 1));
        minds.hp.add(HP_POT); // the supply code's own list of what a bot drinks never contains a scroll

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
        assertEquals(60, count(giver, SCROLL));
    }

    @Test
    void mpPotionsOnlyGoToABotThatDrinksThem() {
        addItem(giver, item(HP_POT + 2, 60)); // a blue potion the giver holds
        minds.mp.add(HP_POT + 2);
        // The receiver holds no MP potions at all: it does not drink them, so it is not short of them.
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
    }

    // ── atomicity ──

    @Test
    void aFullReceiverBagMovesNothing() {
        addItem(giver, item(HP_POT, 60));
        minds.hp.add(HP_POT); // the receiver holds none, and its use tab is full: nowhere to put even one
        fillUse(receiver);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
        assertEquals(60, count(giver, HP_POT));
        assertEquals(0, count(receiver, HP_POT));
    }

    @Test
    void aStaleGiftMovesNothing() {
        addItem(giver, item(HP_POT, 60));
        addItem(receiver, item(HP_POT, 2));
        minds.hp.add(HP_POT);
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();

        removeAll(giver, HP_POT); // the giver drank or sold that stack between choosing and giving

        BotBotGift.Result result = BotBotGift.give(plan);
        assertFalse(result.ok());
        assertEquals(0, count(giver, HP_POT));
        assertEquals(2, count(receiver, HP_POT), "the receiver got nothing");
    }

    @Test
    void anEquipGiftIntoAFullBagMovesNothing() {
        spare(giver, UNIVERSAL);
        fillEquip(receiver);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "no room to even offer it");
        assertEquals(1, countEquips(giver, UNIVERSAL), "the giver still has its spare");
        assertEquals(0, countEquips(receiver, UNIVERSAL));
    }

    @Test
    void aBagThatFillsUpBetweenChoosingAndGivingUndoesNothing() {
        spare(giver, UNIVERSAL);
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();
        fillEquip(receiver);

        BotBotGift.Result result = BotBotGift.give(plan);

        assertFalse(result.ok());
        assertEquals(1, countEquips(giver, UNIVERSAL), "the gift stayed with the giver");
        assertEquals(0, countEquips(receiver, UNIVERSAL));
    }

    @Test
    void aGivenEquipLandsInTheReceiversBagAndLeavesTheGivers() {
        spare(giver, UNIVERSAL);
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();

        assertTrue(BotBotGift.give(plan).ok());
        assertEquals(0, countEquips(giver, UNIVERSAL));
        assertEquals(1, countEquips(receiver, UNIVERSAL));
    }

    // ── the effects ──

    @Test
    void theReceiverWarmerThanTheGiverAndTheMemoryKeepsTheGift() {
        addItem(giver, item(HP_POT, 60));
        addItem(receiver, item(HP_POT, 2));
        minds.hp.add(HP_POT);
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();
        double receiverBefore = affection(RECEIVER_ID);
        double giverBefore = affection(GIVER_ID);

        assertTrue(BotBotGift.give(plan).ok());

        assertTrue(BotBonds.score(RECEIVER_ID, GIVER_ID) > 0, "the receiver's side warmed");
        assertEquals(0.0, BotBonds.score(GIVER_ID, RECEIVER_ID), 0.0, "a gift does not by itself warm the giver");
        assertTrue(affection(RECEIVER_ID) > receiverBefore, "the receiver's memory felt the gift");
        assertEquals(giverBefore, affection(GIVER_ID), 1e-9);
    }

    @Test
    void aGestureMovesNothingButStillWarms() {
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0); // a pair already close enough for a kind word
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();

        assertEquals(BotBotGift.Kind.GESTURE, plan.kind());
        assertFalse(plan.itemGift());
        assertTrue(BotBotGift.give(plan).ok());
        assertEquals(0, count(receiver, HP_POT));
        assertEquals(0, countEquips(receiver, UNIVERSAL));
        assertTrue(BotBonds.score(RECEIVER_ID, GIVER_ID) > 0);
    }

    @Test
    void aColdPairGetsNoGesture() {
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
    }

    @Test
    void theLineOnlyGoesOutWhenARealPlayerIsCloseEnough() {
        addItem(giver, item(HP_POT, 60));
        addItem(receiver, item(HP_POT, 2));
        minds.hp.add(HP_POT);
        BotBotGift.Plan plan = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();

        assertTrue(BotBotGift.give(plan).ok());
        verify(map, never()).broadcastMessage(any());

        Character player = mock(Character.class);
        when(player.getPosition()).thenReturn(new Point(10, 0));
        players.add(player);
        clock.addAndGet(25 * 60 * 60_000L);
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0);
        addItem(receiver, item(HP_POT, 1)); // still short: another gift is possible
        BotBotGift.Plan again = BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow();

        assertTrue(BotBotGift.give(again).ok());
        verify(map, times(1)).broadcastMessage(any());
    }

    // ── limits ──

    @Test
    void oneGiverWaitsItsCooldownAndOnePairWaitsLonger() {
        spare(giver, UNIVERSAL);
        assertTrue(BotBotGift.give(BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow()).ok());

        clock.addAndGet(3 * 60 * 60_000L);
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "same giver, inside 6 h");

        clock.addAndGet(3 * 60 * 60_000L + 1);
        Character other = bot(OTHER_ID, "Other", Job.MAGICIAN, 30, new Point(30, 0));
        spare(giver, UNIVERSAL);
        assertTrue(BotBotGift.choose(giver, other, minds, clock.get()).isPresent(), "a new receiver, giver window run out");

        clock.addAndGet(3 * 60 * 60_000L);
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "same pair, inside 24 h");
    }

    @Test
    void oneReceiverHasADailyCap() {
        System.setProperty("slums.botgifts.receiver.perDay", "1");
        System.setProperty("slums.botgifts.giver.cooldownMin", "0");
        System.setProperty("slums.botgifts.pair.cooldownMin", "0");
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0);
        minds.bonds.put(key(OTHER_ID, RECEIVER_ID), 20.0);
        assertTrue(BotBotGift.give(BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow()).ok());

        Character second = bot(OTHER_ID, "Second", Job.WARRIOR, 30, new Point(30, 0));
        assertTrue(BotBotGift.choose(second, receiver, minds, clock.get()).isEmpty(),
                "the receiver had its one gift today");

        clock.addAndGet(24 * 60 * 60_000L);
        assertTrue(BotBotGift.choose(second, receiver, minds, clock.get()).isPresent(), "a new day");
    }

    @Test
    void theWorldHasAnHourlyCap() {
        System.setProperty("slums.botgifts.perHour", "1");
        System.setProperty("slums.botgifts.giver.cooldownMin", "0");
        System.setProperty("slums.botgifts.pair.cooldownMin", "0");
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0);
        minds.bonds.put(key(OTHER_ID, RECEIVER_ID), 20.0);
        assertTrue(BotBotGift.give(BotBotGift.choose(giver, receiver, minds, clock.get()).orElseThrow()).ok());

        Character second = bot(OTHER_ID, "Second", Job.WARRIOR, 30, new Point(30, 0));
        assertTrue(BotBotGift.choose(second, receiver, minds, clock.get()).isEmpty());

        clock.addAndGet(60 * 60_000L);
        assertTrue(BotBotGift.choose(second, receiver, minds, clock.get()).isPresent());
    }

    @Test
    void anExpensiveGiftIsNoGift() {
        System.setProperty("slums.botgifts.valueCap", "100");
        addItem(giver, item(HP_POT, 60));
        addItem(receiver, item(HP_POT, 1));
        minds.hp.add(HP_POT);
        BotBotGift.useValueForTest(item -> 10_000L);

        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
    }

    @Test
    void switchedOffGivesNothing() {
        System.setProperty("slums.botgifts", "false");
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0);

        assertFalse(BotBotGift.enabled());
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty());
        BotBotGift.Plan forced = new BotBotGift.Plan(giver, receiver, BotBotGift.Kind.GESTURE, null, 0, "forced");
        assertFalse(BotBotGift.give(forced).ok());
    }

    // ── the gates ──

    @Test
    void busyFarApartOrOnAnotherMapNeverGift() {
        minds.bonds.put(key(GIVER_ID, RECEIVER_ID), 20.0);

        receiver.setPosition(new Point(5_000, 0));
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "out of range");

        receiver.setPosition(new Point(20, 0));
        MapleMap elsewhere = mock(MapleMap.class);
        when(elsewhere.getAllPlayers()).thenReturn(players);
        doReturn(elsewhere).when(receiver).getMap();
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "not the same map");

        doReturn(map).when(receiver).getMap();
        doReturn(mock(server.Trade.class)).when(receiver).getTrade();
        assertTrue(BotBotGift.choose(giver, receiver, minds, clock.get()).isEmpty(), "in a trade");

        doReturn(null).when(receiver).getTrade();
        assertNotNull(BotBotGift.choose(giver, receiver, minds, clock.get()).orElse(null));
    }

    // ── fixtures ──

    private Character bot(int id, String name, Job job, int level, Point at) {
        Character c = spy(Character.getDefault(new BotClient(0, 0)));
        doReturn(id).when(c).getId();
        doReturn(name).when(c).getName();
        doReturn(job).when(c).getJob();
        doReturn(level).when(c).getLevel();
        doReturn(map).when(c).getMap();
        doReturn(MAP).when(c).getMapId();
        c.setPosition(at);
        if (!players.contains(c)) {
            players.add(c);
        }
        return c;
    }

    private Equip spare(Character who, int itemId) {
        Equip e = new Equip(itemId, (short) 0);
        who.getInventory(InventoryType.EQUIP).addItem(e);
        return e;
    }

    private static Item item(int itemId, int quantity) {
        return new Item(itemId, (short) 0, (short) quantity, -1);
    }

    private static void addItem(Character who, Item it) {
        who.getInventory(InventoryType.USE).addItem(it);
    }

    private static void fillUse(Character who) {
        Inventory inv = who.getInventory(InventoryType.USE);
        while (inv.getNumFreeSlot() > 0) {
            inv.addItem(new Item(HP_POT + 1000 + inv.getNumFreeSlot(), (short) 0, (short) 1, -1));
        }
    }

    private void fillEquip(Character who) {
        Inventory inv = who.getInventory(InventoryType.EQUIP);
        while (inv.getNumFreeSlot() > 0) {
            inv.addItem(new Equip(1_002_000 + inv.getNumFreeSlot(), (short) 0));
        }
    }

    private static void clearBag(Character who, InventoryType type) {
        Inventory inv = who.getInventory(type);
        for (Item it : new ArrayList<>(inv.list())) {
            inv.removeSlot(it.getPosition());
        }
    }

    private static void removeAll(Character who, int itemId) {
        Inventory inv = who.getInventory(InventoryType.USE);
        for (Item it : new ArrayList<>(inv.list())) {
            if (it.getItemId() == itemId) {
                inv.removeSlot(it.getPosition());
            }
        }
    }

    private static int count(Character who, int itemId) {
        int n = 0;
        for (Item it : who.getInventory(InventoryType.USE).list()) {
            if (it.getItemId() == itemId) {
                n += it.getQuantity();
            }
        }
        return n;
    }

    private static int countEquips(Character who, int itemId) {
        int n = 0;
        for (Item it : who.getInventory(InventoryType.EQUIP).list()) {
            if (it.getItemId() == itemId) {
                n++;
            }
        }
        return n;
    }

    private double affection(int botId) {
        LifeMemory memory = LifeMemoryStore.of(botId);
        return memory.mind(m -> m.emotion(Emotion.AFFECTION, clock.get()));
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    /** The minds a test gives the chooser: a bond table and the potion stacks it may count. */
    private static final class FakeMinds implements BotBotGift.MindsAndBonds {
        final Map<Long, Double> bonds = new HashMap<>();
        final Set<Integer> hp = new HashSet<>();
        final Set<Integer> mp = new HashSet<>();
        final Set<Integer> quests = new HashSet<>();

        @Override
        public double bond(int botId, int otherId) {
            return bonds.getOrDefault(key(botId, otherId), 0.0);
        }

        @Override
        public List<Item> potions(Character bot, boolean forHp) {
            Set<Integer> ids = forHp ? hp : mp;
            List<Item> out = new ArrayList<>();
            for (Item it : bot.getInventory(InventoryType.USE).list()) {
                if (ids.contains(it.getItemId())) {
                    out.add(it);
                }
            }
            return out;
        }

        @Override
        public int potionCount(Character bot, boolean forHp) {
            int n = 0;
            for (Item it : potions(bot, forHp)) {
                n += it.getQuantity();
            }
            return n;
        }
    }

    /** The item data's job/level rules, as the gear optimizer sees them. */
    private static final class JobGear implements GearUpgrades.Scorer {
        final Map<Integer, int[]> rules = new HashMap<>();

        /** Wearers whose slots are already better than anything on offer (the optimizer's answer: gain 0). */
        final Set<Integer> satisfied = new HashSet<>();

        void item(int itemId, int reqJob, int reqLevel) {
            rules.put(itemId, new int[]{reqJob, reqLevel});
        }

        @Override
        public double gain(Character wearer, Equip candidate) {
            if (satisfied.contains(wearer.getId())) {
                return 0;
            }
            int[] rule = rules.get(candidate.getItemId());
            if (rule == null || rule[1] > wearer.getLevel()) {
                return 0;
            }
            int niche = wearer.getJob().getJobNiche();
            int bit = niche >= 1 && niche <= 5 ? 1 << (niche - 1) : 0;
            if (rule[0] != 0 && (rule[0] & bit) == 0) {
                return 0;
            }
            return 0.5;
        }
    }
}
