package soloMapling.dashboard;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 35: the economy estimate used until the ticket 15 ledger is merged. */
class DerivedEconomyTest {

    private static final long T0 = 1_792_400_000_000L;

    private static LiveSnapshot.BotSample at(int id, int map, long meso) {
        return new LiveSnapshot.BotSample(id, "b" + id, 20, "WARRIOR", map, "training", "grind", meso);
    }

    @Test
    void risesAreIntakeOnTheBotsMapAndFallsAreSpending() {
        DerivedEconomy e = new DerivedEconomy(ZoneOffset.UTC);
        e.observe(List.of(at(1, 100, 500), at(2, 200, 1_000)), T0);
        assertTrue(e.mapsToday().isEmpty(), "the first sight of a bot is its baseline");

        e.observe(List.of(at(1, 100, 800), at(2, 200, 400)), T0 + 60_000);
        e.observe(List.of(at(1, 101, 1_000), at(2, 200, 400)), T0 + 120_000);

        Map<Integer, Long> intake = new java.util.HashMap<>();
        e.mapsToday().forEach(m -> intake.put(m.mapId(), m.mesos()));
        assertEquals(Map.of(100, 300L, 101, 200L, 200, 0L), intake);
        assertEquals(Map.of(200, 600L), e.spentToday());
        assertEquals(500L, e.botsToday().get(0).mesos());
    }

    @Test
    void aBotThatLeftStartsOverAndTheDayResets() {
        DerivedEconomy e = new DerivedEconomy(ZoneOffset.UTC);
        e.observe(List.of(at(1, 100, 500)), T0);
        e.observe(List.of(), T0 + 60_000);                       // logged out
        e.observe(List.of(at(1, 100, 9_999)), T0 + 120_000);     // back with more (a trade while away)
        assertTrue(e.mapsToday().isEmpty(), "what happened while it was away is not intake");

        e.observe(List.of(at(1, 100, 10_999)), T0 + 180_000);
        assertEquals(1, e.mapsToday().size());
        e.observe(List.of(at(1, 100, 11_999)), T0 + 86_400_000L);
        assertEquals(1_000L, e.mapsToday().get(0).mesos(), "a new day counts from zero");
    }
}
