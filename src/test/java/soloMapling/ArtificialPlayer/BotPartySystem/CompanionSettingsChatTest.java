package soloMapling.ArtificialPlayer.BotPartySystem;

import client.BotClient;
import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Order;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.companions.CompanionSettingsStore;
import soloMapling.companions.OwnedAltService;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-settings (finished by Bots after the helper's early stops): the owner's chat switches. "heals off",
 * "support off" / "buffs off", "buff pots off", "pots at 60" and "mp pots at 40" are orders only when the settings
 * switch is on for a companion in the party; they save and the companion answers with the state now in force. Bare
 * "pots" is still the wave 2 report, a bare "heals" is chat, and a regular with the defaults is unchanged.
 */
class CompanionSettingsChatTest {

    private static final int OWNER = 1;
    private static final int ALT = 1_000_000_602;

    private Character player;
    private Character jane;
    private MockedStatic<CharacterStorage> storage;
    private MockedStatic<OwnedAltService> svc;
    private MockedStatic<CompanionSwitches> sw;

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
        sw.close();
        svc.close();
        storage.close();
        CompanionSettingsStore.useForTest(null, null);
        System.clearProperty(Feature.SETTINGS.property(true));
        System.clearProperty(Feature.SETTINGS.property(false));
    }

    private Optional<Order> read(String line) {
        return PartyBotOrders.read(player, line);
    }

    @Test
    void theSwitchesAndLinesAreOrdersForAnAlt() {
        assertEquals(Verb.HEALS, read("heals off").orElseThrow().verb());
        assertEquals("off", read("jane heals off").orElseThrow().arg());
        assertEquals("on", read("heals on").orElseThrow().arg());
        assertEquals(Verb.SUPPORT, read("support off").orElseThrow().verb());
        assertEquals(Verb.SUPPORT, read("buffs off").orElseThrow().verb());
        assertEquals(Verb.BUFF_POTS, read("buff pots off").orElseThrow().verb());
        Order hp = read("pots at 60").orElseThrow();
        assertEquals(Verb.HP_POTS, hp.verb());
        assertEquals("60", hp.arg());
        assertEquals(Verb.HP_POTS, read("hp pots at 60").orElseThrow().verb());
        Order mp = read("mp pots at 40").orElseThrow();
        assertEquals(Verb.MP_POTS, mp.verb());
        assertEquals("40", mp.arg());
        assertEquals(Verb.CARD, read("jane card").orElseThrow().verb());
    }

    /** "@alts card jane" passes "card jane" to the reader: the card opens for the companion named after the word. */
    @Test
    void cardWithTheNameAfterItIsTheCardOrderForThatCompanion() {
        Order card = read("card jane").orElseThrow();
        assertEquals(Verb.CARD, card.verb());
        assertEquals(List.of(ALT), card.targets().stream().map(BotCommandParser.Target::id).toList());
    }

    @Test
    void wordsWithoutTheirArgumentStayWhatTheyWere() {
        assertEquals(Verb.POTS, read("pots").orElseThrow().verb(), "bare pots is still the wave 2 report");
        assertTrue(read("heals").isEmpty(), "a bare heals is chat");
        assertTrue(read("support").isEmpty());
        assertTrue(read("trade pots at 60").map(o -> o.verb() != Verb.HP_POTS).orElse(true),
                "trade pots at 60 is not a potion line");
    }

    @Test
    void aRegularWithTheDefaultsIsUnchanged() {
        when(jane.isOwnedAlt()).thenReturn(false);
        assertTrue(read("heals off").isEmpty(), "a regular's heals off stays chat");
        assertTrue(read("pots at 60").isEmpty());
        assertEquals(Verb.GRIND, read("grind").orElseThrow().verb(), "its old orders are unchanged");

        System.setProperty(Feature.SETTINGS.property(false), "true");
        assertEquals(Verb.HEALS, read("heals off").orElseThrow().verb(), "with the regulars switch on it is read");
    }

    @Test
    void aSwitchSavesAndTheCompanionAnswersWithTheStateInForce() {
        assertEquals("heals off", PartyBotOrders.apply(player, jane, Verb.HEALS, "off"));
        assertFalse(CompanionSettingsStore.heals(jane));
        assertEquals("party buffs off", PartyBotOrders.apply(player, jane, Verb.SUPPORT, "off"));
        assertFalse(CompanionSettingsStore.support(jane));
        assertEquals("buff pots off", PartyBotOrders.apply(player, jane, Verb.BUFF_POTS, "off"));
        assertFalse(CompanionSettingsStore.buffPots(jane));
        assertEquals("k, i'll pot at 60%", PartyBotOrders.apply(player, jane, Verb.HP_POTS, "60"));
        assertEquals(60, CompanionSettingsStore.hpPercent(jane));
        assertEquals("k, i'll pot at 10%", PartyBotOrders.apply(player, jane, Verb.HP_POTS, "5"),
                "the answer says what was stored after the 10..90 clamp");
        assertEquals("k, i'll drink mp at 40%", PartyBotOrders.apply(player, jane, Verb.MP_POTS, "40"));
        assertEquals(40, CompanionSettingsStore.mpPercent(jane));
    }

    @Test
    void theTypoHintKnowsTheNewWords() {
        assertEquals(Optional.of("heals"), CompanionReports.typoSuggestion("heels off", List.of()));
        assertEquals(Optional.of("support"), CompanionReports.typoSuggestion("suport off", List.of()));
    }
}
