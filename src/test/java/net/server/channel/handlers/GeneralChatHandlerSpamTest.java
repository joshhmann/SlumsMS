package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Auto-QA rv-4 lost a `!qa do revenge` line: the harness sends commands 400 ms apart, but the chat gate stamps lines
 * with the server's tick clock, which only moves every UPDATE_INTERVAL (777 ms on the servers), so both lines could
 * land in the same tick and the second was dropped without a trace. A GM's own lines must never be dropped.
 */
class GeneralChatHandlerSpamTest {

    static final long NOW = 1_000_000L;

    @Test
    void twoPlayerLinesInsideOneTickDropTheSecond() {
        assertTrue(GeneralChatHandler.spamBlocked(false, NOW, NOW), "same tick");
        assertTrue(GeneralChatHandler.spamBlocked(false, NOW, NOW + 199), "inside the gate");
        assertFalse(GeneralChatHandler.spamBlocked(false, NOW, NOW + GeneralChatHandler.CHAT_SPAM_MS), "gate elapsed");
    }

    @Test
    void aGmsLinesWithinOneTickAreKept() {
        assertFalse(GeneralChatHandler.spamBlocked(true, NOW, NOW), "a GM types as fast as the client allows");
        assertFalse(GeneralChatHandler.spamBlocked(true, NOW, NOW + 1));
    }
}
