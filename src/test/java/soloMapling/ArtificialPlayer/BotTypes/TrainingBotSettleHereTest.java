package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** t-bt-2 staging: a staged training bot is free to move at once instead of racing its own first trip. */
class TrainingBotSettleHereTest {

    private static final int SPAWN_MAP = 105050100;   // Ant Tunnel 2, where the crew of the 11-02 TEST run stood
    private static final int PICKED_MAP = 106010105;  // the map its own first decision was walking it to

    @Test
    void aStagedBotGrindsWhereItStandsInsteadOfRacingItsFirstTrip() throws Exception {
        Staged staged = new Staged();
        try (var index = mockStatic(MapMobIndex.class); var chooser = mockStatic(TrainingMapChooser.class);
             var movement = mockStatic(GCMovement.class)) {
            index.when(() -> MapMobIndex.mobCount(SPAWN_MAP)).thenReturn(6);
            index.when(() -> MapMobIndex.level(SPAWN_MAP)).thenReturn(23);
            assertTrue(staged.bot.settleHere(), "a bot standing on a field map can be left grinding there");
            assertEquals("GRIND", staged.phase(),
                    "free at once: the check's blocker reads 'on its way somewhere (go_train)' otherwise");
            assertEquals(SPAWN_MAP, staged.trainMap());
            assertTrue(staged.stayPut(), "its own next decision must not walk it off the map the check staged");
            movement.verify(() -> GCMovement.cancelTravel(staged.chr), times(1));
        }
    }

    @Test
    void aMoblessSpawnMapKeepsTheBotInTownInsteadOfWalkingItSomewhere() throws Exception {
        Staged staged = new Staged();
        try (var index = mockStatic(MapMobIndex.class); var chooser = mockStatic(TrainingMapChooser.class);
             var movement = mockStatic(GCMovement.class)) {
            index.when(() -> MapMobIndex.mobCount(SPAWN_MAP)).thenReturn(0);
            assertTrue(staged.bot.settleHere(), "a town map holds the bot in town: nothing to grind does not mean walk");
            assertEquals("IN_TOWN", staged.phase(), "still a free phase, so the crew can move at once");
            assertTrue(staged.stayPut());
            movement.verify(() -> GCMovement.cancelTravel(staged.chr), times(1));
        }
    }

    @Test
    void aBotStillStartingIsOnlyPinnedSoItsOwnInitRunsFirst() throws Exception {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_777_084);
        chr.setName("JustSpawned");
        chr.setLevel(60);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        when(map.getId()).thenReturn(SPAWN_MAP);
        chr.setMap(map);
        TrainingBot bot = spy(new TrainingBot(chr));
        doNothing().when(bot).nudgeSoon(anyLong());
        bot.setRunning(true);
        try (var index = mockStatic(MapMobIndex.class); var chooser = mockStatic(TrainingMapChooser.class);
             var movement = mockStatic(GCMovement.class)) {
            assertTrue(bot.settleHere());
            assertEquals("INIT", phaseOf(bot),
                    "a fresh bot keeps its own init; the pin settles it on the decision after it");
            assertTrue(stayPutOf(bot));
            movement.verifyNoInteractions();
        }
    }

    private static String phaseOf(TrainingBot bot) throws Exception {
        Field f = TrainingBot.class.getDeclaredField("phase");
        f.setAccessible(true);
        return f.get(bot).toString();
    }

    private static boolean stayPutOf(TrainingBot bot) throws Exception {
        Field f = TrainingBot.class.getDeclaredField("stayPutOnly");
        f.setAccessible(true);
        return f.getBoolean(bot);
    }

    /** A live-shaped training bot caught in the first trip its own brain started. */
    private static final class Staged {
        final Character chr = Character.getDefault(new BotClient(0, 0));
        final TrainingBot bot;

        Staged() throws Exception {
            chr.setID(1_000_777_083);
            chr.setName("PickleCurse");
            chr.setLevel(60);
            server.maps.MapleMap map = mock(server.maps.MapleMap.class);
            when(map.getId()).thenReturn(SPAWN_MAP);
            chr.setMap(map);
            bot = spy(new TrainingBot(chr));
            doNothing().when(bot).nudgeSoon(anyLong());
            bot.setRunning(true);
            field("phase").set(bot, TrainingBot.Phase.GO_TRAIN);
            field("currentTrainMapId").setInt(bot, PICKED_MAP);
        }

        String phase() throws Exception { return field("phase").get(bot).toString(); }

        int trainMap() throws Exception { return field("currentTrainMapId").getInt(bot); }

        boolean stayPut() throws Exception { return field("stayPutOnly").getBoolean(bot); }

        private static Field field(String name) throws Exception {
            Field f = TrainingBot.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }
    }
}
