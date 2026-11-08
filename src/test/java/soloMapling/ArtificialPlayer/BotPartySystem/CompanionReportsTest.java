package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.FieldLimit;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyNeeds;
import soloMapling.companions.OwnedAltService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 (alt-qol-batch group A): the chat side of companions. The reports say what the character holds (counted with
 * the existing inventory code); the "did you mean" fires only on a line addressed to a companion; the confirm before a
 * drop needs a yes from the player who asked, inside the window; a regular with the default switches gets none of it.
 */
class CompanionReportsTest {

    private static final int OWNER = 1;
    private static final int ALT = 2;

    private Character player;
    private Character jane;
    private MockedStatic<CharacterStorage> storage;
    private MockedStatic<SocialCommands> speak;
    private final List<String> spoken = new ArrayList<>();

    /** An owned alt: a bot with a client of its own, reports on by default. */
    private static Character alt() {
        Character c = mock(Character.class);
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.isOwnedAlt()).thenReturn(true);
        return c;
    }

    @BeforeEach
    void party() {
        player = mock(Character.class);
        when(player.getId()).thenReturn(OWNER);
        Party party = mock(Party.class);
        when(player.getParty()).thenReturn(party);
        PartyCharacter pc = mock(PartyCharacter.class);
        when(pc.getId()).thenReturn(ALT);
        when(party.getPartyMembers()).thenReturn(List.of(pc));

        jane = mock(Character.class);
        when(jane.getId()).thenReturn(ALT);
        when(jane.getName()).thenReturn("Jane");
        when(jane.getClient()).thenReturn(mock(BotClient.class));
        when(jane.isOwnedAlt()).thenReturn(true);
        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(jane);

        storage = mockStatic(CharacterStorage.class);
        storage.when(() -> CharacterStorage.getBotById(ALT)).thenReturn(bot);
        storage.when(() -> CharacterStorage.checkIfInvisibleBot(ALT)).thenReturn(false);
        speak = mockStatic(SocialCommands.class);
        speak.when(() -> SocialCommands.BotSpeak(any(), any())).thenAnswer(inv -> {
            spoken.add(((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1));
            return null;
        });
    }

    @AfterEach
    void close() {
        spoken.clear();
        speak.close();
        storage.close();
        System.clearProperty(Feature.CHAT_REPORTS.property(true));
        System.clearProperty(Feature.CHAT_REPORTS.property(false));
        CompanionReports.clearPending();
        CompanionReports.clock(null);
    }

    // ── the reports ──

    @Test
    void potsSaysBothCounts() {
        Character c = alt();
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(120);
            v.when(() -> BotVitals.potionCount(c, false)).thenReturn(40);
            assertEquals("I have 120 hp pots and 40 mp pots", CompanionReports.pots(c));
        }
    }

    @Test
    void potsSaysSoWhenThereAreNone() {
        Character c = alt();
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class)) {
            v.when(() -> BotVitals.potionCount(c, false)).thenReturn(0);
            assertEquals("no pots on me rn", CompanionReports.pots(c));
        }
    }

    @Test
    void statsIsOneLine() {
        Character c = alt();
        when(c.getLevel()).thenReturn(42);
        when(c.getJob()).thenReturn(Job.WARRIOR);
        when(c.getHp()).thenReturn(500);
        when(c.getMaxHp()).thenReturn(800);
        when(c.getMp()).thenReturn(100);
        when(c.getMaxMp()).thenReturn(200);
        when(c.getStr()).thenReturn(30);
        when(c.getDex()).thenReturn(4);
        when(c.getInt()).thenReturn(4);
        when(c.getLuk()).thenReturn(4);
        when(c.getRemainingAp()).thenReturn(3);
        assertEquals("lv42 warrior, hp 500/800 mp 100/200, 30 str 4 dex 4 int 4 luk, 3 ap free",
                CompanionReports.stats(c));
    }

    @Test
    void statsLeavesOutFreeApWhenThereIsNone() {
        Character c = alt();
        when(c.getLevel()).thenReturn(9);
        when(c.getJob()).thenReturn(Job.BEGINNER);
        assertEquals("lv9 beginner, hp 0/0 mp 0/0, 0 str 0 dex 0 int 0 luk", CompanionReports.stats(c));
    }

    @Test
    void slotsSaysUsedOutOfTotal() {
        Character c = alt();
        Character holder = mock(Character.class);
        for (InventoryType t : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC,
                InventoryType.SETUP)) {
            when(c.getInventory(t)).thenReturn(new Inventory(holder, t, (byte) 24));
        }
        Inventory use = c.getInventory(InventoryType.USE);
        use.addItemFromDB(new Item(2000000, (short) 1, (short) 5));
        use.addItemFromDB(new Item(2000001, (short) 2, (short) 5));
        use.addItemFromDB(new Item(2000002, (short) 3, (short) 5));
        assertEquals("equip 0/24, use 3/24, etc 0/24, setup 0/24", CompanionReports.slots(c));
    }

    /**
     * The keep rule (owner, 8 Nov 2026): asking for the reports on an alt loaded with today's numbers answers with
     * exactly those numbers, and asks the same thing twice without changing anything about the character.
     */
    @Test
    void anExistingCharacterKeepsEverythingItHas() {
        Character c = alt();
        when(c.getLevel()).thenReturn(58);
        when(c.getJob()).thenReturn(Job.WARRIOR);
        when(c.getHp()).thenReturn(1200);
        when(c.getMaxHp()).thenReturn(1400);
        when(c.getMp()).thenReturn(300);
        when(c.getMaxMp()).thenReturn(400);
        when(c.getStr()).thenReturn(90);
        when(c.getDex()).thenReturn(40);
        when(c.getInt()).thenReturn(4);
        when(c.getLuk()).thenReturn(4);
        when(c.getRemainingAp()).thenReturn(0);
        when(c.getMeso()).thenReturn(5_000_000);
        Character holder = mock(Character.class);
        for (InventoryType t : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.ETC,
                InventoryType.SETUP)) {
            when(c.getInventory(t)).thenReturn(new Inventory(holder, t, (byte) 24));
        }
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(60);
            v.when(() -> BotVitals.potionCount(c, false)).thenReturn(30);
            String first = CompanionReports.stats(c) + "|" + CompanionReports.pots(c) + "|"
                    + CompanionReports.slots(c);
            String again = CompanionReports.stats(c) + "|" + CompanionReports.pots(c) + "|"
                    + CompanionReports.slots(c);
            assertEquals(first, again);
            assertTrue(first.startsWith("lv58 warrior, hp 1200/1400 mp 300/400, 90 str 40 dex 4 int 4 luk|"));
            assertTrue(first.endsWith("|I have 60 hp pots and 30 mp pots|equip 0/24, use 0/24, etc 0/24, setup 0/24"));
        }
        verify(c, never()).setLevel(anyInt());
        assertEquals(5_000_000, c.getMeso(), "the mesos are still the character's");
    }

    /** The answer goes out through the reply path it is handed, and only for a companion the switch is on for. */
    @Test
    void anAnswerGoesOutOnlyWithTheSwitchOnForThatCompanion() {
        Character c = alt();
        Character regular = mock(Character.class);
        when(regular.getClient()).thenReturn(mock(BotClient.class));
        List<String> said = new ArrayList<>();
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(120);
            v.when(() -> BotVitals.potionCount(c, false)).thenReturn(40);
            v.when(() -> BotVitals.potionCount(regular, false)).thenReturn(0);
            assertTrue(CompanionReports.answer(c, Verb.POTS, said::add));
            assertEquals(List.of("I have 120 hp pots and 40 mp pots"), said);
            assertFalse(CompanionReports.answer(regular, Verb.POTS, said::add), "a regular with the defaults");
            System.setProperty(Feature.CHAT_REPORTS.property(false), "true");
            assertTrue(CompanionReports.answer(regular, Verb.POTS, said::add), "the regulars' switch is on");
            assertFalse(CompanionReports.answer(mock(Character.class), Verb.POTS, said::add), "a player");
        }
    }

    // ── the low-pots note ──

    @Test
    void theLowNoteStartsAtNineteen() {
        Character c = alt();
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            s.when(() -> BotSupplies.needs(c))
                    .thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 19, 0, false));
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(19);
            assertEquals(" (only 19 HP pots)", CompanionReports.lowNote(c));
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(20);
            assertEquals("", CompanionReports.lowNote(c), "at twenty it says nothing");
        }
    }

    @Test
    void theLowNoteCountsMpOnlyForAJobThatUsesIt() {
        Character c = alt();
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            s.when(() -> BotSupplies.needs(c))
                    .thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 19, 3, true));
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(19);
            v.when(() -> BotVitals.potionCount(c, false)).thenReturn(3);
            assertEquals(" (only 19 HP pots and 3 MP pots)", CompanionReports.lowNote(c));
            s.when(() -> BotSupplies.needs(c))
                    .thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 25, 3, true));
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(25);
            assertEquals(" (only 3 MP pots)", CompanionReports.lowNote(c));
            s.when(() -> BotSupplies.needs(c))
                    .thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 25, 3, false));
            assertEquals("", CompanionReports.lowNote(c), "a job that does not use MP is not low on it");
        }
    }

    // ── "did you mean" ──

    @Test
    void aMistypedOrderAddressedToACompanionGetsTheHint() {
        assertTrue(PartyBotOrders.typoHint(player, "jane grnd", false));
        assertEquals(List.of("Jane: did you mean 'grind'?"), spoken);
    }

    @Test
    void anAtAltsLineGetsTheHintWithoutTheName() {
        assertTrue(PartyBotOrders.typoHint(player, "grnd", true));
        assertEquals(List.of("Jane: did you mean 'grind'?"), spoken);
    }

    @Test
    void ordinaryPartyChatThatTheParserIgnoresStaysChat() {
        assertFalse(PartyBotOrders.typoHint(player, "i think grnd is fine", false));
        assertFalse(PartyBotOrders.typoHint(player, "jane says grnd is fine", false), "not the opening word");
        assertTrue(spoken.isEmpty());
    }

    @Test
    void aRegularWithTheDefaultSwitchesGetsNoHint() {
        when(jane.isOwnedAlt()).thenReturn(false);
        assertFalse(PartyBotOrders.typoHint(player, "jane grnd", false));
        assertTrue(spoken.isEmpty());
        System.setProperty(Feature.CHAT_REPORTS.property(false), "true");
        assertTrue(PartyBotOrders.typoHint(player, "jane grnd", false), "the regulars' switch is on");
        assertEquals(List.of("Jane: did you mean 'grind'?"), spoken);
    }

    // ── confirm before drop ──

    @Test
    void aDropWaitsForAYesFromTheSamePlayerInsideTheWindow() {
        Character alt = alt();
        List<String> said = new ArrayList<>();
        CompanionItems.Companion c = new CompanionItems.Companion(alt, said::add);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        long[] now = {1_000_000L};
        CompanionReports.clock(() -> now[0]);
        try (MockedStatic<CompanionItems> ci = mockStatic(CompanionItems.class)) {
            assertTrue(CompanionReports.confirmFirst(c, owner, "pots"));
            assertEquals(List.of("just to confirm, drop my pots? say yes"), said);
            ci.verify(() -> CompanionItems.dropNow(c, "pots"), never());

            Character stranger = mock(Character.class);
            when(stranger.getId()).thenReturn(99);
            assertFalse(CompanionReports.confirmDrop(stranger, "yes"), "someone else's yes does nothing");
            ci.verify(() -> CompanionItems.dropNow(c, "pots"), never());

            now[0] += 29_000L;
            assertTrue(CompanionReports.confirmDrop(owner, "yes"));
            ci.verify(() -> CompanionItems.dropNow(c, "pots"));
        }
    }

    @Test
    void aYesAfterTheWindowDoesNothing() {
        Character alt = alt();
        List<String> said = new ArrayList<>();
        CompanionItems.Companion c = new CompanionItems.Companion(alt, said::add);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        long[] now = {5_000L};
        CompanionReports.clock(() -> now[0]);
        try (MockedStatic<CompanionItems> ci = mockStatic(CompanionItems.class)) {
            CompanionReports.confirmFirst(c, owner, "pots");
            now[0] += CompanionReports.CONFIRM_MS + 1;
            assertFalse(CompanionReports.confirmDrop(owner, "yes"));
            ci.verify(() -> CompanionItems.dropNow(any(), any()), never());
        }
    }

    @Test
    void anythingButAYesCancels() {
        Character alt = alt();
        CompanionItems.Companion c = new CompanionItems.Companion(alt, line -> {
        });
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        long[] now = {7_000L};
        CompanionReports.clock(() -> now[0]);
        try (MockedStatic<CompanionItems> ci = mockStatic(CompanionItems.class)) {
            CompanionReports.confirmFirst(c, owner, "pots");
            assertFalse(CompanionReports.confirmDrop(owner, "nah keep them"));
            assertFalse(CompanionReports.confirmDrop(owner, "yes"), "the pending ask was cancelled");
            ci.verify(() -> CompanionItems.dropNow(any(), any()), never());
        }
    }

    @Test
    void aDropLimitedMapRefusesWithALine() {
        Character alt = alt();
        MapleMap map = mock(MapleMap.class);
        when(map.getFieldLimit()).thenReturn((int) FieldLimit.DROP_LIMIT.getValue());
        when(alt.getMap()).thenReturn(map);
        List<String> said = new ArrayList<>();
        CompanionItems.Companion c = new CompanionItems.Companion(alt, said::add);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        try (MockedStatic<CompanionItems> ci = mockStatic(CompanionItems.class)) {
            assertTrue(CompanionReports.confirmFirst(c, owner, "pots"));
            assertEquals(List.of(CompanionReports.dropBlockedLine()), said);
            assertFalse(CompanionReports.confirmDrop(owner, "yes"), "nothing was asked");
            ci.verify(() -> CompanionItems.dropNow(any(), any()), never());
        }
    }

    // ── how the reports reach the parser ──

    /**
     * "pots" is an order only when a companion would answer it: an alt with the switch on reads it, a regular with the
     * defaults keeps it as ordinary chat (and keeps every order it had), and the regulars' switch turns it on.
     */
    @Test
    void potsIsAnOrderOnlyWhenACompanionWouldAnswerIt() {
        OwnedAltService service = mock(OwnedAltService.class);
        try (MockedStatic<OwnedAltService> svc = mockStatic(OwnedAltService.class);
             MockedStatic<CompanionSwitches> sw = mockStatic(CompanionSwitches.class)) {
            svc.when(OwnedAltService::getInstance).thenReturn(service);
            when(service.active()).thenReturn(List.of(new OwnedAltService.Active(player, jane)));
            sw.when(CompanionSwitches::any).thenReturn(true);
            sw.when(() -> CompanionSwitches.allowed(true)).thenReturn(EnumSet.of(Verb.FOLLOW, Verb.GRIND));
            sw.when(() -> CompanionSwitches.allowed(false)).thenReturn(EnumSet.of(Verb.FOLLOW, Verb.GRIND));

            assertEquals(Verb.POTS, PartyBotOrders.read(player, "pots").orElseThrow().verb());
            assertEquals(Verb.POTS, PartyBotOrders.read(player, "how many pots").orElseThrow().verb());

            when(jane.isOwnedAlt()).thenReturn(false); // a party regular, defaults
            assertTrue(PartyBotOrders.read(player, "pots").isEmpty(), "a regular's \"pots\" stays chat");
            assertEquals(Verb.GRIND, PartyBotOrders.read(player, "grind").orElseThrow().verb(),
                    "the orders a regular had are unchanged");

            System.setProperty(Feature.CHAT_REPORTS.property(false), "true");
            assertEquals(Verb.POTS, PartyBotOrders.read(player, "pots").orElseThrow().verb());
        }
    }
}
