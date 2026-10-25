package net.server.channel.handlers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TakeDamageHandlerMesoGuardTest {
    @Test
    void mesoGuardCostIsPercentOfHalvedDamage() {
        assertEquals(500, TakeDamageHandler.mesoGuardLoss(1000, 50));
        assertEquals(0, TakeDamageHandler.mesoGuardLoss(0, 50));
        assertEquals(33, TakeDamageHandler.mesoGuardLoss(100, 33));
    }
}
