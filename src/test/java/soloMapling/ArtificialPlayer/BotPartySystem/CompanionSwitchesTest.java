package soloMapling.ArtificialPlayer.BotPartySystem;

import config.ServerConfig;
import config.YamlConfig;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 ticket 99: everything off by default; each switch opens its own commands, item ones only with an alt out. */
class CompanionSwitchesTest {

    @Test
    void offByDefault() {
        ServerConfig fresh = new ServerConfig();
        assertFalse(fresh.COMPANION_ORDERS_ENABLED);
        assertFalse(fresh.COMPANION_TRADES_ENABLED);
        assertFalse(fresh.COMPANION_UPGRADES_ENABLED);
        assertFalse(fresh.COMPANION_SUPPLIES_ENABLED);
        assertFalse(fresh.OWNED_ALTS_INVITE_SUMMON_ENABLED);
    }

    /** 1.6.3 s-lanes: the lanes order is opened by its own {@code -D}, off unless a server starts with it. */
    @Test
    void theLanesSwitchOpensTheLanesOrder() {
        String was = System.getProperty("slums.alts.lanes");
        try {
            System.clearProperty("slums.alts.lanes");
            assertFalse(CompanionSwitches.lanes());
            assertFalse(CompanionSwitches.allowed(true).contains(Verb.LANES));
            System.setProperty("slums.alts.lanes", "false");
            assertFalse(CompanionSwitches.lanes(), "only true turns it on");
            System.setProperty("slums.alts.lanes", "true");
            assertTrue(CompanionSwitches.lanes());
            assertTrue(CompanionSwitches.any());
            assertTrue(CompanionSwitches.stances());
            assertTrue(CompanionSwitches.allowed(true).contains(Verb.LANES));
        } finally {
            if (was == null) {
                System.clearProperty("slums.alts.lanes");
            } else {
                System.setProperty("slums.alts.lanes", was);
            }
        }
    }

    @Test
    void eachSwitchOpensItsOwnCommands() {
        ServerConfig c = YamlConfig.config.server;
        boolean[] was = {c.COMPANION_ORDERS_ENABLED, c.COMPANION_TRADES_ENABLED, c.COMPANION_UPGRADES_ENABLED,
                c.COMPANION_SUPPLIES_ENABLED};
        try {
            c.COMPANION_ORDERS_ENABLED = false;
            c.COMPANION_TRADES_ENABLED = false;
            c.COMPANION_UPGRADES_ENABLED = false;
            c.COMPANION_SUPPLIES_ENABLED = false;
            assertEquals(BotCommandParser.BASE, CompanionSwitches.allowed(true));
            assertFalse(CompanionSwitches.any());
            c.COMPANION_ORDERS_ENABLED = true;
            assertTrue(CompanionSwitches.allowed(false).contains(Verb.FORMATION));
            c.COMPANION_TRADES_ENABLED = true;
            assertFalse(CompanionSwitches.allowed(false).contains(Verb.TRADE), "no alt out: nobody could hand over");
            assertTrue(CompanionSwitches.allowed(true).contains(Verb.TRADE));
            assertFalse(CompanionSwitches.allowed(true).contains(Verb.NEED));
            c.COMPANION_SUPPLIES_ENABLED = true;
            assertTrue(CompanionSwitches.allowed(true).contains(Verb.NEED));
            assertFalse(CompanionSwitches.allowed(true).contains(Verb.UPGRADES));
        } finally {
            c.COMPANION_ORDERS_ENABLED = was[0];
            c.COMPANION_TRADES_ENABLED = was[1];
            c.COMPANION_UPGRADES_ENABLED = was[2];
            c.COMPANION_SUPPLIES_ENABLED = was[3];
        }
    }
}
