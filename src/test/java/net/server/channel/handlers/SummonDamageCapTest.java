package net.server.channel.handlers;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.WeaponType;
import org.junit.jupiter.api.Test;
import server.StatEffect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.4.2 Cosmic parity: summon hits are clamped to the stat-based estimate again (the repack had commented out the
 * clamp, so a packet-edited summon could hit for anything).
 */
class SummonDamageCapTest {

    @Test
    void aHitAboveTheEstimateIsCutToIt() {
        assertEquals(5_000, SummonDamageHandler.capSummonDamage(2_000_000_000, 5_000));
    }

    @Test
    void aLegitHitIsUntouched() {
        assertEquals(4_321, SummonDamageHandler.capSummonDamage(4_321, 5_000));
        assertEquals(0, SummonDamageHandler.capSummonDamage(0, 5_000), "a miss stays a miss");
    }

    @Test
    void weaponSummonEstimateScalesWithTheOwnersDamage() {
        StatEffect hawk = mock(StatEffect.class);
        when(hawk.getWatk()).thenReturn((short) 100);
        Character owner = mock(Character.class);
        when(owner.getTotalWatk()).thenReturn(200);
        Inventory equipped = mock(Inventory.class);
        when(owner.getInventory(InventoryType.EQUIPPED)).thenReturn(equipped);
        when(owner.calculateMaxBaseDamage(200, WeaponType.SWORD1H)).thenReturn(1_000);

        // Cosmic: maxBase >= 438 -> 0.054 per summon watk point: 1000 * 0.054 * 100
        assertEquals(5_400, SummonDamageHandler.calcMaxDamage(hawk, owner, false));
    }

    @Test
    void magicSummonEstimateScalesWithTheOwnersMagic() {
        StatEffect ifrit = mock(StatEffect.class);
        when(ifrit.getMatk()).thenReturn((short) 150);
        Character owner = mock(Character.class);
        when(owner.getTotalMagic()).thenReturn(800);
        when(owner.calculateMaxBaseMagicDamage(800)).thenReturn(10_000);

        assertEquals(75_000, SummonDamageHandler.calcMaxDamage(ifrit, owner, true));
    }
}
