package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.Trade;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Target;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyShare;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.ArtificialPlayer.BotTradeSystem.TradeCategories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 ticket 99: item orders go to the one companion that has the thing (the others stay quiet), through a normal
 * trade window; "need pot" picks the donor with the most to spare; and nothing a companion says gives it away.
 */
class CompanionItemsTest {

    static final int RED = 2000000;
    final MapleMap map = mock(MapleMap.class);
    final List<String> opened = new ArrayList<>();
    final List<Runnable> steps = new ArrayList<>();

    /** Runs the first beat of every handover started (the invite); the rest of the window is BotGiveTradeTest's. */
    private void invites() {
        List<Runnable> first = new ArrayList<>(steps);
        steps.clear();
        first.forEach(Runnable::run);
    }
    final Map<Character, List<String>> said = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        BotGiveTrade.useForTest(new BotGiveTrade.Window() {
            public boolean invite(Character g, Character r) { opened.add(g.getName() + "->" + r.getName()); return true; }
            public boolean open(Character g) { return true; }
            public boolean accepted(Character g) { return false; }
            public void accept(Character r, Character g) { }
            public Trade.PutResult put(Character g, InventoryType t, short p, short q, byte s) { return Trade.PutResult.ADDED; }
            public void mesos(Character g, int m) { }
            public void chat(Character g, String l) { }
            public boolean partnerLocked(Character g) { return false; }
            public void confirm(Character w) { }
            public void cancel(Character g) { }
            public void onTraded(Character g, Runnable r) { }
        }, (ms, step) -> steps.add(step));
        TradeCategories.useForTest(new TradeCategories.Facts() {
            public boolean tradeable(Item item) { return true; }
            public String name(int itemId) { return "Red Potion"; }
            public boolean potion(int itemId) { return itemId / 10000 == 200; }
            public short slotOf(int itemId) { return 0; }
        });
        SupplyShare.useForTest((holder, kind, needy) -> kind == SupplyShare.Kind.HP
                ? new ArrayList<>(holder.getInventory(InventoryType.USE).list()) : List.of());
    }

    @AfterEach
    void tearDown() {
        BotGiveTrade.useForTest(null, null);
        TradeCategories.useForTest(null);
        SupplyShare.useForTest(null);
    }

    private Character chr(int id, String name, boolean alt, int reds, int mesos) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMap()).thenReturn(map);
        when(c.getMeso()).thenReturn(mesos);
        when(c.isOwnedAlt()).thenReturn(alt);
        Client client = alt ? mock(BotClient.class) : mock(Client.class);
        when(c.getClient()).thenReturn(client);
        Map<InventoryType, Inventory> bags = new EnumMap<>(InventoryType.class);
        for (InventoryType t : InventoryType.values()) {
            bags.put(t, new Inventory(c, t, (byte) 96));
            when(c.getInventory(t)).thenReturn(bags.get(t));
        }
        if (reds > 0) {
            bags.get(InventoryType.USE).addItemFromDB(new Item(RED, (short) 1, (short) reds));
        }
        return c;
    }

    private CompanionItems.Companion companion(Character c) {
        return new CompanionItems.Companion(c, line -> said.computeIfAbsent(c, k -> new ArrayList<>()).add(line));
    }

    private static Order everyone(Verb verb, String arg) {
        return new Order(verb, List.of(new Target(2, "A"), new Target(3, "B")), List.of(), true, arg);
    }

    @Test
    void theCompanionThatHasItTradesTheOthersStayQuiet() {
        Character owner = chr(1, "Owner", false, 0, 0);
        Character a = chr(2, "A", true, 0, 0);
        Character b = chr(3, "B", true, 80, 0);
        CompanionItems.carryOut(owner, everyone(Verb.TRADE, "pots"), List.of(companion(a), companion(b)));
        invites();
        assertEquals(List.of("B->Owner"), opened);
        assertFalse(said.containsKey(a));
        assertEquals(1, said.get(b).size());
    }

    @Test
    void nobodyHasItOneSaysSo() {
        Character owner = chr(1, "Owner", false, 0, 0);
        Character a = chr(2, "A", true, 0, 0);
        Character b = chr(3, "B", true, 0, 0);
        CompanionItems.carryOut(owner, everyone(Verb.TRADE, "scrolls"), List.of(companion(a), companion(b)));
        assertTrue(opened.isEmpty());
        assertEquals(List.of("i don't have any scrolls"), said.get(a));
        assertFalse(said.containsKey(b));
    }

    @Test
    void mesosItHasNotGotAreSaid() {
        Character owner = chr(1, "Owner", false, 0, 0);
        Character a = chr(2, "A", true, 0, 40_000);
        CompanionItems.carryOut(owner, everyone(Verb.TRADE, "mesos:100000"), List.of(companion(a)));
        invites();
        assertEquals("i only have 40000, here", said.get(a).get(0));
        assertEquals(List.of("A->Owner"), opened);
    }

    @Test
    void needPotGoesToTheDonorWithTheMostToSpare() {
        Character owner = chr(1, "Owner", false, 0, 0);
        Character a = chr(2, "A", true, 70, 0);
        Character b = chr(3, "B", true, 400, 0);
        CompanionItems.carryOut(owner, everyone(Verb.NEED, "hp"), List.of(companion(a), companion(b)));
        invites();
        assertEquals(List.of("B->Owner"), opened);
        assertEquals("got some hp pots, inv u", said.get(b).get(0));
    }

    @Test
    void needPotWhenNobodyCanSpare() {
        Character owner = chr(1, "Owner", false, 0, 0);
        Character a = chr(2, "A", true, 20, 0);
        CompanionItems.carryOut(owner, everyone(Verb.NEED, "pots"), List.of(companion(a)));
        assertTrue(opened.isEmpty());
        assertEquals("we're low on pots too, might need a shop run", said.get(a).get(0));
    }

    @Test
    void aSiblingRunningLowGetsSomeFromTheRichestSibling() {
        Character needy = chr(2, "A", true, 3, 0);
        Character rich = chr(3, "B", true, 300, 0);
        List<String> lines = new ArrayList<>();
        assertTrue(CompanionItems.shareWithSibling(needy, SupplyShare.Kind.HP, List.of(rich),
                (donor, line) -> lines.add(donor.getName() + ": " + line)));
        invites();
        assertEquals(List.of("B->A"), opened);
        assertEquals(List.of("B: A, have some of my hp pots"), lines);
    }

    /** Owner rule: a companion never says or hints it is anything but a player. */
    @Test
    void nothingACompanionSaysGivesItAway() throws Exception {
        Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern giveaway = Pattern.compile("(?i)\\b(bot|bots|robot|ai|a\\.i\\.|npc|program|script|algorithm|automated)\\b");
        for (String file : List.of(
                "src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java",
                "src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotGiveTrade.java",
                "src/main/java/soloMapling/companions/OwnedAltOrders.java",
                "src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/Formation.java",
                "src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/PartyBotOrders.java")) {
            String src = Files.readString(Path.of(file))
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", ""); // what is said, not comments
            Matcher m = literal.matcher(src);
            while (m.find()) {
                assertFalse(giveaway.matcher(m.group(1)).find(), file + " says: " + m.group(1));
            }
        }
    }
}
