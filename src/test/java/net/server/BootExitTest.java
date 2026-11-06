package net.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.6.5 P0-4: a failed boot must end the process with status 1, so systemd marks the unit failed. The world-setup
 * catch in {@code Server.init} logged "Syntax error in 'world.ini'" and exited 0, and a throw after ThreadManager
 * started left a JVM running with no login port.
 */
class BootExitTest {

    private final IntConsumer original = Server.bootExit;
    private final List<Integer> exits = new ArrayList<>();

    @AfterEach
    void restore() {
        Server.bootExit = original;
    }

    @Test
    void aBootThatThrowsExitsWithStatusOne() {
        Server.bootExit = exits::add;
        Server.boot(() -> {
            throw new IllegalStateException("Setting up the worlds failed", new java.net.BindException("Address already in use"));
        });
        assertEquals(List.of(1), exits);
    }

    @Test
    void aBootThatCompletesDoesNotExit() {
        Server.bootExit = exits::add;
        Server.boot(() -> { });
        assertEquals(List.of(), exits);
    }
}
