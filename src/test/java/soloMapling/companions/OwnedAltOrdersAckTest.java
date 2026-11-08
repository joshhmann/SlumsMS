package soloMapling.companions;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance.Mode;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyNeeds;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7: the grind and follow acknowledgements say when the companion is nearly out of pots, so the player hears it
 * before the fight rather than after it. Only when the chat switch is on for that companion: a regular party bot with
 * the defaults is unchanged.
 */
class OwnedAltOrdersAckTest {

    private static Character alt(boolean owned) {
        Character c = mock(Character.class);
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.isOwnedAlt()).thenReturn(owned);
        return c;
    }

    @AfterEach
    void clear() {
        System.clearProperty(Feature.CHAT_REPORTS.property(true));
        System.clearProperty(Feature.CHAT_REPORTS.property(false));
    }

    @Test
    void theGrindAckSaysThePotsAreNearlyOut() {
        Character c = alt(true);
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(4);
            s.when(() -> BotSupplies.needs(c)).thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 4, 60, false));
            String ack = OwnedAltOrders.ack(Verb.GRIND, Mode.SENTRY, c);
            assertTrue(ack.endsWith(" (only 4 HP pots)"), ack);
        }
    }

    @Test
    void aFullBagOfPotsNeedsNoNote() {
        Character c = alt(true);
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(20);
            s.when(() -> BotSupplies.needs(c)).thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 20, 60, false));
            assertFalse(OwnedAltOrders.ack(Verb.GRIND, Mode.SENTRY, c).contains("only"));
        }
    }

    @Test
    void theNoteIsForGrindAndFollowOnly() {
        Character c = alt(true);
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(4);
            s.when(() -> BotSupplies.needs(c)).thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 4, 60, false));
            assertFalse(OwnedAltOrders.ack(Verb.STAY, Mode.STAY, c).contains("only"));
            assertTrue(OwnedAltOrders.ack(Verb.FOLLOW, Mode.FOLLOW, c).contains(" (only 4 HP pots)"));
        }
    }

    @Test
    void aRegularWithTheDefaultSwitchesHearsTheSameAckAsBefore() {
        Character c = alt(false);
        try (MockedStatic<BotVitals> v = mockStatic(BotVitals.class);
             MockedStatic<BotSupplies> s = mockStatic(BotSupplies.class)) {
            v.when(() -> BotVitals.potionCount(c, true)).thenReturn(4);
            s.when(() -> BotSupplies.needs(c)).thenReturn(new SupplyNeeds(null, 0, 0, List.of(), 4, 60, false));
            assertFalse(OwnedAltOrders.ack(Verb.GRIND, Mode.SENTRY, c).contains("only"));
            System.setProperty(Feature.CHAT_REPORTS.property(false), "true");
            assertTrue(OwnedAltOrders.ack(Verb.GRIND, Mode.SENTRY, c).contains(" (only 4 HP pots)"));
        }
    }
}
