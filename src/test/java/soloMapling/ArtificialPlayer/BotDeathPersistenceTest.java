package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.5.0 rc3 pre-release review: a dead bot is not logged out, stored dead, or moved to another channel. */
class BotDeathPersistenceTest {

    private static final int ID = BotStore.BOT_ID_BASE + 4_242;

    @AfterEach
    void reset() {
        BotDeaths.clearBot(ID);
    }

    @SuppressWarnings("unchecked")
    private static void lieDead(int id) throws Exception {
        Field f = BotDeaths.class.getDeclaredField("lyingUntil");
        f.setAccessible(true);
        ((Map<Integer, Long>) f.get(null)).put(id, Long.MAX_VALUE);
    }

    private static Character bot() {
        Character chr = testutil.Mocks.chr("Corpse");
        when(chr.getId()).thenReturn(ID);
        return chr;
    }

    @Test
    void aBotIsNeverStoredDead() {
        assertEquals(50, BotStore.savedHp(0, 900), "the respawn HP");
        assertEquals(30, BotStore.savedHp(0, 30), "never above its max");
        assertEquals(1, BotStore.savedHp(-5, 0));
        assertEquals(412, BotStore.savedHp(412, 900));
    }

    @Test
    void aRowStoredDeadComesBackAtHalfHp() {
        assertEquals(450, BotStore.restoredHp(0, 900));
        assertEquals(1, BotStore.restoredHp(0, 1));
        assertEquals(17, BotStore.restoredHp(17, 900));
    }

    @Test
    void aDeadBotMayNotLogOut() throws Exception {
        Character chr = bot();
        BotSM sm = mock(BotSM.class);
        when(sm.getChr()).thenReturn(chr);
        assertTrue(BotPopulationDirector.mayLogOut(sm));
        lieDead(ID);
        assertFalse(BotPopulationDirector.mayLogOut(sm));
    }

    @Test
    void aDeadBotIsNotMovedToAnotherChannel() throws Exception {
        Character chr = bot();
        when(chr.getMap()).thenReturn(mock(MapleMap.class));
        when(chr.getClient()).thenReturn(mock(client.Client.class));
        lieDead(ID);
        assertFalse(BotChannels.moveToChannel(chr, 2));
    }

    // Review P2: the bot's own changeMap could run between leaving the old channel's storage and joining the new one
    // (it then finds no storage and disconnects the bot). moveToChannel holds the warp lock every changeMap takes.
    @Test
    void aMapChangeWaitsForTheWarpLock() throws Exception {
        Character chr = Character.getDefault(new client.BotClient(0, 0));
        MapleMap target = mock(MapleMap.class);
        AtomicBoolean entered = new AtomicBoolean();
        when(target.getId()).thenAnswer(call -> {
            entered.set(true);
            throw new IllegalStateException("stop here");
        });
        chr.getWarpLock().lock();
        Thread warp = new Thread(() -> {
            try {
                chr.changeMap(target, new Point(0, 0));
            } catch (RuntimeException expected) {
                // the mock map ends the warp once it is inside
            }
        });
        try {
            warp.start();
            warp.join(300);
            assertFalse(entered.get(), "changeMap ran while the channel move held the lock");
        } finally {
            chr.getWarpLock().unlock();
        }
        warp.join(5_000);
        assertTrue(entered.get());
    }

    // 1.5.0 rc4: only bots take the warp lock. A player's warp can warp others while it runs (party quest scripts, a
    // mini dungeon leader, the Ariant arena closing); two of those racing could deadlock on each other's warp lock.
    @Test
    void aPlayersMapChangeTakesNoWarpLock() throws Exception {
        Character chr = Character.getDefault(mock(client.Client.class));
        MapleMap target = mock(MapleMap.class);
        AtomicBoolean entered = new AtomicBoolean();
        when(target.getId()).thenAnswer(call -> {
            entered.set(true);
            throw new IllegalStateException("stop here");
        });
        chr.getWarpLock().lock();
        Thread warp = new Thread(() -> {
            try {
                chr.changeMap(target, new Point(0, 0));
            } catch (RuntimeException expected) {
                // the mock map ends the warp once it is inside
            }
        });
        try {
            warp.start();
            warp.join(5_000);
            assertTrue(entered.get(), "a player's changeMap waited for the warp lock");
        } finally {
            chr.getWarpLock().unlock();
        }
    }
}
