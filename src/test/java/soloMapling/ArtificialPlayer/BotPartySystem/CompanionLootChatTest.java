package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import client.inventory.Equip;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.companions.CompanionSettingsStore;
import soloMapling.companions.CompanionUpgradeOffer;
import soloMapling.companions.OwnedAltService;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-qol-batch group D: the real command the docs tell players to type. "@alts loot everything" (and the
 * party-chat form, and "jane loot everything") is an order only when the loot switch is on for a companion; a regular
 * with the defaults keeps its old behaviour, and "-Dslums.companion.loot.regulars=true" makes it read them too.
 */
class CompanionLootChatTest {

    private static final int OWNER = 1;
    private static final int ALT = 1_000_000_603;
    /** The scorer below calls this an upgrade for the owner. */
    private static final int OFFER_UPGRADE = 1302000;

    private Character player;
    private Character jane;
    private MockedStatic<CharacterStorage> storage;
    private MockedStatic<OwnedAltService> svc;
    private MockedStatic<CompanionSwitches> sw;
    private MockedStatic<ItemInformationProvider> itemData;
    private final List<String> handed = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000L);
    private MapleMap field;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void party() {
        player = mock(Character.class);
        when(player.getId()).thenReturn(OWNER);
        when(player.getName()).thenReturn("Main");
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

        // the upgrade offer: the same seams CompanionUpgradeOfferTest uses (an Equip's constructor reads item data)
        field = mock(MapleMap.class);
        when(player.getMap()).thenReturn(field);
        when(jane.getMap()).thenReturn(field);
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);
        when(ii.getEquipLevel(anyInt(), anyBoolean())).thenReturn(0);
        when(ii.getName(anyInt())).thenReturn("Sword");
        GearUpgrades.install((wearer, candidate) -> candidate.getItemId() == OFFER_UPGRADE ? 4 : 0);
        CompanionUpgradeOffer.forget();
        CompanionUpgradeOffer.useForTest((from, to, piece, say) -> {
            handed.add(from.getName() + " -> " + to.getName() + " " + piece.getItemId());
            return true;
        }, now::get);

        BotSM bot = mock(BotSM.class);
        when(bot.getChr()).thenReturn(jane);

        storage = mockStatic(CharacterStorage.class);
        storage.when(() -> CharacterStorage.getBotById(ALT)).thenReturn(bot);
        storage.when(() -> CharacterStorage.checkIfInvisibleBot(ALT)).thenReturn(false);
        OwnedAltService service = mock(OwnedAltService.class);
        svc = mockStatic(OwnedAltService.class);
        svc.when(OwnedAltService::getInstance).thenReturn(service);
        when(service.active()).thenReturn(List.of(new OwnedAltService.Active(player, jane)));
        sw = mockStatic(CompanionSwitches.class);
        sw.when(CompanionSwitches::any).thenReturn(true);
        sw.when(() -> CompanionSwitches.allowed(true)).thenReturn(EnumSet.of(Verb.FOLLOW, Verb.GRIND));
        sw.when(() -> CompanionSwitches.allowed(false)).thenReturn(EnumSet.of(Verb.FOLLOW, Verb.GRIND));
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);
    }

    @AfterEach
    void close() {
        CompanionUpgradeOffer.useForTest(null, null);
        CompanionUpgradeOffer.forget();
        GearUpgrades.install(null);
        itemData.close();
        sw.close();
        svc.close();
        storage.close();
        CompanionSettingsStore.useForTest(null, null);
        System.clearProperty(Feature.LOOT.property(true));
        System.clearProperty(Feature.LOOT.property(false));
    }

    private Optional<Order> read(String line) {
        return PartyBotOrders.read(player, line);
    }

    @Test
    void everyLootModeTheDocsListIsTheRealCommand() {
        assertEquals("everything", read("loot everything").orElseThrow().arg());
        assertEquals(Verb.LOOT, read("loot everything").orElseThrow().verb());
        assertEquals("my kills", read("loot my kills").orElseThrow().arg());
        assertEquals("my kills", read("loot kills").orElseThrow().arg());
        assertEquals("quest only", read("loot quest").orElseThrow().arg());
        assertEquals("quest only", read("loot quests").orElseThrow().arg());
        assertEquals("nothing", read("loot nothing").orElseThrow().arg());
        assertEquals("like you", read("loot like me").orElseThrow().arg(),
                "\"loot like me\" is stored as the mode \"like you\"");
        assertEquals("like you", read("loot like you").orElseThrow().arg());
        assertEquals(List.of(ALT), read("jane loot everything").orElseThrow().targets().stream()
                .map(BotCommandParser.Target::id).toList(), "the named form works too: @alts jane loot everything");
    }

    @Test
    void aBareLootAndOrdinaryChatStayChat() {
        assertTrue(read("loot").isEmpty(), "a bare loot has to say what");
        assertTrue(read("loot later").isEmpty());
        assertTrue(read("i loot everything i see").isEmpty(), "a sentence is not an order");
    }

    @Test
    void sellJunkAndTheOffersSwitchAreOrdersToo() {
        Order sell = read("sell junk").orElseThrow();
        assertEquals(Verb.SELL_JUNK, sell.verb());
        assertEquals(null, sell.arg(), "plain junk only");
        assertEquals("equips", read("sell junk equips too").orElseThrow().arg());
        assertEquals("equips", read("sell junk equips").orElseThrow().arg());
        assertTrue(read("sell me that").isEmpty(), "not a junk order");
        assertEquals("off", read("offers off").orElseThrow().arg());
        assertEquals("on", read("offers on").orElseThrow().arg());
        assertEquals("off", read("offer off").orElseThrow().arg());
    }

    @Test
    void theOrderSavesTheModeAndTheAltSaysWhatItDoesNow() {
        Order order = read("loot nothing").orElseThrow();
        assertEquals("k, loot nothing", PartyBotOrders.apply(player, jane, order.verb(), order.arg()));
        assertEquals("nothing", CompanionSettingsStore.get(OWNER, ALT).lootMode());

        Order quest = read("loot quest").orElseThrow();
        assertEquals("k, loot quest items", PartyBotOrders.apply(player, jane, quest.verb(), quest.arg()));
        assertEquals("quest only", CompanionSettingsStore.get(OWNER, ALT).lootMode());

        assertEquals("offers off", PartyBotOrders.apply(player, jane, Verb.OFFERS, "off"));
        assertFalse(CompanionSettingsStore.stored(jane).offers());
        assertEquals("offers on", PartyBotOrders.apply(player, jane, Verb.OFFERS, "on"));
        assertTrue(CompanionSettingsStore.stored(jane).offers());
    }

    @Test
    void aRegularIsUnchangedUntilTheRegularsSwitchIsOn() {
        when(jane.isOwnedAlt()).thenReturn(false);
        assertTrue(read("loot everything").isEmpty(), "a regular with the defaults never reads it: still chat");
        assertTrue(read("sell junk").isEmpty());
        assertTrue(read("offers off").isEmpty());
        assertEquals(Verb.GRIND, read("grind").orElseThrow().verb(), "its old orders are unchanged");

        System.setProperty(Feature.LOOT.property(false), "true");
        assertEquals(Verb.LOOT, read("loot everything").orElseThrow().verb(), "with the regulars switch on it is read");
        assertEquals(Verb.SELL_JUNK, read("sell junk").orElseThrow().verb());
        assertEquals(Verb.OFFERS, read("offers off").orElseThrow().verb());
    }

    @Test
    void theOwnersYesHandsTheOfferedPieceOver() {
        List<String> said = new ArrayList<>();
        Equip piece = new Equip(OFFER_UPGRADE, (short) 0);
        String line = CompanionUpgradeOffer.consider(jane, player, piece, said::add);
        assertNotNull(line, "the alt found an upgrade for its owner");
        assertEquals(1, said.size());
        assertTrue(CompanionUpgradeOffer.waitingFor(player));

        assertTrue(PartyBotOrders.fromPartyChat(player, "yes"), "the yes is the answer, not an order");
        assertEquals(List.of("Jane -> Main " + OFFER_UPGRADE), handed, "the piece changed hands");
        assertFalse(CompanionUpgradeOffer.waitingFor(player), "one yes, one handover");
    }

    @Test
    void ordinaryChatDoesNotTouchAnOffer() {
        List<String> said = new ArrayList<>();
        CompanionUpgradeOffer.consider(jane, player, new Equip(OFFER_UPGRADE, (short) 0), said::add);
        assertTrue(CompanionUpgradeOffer.waitingFor(player));

        assertFalse(PartyBotOrders.fromPartyChat(player, "brb, one sec"), "not an answer and not an order");
        assertTrue(CompanionUpgradeOffer.waitingFor(player), "the offer keeps waiting");
        assertTrue(handed.isEmpty());
    }
}
