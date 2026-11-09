package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 1.6.7: the shared companion layer's policy: owned alts ON, party regulars OFF, players never. */
class PartyCompanionPolicyTest {

    @AfterEach
    void clear() {
        for (Feature f : Feature.values()) {
            System.clearProperty(f.property(true));
            System.clearProperty(f.property(false));
        }
    }

    private static Character chr(boolean bot, boolean alt) {
        Character c = mock(Character.class);
        when(c.isOwnedAlt()).thenReturn(alt);
        return c;
    }

    @Test
    void altsOnRegularsOffPlayersNeverByDefault() {
        Character alt = chr(true, true);
        Character regular = chr(true, false);
        Character player = chr(false, false);
        try (MockedStatic<BotHelpers> h = mockStatic(BotHelpers.class)) {
            h.when(() -> BotHelpers.isBot(alt)).thenReturn(true);
            h.when(() -> BotHelpers.isBot(regular)).thenReturn(true);
            h.when(() -> BotHelpers.isBot(player)).thenReturn(false);
            for (Feature f : Feature.values()) {
                assertTrue(PartyCompanionPolicy.on(f, alt), f + " alt");
                assertFalse(PartyCompanionPolicy.on(f, regular), f + " regular");
                assertFalse(PartyCompanionPolicy.on(f, player), f + " player");
            }
        }
    }

    @Test
    void eachSwitchFlipsOneFeatureForOneKind() {
        Character alt = chr(true, true);
        Character regular = chr(true, false);
        System.setProperty(Feature.SHARED_PICKER.property(false), "true");
        System.setProperty(Feature.SUPPLIES.property(true), "false");
        try (MockedStatic<BotHelpers> h = mockStatic(BotHelpers.class)) {
            h.when(() -> BotHelpers.isBot(alt)).thenReturn(true);
            h.when(() -> BotHelpers.isBot(regular)).thenReturn(true);
            assertTrue(PartyCompanionPolicy.on(Feature.SHARED_PICKER, regular));
            assertFalse(PartyCompanionPolicy.on(Feature.SUPPLIES, alt));
            assertTrue(PartyCompanionPolicy.on(Feature.DEATH_RULES, alt));
            assertFalse(PartyCompanionPolicy.on(Feature.DEATH_RULES, regular));
        }
        assertEquals("slums.companion.picker.regulars", Feature.SHARED_PICKER.property(false));
    }
}
