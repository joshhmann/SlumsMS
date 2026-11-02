package server.maps;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;
import tools.Pair;

import java.lang.reflect.Field;
import java.util.Map;

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
 *
 * <p>Ticket 164: the two loaders then disagreed about a state node that carries no event at all. {@code getReactorS}
 * (the runtime spawn path) registers such a state as {@link ReactorFactory}-inert (type 999), {@code getReactor} (the
 * map-load path) registered nothing, so {@code ReactorStats.getType} answered -1 and {@code Reactor.isActive()} was
 * false: every click was swallowed before the script ran. The cases below pin both loaders to the same answer, and pin
 * the missing-image guard that keeps one absent Reactor.wz image from failing a whole map.
 */
class ReactorLoadTest {

    private static final int ITEMLESS_REACTOR = 7492024;
    private static final int ORDINARY_REACTOR = 6938001;      // a stage pass: waits for item 4034370, one of it

    /** Orbis PQ piece box: root states 0-5, and only state 0 has no event. */
    private static final int ORBIS_PIECE_BOX = 2002011;
    /** 2002012/2002013 are {@code info/link} stubs onto 2002011, so they must inherit that answer. */
    private static final int[] LINKED_PIECE_BOXES = {2002012, 2002013};
    /** Pirate PQ treasure chest (the map names it treasure1): state 0 has no event, state 1 waits for item 4031437. */
    private static final int PIRATE_TREASURE_CHEST = 2512001;
    /** A CWKPQ sigil tile on map 610030400: state 0 is a 1x1 canvas, only state 1 has the event. */
    private static final int CWKPQ_SIGIL_TILE = 6109016;
    /**
     * Reactor.wz has no image for 1012003 at all - map 1700 places it five times - so the map-load path used to throw
     * a NullPointerException out of {@code MapFactory.loadMapFromWz}, which has no per-reactor catch.
     */
    private static final int IMAGELESS_REACTOR = 1012003;

    /** The factory memoises per id, so each case has to start from a cold cache to really load the image again. */
    @BeforeEach
    void forgetWhatTheFactoryMemoises() throws Exception {
        Field cache = ReactorFactory.class.getDeclaredField("reactorStats");
        cache.setAccessible(true);
        ((Map<?, ?>) cache.get(null)).clear();
    }

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

    @Test
    void aFirstStateWithoutAnEventIsInertOnTheMapLoadPathToo() throws Exception {
        WzTestData.assumePresent("Reactor.wz");

        int[] statelessFirstStates = {ORBIS_PIECE_BOX, LINKED_PIECE_BOXES[0], LINKED_PIECE_BOXES[1],
                PIRATE_TREASURE_CHEST, CWKPQ_SIGIL_TILE};
        for (int rid : statelessFirstStates) {
            forgetWhatTheFactoryMemoises();
            int runtimeType = ReactorFactory.getReactorS(rid).getType((byte) 0);

            forgetWhatTheFactoryMemoises();
            ReactorStats loaded = ReactorFactory.getReactor(rid);

            assertEquals(999, loaded.getType((byte) 0),
                    "state 0 of reactor " + rid + " carries no event, so the map load path must register it inert");
            assertEquals(runtimeType, loaded.getType((byte) 0),
                    "both loaders must give state 0 of reactor " + rid + " the same type");
            assertEquals(-1, loaded.getTimeout((byte) 0),
                    "an inert state must not schedule a timeout for reactor " + rid);
        }
    }

    @Test
    void aReactorWithNoImageLoadsInertInsteadOfFailingTheMap() throws Exception {
        WzTestData.assumePresent("Reactor.wz");

        ReactorStats stats = ReactorFactory.getReactor(IMAGELESS_REACTOR);

        assertNotNull(stats, "one absent image must not take the map down with it");
        assertEquals(-1, stats.getType((byte) 0),
                "there is no state to hit, so the placed reactor stays inert - the value Reactor.isActive() rejects");
        assertEquals(-1, stats.getTimeout((byte) 0));
    }
}
