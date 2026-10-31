package server.maps;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;
import tools.Pair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * s-maproute round 4 (b253): a reactor whose item event names no item must not throw out of the map load.
 *
 * <p>Map 706030000 is a landing spot in {@code soloMapling/map-routing.tsv} (row {@code 706030000 home00 -> 691000100})
 * and spawns reactor 7492024. Every state of that image is a type-100 event ("waits for an item") with no item child,
 * so {@code DataTool.getIntConvert("0", event)} in {@code ReactorFactory} dereferenced a null and
 * {@code MapFactory.loadMapFromWz} could not load the map at all. Three reactors in the data are built like it
 * (3109000, 7492024, 9260002); one map spawns one of them.
 */
class ReactorLoadTest {

    private static final int ITEMLESS_REACTOR = 7492024;
    private static final int ORDINARY_REACTOR = 6938001;      // a stage pass: waits for item 4034370, one of it

    @Test
    void aReactorWhoseItemEventNamesNoItemStillLoads() {
        WzTestData.assumePresent("Reactor.wz");
        ReactorStats stats = ReactorFactory.getReactor(ITEMLESS_REACTOR);

        assertNotNull(stats, "reactor 7492024's image is there; only its event is incomplete");
        assertEquals(999, stats.getType((byte) 0),
                "no item can satisfy the state, so it is inert - the same value a state without an event gets");
        assertNull(stats.getReactItem((byte) 0, (byte) 0));
    }

    @Test
    void theRuntimeSpawnPathAgreesWithTheMapLoadPath() {
        WzTestData.assumePresent("Reactor.wz");
        assertEquals(999, ReactorFactory.getReactorS(ITEMLESS_REACTOR).getType((byte) 0),
                "MapleMap spawns reactors through getReactorS and would throw there instead");
    }

    @Test
    void aReactorThatWaitsForARealItemStillWaitsForIt() {
        WzTestData.assumePresent("Reactor.wz");
        ReactorStats stats = ReactorFactory.getReactor(ORDINARY_REACTOR);

        assertEquals(100, stats.getType((byte) 0));
        assertEquals(new Pair<>(4034370, 1), stats.getReactItem((byte) 0, (byte) 0),
                "skipping an incomplete item event must not turn item reactors off");
    }
}
