package soloMapling.Economy;

import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SaleRulesTest {

    @BeforeEach
    void setUp() {
        new EconomyTestSupport();
    }

    @AfterEach
    void tearDown() {
        EconomyTestSupport.reset();
    }

    @Test
    void whatABotNeverSells() {
        assertEquals("Chaos or White Scroll", SaleRules.whyNot(EconomyTestSupport.item(2049100, 1)));
        assertEquals("Chaos or White Scroll", SaleRules.whyNot(EconomyTestSupport.item(2340000, 1)));
        assertEquals("cash item", SaleRules.whyNot(EconomyTestSupport.item(5220000, 1)));
        assertEquals("boss-only drop", SaleRules.whyNot(EconomyTestSupport.item(4000999, 1)));
        assertEquals("untradeable", SaleRules.whyNot(EconomyTestSupport.item(4031999, 1)));
        Item expiring = EconomyTestSupport.item(2040804, 1);
        expiring.setExpiration(System.currentTimeMillis() + 60_000);
        assertEquals("expires", SaleRules.whyNot(expiring));
        assertNull(SaleRules.whyNot(EconomyTestSupport.item(2040804, 1)), "a glove scroll is fine");
        assertNull(SaleRules.whyNot(EconomyTestSupport.item(4004000, 10)), "an ore stack is fine");
    }
}
