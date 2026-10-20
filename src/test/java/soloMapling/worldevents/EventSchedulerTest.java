package soloMapling.worldevents;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPopulationCurve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSchedulerTest {

    private static final long MIN = 60_000L;

    private static EventScheduler.Settings on() {
        return new EventScheduler.Settings(true, 30_000, 120, 1, 30 * MIN, 0.2);
    }

    private static EventTemplate template(String id, double weight, int minutes, int cooldown) {
        return new EventTemplate(id, EventTemplate.Kind.HOT_SPOT, id, weight, minutes, cooldown, 0, 0, 5,
                List.of(EventTemplate.Spot.map(100)), List.of(), 0, 0, 1.5, List.of(), true);
    }

    /** A Random whose doubles are always this value. */
    private static Random fixed(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    void offByDefault() {
        System.clearProperty("solomapling.worldEvents");
        assertFalse(EventScheduler.Settings.fromSystemProperties().auto());
        EventScheduler.Settings off = on().withAuto(false);
        assertNull(EventScheduler.decide(off, List.of(template("a", 1, 20, 0)), List.of(), Map.of(), 0, 0, 1.0,
                fixed(0)), "nothing starts by itself while the scheduler is off");
    }

    @Test
    void propertiesAreReadAndCapped() {
        try {
            System.setProperty("solomapling.worldEvents", "true");
            System.setProperty("solomapling.worldEvents.maxActive", "9");
            System.setProperty("solomapling.worldEvents.meanGapMinutes", "60");
            EventScheduler.Settings s = EventScheduler.Settings.fromSystemProperties();
            assertTrue(s.auto());
            assertEquals(EventScheduler.MAX_ACTIVE_CAP, s.maxActive(), "never more than 2 at once");
            assertEquals(60, s.meanGapMinutes());
        } finally {
            System.clearProperty("solomapling.worldEvents");
            System.clearProperty("solomapling.worldEvents.maxActive");
            System.clearProperty("solomapling.worldEvents.meanGapMinutes");
        }
    }

    @Test
    void chanceFollowsTheCurveAndIsZeroWhenQuiet() {
        EventScheduler.Settings s = on();
        assertEquals(0, EventScheduler.startChance(s, 0.1), "below quietBelow nothing starts");
        assertEquals(0, EventScheduler.startChance(s, 0.0));
        double low = EventScheduler.startChance(s, 0.3);
        double peak = EventScheduler.startChance(s, 1.0);
        assertTrue(low > 0 && peak > low, "busier hours, more events");
        // At peak: one event per 120 minutes = 240 ticks of 30 s.
        assertEquals(1 - Math.exp(-1.0 / 240), peak, 1e-9);
    }

    @Test
    void respectsCapGapAndCooldown() {
        EventScheduler.Settings s = on();
        List<EventTemplate> ts = List.of(template("a", 1, 20, 60), template("b", 1, 20, 60));
        long now = 1_000 * MIN;
        Random yes = fixed(0.0);
        assertNotNull(EventScheduler.decide(s, ts, List.of(), Map.of(), 0, now, 1.0, yes));
        List<EventScheduler.Running> one = List.of(new EventScheduler.Running(1, "a", now + 10 * MIN));
        assertNull(EventScheduler.decide(s, ts, one, Map.of(), 0, now, 1.0, yes), "max one at once");
        assertNull(EventScheduler.decide(s, ts, List.of(), Map.of(), now - 10 * MIN, now, 1.0, yes),
                "30 minutes between starts");
        Map<String, Long> ended = Map.of("a", now - 30 * MIN, "b", now - 30 * MIN);
        assertNull(EventScheduler.decide(s, ts, List.of(), ended, 0, now, 1.0, yes), "both resting");
        Map<String, Long> oneRested = Map.of("a", now - 30 * MIN, "b", now - 61 * MIN);
        assertEquals("b", EventScheduler.decide(s, ts, List.of(), oneRested, 0, now, 1.0, yes).id());
        assertNull(EventScheduler.decide(s, ts, List.of(), Map.of(), 0, now, 1.0, fixed(0.999)), "dice say no");
    }

    @Test
    void neverRunsTheSameTemplateTwiceOrADisabledOne() {
        EventTemplate a = template("a", 1, 20, 0);
        EventTemplate off = new EventTemplate("off", EventTemplate.Kind.HOT_SPOT, "off", 5, 20, 0, 0, 0, 5,
                List.of(EventTemplate.Spot.map(1)), List.of(), 0, 0, 1.5, List.of(), false);
        EventTemplate gmOnly = template("gm", 0, 20, 0);
        List<EventScheduler.Running> running = List.of(new EventScheduler.Running(1, "a", 99));
        assertTrue(EventScheduler.eligible(List.of(a, off, gmOnly), running, Map.of(), 0).isEmpty());
    }

    @Test
    void everyEventEnds() {
        List<EventScheduler.Running> running = List.of(new EventScheduler.Running(1, "a", 100),
                new EventScheduler.Running(2, "b", 200));
        assertTrue(EventScheduler.overdue(running, 99).isEmpty());
        assertEquals(1, EventScheduler.overdue(running, 100).size());
        assertEquals(2, EventScheduler.overdue(running, 10_000).size());
        assertTrue(EventScheduler.roomFor(running.subList(0, 1)));
        assertFalse(EventScheduler.roomFor(running), "the GM cap is 2 at once too");
    }

    @Test
    void weightedPick() {
        List<EventTemplate> ts = List.of(template("heavy", 3, 20, 0), template("light", 1, 20, 0));
        Random rng = new Random(7);
        int heavy = 0;
        for (int i = 0; i < 4000; i++) {
            if (EventScheduler.pick(ts, rng).id().equals("heavy")) {
                heavy++;
            }
        }
        assertEquals(0.75, heavy / 4000.0, 0.03);
        assertNull(EventScheduler.pick(List.of(), rng));
    }

    /**
     * A simulated month on the default curve and settings: a gentle rate (a handful a day), leaning to the evening,
     * none in the quiet early morning, never two at once.
     */
    @Test
    void aSimulatedMonthIsGentleAndLeansToPeakHours() {
        BotPopulationCurve curve = BotPopulationCurve.parse(BotPopulationCurve.DEFAULT, 1.0);
        EventScheduler.Settings s = on();
        List<EventTemplate> ts = List.of(template("inv", 3, 25, 240), template("boss", 4, 30, 90),
                template("hot", 4, 30, 120), template("chest", 2, 20, 180), template("npc", 2, 30, 180));
        Random rng = new Random(21);
        List<EventScheduler.Running> running = new ArrayList<>();
        Map<String, Long> ended = new HashMap<>();
        long lastStart = 0;
        int[] byHour = new int[24];
        int total = 0;
        int days = 30;
        for (long now = 0; now < days * 24 * 60 * MIN; now += s.tickMs()) {
            for (EventScheduler.Running r : EventScheduler.overdue(running, now)) {
                running.remove(r);
                ended.put(r.templateId(), now);
            }
            assertTrue(running.size() <= 1);
            double hour = (now / (double) (60 * MIN)) % 24;
            EventTemplate t = EventScheduler.decide(s, ts, running, ended, lastStart, now, curve.fractionAt(hour), rng);
            if (t != null) {
                running.add(new EventScheduler.Running(total, t.id(), now + t.durationMs()));
                lastStart = now;
                byHour[(int) hour]++;
                total++;
            }
        }
        double perDay = total / (double) days;
        assertTrue(perDay >= 2 && perDay <= 10, "events per day: " + perDay);
        int evening = byHour[18] + byHour[19] + byHour[20] + byHour[21];
        int morning = byHour[6] + byHour[7] + byHour[8] + byHour[9];
        assertTrue(evening > 2 * morning, "evening " + evening + " vs morning " + morning);
        // 6:00 is the trough (0.15 of peak, under quietBelow 0.2): nothing starts around it.
        assertEquals(0, byHour[5] + byHour[6], "quiet hours");
    }
}
