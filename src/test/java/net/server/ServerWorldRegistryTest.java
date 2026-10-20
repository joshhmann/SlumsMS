package net.server;

import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 41: worlds are found by id, not by their place in the list, so Bera (1) can run without a world 0. */
class ServerWorldRegistryTest {
    private final Server server = Server.getInstance();

    private static World world(int id) {
        World w = mock(World.class);
        when(w.getId()).thenReturn(id);
        return w;
    }

    @BeforeEach
    @AfterEach
    void reset() {
        server.resetServerWorlds();
    }

    @Test
    void beraAloneIsWorldOneAndHome() {
        World bera = world(1);
        assertTrue(server.registerWorld(bera, 0, Map.of(1, "203.0.113.1:7585"), "Welcome to Bera!"));

        assertSame(bera, server.getWorld(1));
        assertNull(server.getWorld(0));
        assertEquals(1, server.getWorldsSize());
        assertEquals(2, server.getWorldIdBound());
        assertEquals(1, server.getHomeWorldId());
        assertEquals(java.util.Set.of(1), server.getOpenChannels(1));
        assertTrue(server.getOpenChannels(0).isEmpty());
        assertNull(server.getChannel(0, 1));
        assertEquals(1, server.worldRecommendedList().size());
        assertEquals(1, server.worldRecommendedList().get(0).getLeft());
        assertTrue(server.getWorldPlayerRanking(1).isEmpty());   // no ranking loaded yet: empty, not an exception
    }

    @Test
    void anIdCannotRunTwiceAndWorldsRegisterInSlotOrder() {
        assertTrue(server.registerWorld(world(1), 0, Map.of(), ""));
        assertFalse(server.registerWorld(world(1), 1, Map.of(), ""));
        assertFalse(server.registerWorld(world(0), 0, Map.of(), ""));   // slot 0 is taken
        assertTrue(server.registerWorld(world(0), 1, Map.of(), ""));

        assertEquals(1, server.getHomeWorldId());   // the first world started, not the lowest id
        assertEquals(2, server.getWorldsSize());
        assertEquals(1, server.getWorlds().get(0).getId());
    }

    @Test
    void botsLiveInTheHomeWorld() {
        assertTrue(server.registerWorld(world(1), 0, Map.of(), ""));
        assertEquals(1, soloMapling.server.SoloMaplingConstants.GameConstants.homeWorld());
        assertSame(server.getWorld(1), soloMapling.server.SoloMaplingUtilities.world());
    }

    @Test
    void withNoWorldTheHomeWorldIsZero() {
        assertEquals(0, server.getHomeWorldId());
        assertEquals(0, server.getWorldIdBound());
        assertNull(server.getWorld(0));
    }
}
