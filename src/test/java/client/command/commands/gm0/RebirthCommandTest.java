package client.command.commands.gm0;

import client.command.CommandsExecutor;
import constants.id.MapId;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.6.6.1 (owner 8 Nov 2026): @rebirth opens Wyatt the Reborn's rebirth talk, only in Base of Roots. The released
 * client sends no talk for a click on Wyatt (1300009), so without it nobody could make an Essence Stone or be reborn.
 */
class RebirthCommandTest {

    @Test
    void itIsAPlayerCommand() {
        var tiers = CommandsExecutor.getInstance().getGmCommands();
        assertTrue(tiers.get(0).getLeft().contains("rebirth"), "player tier: " + tiers.get(0).getLeft());
    }

    @Test
    void inBaseOfRootsItOpensWyattsTalk() {
        AtomicInteger opened = new AtomicInteger();
        assertNull(RebirthCommand.handle(true, MapId.MAPLEROOT_HUB, opened::incrementAndGet));
        assertEquals(993910000, MapId.MAPLEROOT_HUB, "Wyatt's map");
        assertEquals(1, opened.get());
    }

    @Test
    void anywhereElseItIsRefusedAndOpensNothing() {
        AtomicInteger opened = new AtomicInteger();
        for (int map : new int[]{MapId.HENESYS, 211070000, 993910001, 0}) {
            assertEquals(RebirthCommand.NOT_HERE, RebirthCommand.handle(true, map, opened::incrementAndGet), "map " + map);
        }
        assertEquals(RebirthCommand.OFF, RebirthCommand.handle(false, MapId.MAPLEROOT_HUB, opened::incrementAndGet));
        assertEquals(0, opened.get());
    }
}
