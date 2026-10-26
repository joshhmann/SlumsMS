package soloMapling.worldevents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 world events: the daily plan (headline at 8 PM PT, small ones at peak hours, at most 2 at once, restarts). */
class EventDirectorTest {

    static final ZoneId PT = ZoneId.of("America/Los_Angeles");
    static final long MIN = 60_000L;

    static EventTemplate template(String id, EventTemplate.Slot slot, int minutes, int cooldown) {
        return new EventTemplate(id, EventTemplate.Kind.HOT_SPOT, id, 1, minutes, cooldown, 0, 0, 5,
                List.of(EventTemplate.Spot.map(100 + id.length()), EventTemplate.Spot.map(200 + id.length())),
                List.of(), 0, 0, 1.5, List.of(), true, slot, 0, List.of(), 0);
    }

    static List<EventTemplate> templates() {
        return List.of(template("cake", EventTemplate.Slot.HEADLINE, 30, 600),
                template("invade", EventTemplate.Slot.HEADLINE, 30, 600),
                template("perion", EventTemplate.Slot.HEADLINE, 30, 600),
                template("hot", EventTemplate.Slot.SMALL, 30, 120),
                template("boss", EventTemplate.Slot.SMALL, 30, 90),
                template("chest", EventTemplate.Slot.SMALL, 20, 180),
                template("wed", EventTemplate.Slot.MANUAL, 6, 0));
    }

    static long local(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, PT).toInstant().toEpochMilli();
    }

    static EventDirector director() {
        return new EventDirector(EventDirector.Config.defaults(PT));
    }

    @Test
    void headlineIsEightPmLocalOnBothSidesOfDst() {
        // US DST ends Sun Nov 1 2026 and starts Sun Mar 8 2026: 8 PM stays 8 PM, the UTC hour moves
        for (LocalDate day : List.of(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 9))) {
            long at = EventDirector.at(day, LocalTime.of(20, 0), PT);
            ZonedDateTime local = Instant.ofEpochMilli(at).atZone(PT);
            assertEquals(20, local.getHour(), day.toString());
            assertEquals(day, local.toLocalDate());
        }
        int pdtUtc = Instant.ofEpochMilli(EventDirector.at(LocalDate.of(2026, 10, 31), LocalTime.of(20, 0), PT))
                .atZone(ZoneId.of("UTC")).getHour();
        int pstUtc = Instant.ofEpochMilli(EventDirector.at(LocalDate.of(2026, 11, 1), LocalTime.of(20, 0), PT))
                .atZone(ZoneId.of("UTC")).getHour();
        assertEquals(3, pdtUtc, "8 PM PDT is 03:00 UTC");
        assertEquals(4, pstUtc, "8 PM PST is 04:00 UTC");

        // and the director plans the day it is in, on the DST day itself
        EventDirector d = director();
        d.tick(local(2026, 11, 1, 9, 0), templates(), 0, Map.of(), new Random(1));
        assertEquals("2026-11-01", d.state().day());
        assertEquals(local(2026, 11, 1, 20, 0), d.state().headlineAt());
        assertTrue(EventDirector.clock(d.state().headlineAt(), PT).startsWith("8:00 PM PST (04:00 UTC)"),
                EventDirector.clock(d.state().headlineAt(), PT));
    }

    /** Ticks every 30 s from {@code from} to {@code to}, starting what the director asks; returns every action. */
    static List<EventDirector.Action> run(EventDirector d, long from, long to, List<long[]> running, Random rng) {
        List<EventDirector.Action> all = new ArrayList<>();
        Map<String, Long> ended = new HashMap<>();
        for (long now = from; now <= to; now += 30_000) {
            final long t = now;
            running.removeIf(r -> r[1] <= t);
            for (EventDirector.Action a : d.tick(now, templates(), running.size(), ended, rng)) {
                all.add(a);
                switch (a.kind()) {
                    case START_HEADLINE -> {
                        assertTrue(running.size() < 2, "never a third event");
                        running.add(new long[]{now, now + 30 * MIN});
                        d.headlineStarted(a.templateId());
                    }
                    case START_SMALL -> {
                        assertTrue(running.size() < 1, "a small one never takes the last place");
                        running.add(new long[]{now, now + 30 * MIN});
                        ended.put(a.templateId(), now + 30 * MIN);
                        d.smallStarted();
                    }
                    default -> {
                    }
                }
                assertTrue(running.size() <= EventScheduler.MAX_ACTIVE_CAP, "at most 2 at once");
            }
        }
        return all;
    }

    @Test
    void warnsAtTenAndOneMinuteThenStartsTheHeadlineOnce() {
        EventDirector d = director();
        List<EventDirector.Action> acts = run(d, local(2026, 10, 26, 19, 40), local(2026, 10, 26, 21, 0),
                new ArrayList<>(), new Random(3));
        List<EventDirector.Action> warns = acts.stream().filter(a -> a.kind() == EventDirector.Kind.WARN).toList();
        assertEquals(List.of(10, 1), warns.stream().map(EventDirector.Action::minutesLeft).toList());
        List<EventDirector.Action> starts = acts.stream()
                .filter(a -> a.kind() == EventDirector.Kind.START_HEADLINE).toList();
        assertEquals(1, starts.size(), acts.toString());
        assertEquals(d.state().headlineTemplate(), starts.get(0).templateId());
        assertTrue(d.state().headlineMap() > 0, "the map is planned with the day");
        assertEquals("started", d.state().headlineStatus());
        assertTrue(templates().stream().anyMatch(t -> t.id().equals(starts.get(0).templateId())
                && t.slot() == EventTemplate.Slot.HEADLINE), "only a headline template");
    }

    @Test
    void aRestartNeitherRepeatsAWarningNorFiresTheHeadlineTwice(@TempDir Path dir) {
        Path file = dir.resolve("schedule.json");
        EventDirector before = director();
        Random rng = new Random(5);
        // up at 19:45: the 10-minute warning goes out at 19:50, then the server restarts at 19:55
        List<EventDirector.Action> early = run(before, local(2026, 10, 26, 19, 45), local(2026, 10, 26, 19, 55),
                new ArrayList<>(), rng);
        assertEquals(1, early.stream().filter(a -> a.kind() == EventDirector.Kind.WARN).count());
        assertTrue(before.save(file));

        EventDirector after = director();
        assertTrue(after.load(file));
        assertEquals(before.state().headlineTemplate(), after.state().headlineTemplate());
        List<EventDirector.Action> rest = run(after, local(2026, 10, 26, 19, 55, 30), local(2026, 10, 26, 20, 5),
                new ArrayList<>(), rng);
        assertEquals(List.of(1), rest.stream().filter(a -> a.kind() == EventDirector.Kind.WARN)
                .map(EventDirector.Action::minutesLeft).toList(), "only the 1-minute warning is left");
        assertEquals(1, rest.stream().filter(a -> a.kind() == EventDirector.Kind.START_HEADLINE).count());
        assertTrue(after.save(file));

        // a second restart at 20:10, during the headline: nothing again
        EventDirector again = director();
        assertTrue(again.load(file));
        List<EventDirector.Action> late = run(again, local(2026, 10, 26, 20, 10), local(2026, 10, 26, 20, 40),
                new ArrayList<>(List.<long[]>of(new long[]{0, local(2026, 10, 26, 20, 30)})), rng);
        assertTrue(late.stream().noneMatch(a -> a.kind() != EventDirector.Kind.START_SMALL), late.toString());
        assertEquals(List.of(before.state().headlineTemplate()), again.state().recentHeadlines());
    }

    static long local(int y, int m, int d, int h, int min, int sec) {
        return local(y, m, d, h, min) + sec * 1000L;
    }

    @Test
    void aPlanMadeAfterTheEveningSkipsItInsteadOfFiringLate() {
        EventDirector d = director();
        List<EventDirector.Action> acts = run(d, local(2026, 10, 26, 21, 0), local(2026, 10, 26, 23, 59),
                new ArrayList<>(), new Random(9));
        assertTrue(acts.stream().noneMatch(a -> a.kind() == EventDirector.Kind.START_HEADLINE
                || a.kind() == EventDirector.Kind.WARN), acts.toString());
        assertEquals("missed", d.state().headlineStatus());
    }

    @Test
    void aHeadlineThatFailsIsNotRetriedEveryTick() {
        EventDirector d = director();
        long now = local(2026, 10, 26, 20, 0);
        List<EventDirector.Action> first = d.tick(now, templates(), 0, Map.of(), new Random(1));
        assertTrue(first.stream().anyMatch(a -> a.kind() == EventDirector.Kind.START_HEADLINE));
        d.headlineFailed();
        assertTrue(d.tick(now + 30_000, templates(), 0, Map.of(), new Random(1)).stream()
                .noneMatch(a -> a.kind() == EventDirector.Kind.START_HEADLINE));
    }

    @Test
    void theHeadlineWaitsForRoomWithinItsGrace() {
        EventDirector d = director();
        long at = local(2026, 10, 26, 20, 0);
        d.tick(at - 60 * MIN, templates(), 0, Map.of(), new Random(1));
        assertTrue(d.tick(at, templates(), 2, Map.of(), new Random(1)).stream()
                .noneMatch(a -> a.kind() == EventDirector.Kind.START_HEADLINE), "two running: it waits");
        assertTrue(d.tick(at + 10 * MIN, templates(), 1, Map.of(), new Random(1)).stream()
                .anyMatch(a -> a.kind() == EventDirector.Kind.START_HEADLINE), "room again within the grace");
    }

    @Test
    void smallEventsAreOneToThreeInsidePeakWindowsAwayFromTheHeadline() {
        EventDirector.Config c = EventDirector.Config.defaults(PT);
        for (int seed = 0; seed < 300; seed++) {
            EventDirector d = new EventDirector(c);
            LocalDate day = LocalDate.of(2026, 10, 1).plusDays(seed % 60);
            long midnight = EventDirector.at(day, LocalTime.MIDNIGHT, PT);
            d.tick(midnight + 1000, templates(), 0, Map.of(), new Random(seed));
            List<Long> small = d.state().smallAt();
            assertTrue(small.size() >= 1 && small.size() <= 3, seed + ": " + small.size());
            long headline = d.state().headlineAt();
            for (int i = 0; i < small.size(); i++) {
                long t = small.get(i);
                LocalTime lt = Instant.ofEpochMilli(t).atZone(PT).toLocalTime();
                assertTrue(c.peaks().stream().anyMatch(w -> !lt.isBefore(w.from()) && lt.isBefore(w.to())),
                        seed + ": " + lt + " is in a peak window");
                assertTrue(Math.abs(t - headline) >= EventDirector.HEADLINE_CLEARANCE_MS, seed + ": clear of 8 PM");
                if (i > 0) {
                    assertTrue(t - small.get(i - 1) >= 30 * MIN, "sorted and spread");
                }
            }
        }
    }

    @Test
    void aWholeDayNeverRunsMoreThanTwoAndStartsEverySmallOneInItsWindow() {
        for (int seed = 0; seed < 40; seed++) {
            EventDirector d = director();
            List<long[]> running = new ArrayList<>();
            List<EventDirector.Action> acts = run(d, local(2026, 10, 27, 0, 1), local(2026, 10, 27, 23, 59),
                    running, new Random(seed));
            long smalls = acts.stream().filter(a -> a.kind() == EventDirector.Kind.START_SMALL).count();
            assertTrue(smalls >= 1 && smalls <= 3, seed + ": " + smalls);
            assertEquals(1, acts.stream().filter(a -> a.kind() == EventDirector.Kind.START_HEADLINE).count());
            for (EventDirector.Action a : acts) {
                if (a.kind() == EventDirector.Kind.START_SMALL) {
                    assertTrue(templates().stream().anyMatch(t -> t.id().equals(a.templateId())
                            && t.slot() == EventTemplate.Slot.SMALL), a.toString());
                }
            }
        }
    }

    @Test
    void theHeadlineRotates() {
        EventDirector d = director();
        List<String> picks = new ArrayList<>();
        for (int day = 0; day < 12; day++) {
            long at = EventDirector.at(LocalDate.of(2026, 11, 1).plusDays(day), LocalTime.of(20, 0), PT);
            for (EventDirector.Action a : d.tick(at, templates(), 0, Map.of(), new Random(day))) {
                if (a.kind() == EventDirector.Kind.START_HEADLINE) {
                    picks.add(a.templateId());
                    d.headlineStarted(a.templateId());
                }
            }
        }
        assertEquals(12, picks.size());
        for (int i = 1; i < picks.size(); i++) {
            assertNotEquals(picks.get(i - 1), picks.get(i), "never the same two nights running: " + picks);
            if (i > 1) {
                assertNotEquals(picks.get(i - 2), picks.get(i), "three headline templates: a 3-night rotation " + picks);
            }
        }
    }

    @Test
    void savedStateRoundTripsAndABadFileIsAFreshStart(@TempDir Path dir) throws Exception {
        EventDirector d = director();
        d.tick(local(2026, 10, 26, 12, 0), templates(), 0, Map.of("hot", 123L), new Random(2));
        d.headlineStarted("cake");
        Path file = dir.resolve("s.json");
        assertTrue(d.save(file));
        EventDirector back = director();
        assertTrue(back.load(file));
        assertEquals(d.toMap(), back.toMap());
        assertEquals(123L, back.state().lastEndedAt().get("hot"));

        java.nio.file.Files.writeString(file, "{not json");
        EventDirector fresh = director();
        assertFalse(fresh.load(file));
        assertEquals("", fresh.state().day());
        assertFalse(fresh.load(dir.resolve("missing.json")));
    }

    @Test
    void describeShowsLocalAndUtcTimes() {
        EventDirector d = director();
        long now = local(2026, 10, 26, 12, 0);
        d.tick(now, templates(), 0, Map.of(), new Random(4));
        List<String> lines = d.describe(now);
        assertTrue(lines.get(0).contains("8:00 PM PDT (03:00 UTC)"), lines.get(0));
        assertTrue(lines.get(0).contains("in 480 min"), lines.get(0));
    }

    @Test
    void windowsParse() {
        assertEquals(List.of(new EventDirector.Window(LocalTime.of(12, 0), LocalTime.of(14, 0)),
                        new EventDirector.Window(LocalTime.of(21, 30), LocalTime.of(23, 30))),
                EventDirector.Config.parseWindows("12:00-14:00, 21:30-23:30"));
    }
}
