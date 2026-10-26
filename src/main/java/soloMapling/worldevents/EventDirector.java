package soloMapling.worldevents;

import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * 1.6.0 world events: the daily plan (owner decision, 2026-10-26). A template scheduler, never a model:
 * <ul>
 *   <li><b>Headline:</b> one event every evening at {@link Config#headlineAt} (8 PM) in the server's time zone
 *   ({@code BOT_ROSTER_TIMEZONE}, America/Los_Angeles), DST included: the time is a wall-clock time, so it stays at
 *   8 PM local across the change. The template is a weighted pick among the {@code slot: headline} templates that
 *   did not run in the last {@link #ROTATION_MEMORY} evenings, and is chosen when the day's plan is made, so
 *   {@code !event next} and the 10- and 1-minute warnings can name it.</li>
 *   <li><b>Small:</b> {@link Config#minSmall}-{@link Config#maxSmall} random times a day inside the peak windows
 *   (one window each while there are enough), never near the headline. The template is picked when its time
 *   comes, among the {@code slot: small} templates that are rested.</li>
 *   <li><b>Concurrency:</b> nothing starts when {@link EventScheduler#MAX_ACTIVE_CAP} (2) run; a small event waits up
 *   to {@link Config#smallPatienceMs} for room, then is dropped. The headline waits up to {@link Config#graceMs}.</li>
 *   <li><b>Restarts:</b> the plan ({@link State}) is saved after every change, so a restart neither fires the
 *   headline twice nor repeats a warning; a plan made after its time (the server was down at 8 PM and back after the
 *   grace) skips that evening rather than firing late.</li>
 * </ul>
 * Pure: the clock, the random source and the number of running events are passed in; the live wiring is
 * {@link WorldEvents}.
 */
public final class EventDirector {

    /** How many past headline templates the rotation avoids (when another one is left). */
    public static final int ROTATION_MEMORY = 2;
    /** No small event closer to the headline than this (before its start, or during its run). */
    static final long HEADLINE_CLEARANCE_MS = 45 * 60_000L;

    /** A peak window in local time. */
    public record Window(LocalTime from, LocalTime to) {
        public Window {
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("a window ends after it starts: " + from + "-" + to);
            }
        }

        @Override
        public String toString() {
            return from + "-" + to;
        }
    }

    /**
     * The schedule's tunables ({@code -D} properties; config.yaml is strict).
     *
     * @param zone            the server's zone ({@code BOT_ROSTER_TIMEZONE})
     * @param headlineAt      {@code solomapling.events.headlineAt}, default 20:00
     * @param warnMinutes     minutes before the headline that a warning goes out, default 10 and 1
     * @param graceMs         how late the headline may still start (a busy slot, a restart), default 30 min
     * @param peaks           {@code solomapling.events.peakWindows}, default 12:00-14:00,17:00-19:00,21:30-23:30
     * @param minSmall        {@code solomapling.events.smallMin}, default 1
     * @param maxSmall        {@code solomapling.events.smallMax}, default 3
     * @param smallPatienceMs how long a small event waits for room before it is dropped, default 30 min
     */
    public record Config(ZoneId zone, LocalTime headlineAt, List<Integer> warnMinutes, long graceMs,
                         List<Window> peaks, int minSmall, int maxSmall, long smallPatienceMs) {
        public Config {
            warnMinutes = List.copyOf(new TreeSet<>(warnMinutes).descendingSet());
            peaks = List.copyOf(peaks);
            minSmall = Math.max(0, minSmall);
            maxSmall = Math.max(minSmall, maxSmall);
        }

        public static Config defaults(ZoneId zone) {
            return new Config(zone, LocalTime.of(20, 0), List.of(10, 1), 30 * 60_000L,
                    parseWindows("12:00-14:00,17:00-19:00,21:30-23:30"), 1, 3, 30 * 60_000L);
        }

        public static Config fromSystemProperties(ZoneId zone) {
            Config d = defaults(zone);
            LocalTime at = d.headlineAt();
            try {
                at = LocalTime.parse(System.getProperty("solomapling.events.headlineAt", "20:00").trim());
            } catch (DateTimeParseException ignored) {
                // keep 20:00
            }
            List<Window> peaks = d.peaks();
            try {
                String raw = System.getProperty("solomapling.events.peakWindows");
                if (raw != null && !raw.isBlank()) {
                    peaks = parseWindows(raw);
                }
            } catch (RuntimeException ignored) {
                // keep the defaults
            }
            int min = Integer.getInteger("solomapling.events.smallMin", d.minSmall());
            int max = Integer.getInteger("solomapling.events.smallMax", d.maxSmall());
            return new Config(zone, at, d.warnMinutes(), d.graceMs(), peaks, Math.min(3, min), Math.min(3, max),
                    d.smallPatienceMs());
        }

        /** "12:00-14:00,17:00-19:00" -> windows. */
        public static List<Window> parseWindows(String raw) {
            List<Window> out = new ArrayList<>();
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                String[] ends = p.split("-");
                out.add(new Window(LocalTime.parse(ends[0].trim()), LocalTime.parse(ends[1].trim())));
            }
            return out;
        }
    }

    /** The day's plan and what has happened; saved as JSON ({@link #toMap}). */
    public static final class State {
        /** The local date the plan is for (ISO), or "" before the first plan. */
        String day = "";
        long headlineAt;
        String headlineTemplate = "";
        int headlineMap;
        /** started, missed (the slot passed while down or blocked), or "" while it is still to come. */
        String headlineStatus = "";
        final TreeSet<Integer> warned = new TreeSet<>();
        final List<Long> smallAt = new ArrayList<>();
        /** How many of {@link #smallAt} (in order) are done: started, or dropped for want of room. */
        int smallDone;
        final List<String> recentHeadlines = new ArrayList<>();
        final Map<String, Long> lastEndedAt = new HashMap<>();

        public String day() {
            return day;
        }

        public long headlineAt() {
            return headlineAt;
        }

        public String headlineTemplate() {
            return headlineTemplate;
        }

        public int headlineMap() {
            return headlineMap;
        }

        public String headlineStatus() {
            return headlineStatus;
        }

        public List<Long> smallAt() {
            return List.copyOf(smallAt);
        }

        public int smallDone() {
            return smallDone;
        }

        public List<String> recentHeadlines() {
            return List.copyOf(recentHeadlines);
        }

        public Map<String, Long> lastEndedAt() {
            return Map.copyOf(lastEndedAt);
        }
    }

    /** What the live side should do now. */
    public enum Kind { WARN, START_HEADLINE, START_SMALL }

    /**
     * One thing to do. WARN: {@code minutesLeft} until the headline {@code templateId} on {@code mapId}.
     * START_HEADLINE: start that template there, then call {@link #headlineStarted} or {@link #headlineFailed}.
     * START_SMALL: start {@code templateId} (its own map pick), then {@link #smallStarted}.
     */
    public record Action(Kind kind, String templateId, int mapId, int minutesLeft) {
    }

    private final Config config;
    private State state = new State();

    public EventDirector(Config config) {
        this.config = config;
    }

    public Config config() {
        return config;
    }

    public synchronized State state() {
        return state;
    }

    // ── the plan ────────────────────────────────────────────────────────────────────────────────

    /** The instant of {@code time} local on {@code date} in {@code zone}: wall-clock, so DST keeps it at 8 PM. */
    public static long at(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli();
    }

    public static LocalDate localDate(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
    }

    /** Makes today's plan when the day changed (or there is none). True when a new plan was made. */
    synchronized boolean ensurePlan(long now, List<EventTemplate> templates, Random rng) {
        LocalDate today = localDate(now, config.zone());
        if (today.toString().equals(state.day)) {
            return false;
        }
        State next = new State();
        next.day = today.toString();
        next.recentHeadlines.addAll(state.recentHeadlines);
        next.lastEndedAt.putAll(state.lastEndedAt);
        next.headlineAt = at(today, config.headlineAt(), config.zone());
        EventTemplate pick = pickHeadline(templates, next.recentHeadlines, rng);
        if (pick != null) {
            next.headlineTemplate = pick.id();
            next.headlineMap = pick.spots().isEmpty() ? 0 : pick.spots().get(rng.nextInt(pick.spots().size())).mapId();
        } else {
            next.headlineStatus = "none";
        }
        if (now > next.headlineAt + config.graceMs()) {
            next.headlineStatus = "missed"; // planned after its evening (a late start): never fire it late
        }
        for (int w : config.warnMinutes()) {
            if (now >= next.headlineAt - w * 60_000L) {
                next.warned.add(w); // too late for this warning: the next one (or the start) speaks
            }
        }
        next.smallAt.addAll(planSmall(today, now, next.headlineAt, rng));
        state = next;
        return true;
    }

    /** A weighted pick among enabled headline templates, avoiding the last {@link #ROTATION_MEMORY} when it can. */
    static EventTemplate pickHeadline(List<EventTemplate> templates, List<String> recent, Random rng) {
        List<EventTemplate> headline = new ArrayList<>();
        for (EventTemplate t : templates) {
            if (t.enabled() && t.weight() > 0 && t.slot() == EventTemplate.Slot.HEADLINE && !t.spots().isEmpty()) {
                headline.add(t);
            }
        }
        List<EventTemplate> fresh = new ArrayList<>();
        List<String> avoid = recent.subList(Math.max(0, recent.size() - ROTATION_MEMORY), recent.size());
        for (EventTemplate t : headline) {
            if (!avoid.contains(t.id())) {
                fresh.add(t);
            }
        }
        if (fresh.isEmpty()) {
            // fewer headline templates than the memory: just never the same as last night, if there is another
            String last = recent.isEmpty() ? null : recent.get(recent.size() - 1);
            for (EventTemplate t : headline) {
                if (!t.id().equals(last)) {
                    fresh.add(t);
                }
            }
        }
        return EventScheduler.pick(fresh.isEmpty() ? headline : fresh, rng);
    }

    /**
     * 1-3 times in the peak windows, each in a different window while there are enough, at least
     * {@link #HEADLINE_CLEARANCE_MS} from the headline, and none already past. Sorted.
     */
    List<Long> planSmall(LocalDate day, long now, long headlineAt, Random rng) {
        List<Long> out = new ArrayList<>();
        if (config.peaks().isEmpty() || config.maxSmall() <= 0) {
            return out;
        }
        int n = config.minSmall() + rng.nextInt(config.maxSmall() - config.minSmall() + 1);
        List<Window> order = new ArrayList<>(config.peaks());
        Collections.shuffle(order, rng);
        for (int i = 0, tries = 0; out.size() < n && tries < 40; tries++, i++) {
            Window w = order.get(i % order.size());
            long from = at(day, w.from(), config.zone());
            long to = at(day, w.to(), config.zone());
            if (to <= from) {
                continue;
            }
            long raw = from + (long) (rng.nextDouble() * (to - from));
            long t = raw - raw % 60_000L; // whole minutes
            boolean nearHeadline = t > headlineAt - HEADLINE_CLEARANCE_MS && t < headlineAt + HEADLINE_CLEARANCE_MS;
            boolean tooClose = out.stream().anyMatch(o -> Math.abs(o - t) < 30 * 60_000L);
            if (t <= now || nearHeadline || tooClose) {
                continue;
            }
            out.add(t);
        }
        Collections.sort(out);
        return out;
    }

    // ── each tick ───────────────────────────────────────────────────────────────────────────────

    /**
     * What to do now. {@code running} is the number of events running. Starting is the caller's job; it reports back
     * with {@link #headlineStarted}, {@link #headlineFailed} and {@link #smallStarted}. Calling it again before a
     * report returns the same start again (so a failed start is retried until its grace runs out).
     */
    public synchronized List<Action> tick(long now, List<EventTemplate> templates, int running,
                                          Map<String, Long> lastEndedAt, Random rng) {
        ensurePlan(now, templates, rng);
        state.lastEndedAt.putAll(lastEndedAt);
        List<Action> out = new ArrayList<>();
        boolean headlinePending = state.headlineStatus.isEmpty() && !state.headlineTemplate.isEmpty();
        if (headlinePending) {
            long left = state.headlineAt - now;
            if (left > 0) {
                int minutesLeft = (int) ((left + 59_999) / 60_000);
                for (int w : config.warnMinutes()) {
                    if (!state.warned.contains(w) && minutesLeft <= w) {
                        // one warning per tick; a later warning that is also due swallows the earlier ones
                        for (int earlier : config.warnMinutes()) {
                            if (earlier >= minutesLeft) {
                                state.warned.add(earlier);
                            }
                        }
                        out.add(new Action(Kind.WARN, state.headlineTemplate, state.headlineMap, minutesLeft));
                        break;
                    }
                }
            } else if (now - state.headlineAt > config.graceMs()) {
                state.headlineStatus = "missed";
            } else if (running < EventScheduler.MAX_ACTIVE_CAP) {
                out.add(new Action(Kind.START_HEADLINE, state.headlineTemplate, state.headlineMap, 0));
            }
        }
        // small events: the next one due, if there is room and the headline is not close
        while (state.smallDone < state.smallAt.size()) {
            long due = state.smallAt.get(state.smallDone);
            if (now < due) {
                break;
            }
            if (now - due > config.smallPatienceMs()) {
                state.smallDone++; // waited too long for room: dropped
                continue;
            }
            boolean headlineNear = !state.headlineTemplate.isEmpty()
                    && (state.headlineStatus.isEmpty() || state.headlineStatus.equals("started"))
                    && now > state.headlineAt - HEADLINE_CLEARANCE_MS && now < state.headlineAt + HEADLINE_CLEARANCE_MS;
            boolean startingHeadline = out.stream().anyMatch(a -> a.kind() == Kind.START_HEADLINE);
            if (running >= EventScheduler.MAX_ACTIVE_CAP - 1 || headlineNear || startingHeadline) {
                break; // keep the second slot free (at most 1-2 at once; a small one never takes the last place)
            }
            EventTemplate pick = pickSmall(templates, List.of(), state.lastEndedAt, now, rng);
            if (pick == null) {
                state.smallDone++; // nothing rested: skip this one
                continue;
            }
            out.add(new Action(Kind.START_SMALL, pick.id(), 0, 0));
            break;
        }
        return out;
    }

    static EventTemplate pickSmall(List<EventTemplate> templates, List<EventScheduler.Running> running,
                                   Map<String, Long> lastEndedAt, long now, Random rng) {
        List<EventTemplate> small = new ArrayList<>();
        for (EventTemplate t : EventScheduler.eligible(templates, running, lastEndedAt, now)) {
            if (t.slot() == EventTemplate.Slot.SMALL) {
                small.add(t);
            }
        }
        return EventScheduler.pick(small, rng);
    }

    public synchronized void headlineStarted(String templateId) {
        state.headlineStatus = "started";
        state.recentHeadlines.add(templateId);
        while (state.recentHeadlines.size() > 10) {
            state.recentHeadlines.remove(0);
        }
    }

    /** The headline could not start (no map, mobs did not load): skip tonight rather than retry every tick. */
    public synchronized void headlineFailed() {
        state.headlineStatus = "failed";
    }

    public synchronized void smallStarted() {
        if (state.smallDone < state.smallAt.size()) {
            state.smallDone++;
        }
    }

    /** The events' last end times (template cooldowns), saved with the plan. */
    public synchronized void mergeEnded(Map<String, Long> ended) {
        state.lastEndedAt.putAll(ended);
    }

    /** A GM started an event by hand: it counts for the headline rotation when it is a headline template. */
    public synchronized void noteManualStart(EventTemplate t) {
        if (t.slot() == EventTemplate.Slot.HEADLINE) {
            state.recentHeadlines.add(t.id());
            while (state.recentHeadlines.size() > 10) {
                state.recentHeadlines.remove(0);
            }
        }
    }

    /** {@code !event next}: today's plan in words, times local and UTC. */
    public synchronized List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        ZoneId zone = config.zone();
        String head = state.headlineTemplate.isEmpty() ? "no headline template" : state.headlineTemplate
                + (state.headlineMap > 0 ? " on map " + state.headlineMap : "");
        String status = state.headlineStatus.isEmpty()
                ? (now < state.headlineAt ? "in " + Math.max(0, (state.headlineAt - now) / 60_000) + " min" : "due now")
                : state.headlineStatus;
        out.add("Headline " + state.day + ": " + head + " at " + clock(state.headlineAt, zone) + " (" + status + ")");
        if (state.smallAt.isEmpty()) {
            out.add("Small events today: none left");
        }
        for (int i = 0; i < state.smallAt.size(); i++) {
            long t = state.smallAt.get(i);
            out.add("Small event " + (i + 1) + " at " + clock(t, zone) + (i < state.smallDone ? " (done)"
                    : now >= t ? " (due, waiting for room)" : " (in " + (t - now) / 60_000 + " min)"));
        }
        out.add("Recent headlines: " + (state.recentHeadlines.isEmpty() ? "none" : state.recentHeadlines));
        return out;
    }

    /** "8:00 PM PDT (03:00 UTC)". */
    static String clock(long at, ZoneId zone) {
        ZonedDateTime local = Instant.ofEpochMilli(at).atZone(zone);
        ZonedDateTime utc = Instant.ofEpochMilli(at).atZone(ZoneId.of("UTC"));
        return java.time.format.DateTimeFormatter.ofPattern("h:mm a zzz", Locale.US).format(local)
                + String.format(Locale.ROOT, " (%02d:%02d UTC)", utc.getHour(), utc.getMinute());
    }

    // ── saving ──────────────────────────────────────────────────────────────────────────────────

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", state.day);
        m.put("headlineAt", state.headlineAt);
        m.put("headlineTemplate", state.headlineTemplate);
        m.put("headlineMap", state.headlineMap);
        m.put("headlineStatus", state.headlineStatus);
        m.put("warned", new ArrayList<>(state.warned));
        m.put("smallAt", new ArrayList<>(state.smallAt));
        m.put("smallDone", state.smallDone);
        m.put("recentHeadlines", new ArrayList<>(state.recentHeadlines));
        m.put("lastEndedAt", new LinkedHashMap<>(state.lastEndedAt));
        return m;
    }

    /** Restores a saved plan; anything missing or malformed leaves a fresh state (a new plan is made). */
    public synchronized void fromMap(Map<?, ?> m) {
        State s = new State();
        try {
            s.day = String.valueOf(m.get("day") == null ? "" : m.get("day"));
            s.headlineAt = num(m.get("headlineAt"));
            s.headlineTemplate = m.get("headlineTemplate") == null ? "" : String.valueOf(m.get("headlineTemplate"));
            s.headlineMap = (int) num(m.get("headlineMap"));
            s.headlineStatus = m.get("headlineStatus") == null ? "" : String.valueOf(m.get("headlineStatus"));
            if (m.get("warned") instanceof List<?> l) {
                l.forEach(o -> s.warned.add((int) num(o)));
            }
            if (m.get("smallAt") instanceof List<?> l) {
                l.forEach(o -> s.smallAt.add(num(o)));
            }
            s.smallDone = (int) num(m.get("smallDone"));
            if (m.get("recentHeadlines") instanceof List<?> l) {
                l.forEach(o -> s.recentHeadlines.add(String.valueOf(o)));
            }
            if (m.get("lastEndedAt") instanceof Map<?, ?> e) {
                e.forEach((k, v) -> s.lastEndedAt.put(String.valueOf(k), num(v)));
            }
        } catch (RuntimeException bad) {
            return;
        }
        state = s;
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0 : (long) Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Writes the plan (atomically: a temp file, then a move). Best effort; returns false on a problem. */
    public synchronized boolean save(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(toMap()), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Reads a saved plan; false (and a fresh state) when there is none or it does not parse. */
    public boolean load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return false;
            }
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> m) {
                fromMap(m);
                return true;
            }
        } catch (IOException | RuntimeException e) {
            // a bad file is a fresh start
        }
        return false;
    }
}
