package soloMapling.ArtificialPlayer.BotTownSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalSpaceNpcTest {
    @AfterEach
    void clearReservations() {
        PersonalSpace.clearForTest();
    }

    private MapleMap townWithNpc(int npcY) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(100000000);
        when(map.getAllPlayers()).thenReturn(List.of());
        MapObject npc = mock(MapObject.class);
        when(npc.getType()).thenReturn(MapObjectType.NPC);
        when(npc.getPosition()).thenReturn(new Point(0, npcY));
        when(map.getMapObjects()).thenReturn(List.of(npc));
        return map;
    }

    @Test
    void destinationsAndSpawnsKeepClearOfAnNpcEvenWhenNoCharactersArePresent() {
        MapleMap map = townWithNpc(274);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            Point dest = PersonalSpace.freeSpotNear(map, new Point(0, 274), 1_000_005_001);
            assertTrue(Math.abs(dest.x) >= PersonalSpace.CLEAR_PX, "destination must clear the NPC");
            PersonalSpace.clearForTest();
            Point spawn = PersonalSpace.spreadSpawns(map, List.of(new Point(0, 274))).getFirst();
            assertTrue(Math.abs(spawn.x) >= PersonalSpace.CLEAR_PX, "spawn must clear the NPC");
        }
    }

    @Test
    void aRestingBotStepsOffAnNpcOnItsOwnGround() {
        MapleMap map = townWithNpc(274);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_005_002);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 274));
        try (var movement = mockStatic(GCMovement.class); var tuning = mockStatic(BotCombatTuning.class)) {
            tuning.when(BotCombatTuning::spread).thenReturn(true);
            movement.when(() -> GCMovement.isEnabled(bot)).thenReturn(true);
            movement.when(() -> GCMovement.sweepPose(bot)).thenReturn("GROUNDED");
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            assertTrue(PersonalSpace.keepNow(bot), "an NPC alone must cause a resting bot to step");
            movement.verify(() -> GCMovement.move(eq(bot), intThat(x -> Math.abs(x) >= PersonalSpace.CLEAR_PX),
                    eq(274), isNull()));
        }
    }

    @Test
    void anNpcOnAnotherPlatformDoesNotDisplaceADestination() {
        MapleMap map = townWithNpc(174);
        try (var movement = mockStatic(GCMovement.class)) {
            movement.when(() -> GCMovement.groundPointBelow(eq(map), anyInt(), anyInt()))
                    .thenAnswer(c -> new Point(c.getArgument(1), 274));
            assertEquals(new Point(0, 274), PersonalSpace.freeSpotNear(map, new Point(0, 274), 1_000_005_003));
        }
    }
}
