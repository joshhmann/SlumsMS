package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// Ticket 03: the default relocation policy, with the world (maps around each hub, route fares, the clock) faked so
// each rule shows on its own: when a bot moves on, where to, what it must afford, and the world-wide pace.
class TrainingTravelTest {

    private static final int HENESYS = 100000000;
    private static final int ORBIS = 200000000;
    private static final int LEAFRE = 240000000;
    private static final int EL_NATH = 211000000;

    // Maps around each hub: Orbis ~40, Leafre ~80, El Nath only a Lion King's Castle wall (rebirth 1) at 80.
    private static final Map<Integer, List<TrainingMap>> AROUND = Map.of(
            ORBIS, List.of(new TrainingMap(200010000, 40, 2), new TrainingMap(200010100, 42, 3)),
            LEAFRE, List.of(new TrainingMap(240010200, 80, 3), new TrainingMap(240020100, 82, 4)),
            EL_NATH, List.of(new TrainingMap(211060100, 80, 5)),
            HENESYS, List.of(new TrainingMap(100020000, 10, 1)));

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private int fare = 5_000;

    private TrainingTravel.RegionTravel policy() {
        return new TrainingTravel.RegionTravel(hub -> AROUND.getOrDefault(hub, List.of()),
                (from, to, rebirths, job) -> fare, clock::get);
    }

    @BeforeEach
    void regions() {
        TrainingRegions.install(new TrainingRegions.Config(List.of(
                region("Victoria Island", 100000000, 110000000, HENESYS, 1),
                region("Orbis", 200000000, 201000000, ORBIS, 25),
                region("El Nath", 211000000, 212000000, EL_NATH, 45),
                region("Leafre", 240000000, 241000000, LEAFRE, 70)),
                new TrainingRegions.Tuning(0.05, 0.80, 2, 20)));
    }

    @AfterEach
    void restore() {
        TrainingRegions.install(null);
        TrainingTravel.install(null);
    }

    private static TrainingRegions.Region region(String name, int min, int max, int hub, int minLevel) {
        return new TrainingRegions.Region(name, List.of(new TrainingRegions.Window(min, max)), List.of(), List.of(hub),
                minLevel, 255, 1.0, true);
    }

    private static TrainingTravel.Request request(int level, int meso, boolean localInBand, int rebirths) {
        return new TrainingTravel.Request(null, level, rebirths, 100, meso, HENESYS, HENESYS, localInBand,
                Math.max(1, level - 25), level + 12);
    }

    @Test
    void anOutgrownBotUsuallyMovesToTheRegionThatFits() {
        TrainingTravel.Destination d = policy().choose(request(75, 100_000, false, 0), 0.5, 0.5);

        assertNotNull(d);
        assertEquals("Leafre", d.region());
        assertEquals(LEAFRE, d.hubMapId());
    }

    @Test
    void anOutgrownBotSometimesStays() {
        assertNull(policy().choose(request(75, 100_000, false, 0), 0.85, 0.5));
    }

    @Test
    void aBotWhoseRegionStillFitsRarelyWanders() {
        assertNull(policy().choose(request(40, 100_000, true, 0), 0.06, 0.5));
        assertEquals("Orbis", policy().choose(request(40, 100_000, true, 0), 0.04, 0.5).region());
    }

    @Test
    void aRegionBelowItsTravelLevelDoesNotAttract() {
        // Level 30: Orbis (25+) fits, Leafre (70+) never even if its maps were in band.
        TrainingTravel.Destination d = policy().choose(request(30, 100_000, false, 0), 0.1, 0.99);

        assertEquals("Orbis", d.region());
    }

    @Test
    void aBotThatCantPayTheFaresStays() {
        fare = 40_000;

        assertNull(policy().choose(request(75, 39_999, false, 0), 0.1, 0.5));
        assertNotNull(policy().choose(request(75, 40_000, false, 0), 0.1, 0.5));
    }

    @Test
    void anUnreachableRegionIsSkipped() {
        fare = -1;

        assertNull(policy().choose(request(75, 100_000, false, 0), 0.1, 0.5));
    }

    @Test
    void gatedMapsDoNotMakeARegionFit() {
        // Level 80 with Leafre removed: El Nath's only in-band map is a Lion King's Castle wall.
        TrainingRegions.install(new TrainingRegions.Config(List.of(
                region("Victoria Island", 100000000, 110000000, HENESYS, 1),
                region("El Nath", 211000000, 212000000, EL_NATH, 45)),
                new TrainingRegions.Tuning(0.05, 0.80, 2, 20)));

        assertNull(policy().choose(request(80, 100_000, false, 0), 0.1, 0.5));
        assertEquals("El Nath", policy().choose(request(80, 100_000, false, 1), 0.1, 0.5).region());
    }

    @Test
    void departuresArePacedWorldWide() {
        TrainingTravel.RegionTravel p = policy();

        assertNotNull(p.choose(request(75, 100_000, false, 0), 0.1, 0.5));
        assertNotNull(p.choose(request(75, 100_000, false, 0), 0.1, 0.5));
        assertNull(p.choose(request(75, 100_000, false, 0), 0.1, 0.5), "two a minute in this table");
        clock.addAndGet(60_000L);
        assertNotNull(p.choose(request(75, 100_000, false, 0), 0.1, 0.5));
    }

    @Test
    void ticket31CanReplaceThePolicy() {
        TrainingTravel.install(r -> new TrainingTravel.Destination("Leafre", LEAFRE));

        assertEquals(LEAFRE, TrainingTravel.consider(request(10, 0, true, 0)).hubMapId());

        TrainingTravel.install(r -> {
            throw new IllegalStateException("broken path");
        });
        assertNull(TrainingTravel.consider(request(10, 0, true, 0)), "a broken policy keeps bots home");
    }
}
