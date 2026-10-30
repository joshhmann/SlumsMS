package soloMapling.Economy;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (owner, grill 2026-10-30: "bot friends handing over items: yes, small and capped"): a regular who likes the
 * player answers "got any pots?" with a small gift out of its own bag, through the gift path and the bot ledger, under
 * the four caps; anyone else says no.
 */
class BotGiftAsksTest {

    static final int MAP = 104_040_000;
    static final int WHITE = 2000002;       // White Potion (HP)
    static final int BLUE = 2000003;        // Blue Potion (MP)
    static final int ARROW = 2060000;       // Arrow for Bow
    static final int SWORD = 1302000;

    EconomyTestSupport t;
    MapleMap field;
    final List<MapItem> drops = new ArrayList<>();
    final Set<Long> friends = new HashSet<>();
    final List<String> said = new ArrayList<>();
    final List<String> gave = new ArrayList<>();
    int nextBot = 1_000_000_100;

    @BeforeEach
    void setUp() {
        t = new EconomyTestSupport();
        BotGifts.resetForTest(t.store, t.now::get);
        System.setProperty("solomapling.botHoldOffBusy", "false");
        t.statics.put(WHITE, 300L);
        t.statics.put(BLUE, 600L);
        t.statics.put(ARROW, 1L);
        field = mock(MapleMap.class);
        when(field.getId()).thenReturn(MAP);
        when(field.getFieldLimit()).thenReturn(0);
        when(field.spawnItemDropNoExpire(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> {
                    Item item = inv.getArgument(2);
                    Character owner = inv.getArgument(1);
                    MapItem drop = new MapItem(item, inv.getArgument(3), inv.getArgument(0), owner,
                            owner.getClient(), (byte) 0, false);
                    drops.add(drop);
                    return drop;
                });
        // the supply rules read the bot's real bag: HP = White Potions, MP = Blue Potions, ammo = arrows
        SupplyShare.useForTest((holder, kind, needy) -> switch (kind) {
            case HP -> holder.getInventory(InventoryType.USE).listById(WHITE);
            case MP -> holder.getInventory(InventoryType.USE).listById(BLUE);
            case AMMO -> holder.getInventory(InventoryType.USE).listById(ARROW);
            case ROCKS -> List.of();
        });
        BotGiftAsks.useForTest(new BotGiftAsks.Effects() {
            @Override
            public boolean friend(int botId, int playerId) {
                return friends.contains(BotGifts.key(botId, playerId));
            }

            @Override
            public void say(Character bot, String line) {
                said.add(line);
            }

            @Override
            public void gave(Character bot, Character player, String scripted) {
                gave.add(scripted);
            }
        });
    }

    @AfterEach
    void tearDown() {
        BotGiftAsks.useForTest(null);
        SupplyShare.useForTest(null);
        BotGifts.resetForTest(null, null);
        EconomyTestSupport.reset();
        System.clearProperty("solomapling.botHoldOffBusy");
    }

    // ---- fixtures ----

    Character bot(int whites) throws Exception {
        Character b = Character.getDefault(new BotClient(0, 0));
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(b, nextBot++);
        b.setName("Regular" + b.getId());
        b.setMap(field);
        b.setPosition(new Point(0, 0));
        if (whites > 0) {
            assertTrue(BotHoldings.grantItem(b, new Item(WHITE, (short) 0, (short) whites)));
        }
        return b;
    }

    Character player(int id, int account) {
        Character p = mock(Character.class);
        when(p.getId()).thenReturn(id);
        when(p.getName()).thenReturn("Player" + id);
        when(p.getAccountID()).thenReturn(account);
        when(p.getMap()).thenReturn(field);
        when(p.getMapId()).thenReturn(MAP);
        when(p.getPosition()).thenReturn(new Point(5, 0));
        when(p.getClient()).thenReturn(mock(Client.class));
        return p;
    }

    void befriend(Character bot, Character player) {
        friends.add(BotGifts.key(bot.getId(), player.getId()));
    }

    static int held(Character bot, int itemId) {
        int n = 0;
        for (Item i : bot.getInventory(ItemConstants.getInventoryType(itemId)).listById(itemId)) {
            n += i.getQuantity();
        }
        return n;
    }

    int landed(int itemId) {
        return drops.stream().filter(d -> d.getItemId() == itemId).mapToInt(d -> d.getItem().getQuantity()).sum();
    }

    // ---- what counts as an ask ----

    @Test
    void theWaysAPlayerAsksAreRead() {
        assertEquals("pots", BotCommandParser.supplyAsk("got any pots?"));
        assertEquals("pots", BotCommandParser.supplyAsk("can u spare a pot"));
        assertEquals("pots", BotCommandParser.supplyAsk("need pots"));
        assertEquals("scrolls", BotCommandParser.supplyAsk("any scrolls?"));
        assertEquals("mp", BotCommandParser.supplyAsk("can i get some mana pots pls"));
        assertEquals("hp", BotCommandParser.supplyAsk("hey do you have any hp pots to spare"));
        assertEquals("ammo", BotCommandParser.supplyAsk("u got any arrows?"));
        assertEquals("pots", BotCommandParser.supplyAsk("low on pots lol"));
        assertNull(BotCommandParser.supplyAsk("i got pots"), "a statement, not an ask");
        assertNull(BotCommandParser.supplyAsk("need pots for my quest at ellinia tomorrow"));
        assertNull(BotCommandParser.supplyAsk("any good training spots?"));
        assertNull(BotCommandParser.supplyAsk("need rocks"), "rocks stay a companion thing");
        assertNull(BotCommandParser.supplyAsk("got any equips"), "gear is never asked for here");
    }

    // ---- not a friend ----

    @Test
    void aBotThatIsNotAFriendSaysNoAndGivesNothing() throws Exception {
        Character bot = bot(200);
        Character p = player(11, 501);

        assertEquals(BotGiftAsks.Outcome.NOT_A_FRIEND, BotGiftAsks.handle(bot, p, "got any pots?"));

        assertEquals(1, said.size(), "a natural no");
        assertTrue(gave.isEmpty());
        assertTrue(drops.isEmpty());
        assertEquals(200, held(bot, WHITE));
        assertEquals(0, EconomyMeters.get(EconomyMeters.GIFT_ASKS));
    }

    @Test
    void chatThatIsNotAnAskIsLeftToTheNormalReply() throws Exception {
        Character bot = bot(200);
        Character p = player(11, 501);
        befriend(bot, p);
        assertFalse(BotGiftAsks.tryHandle(bot, p, "where do u train"));
        assertTrue(said.isEmpty());
    }

    @Test
    void theSwitchTurnsItOff() throws Exception {
        EconomyConfig.useForTest(Map.of("giftAsks.enabled", "false"));
        Character bot = bot(200);
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.NOT_AN_ASK, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertTrue(drops.isEmpty());
    }

    // ---- the gift and the ledger ----

    @Test
    void aFriendGivesASmallGiftOutOfItsOwnBagThroughTheLedger() throws Exception {
        Character bot = bot(200);
        Character p = player(11, 501);
        befriend(bot, p);

        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "can u spare a pot"));

        assertEquals(20, landed(WHITE), "giftAsks.potions: 20 at most");
        assertEquals(180, held(bot, WHITE), "the units left the bot's own bag: nothing minted");
        assertEquals(1, drops.size(), "one stacked drop, not twenty");
        assertEquals(p.getId(), drops.get(0).getOwnerId(), "theirs alone");
        assertEquals(1, gave.size());
        assertTrue(said.isEmpty());
        // the debit is in the ledger: a settle (the bot could not be saved) hands over the 180 it still holds, not
        // the 200 it was given - the 20 are not paid out twice
        BotHoldings.settle(bot);
        org.mockito.ArgumentCaptor<Item> settled = org.mockito.ArgumentCaptor.forClass(Item.class);
        verify(field, org.mockito.Mockito.atLeastOnce())
                .spawnItemDrop(any(), any(), settled.capture(), any(), anyBoolean(), anyBoolean());
        assertEquals(180, settled.getAllValues().stream().filter(i -> i.getItemId() == WHITE)
                .mapToInt(Item::getQuantity).sum());
        assertEquals(1, EconomyMeters.get(EconomyMeters.GIFT_ASKS));
        assertTrue(t.store.lastGift(bot.getId(), p.getId()) > 0 || BotGifts.lastGiftAt(bot.getId(), p.getId()) > 0);
    }

    @Test
    void theBotKeepsItsOwnReserve() throws Exception {
        Character bot = bot(55);   // keeps 50: only 5 to spare
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "need pots"));
        assertEquals(5, landed(WHITE));
        assertEquals(50, held(bot, WHITE));
    }

    @Test
    void aFriendWithNothingToSpareSaysSo() throws Exception {
        Character bot = bot(40);   // under its own reserve
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.NOTHING_TO_SPARE, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertEquals(1, said.size());
        assertTrue(drops.isEmpty());
        assertEquals(0, EconomyMeters.get(EconomyMeters.GIFT_ASKS), "a no uses up no cap");
    }

    // ---- the item filter ----

    @Test
    void theOneGiftRuleRefusesGearBlockedItemsAndAnythingOverTheValueCap() {
        assertNotNull(BotGifts.giftProblem(new Item(SWORD, (short) 0, (short) 1), 1, 100_000), "never gear");
        assertNotNull(BotGifts.giftProblem(new Item(4031999, (short) 0, (short) 1), 1, 100_000), "untradeable");
        assertNotNull(BotGifts.giftProblem(new Item(5000000, (short) 0, (short) 1), 1, 100_000), "cash item");
        assertNotNull(BotGifts.giftProblem(new Item(4000999, (short) 0, (short) 1), 1, 100_000), "boss-only drop");
        assertNull(BotGifts.giftProblem(new Item(WHITE, (short) 0, (short) 100), 20, 100_000));
        assertNotNull(BotGifts.giftProblem(new Item(WHITE, (short) 0, (short) 100), 400, 100_000), "120k > 100k");
        assertEquals(10, BotGifts.unitsUnder(new Item(BLUE, (short) 0, (short) 100), 20, 6_000));
    }

    @Test
    void aStackBotsMayNotSellIsNeverGiven() throws Exception {
        SaleRules.useForTest(Set.of(), id -> false, id -> id == WHITE);   // White Potions untradeable here
        Character bot = bot(200);
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.NOTHING_TO_SPARE, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertTrue(drops.isEmpty());
        assertEquals(200, held(bot, WHITE));
    }

    @Test
    void theValueCapCutsTheGiftDown() throws Exception {
        EconomyConfig.useForTest(Map.of("giftAsks.maxValue", "3000"));
        Character bot = bot(200);   // 300 a potion: 10 fit under 3,000
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertEquals(10, landed(WHITE));
    }

    // ---- the caps ----

    @Test
    void onePairGetsOneGiftAWeek() throws Exception {
        Character bot = bot(500);
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "got any pots?"));
        t.now.addAndGet(EconomyTestSupport.DAY * 2);
        assertEquals(BotGiftAsks.Outcome.ALREADY_THIS_WEEK, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertEquals(1, said.size());
        t.now.addAndGet(EconomyTestSupport.DAY * 6);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "got any pots?"));
    }

    @Test
    void anUnpromptedGiftThisWeekCountsForThePair() throws Exception {
        Character bot = bot(500);
        Character p = player(11, 501);
        befriend(bot, p);
        t.store.saveGift(bot.getId(), p.getId(), t.now.get() - EconomyTestSupport.DAY, "earlier gift");
        assertEquals(BotGiftAsks.Outcome.ALREADY_THIS_WEEK, BotGiftAsks.handle(bot, p, "got any pots?"));
    }

    @Test
    void aBotGivesAtMostTwoADay() throws Exception {
        Character bot = bot(500);
        for (int i = 0; i < 3; i++) {
            befriend(bot, player(20 + i, 600 + i));
        }
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, player(20, 600), "got any pots?"));
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, player(21, 601), "got any pots?"));
        assertEquals(BotGiftAsks.Outcome.CAPPED, BotGiftAsks.handle(bot, player(22, 602), "got any pots?"));
        assertEquals(40, landed(WHITE));
        assertEquals(2, EconomyMeters.get(EconomyMeters.GIFT_ASKS), "a refused claim gives its world slot back");
    }

    @Test
    void aPlayerAccountGetsAtMostThreeADayAcrossItsCharacters() throws Exception {
        List<Character> bots = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            bots.add(bot(500));
        }
        int[] chars = {31, 32, 33, 34};   // one account, four characters
        for (int i = 0; i < 4; i++) {
            befriend(bots.get(i), player(chars[i], 700));
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bots.get(i), player(chars[i], 700), "need pots"));
        }
        assertEquals(BotGiftAsks.Outcome.CAPPED, BotGiftAsks.handle(bots.get(3), player(chars[3], 700), "need pots"));
        assertEquals(500, held(bots.get(3), WHITE), "the capped bot gave nothing");
        assertEquals(0, EconomyMeters.get(BotGiftAsks.botMeter(bots.get(3).getId())), "its own slot given back");
    }

    @Test
    void theWorldHasADailyCap() throws Exception {
        EconomyConfig.useForTest(Map.of("giftAsks.worldPerDay", "1"));
        Character a = bot(500);
        Character b = bot(500);
        befriend(a, player(41, 800));
        befriend(b, player(42, 801));
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(a, player(41, 800), "got any pots?"));
        assertEquals(BotGiftAsks.Outcome.CAPPED, BotGiftAsks.handle(b, player(42, 801), "got any pots?"));
        assertEquals(0, EconomyMeters.get(BotGiftAsks.accountMeter(801)), "the account slot is given back");
        t.now.addAndGet(EconomyTestSupport.DAY);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(b, player(42, 801), "got any pots?"),
                "a new day, a new world cap");
    }

    @Test
    void whereDropsVanishTheBotSaysNoAndKeepsItsPots() throws Exception {
        when(field.getFieldLimit()).thenReturn((int) server.maps.FieldLimit.DROP_LIMIT.getValue());
        Character bot = bot(200);
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.NO_DROPS, BotGiftAsks.handle(bot, p, "got any pots?"));
        assertEquals(200, held(bot, WHITE));
        verify(field, never()).spawnItemDropNoExpire(any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void arrowsComeFromTheBotsQuiverInOneStack() throws Exception {
        Character bot = bot(0);
        assertTrue(BotHoldings.grantItem(bot, new Item(ARROW, (short) 0, (short) 1000)));
        Character p = player(11, 501);
        befriend(bot, p);
        assertEquals(BotGiftAsks.Outcome.GAVE, BotGiftAsks.handle(bot, p, "u got any arrows?"));
        assertEquals(200, landed(ARROW));
        assertEquals(800, held(bot, ARROW));
    }
}
