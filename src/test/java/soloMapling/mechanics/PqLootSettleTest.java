package soloMapling.mechanics;

import client.BotClient;
import client.Character;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapItem;
import server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;

import java.awt.Point;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 1.6.5 b331 part (a) (TEST 2026-11-06, Client lane diag2: owner's client closes 4 of 4 in ZPQ stage 1 with an owned
 * alt, 0 of 2 solo; none with -Dslums.mechanics.zakumpq=false). The alt's ZakumMinesSolver hits a chest, the chest
 * drops a key (DROP_ITEM_FROM_MAPOBJECT 0x10C) and the alt, already standing in reach, picks it up on the next tick
 * (REMOVE_ITEM_FROM_MAP 0x10D) while the owner's client is still playing the drop's fly-out. The PQ loot path had no
 * settle delay; grind loot has always waited {@link soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker#SETTLE_MS}.
 * A PQ bot now waits the same before it picks a drop up.
 */
class PqLootSettleTest {
    static final int KEY = 4001015; // ZPQ chest 2112012's drop (zakumpq.yaml)

    @Test
    void aBotInReachDoesNotPickUpAChestDropThatIsStillFlyingOut() throws Exception {
        Character bot = botAt(100, 0);
        MapItem drop = chestDrop(bot, System.currentTimeMillis()); // just dropped
        try (MockedStatic<DropCommands> drops = mockStatic(DropCommands.class)) {
            pickUp(bot, drop, System.currentTimeMillis());
            drops.verify(() -> DropCommands.botLootSingleDrop(any(), any()), never());
        }
    }

    @Test
    void aBotInReachPicksUpAChestDropThatHasSettled() throws Exception {
        Character bot = botAt(100, 0);
        long now = System.currentTimeMillis();
        MapItem drop = chestDrop(bot, now - 2_000);
        try (MockedStatic<DropCommands> drops = mockStatic(DropCommands.class)) {
            pickUp(bot, drop, now);
            drops.verify(() -> DropCommands.botLootSingleDrop(bot, drop), times(1));
        }
    }

    static Character botAt(int x, int y) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(1_000_331_001);
        c.setPosition(new Point(x, y));
        return c;
    }

    static MapItem chestDrop(Character hitter, long droppedAt) {
        MapItem mi = new MapItem(new Item(KEY, (short) 0, (short) 1), new Point(110, 0), mock(Reactor.class), hitter,
                hitter.getClient(), (byte) 1, false);
        mi.setDropTime(droppedAt);
        return mi;
    }

    static void pickUp(Character bot, MapItem drop, long now) throws Exception {
        StepExecutor executor = new StepExecutor();
        Method m = StepExecutor.class.getDeclaredMethod("pickUp", Character.class, StepExecutor.Beat.class,
                MapItem.class, long.class);
        m.setAccessible(true);
        m.invoke(executor, bot, new StepExecutor.Beat(), drop, now);
    }
}
