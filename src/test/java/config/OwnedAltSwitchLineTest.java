package config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** AL-4 (ticket 183): one boot line names every owned-alt switch, and the shipped conf states the master switch. */
class OwnedAltSwitchLineTest {

    /** The six companion switches the 1.6.3 conf turns on, plus the master switch it used to leave out. */
    static final List<String> SWITCHES = List.of(
            "OWNED_ALTS_ENABLED",
            "OWNED_ALTS_SUMMON_ENABLED",
            "COMPANION_ORDERS_ENABLED",
            "COMPANION_TRADES_ENABLED",
            "COMPANION_UPGRADES_ENABLED",
            "COMPANION_SUPPLIES_ENABLED",
            "OWNED_ALTS_INVITE_SUMMON_ENABLED");

    @Test void theBootLineNamesEveryOwnedAltSwitch() {
        String line = new ServerConfig().ownedAltSwitchesLine();
        for (String name : SWITCHES) {
            assertTrue(line.contains(name + "="), name + " is missing from the boot line: " + line);
        }
    }

    @Test void theBootLineShowsTheValueEachSwitchActuallyBootedWith() {
        ServerConfig conf = new ServerConfig();
        conf.OWNED_ALTS_ENABLED = true;
        conf.COMPANION_TRADES_ENABLED = true;
        String line = conf.ownedAltSwitchesLine();
        assertTrue(line.contains("OWNED_ALTS_ENABLED=true"), line);
        assertTrue(line.contains("COMPANION_TRADES_ENABLED=true"), line);
        assertTrue(line.contains("COMPANION_ORDERS_ENABLED=false"), "a switch left at its default: " + line);
        assertTrue(line.contains("OWNED_ALTS_SUMMON_ENABLED=true"), "the code default: " + line);
    }

    @Test void theShippedReleaseConfStatesTheMasterSwitch() throws Exception {
        // every release conf from 1.6.3 on (archreview, Nov 5: the test only read 1.6.3, and 1.6.4 had dropped it)
        for (String v : new String[] {"1.6.3", "1.6.4"}) {
            Path conf = Path.of("docs/release/" + v + ".conf");
            assumeTrue(Files.exists(conf), "release conf not in this checkout");
            String text = Files.readString(conf);
            assertTrue(text.contains("OWNED_ALTS_ENABLED=true"),
                    v + ": the master switch is stated in SERVER_SET, not left to its false default");
            assertTrue(text.contains("OWNED_ALTS_SUMMON_ENABLED=true"), v + ": and the summon switch it shipped with");
        }
    }
}
