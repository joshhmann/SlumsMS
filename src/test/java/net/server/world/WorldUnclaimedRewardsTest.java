package net.server.world;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.expeditions.ExpeditionBossLog.BossLogEntry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ticket 185: World's unclaimed-reward ledger did nothing. addUnclaimed never inserted (compute just returned the
 * value it was handed) and removeUnclaimed rewrote the value it had just read, so hasUnclaimedExpedition was always
 * false and the 14 scripts/npc call sites that clear a character's unclaimed boss log were no-ops.
 */
class WorldUnclaimedRewardsTest {
    private static final int FRESH_CID = 2_000_000_501;
    private static final int TWICE_CID = 2_000_000_502;

    private static World world;
    private static int weddingInterval, overtimeInterval;

    @BeforeAll
    static void startWorld() {
        // the constructor registers the world's periodic tasks, so both the timer and two intervals need a value
        server.TimerManager.getInstance().start();
        config.ServerConfig server = config.YamlConfig.config.server;
        weddingInterval = server.WEDDING_RESERVATION_INTERVAL;
        overtimeInterval = server.MAP_DAMAGE_OVERTIME_INTERVAL;
        server.WEDDING_RESERVATION_INTERVAL = 30;
        server.MAP_DAMAGE_OVERTIME_INTERVAL = 10_000;

        world = new World(1, 0, "", 1, 1, 1, 1, 1, 1, 1);
    }

    @AfterAll
    static void stopWorld() {
        world.shutdown();
        server.TimerManager.getInstance().stop();
        config.ServerConfig server = config.YamlConfig.config.server;
        server.WEDDING_RESERVATION_INTERVAL = weddingInterval;
        server.MAP_DAMAGE_OVERTIME_INTERVAL = overtimeInterval;
    }

    @Test
    void aFreshCharacterGainsThenLosesItsUnclaimedEntry() {
        assertFalse(world.hasUnclaimedExpedition(FRESH_CID));   // nothing recorded yet

        world.addUnclaimed(BossLogEntry.ZAKUM, FRESH_CID);
        assertTrue(world.hasUnclaimedExpedition(FRESH_CID));

        world.removeUnclaimed(BossLogEntry.ZAKUM, FRESH_CID);
        assertFalse(world.hasUnclaimedExpedition(FRESH_CID));
        // the per-character map is dropped once empty, not left behind for the next reader
        assertNull(world.removeUnclaimedRewards(FRESH_CID));
        // and a second removal is a no-op, the way a repeated NPC talk would hit it
        assertDoesNotThrow(() -> world.removeUnclaimed(BossLogEntry.ZAKUM, FRESH_CID));
        assertFalse(world.hasUnclaimedExpedition(FRESH_CID));
    }

    @Test
    void aCharacterCalledTwiceCarriesItsCountAndClearsTheSameWay() {
        world.addUnclaimed(BossLogEntry.HORNTAIL, TWICE_CID);
        world.addUnclaimed(BossLogEntry.HORNTAIL, TWICE_CID);
        assertTrue(world.hasUnclaimedExpedition(TWICE_CID));

        // the repeat merges to 2 rather than resetting to 1, and the reader hands back what was left
        Map<BossLogEntry, Short> left = world.removeUnclaimedRewards(TWICE_CID);
        assertNotNull(left);
        assertEquals(1, left.size());
        assertEquals(Short.valueOf((short) 2), left.get(BossLogEntry.HORNTAIL));
        assertFalse(world.hasUnclaimedExpedition(TWICE_CID));
        assertNull(world.removeUnclaimedRewards(TWICE_CID));

        // removeUnclaimed drops the entry outright, and the now-empty map with it
        world.addUnclaimed(BossLogEntry.HORNTAIL, TWICE_CID);
        world.addUnclaimed(BossLogEntry.HORNTAIL, TWICE_CID);
        world.removeUnclaimed(BossLogEntry.HORNTAIL, TWICE_CID);
        assertFalse(world.hasUnclaimedExpedition(TWICE_CID));
        assertNull(world.removeUnclaimedRewards(TWICE_CID));
    }
}
