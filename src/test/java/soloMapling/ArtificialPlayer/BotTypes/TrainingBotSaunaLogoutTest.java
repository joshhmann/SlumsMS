package soloMapling.ArtificialPlayer.BotTypes;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

// 1.3.0 first-boot rehearsal: a Sleepywood bot logged out mid-sauna still had its sauna errand armed on the shared
// scheduler; the dwell's end sent the departed bot travelling to the hotel and the poller warped it every 300 ms.
// Stopping the bot (BotLogout -> manuallyStopBot / removeBotFromServer) must disarm the errand.
class TrainingBotSaunaLogoutTest {

    private static Character newBot() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(1_000_777_002);
        chr.setName("SleepyBot");
        chr.setLevel(30);
        chr.setMap(org.mockito.Mockito.mock(server.maps.MapleMap.class));
        return chr;
    }

    private static Field field(String name) throws Exception {
        Field f = TrainingBot.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test
    @SuppressWarnings("unchecked")
    void stoppingABotMidSaunaDisarmsTheWayOut() throws Exception {
        Character chr = newBot();
        TrainingBot train = new TrainingBot(chr);
        field("saunaTripActive").set(train, true);

        try (MockedStatic<GCMovement> movement = mockStatic(GCMovement.class)) {
            train.stopScheduledTask();
            assertFalse((Boolean) field("saunaTripActive").get(train));

            // The dwell timer fires after the logout: leaveSauna and the door-walk cap must do nothing.
            Method leave = TrainingBot.class.getDeclaredMethod("leaveSauna", Character.class);
            leave.setAccessible(true);
            leave.invoke(train, chr);
            Method enter = TrainingBot.class.getDeclaredMethod("enterSaunaFromHotel", Character.class);
            enter.setAccessible(true);
            enter.invoke(train, chr);

            movement.verify(() -> GCMovement.travel(any(Character.class), anyInt(), any(Consumer.class)),
                    org.mockito.Mockito.never());
            movement.verify(() -> GCMovement.move(any(Character.class), anyInt(), anyInt(), any(Runnable.class)),
                    org.mockito.Mockito.never());
        }
    }
}
