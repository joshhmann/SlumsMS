package client.command.commands.gm3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.6.0 (owner 2026-10-26): !botwatch start [n] [minutes]. */
class BotWatchCommandTest {
    @Test
    void startTakesBotsAndMinutesWithDefaults() {
        assertArrayEquals(new int[]{40, 30}, BotWatchCommand.parse(new String[]{"start"}));
        assertArrayEquals(new int[]{20, 30}, BotWatchCommand.parse(new String[]{"start", "20"}));
        assertArrayEquals(new int[]{20, 10}, BotWatchCommand.parse(new String[]{"start", "20", "10"}));
    }

    @Test
    void nonsenseAndOutOfRangeAreRefused() {
        assertNull(BotWatchCommand.parse(new String[]{"start", "lots"}));
        assertNull(BotWatchCommand.parse(new String[]{"start", "0"}));
        assertNull(BotWatchCommand.parse(new String[]{"start", "500"}));
        assertNull(BotWatchCommand.parse(new String[]{"start", "40", "999"}));
    }
}
