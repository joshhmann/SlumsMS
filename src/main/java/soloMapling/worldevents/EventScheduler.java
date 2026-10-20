package soloMapling.worldevents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks world events (ticket 21). A scheduler with weights, never a model: each tick has a small chance to start one,
 * scaled by the population director's daily curve so events lean toward peak hours, and none start while the world
 * is quiet. At most {@code maxActive} run at once, the same template never runs twice at once, a template rests for
 * its own cooldown after it ends, and two starts are at least {@code minGap} apart. Every event has an end time and
 * {@link #overdue} ends it whatever else happens.
 * <p>
 * All pure: the live wiring is {@link WorldEvents}.
 */
public final class EventScheduler {

    /** Hard cap on events at once, whatever the property says (spec: at most 1-2). */
    public static final int MAX_ACTIVE_CAP = 2;

    /**
     * The tunables ({@code -D} properties; config.yaml is strict).
     *
     * @param auto          whether the scheduler starts events by itself ({@code solomapling.worldEvents}, default
     *                      off; GM commands work either way)
     * @param tickMs        how often the scheduler looks ({@code solomapling.worldEvents.tickSeconds}, 30)
     * @param meanGapMinutes average minutes between events at peak hours
     *                      ({@code solomapling.worldEvents.meanGapMinutes}, 120)
     * @param maxActive     events at once ({@code solomapling.worldEvents.maxActive}, 1, never above 2)
     * @param minGapMs      least time between two starts ({@code solomapling.worldEvents.minGapMinutes}, 30)
     * @param quietBelow    no scheduled events while the curve is below this fraction of peak
     *                      ({@code solomapling.worldEvents.quietBelow}, 0.2)
     */
    public record Settings(boolean auto, long tickMs, double meanGapMinutes, int maxActive, long minGapMs,
                           double quietBelow) {

        public static Settings fromSystemProperties() {
            return new Settings(
                    "true".equalsIgnoreCase(System.getProperty("solomapling.worldEvents", "false").trim()),
                    Math.max(5, Long.getLong("solomapling.worldEvents.tickSeconds", 30)) * 1000L,
                    Math.max(5.0, dbl("solomapling.worldEvents.meanGapMinutes", 120)),
                    Math.max(1, Math.min(MAX_ACTIVE_CAP, Integer.getInteger("solomapling.worldEvents.maxActive", 1))),
                    Math.max(0, Long.getLong("solomapling.worldEvents.minGapMinutes", 30)) * 60_000L,
                    Math.max(0, Math.min(1, dbl("solomapling.worldEvents.quietBelow", 0.2))));
        }

        public Settings withAuto(boolean value) {
            return new Settings(value, tickMs, meanGapMinutes, maxActive, minGapMs, quietBelow);
        }

        private static double dbl(String key, double fallback) {
            try {
                return Double.parseDouble(System.getProperty(key, String.valueOf(fallback)).trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** What the scheduler needs to know about one running event. */
    public record Running(int eventId, String templateId, long endsAt) {
    }

    private EventScheduler() {
    }

    /**
     * The chance one tick starts an event at this point of the daily curve: a Poisson rate of one per
     * {@code meanGapMinutes} at peak, scaled by the curve, and zero while it is quiet.
     */
    public static double startChance(Settings s, double curveFraction) {
        double f = Math.max(0, Math.min(1, curveFraction));
        if (f < s.quietBelow() || f <= 0) {
            return 0;
        }
        double ticksPerGap = s.meanGapMinutes() * 60_000.0 / s.tickMs();
        return 1 - Math.exp(-f / ticksPerGap);
    }

    /** Templates that may start now: enabled, weighted, not running, and rested since they last ended. */
    public static List<EventTemplate> eligible(List<EventTemplate> templates, List<Running> running,
                                               Map<String, Long> lastEndedAt, long now) {
        List<EventTemplate> out = new ArrayList<>();
        for (EventTemplate t : templates) {
            if (!t.enabled() || t.weight() <= 0) {
                continue;
            }
            if (running.stream().anyMatch(r -> r.templateId().equalsIgnoreCase(t.id()))) {
                continue;
            }
            Long ended = lastEndedAt.get(t.id());
            if (ended != null && now - ended < t.cooldownMs()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /** A weighted pick, or null when nothing is eligible. */
    public static EventTemplate pick(List<EventTemplate> eligible, Random rng) {
        double total = 0;
        for (EventTemplate t : eligible) {
            total += t.weight();
        }
        if (total <= 0) {
            return null;
        }
        double roll = rng.nextDouble() * total;
        for (EventTemplate t : eligible) {
            roll -= t.weight();
            if (roll < 0) {
                return t;
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    /**
     * One scheduler tick: the template to start now, or null. Nothing starts when the scheduler is off, the cap is
     * reached, the last start was too recent, the world is quiet, or the dice say no.
     */
    public static EventTemplate decide(Settings s, List<EventTemplate> templates, List<Running> running,
                                       Map<String, Long> lastEndedAt, long lastStartAt, long now,
                                       double curveFraction, Random rng) {
        if (!s.auto() || running.size() >= s.maxActive()) {
            return null;
        }
        if (lastStartAt > 0 && now - lastStartAt < s.minGapMs()) {
            return null;
        }
        if (rng.nextDouble() >= startChance(s, curveFraction)) {
            return null;
        }
        return pick(eligible(templates, running, lastEndedAt, now), rng);
    }

    /** Whether a GM (or the scheduler) may start one more event: the hard cap of {@link #MAX_ACTIVE_CAP}. */
    public static boolean roomFor(List<Running> running) {
        return running.size() < MAX_ACTIVE_CAP;
    }

    /** Running events whose time is up: they end now. */
    public static List<Running> overdue(List<Running> running, long now) {
        List<Running> out = new ArrayList<>();
        for (Running r : running) {
            if (now >= r.endsAt()) {
                out.add(r);
            }
        }
        return out;
    }
}
