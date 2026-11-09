package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import server.maps.Rope;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 follow-plus: an owner up a rope, a ladder, or on a platform only a climb reaches is followed by climbing -
 * an explicit move target the nav layer plans a CLIMB edge from - with a step off the rope head at the top, and the
 * catch-up when the climb makes no progress in 12 s.
 */
class CompanionClimbTest {

    private static final int OWNER = 1;
    private static final int ALT = 2;
    private static final long T0 = 2_000_000L;

    private Character owner;
    private Character alt;
    private MapleMap map;
    private MockedStatic<BotHelpers> helpers;
    private MockedStatic<GCMovement> gc;
    private MockedStatic<BotPartyChat> chat;

    @BeforeEach
    void setUp() {
        map = mock(MapleMap.class);
        owner = character(OWNER, true, new Point(0, -200));
        alt = character(ALT, true, new Point(0, 0));
        when(owner.getMap()).thenReturn(map);
        when(alt.getMap()).thenReturn(map);
        when(owner.getMapId()).thenReturn(100_000_000);
        when(alt.getMapId()).thenReturn(100_000_000);
        helpers = mockStatic(BotHelpers.class);
        helpers.when(() -> BotHelpers.isBot(alt)).thenReturn(true);
        helpers.when(() -> BotHelpers.isBot(owner)).thenReturn(false);
        gc = mockStatic(GCMovement.class);
        chat = mockStatic(BotPartyChat.class);
        CompanionClimb.forget(ALT);
        CompanionStuck.forget(ALT);
        CompanionLines.forget(OWNER);
    }

    @AfterEach
    void tearDown() {
        chat.close();
        gc.close();
        helpers.close();
        System.clearProperty(Feature.FOLLOW_PLUS.property(false));
        CompanionClimb.forget(ALT);
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
    void aFollowTargetOnARopePlatformChoosesAClimb() {
        assertTrue(CompanionClimb.tick(alt, owner, T0), "climbing counts as following this poll");
        gc.verify(() -> GCMovement.move(alt, 0, -200), times(1));
        // the same target a moment later is not re-issued before the re-arm window
        CompanionClimb.tick(alt, owner, T0 + 200);
        gc.verify(() -> GCMovement.move(alt, 0, -200), times(1));
    }

    @Test
    void aNormalWalkIsLeftToTheOrdinaryFollow() {
        when(owner.getPosition()).thenReturn(new Point(40, 0));
        assertFalse(CompanionClimb.tick(alt, owner, T0));
        gc.verify(() -> GCMovement.move(eq(alt), anyInt(), anyInt()), never());
    }

    @Test
    void noProgressTimesOutToTheCatchUp() {
        gc.when(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString())).thenReturn(true);
        Party party = mock(Party.class);
        when(party.getMemberById(OWNER)).thenReturn(mock(PartyCharacter.class));
        when(alt.getParty()).thenReturn(party);

        for (long now = T0; now < T0 + CompanionClimb.CLIMB_TIMEOUT_MS; now += 1_000) {
            assertTrue(CompanionClimb.tick(alt, owner, now), "still climbing at " + now);
        }
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), never());

        CompanionClimb.tick(alt, owner, T0 + CompanionClimb.CLIMB_TIMEOUT_MS);
        gc.verify(() -> GCMovement.setDownBeside(eq(alt), anyInt(), anyInt(), anyString()), times(1));
        chat.verify(() -> BotPartyChat.sayInParty(alt, CompanionStuck.LINE), times(1));
    }

    @Test
    void theRopeTopNudgeStepsOffTheHead() {
        // climbing a rope whose head is at y=0, the owner standing on the ledge past it at x=60
        gc.when(() -> GCMovement.climbingRope(alt)).thenReturn(new Rope(0, 400, 0, false));
        when(alt.getPosition()).thenReturn(new Point(0, 10));
        when(owner.getPosition()).thenReturn(new Point(60, -10));

        assertTrue(CompanionClimb.tick(alt, owner, T0));
        gc.verify(() -> GCMovement.dismountRope(alt, 1), times(1));
    }

    @Test
    void aRegularIsUnchangedWithTheDefaults() {
        Character regular = character(3, false, new Point(0, 0));
        when(regular.getMap()).thenReturn(map);
        helpers.when(() -> BotHelpers.isBot(regular)).thenReturn(true);

        assertFalse(CompanionClimb.tick(regular, owner, T0));
        gc.verify(() -> GCMovement.move(eq(regular), anyInt(), anyInt()), never());

        System.setProperty(Feature.FOLLOW_PLUS.property(false), "true");
        assertTrue(CompanionClimb.tick(regular, owner, T0));
        gc.verify(() -> GCMovement.move(regular, 0, -200), times(1));
        CompanionClimb.forget(3);
    }
}
