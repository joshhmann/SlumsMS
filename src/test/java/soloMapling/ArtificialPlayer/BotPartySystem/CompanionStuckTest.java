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
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 follow-plus: a companion that should be walking to its owner but is not is put beside the owner after 8 s and
 * says so once - unless it is fighting, holding a stance, in an instance it did not enter with the owner, or the
 * switch is off for its kind (party regulars by default).
 */
class CompanionStuckTest {

    private static final int OWNER = 1;
    private static final int ALT = 2;
    private static final long T0 = 1_000_000L;

    private Character owner;
    private Character alt;
    private MapleMap map;
    private Party party;
    private MockedStatic<BotHelpers> helpers;
    private MockedStatic<GCMovement> gc;
    private MockedStatic<BotPartyChat> chat;

    @BeforeEach
    void setUp() {
        owner = character(OWNER, true, new Point(0, 0));
        alt = character(ALT, true, new Point(300, 0));
        map = mock(MapleMap.class);
        when(owner.getMap()).thenReturn(map);
        when(alt.getMap()).thenReturn(map);
        when(owner.getMapId()).thenReturn(100_000_000);
        when(alt.getMapId()).thenReturn(100_000_000);
        party = mock(Party.class);
        when(party.getMemberById(OWNER)).thenReturn(mock(PartyCharacter.class));
        when(alt.getParty()).thenReturn(party);

        helpers = mockStatic(BotHelpers.class);
        helpers.when(() -> BotHelpers.isBot(alt)).thenReturn(true);
        helpers.when(() -> BotHelpers.isBot(owner)).thenReturn(false);
        gc = mockStatic(GCMovement.class);
        gc.when(() -> GCMovement.setDownBeside(any(Character.class), anyInt(), anyInt(), anyString()))
                .thenReturn(true);
        chat = mockStatic(BotPartyChat.class);
        CompanionStuck.forget(ALT);
        CompanionLines.forget(OWNER);
    }

    @AfterEach
    void tearDown() {
        chat.close();
        gc.close();
        helpers.close();
        System.clearProperty(Feature.FOLLOW_PLUS.property(true));
        System.clearProperty(Feature.FOLLOW_PLUS.property(false));
        CompanionStuck.forget(ALT);
        CompanionLines.forget(OWNER);
    }

    private static Character character(int id, boolean ownedAlt, Point at) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isOwnedAlt()).thenReturn(ownedAlt);
        when(c.getPosition()).thenReturn(at);
        when(c.getName()).thenReturn("alt" + id);
        return c;
    }

    @Test
    void stuckAfterEightSecondsSaysOopsAndPutsItBesideTheOwner() {
        assertFalse(CompanionStuck.tick(alt, owner, T0, false), "first sighting is not stuck");
        assertFalse(CompanionStuck.tick(alt, owner, T0 + 7_000, false), "7 s is not 8 s");
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), never());

        assertTrue(CompanionStuck.tick(alt, owner, T0 + 8_000, false), "8 s without moving is stuck");

        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), times(1));
        chat.verify(() -> BotPartyChat.sayInParty(alt, CompanionStuck.LINE), times(1));
        assertFalse(CompanionStuck.LINE.toLowerCase().contains("bot"), "the line never says bot");
    }

    @Test
    void fightingOrHoldingAStanceIsNotStuck() {
        for (long now = T0; now <= T0 + 20_000; now += 1_000) {
            assertFalse(CompanionStuck.tick(alt, owner, now, true), "busy at " + now);
        }
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), never());
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), never());
    }

    @Test
    void anInstanceTheOwnerIsNotInNeverTeleports() {
        // another map object with the same id is another channel or another event instance: same identity rule, the
        // cross-map recovery stays the travel and the catch-up, both of which gate on BotMapGates
        MapleMap elsewhere = mock(MapleMap.class);
        when(alt.getMap()).thenReturn(elsewhere);
        when(alt.getMapId()).thenReturn(100_000_000);
        for (long now = T0; now <= T0 + 20_000; now += 1_000) {
            assertFalse(CompanionStuck.tick(alt, owner, now, false), "not this instance at " + now);
        }
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), never());
        chat.verify(() -> BotPartyChat.sayInParty(eq(alt), anyString()), never());
    }

    @Test
    void oneRecoveryThenTheCooldown() {
        assertFalse(CompanionStuck.tick(alt, owner, T0, false));
        assertTrue(CompanionStuck.tick(alt, owner, T0 + 8_000, false));
        assertFalse(CompanionStuck.tick(alt, owner, T0 + 30_000, false), "inside the cooldown");
        assertTrue(CompanionStuck.tick(alt, owner, T0 + 39_000, false), "after the cooldown");
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), times(2));
        chat.verify(() -> BotPartyChat.sayInParty(alt, CompanionStuck.LINE), times(2));
    }

    @Test
    void aRegularIsUnchangedWithTheDefaults() {
        Character regular = character(3, false, new Point(300, 0));
        when(regular.getMap()).thenReturn(map);
        helpers.when(() -> BotHelpers.isBot(regular)).thenReturn(true);
        when(regular.getParty()).thenReturn(party);
        for (long now = T0; now <= T0 + 20_000; now += 1_000) {
            assertFalse(CompanionStuck.tick(regular, owner, now, false), "default regular at " + now);
        }
        gc.verify(() -> GCMovement.setDownBeside(eq(regular), anyInt(), anyInt(), anyString()), never());
        chat.verify(() -> BotPartyChat.sayInParty(eq(regular), anyString()), never());
        CompanionStuck.forget(3);
    }

    @Test
    void aRegularFollowsTheSwitch() {
        Character regular = character(3, false, new Point(300, 0));
        when(regular.getMap()).thenReturn(map);
        helpers.when(() -> BotHelpers.isBot(regular)).thenReturn(true);
        when(regular.getParty()).thenReturn(party);
        System.setProperty(Feature.FOLLOW_PLUS.property(false), "true");

        assertFalse(CompanionStuck.tick(regular, owner, T0, false));
        assertTrue(CompanionStuck.tick(regular, owner, T0 + 8_000, false));
        gc.verify(() -> GCMovement.setDownBeside(eq(regular), anyInt(), anyInt(), anyString()), times(1));
        chat.verify(() -> BotPartyChat.sayInParty(regular, CompanionStuck.LINE), times(1));
        CompanionStuck.forget(3);
    }
}
