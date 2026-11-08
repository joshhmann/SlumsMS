package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 follow-plus: one short arrival line per town, one welcome-back line after the owner stood still for five
 * minutes and moved again, both once per owner, both silent with {@code @quiet bots}, and neither ever says "bot".
 */
class CompanionLinesTest {

    private static final int OWNER = 1;
    private static final int ALT = 2;
    private static final int ALT2 = 3;
    private static final long T0 = 9_000_000L;

    private Character owner;
    private Character alt;
    private Character alt2;
    private MapleMap map;
    private MockedStatic<BotHelpers> helpers;
    private MockedStatic<BotPartyChat> chat;

    @BeforeEach
    void setUp() {
        map = mock(MapleMap.class);
        when(map.getMapName()).thenReturn("Henesys");
        when(map.isTown()).thenReturn(true);
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getMap()).thenReturn(map);
        when(owner.getMapId()).thenReturn(100_000_000);
        when(owner.getPosition()).thenReturn(new Point(0, 0));
        alt = companion(ALT);
        alt2 = companion(ALT2);
        helpers = mockStatic(BotHelpers.class);
        helpers.when(() -> BotHelpers.isBot(alt)).thenReturn(true);
        helpers.when(() -> BotHelpers.isBot(alt2)).thenReturn(true);
        helpers.when(() -> BotHelpers.isBot(owner)).thenReturn(false);
        chat = mockStatic(BotPartyChat.class);
        BotNoiseFilter.set(OWNER, BotNoiseFilter.Setting.NONE);
        CompanionLines.forget(OWNER);
    }

    @AfterEach
    void tearDown() {
        chat.close();
        helpers.close();
        BotNoiseFilter.set(OWNER, BotNoiseFilter.Setting.NONE);
        CompanionLines.forget(OWNER);
        System.clearProperty(Feature.FOLLOW_PLUS.property(false));
    }

    private Character companion(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isOwnedAlt()).thenReturn(true);
        when(c.getMap()).thenReturn(map);
        when(c.getMapId()).thenReturn(100_000_000);
        when(c.getName()).thenReturn("alt" + id);
        Party party = mock(Party.class);
        when(party.getMemberById(OWNER)).thenReturn(mock(PartyCharacter.class));
        when(c.getParty()).thenReturn(party);
        return c;
    }

    @Test
    void theArrivalLineIsSaidOncePerTown() {
        CompanionLines.tick(owner, alt, T0);
        chat.verify(() -> BotPartyChat.sayInParty(alt, "we're in Henesys"), times(1));

        // the second companion of the same owner, on the same map: nothing more
        CompanionLines.tick(owner, alt2, T0 + 1_000);
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt2), anyString()), never());

        // a third poll on the same map: still one line
        CompanionLines.tick(owner, alt, T0 + 2_000);
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), times(1));
        assertFalse("we're in Henesys".toLowerCase().contains("bot"));
    }

    @Test
    void theWelcomeBackLineComesOnce() {
        CompanionLines.tick(owner, alt, T0); // arrival
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS); // still as long as the away threshold

        when(owner.getPosition()).thenReturn(new Point(50, 0));
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS + 1_000);
        chat.verify(() -> BotPartyChat.sayInParty(alt, "welcome back, still in Henesys"), times(1));

        // moving again does not repeat it
        when(owner.getPosition()).thenReturn(new Point(90, 0));
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS + 2_000);
        chat.verify(() -> BotPartyChat.sayInParty(alt, "welcome back, still in Henesys"), times(1));
        assertFalse("welcome back, still in Henesys".toLowerCase().contains("bot"));
    }

    @Test
    void theArrivalLineIsATownLine() {
        MapleMap field = mock(MapleMap.class);
        when(field.getMapName()).thenReturn("Henesys Hunting Ground I");
        when(field.isTown()).thenReturn(false);
        when(owner.getMap()).thenReturn(field);
        when(owner.getMapId()).thenReturn(100_010_000);
        when(alt.getMap()).thenReturn(field);

        CompanionLines.tick(owner, alt, T0);
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), never());
    }

    @Test
    void aTownReenteredSoonStaysQuiet() {
        CompanionLines.tick(owner, alt, T0); // Henesys, said
        MapleMap field = mock(MapleMap.class);
        when(field.isTown()).thenReturn(false);
        when(owner.getMap()).thenReturn(field);
        when(owner.getMapId()).thenReturn(100_010_000);
        when(alt.getMap()).thenReturn(field);
        CompanionLines.tick(owner, alt, T0 + 60_000); // a hunting field: no line

        when(owner.getMap()).thenReturn(map);
        when(owner.getMapId()).thenReturn(100_000_000);
        when(alt.getMap()).thenReturn(map);
        CompanionLines.tick(owner, alt, T0 + 120_000); // back in Henesys inside MAP_COOLDOWN_MS: quiet
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), times(1));
        assertTrue(CompanionLines.MAP_COOLDOWN_MS > 120_000, "the window is what silences the return");
    }

    @Test
    void aShortPauseIsNotAnAbsence() {
        CompanionLines.tick(owner, alt, T0); // arrival
        when(owner.getPosition()).thenReturn(new Point(50, 0));
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS - 1_000);
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), times(1)); // the arrival only
    }

    @Test
    void quietBotsSilencesBoth() {
        BotNoiseFilter.set(OWNER, new BotNoiseFilter.Setting(false, true)); // @quiet bots
        CompanionLines.tick(owner, alt, T0);
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS);
        when(owner.getPosition()).thenReturn(new Point(50, 0));
        CompanionLines.tick(owner, alt, T0 + CompanionLines.OWNER_AFK_MS + 1_000);
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), never());
    }

    @Test
    void aRegularIsUnchangedWithTheDefaults() {
        Character regular = mock(Character.class);
        when(regular.getId()).thenReturn(4);
        when(regular.isOwnedAlt()).thenReturn(false);
        when(regular.getMap()).thenReturn(map);
        when(regular.getMapId()).thenReturn(100_000_000);
        when(regular.getName()).thenReturn("regular");
        Party ownerParty = alt.getParty();
        when(regular.getParty()).thenReturn(ownerParty);
        helpers.when(() -> BotHelpers.isBot(regular)).thenReturn(true);

        CompanionLines.tick(owner, regular, T0);
        chat.verify(() -> BotPartyChat.sayInParty(eq(regular), anyString()), never());

        System.setProperty(Feature.FOLLOW_PLUS.property(false), "true");
        CompanionLines.forget(OWNER); // a fresh owner so the arrival is said for the new companion
        CompanionLines.tick(owner, regular, T0 + 1_000);
        chat.verify(() -> BotPartyChat.sayInParty(regular, "we're in Henesys"), times(1));
    }
}
