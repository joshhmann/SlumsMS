package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TEST bot watch 2026-10-27 (Maple3 on Ludibrium): the Free Market's doors are scripted portals the world graph cannot
 * see, so a trip there warped, and with a player in town the held warp failed after 45 s. A bot walks through a door.
 */
class GCTravelMarketDoorTest {

    static final int LUDIBRIUM = 220000000, HENESYS = 100000000, FM = GCTravel.FREE_MARKET;

    @Test
    void aTownsMarketPortalIsTheDoorIntoTheFreeMarket() {
        assertTrue(GCTravel.isMarketDoor("market02", LUDIBRIUM, FM), "Ludibrium's market00 portal runs market02");
        assertFalse(GCTravel.isMarketDoor("market02", LUDIBRIUM, HENESYS), "not on a trip to the market");
        assertFalse(GCTravel.isMarketDoor("market00", LUDIBRIUM, FM), "market00 leads out of the market");
        assertFalse(GCTravel.isMarketDoor(null, LUDIBRIUM, FM));
        assertFalse(GCTravel.isMarketDoor("subway_in2", LUDIBRIUM, FM));
    }

    @Test
    void theFreeMarketsDoorLeadsOutWhereverTheTripGoes() {
        assertTrue(GCTravel.isMarketDoor("classicfm_exit", FM, 105070300));
        assertFalse(GCTravel.isMarketDoor("classicfm_exit", FM, FM));
        assertFalse(GCTravel.isMarketDoor("market02", FM, 105070300), "no town door inside the market");
    }

    @Test
    void everyPortalScriptIntoTheFreeMarketIsADoor() throws IOException {
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("scripts/portal"))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String js = Files.readString(f);
                String name = f.getFileName().toString().replaceFirst("\\.js$", "");
                if (js.contains("warp(910000000") && !GCTravel.MARKET_ENTRY_SCRIPTS.contains(name)) {
                    missing.add(name);
                }
            }
        }
        assertEquals(List.of(), missing);
    }
}
