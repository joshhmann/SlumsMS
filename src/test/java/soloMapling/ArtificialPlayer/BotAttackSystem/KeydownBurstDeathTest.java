package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.server.MethodScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 bot audit (B22): a held keydown skill (Hurricane, Rapid Fire) keeps shooting every KEYDOWN_SHOT_MS from a
 * delayed task, and that task only checked the map. Death changes neither the map nor the client, so the rest of the
 * hold went on dealing damage, EXP and loot from a corpse - visible on a watched map, and a way to earn past death.
 * The hold now ends at the first shot that finds the bot dead.
 */
class KeydownBurstDeathTest {

    /* A held keydown plan: two shots, no prepare packet (the hold is what is under test, not the pose packet). */
    private static BotSwingPlan hold() {
        return new BotSwingPlan(BotAttackProfile.Route.RANGED, 0, 0, 0x14, false, 6, false, 0, 2, 1, 1, 1472063);
    }

    private static Monster mob(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    @Test
    void aDeadBotDealsNoMoreKeydownShotsAndNoLoot() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_940);
        when(bot.getMap()).thenReturn(map);
        when(bot.isAlive()).thenReturn(true);
        Monster first = mob(100100), second = mob(100101);

        try (MockedStatic<MethodScheduler> sched = Mockito.mockStatic(MethodScheduler.class)) {
            List<Runnable> delayed = new ArrayList<>();
            sched.when(() -> MethodScheduler.runAfterDelay(any(Runnable.class), anyLong())).thenAnswer(inv -> {
                delayed.add(inv.getArgument(0));
                return null;
            });

            assertFalse(BotAttackEffects.strike(bot, hold(), Map.of(first, List.of(10)), 0, (short) 0,
                    () -> Map.of(second, List.of(10))));
            assertEquals(1, delayed.size(), "fixture: the hold scheduled its second shot");

            when(bot.isAlive()).thenReturn(false); // died mid-hold: still on the same map, so the map check passes

            delayed.get(0).run(); // the second shot comes due
        }

        verify(second, never()).damage(any(), anyLong(), anyBoolean());
        verify(map, never()).killMonster(any(Monster.class), any(Character.class), anyBoolean(), anyInt());
        // the first shot's packet, then the release that ends the keydown pose for the viewers
        verify(map, times(2)).broadcastMessage(eq(bot), any(Packet.class), eq(false));
    }
}
