package soloMapling.ArtificialPlayer.BotAffinitySystem;

import java.util.concurrent.TimeUnit;

/**
 * The arithmetic of ticket 58, pure and tested: how an event moves a tie, how a tie fades, and which state a tie is in.
 *
 * <ul>
 *   <li>Scores run -100..100 and fade toward 0 with time: a grudge halves every {@code grudgeHalfLifeHours} (72), so a
 *   rival cools off within a couple of days and even a nemesis within about a week; a friendship halves every
 *   {@code friendHalfLifeDays} (14).</li>
 *   <li>A state needs history: a friend needs {@code friendAt} (25) and 3 kind things; a rival {@code rivalAt} (-25)
 *   and 2 hostile things; a nemesis {@code nemesisAt} (-65) and 5 hostile things. One bad moment never makes a
 *   nemesis, one buff never makes a friend.</li>
 *   <li>Hearsay (gossip) only nudges a tie that is still near neutral and never past {@link #HEARSAY_CAP}, so gossip
 *   alone never makes a friend or a rival.</li>
 *   <li>A player who said {@code @botdrama off} is never seen as a rival or nemesis, and hostile events about them do
 *   not count.</li>
 * </ul>
 * All thresholds are {@code -Dsolomapling.affinity.*} properties.
 */
public final class AffinityRules {

    public static final double MAX = 100;
    public static final double HEARSAY_CAP = 15;
    /** Below this a tie has faded to nothing: its history is forgotten and the row may be dropped. */
    public static final double FADED = 3;

    private AffinityRules() {
    }

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.affinity." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static double friendAt() {
        return prop("friendAt", 25);
    }

    public static double rivalAt() {
        return prop("rivalAt", -25);
    }

    public static double nemesisAt() {
        return prop("nemesisAt", -65);
    }

    static long grudgeHalfLifeMs() {
        return (long) (prop("grudgeHalfLifeHours", 72) * TimeUnit.HOURS.toMillis(1));
    }

    static long friendHalfLifeMs() {
        return (long) (prop("friendHalfLifeDays", 14) * TimeUnit.DAYS.toMillis(1));
    }

    /** The score {@code dtMs} after it was {@code score}: halves every half-life (grudges faster than friendships). */
    public static double faded(double score, long dtMs) {
        if (score == 0 || dtMs <= 0) {
            return score;
        }
        long halfLife = score < 0 ? grudgeHalfLifeMs() : friendHalfLifeMs();
        if (halfLife <= 0) {
            return score;
        }
        return score * Math.pow(0.5, dtMs / (double) halfLife);
    }

    /** The tie's score now. */
    public static double scoreAt(Tie tie, long now) {
        return faded(tie.score, now - tie.updatedAt);
    }

    /** Which state the tie is in now. */
    public static AffinityState state(Tie tie, long now, boolean optedOut) {
        if (tie == null) {
            return AffinityState.NEUTRAL;
        }
        double s = scoreAt(tie, now);
        AffinityState state;
        if (s >= friendAt() && tie.kind >= 3) {
            state = AffinityState.FRIEND;
        } else if (s <= nemesisAt() && tie.hostile >= 5) {
            state = AffinityState.NEMESIS;
        } else if (s <= rivalAt() && tie.hostile >= 2) {
            state = AffinityState.RIVAL;
        } else {
            state = AffinityState.NEUTRAL;
        }
        return optedOut && state.hostile() ? AffinityState.NEUTRAL : state;
    }

    /**
     * Applies one event at {@code now}. False (nothing changes) when the pair's cooldown for that event is still
     * running, the event is hostile and the player opted out, or hearsay would push past its cap.
     */
    public static boolean apply(Tie tie, AffinityEvent event, long now, boolean optedOut) {
        return apply(tie, event, event.delta(), now, optedOut);
    }

    /** As {@link #apply(Tie, AffinityEvent, long, boolean)} with an explicit delta (a big gift counts more). */
    public static boolean apply(Tie tie, AffinityEvent event, int delta, long now, boolean optedOut) {
        if (delta == 0 || (optedOut && delta < 0)) {
            return false;
        }
        Long last = tie.lastAt.get(event);
        long cooldown = event.cooldownMs();
        if (last != null && cooldown > 0 && now - last < cooldown) {
            return false;
        }
        double current = scoreAt(tie, now);
        if (Math.abs(current) < FADED) {
            tie.kind = 0;      // a faded history is forgotten: earning a state starts over
            tie.hostile = 0;
        }
        double next;
        if (event.hearsay()) {
            if (Math.abs(current) >= HEARSAY_CAP) {
                return false;  // they already know them first hand (or heard enough)
            }
            next = Math.max(-HEARSAY_CAP, Math.min(HEARSAY_CAP, current + delta));
        } else {
            next = Math.max(-MAX, Math.min(MAX, current + delta));
            if (delta > 0) {
                tie.kind = Math.min(999, tie.kind + 1);
            } else {
                tie.hostile = Math.min(999, tie.hostile + 1);
            }
        }
        tie.score = next;
        tie.updatedAt = now;
        tie.lastAt.put(event, now);
        tie.dirty = true;
        return true;
    }

    /** GM staging: puts the tie squarely in a state, as of now (score and history that state needs, no cooldowns). */
    public static void stage(Tie tie, AffinityState state, long now) {
        switch (state) {
            case FRIEND -> set(tie, 60, 5, 0);
            case RIVAL -> set(tie, -40, 0, 3);
            case NEMESIS -> set(tie, -85, 0, 8);
            case NEUTRAL -> set(tie, 0, 0, 0);
        }
        tie.updatedAt = now;
        tie.lastAt.clear();
        tie.dirty = true;
    }

    /** GM: a fresh grudge, as if the player had just KSed the bot again and again: at least a rival, as of now. */
    public static void forceGrudge(Tie tie, long now) {
        double current = scoreAt(tie, now);
        tie.score = Math.max(-MAX, Math.min(current, rivalAt()) - 20);
        tie.hostile = Math.max(tie.hostile + 3, 3);
        tie.kind = 0;
        tie.updatedAt = now;
        tie.dirty = true;
    }

    private static void set(Tie tie, double score, int kind, int hostile) {
        tie.score = score;
        tie.kind = kind;
        tie.hostile = hostile;
    }

    /** Whether a stored tie has faded to nothing (it may be dropped). */
    public static boolean faded(Tie tie, long now) {
        return Math.abs(scoreAt(tie, now)) < FADED;
    }
}
