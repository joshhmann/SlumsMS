package net.server.channel.handlers;

import constants.id.ItemId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Vega's Spell used to eat any USE item (a potion would do) and enhance the equip anyway. */
class VegaSpellScrollTest {
    @Test void aPotionIsNotAScroll() {
        assertFalse(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_10, 2000000, null));
        assertFalse(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_10, 2000000, 0));
    }

    @Test void theScrollMustMatchTheSpell() {
        assertTrue(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_10, 2040002, 10));
        assertTrue(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_60, 2040001, 60));
        assertFalse(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_10, 2040000, 100));
        assertFalse(UseCashItemHandler.vegaAccepts(ItemId.VEGAS_SPELL_60, 2040002, 10));
    }

    @Test void onlyTheTwoSpellsCount() {
        assertFalse(UseCashItemHandler.vegaAccepts(5000000, 2040002, 10));
    }
}
