package soloMapling.Economy;

import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SaleRulesTest {

    @BeforeEach
    void setUp() {
        new EconomyTestSupport();
    }

    @AfterEach
    void tearDown() {
        SaleRules.useConnectionSourceForTest(null);
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

    @Test
    void aBossOnlyListThePoolRefusedIsNotRemembered() throws SQLException {
        // b269 review: the read runs through the gate, and the gate refuses bot work rather than queueing it. The
        // refusal was caught by the same handler as a broken table and cached, so one busy moment at boot left the
        // process selling boss-only drops until a restart. A refusal says nothing about the table: the next caller
        // asks again.
        // economy.yaml's own list is what a refused read still has to apply.
        EconomyConfig.useForTest(java.util.Map.of("saleRules.bossOnly", java.util.Map.of("4000999", 1)));
        AtomicBoolean busy = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        Connection quiet = emptyPool();
        SaleRules.useConnectionSourceForTest(() -> {
            reads.incrementAndGet();
            if (busy.get()) {
                throw new soloMapling.ArtificialPlayer.BotDbGate.RefusedException("the bots' share is busy");
            }
            return quiet;
        });

        assertEquals("boss-only drop", SaleRules.whyNot(EconomyTestSupport.item(4000999, 1)),
                "a refused read still applies economy.yaml's list");
        assertEquals(1, reads.get(), "the first call went to the store");

        busy.set(false);
        SaleRules.bossOnly();
        assertEquals(2, reads.get(), "the refused read was not cached: the next call asks the store again");
    }

    /** A pool whose every query answers "no rows". */
    private static Connection emptyPool() throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(false);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rows);
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(ps);
        return con;
    }
}
