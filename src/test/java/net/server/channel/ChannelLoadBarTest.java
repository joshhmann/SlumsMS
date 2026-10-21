package net.server.channel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.4.1: bots weigh a fraction of a player on the world-list load bar, so ~1,000 bots don't fill every channel. */
class ChannelLoadBarTest {
    @AfterEach
    void clearProperty() {
        System.clearProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY);
    }

    @Test
    void realPlayersCountInFull() {
        assertEquals(400, Channel.loadBar(50, 0, 100, 0.25));
        assertEquals(800, Channel.loadBar(100, 0, 100, 0.25));
    }

    @Test
    void botsCountAsAQuarterByDefault() {
        // 125 bots on a channel (1,000 over 8) used to read full at CHANNEL_LOAD 100; now it is under a third.
        assertEquals(250, Channel.loadBar(0, 125, 100, Channel.botLoadWeight()));
        assertEquals(700, Channel.loadBar(50, 150, 100, 0.25)); // 50 + 150/4 = 87.5 of 100
    }

    @Test
    void theBarNeverRunsPastFull() {
        assertEquals(800, Channel.loadBar(90, 2000, 100, 0.25));
        assertEquals(800, Channel.loadBar(500, 0, 100, 0.25));
    }

    @Test
    void emptyIsZero() {
        assertEquals(0, Channel.loadBar(0, 0, 100, 0.25));
    }

    @Test
    void theWeightComesFromTheSystemPropertyAndIsClamped() {
        assertEquals(0.25, Channel.botLoadWeight());
        System.setProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY, "0.5");
        assertEquals(0.5, Channel.botLoadWeight());
        System.setProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY, "0");
        assertEquals(0.0, Channel.botLoadWeight());
        System.setProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY, "7");
        assertEquals(1.0, Channel.botLoadWeight());
        System.setProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY, "-1");
        assertEquals(0.25, Channel.botLoadWeight());
        System.setProperty(Channel.BOT_LOAD_WEIGHT_PROPERTY, "lots");
        assertEquals(0.25, Channel.botLoadWeight());
    }

    @Test
    void aZeroChannelLoadDoesNotDivideByZero() {
        assertEquals(800, Channel.loadBar(1, 0, 0, 0.25));
        assertEquals(0, Channel.loadBar(0, 0, 0, 0.25));
    }
}
