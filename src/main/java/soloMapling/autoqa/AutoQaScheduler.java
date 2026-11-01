package soloMapling.autoqa;

import java.util.List;
import java.util.Set;

/**
 * Decides which check may start next when a run overlaps checks (ticket 167): the first check in catalogue order
 * whose declared resources are all free, on the first free lane. A check that declares nothing beyond its own lane
 * ({@link Check#laneOnly()}) never blocks anyone; a check that declares {@link Check#WORLD} — or that declares
 * nothing at all, which reads as world-wide — runs alone.
 * <p>
 * With {@code -Dslums.autoqa.longestFirst=true} (ticket 163) the same rules pick the free check with the longest
 * declared budget instead of the first one, so the lanes keep working to the end of the run rather than trailing off
 * behind one long check; the catalogue order is what a run takes by default.
 * <p>
 * Pure decisions on purpose: the runner owns the threads and the players, this owns the packing, so the packing can be
 * tested without a server.
 */
final class AutoQaScheduler {

    /** How many checks a run may overlap: 1 (the default) = one after another, exactly as before ticket 167. */
    static final String PROPERTY = "slums.autoqa.parallel";

    /**
     * Ticket 163: pack the lanes longest-declared-budget-first instead of first-come-in-catalogue-order:
     * {@code -Dslums.autoqa.longestFirst=true}. Off (the catalogue order) by default; no effect at cap 1, where there
     * is nothing to pack.
     */
    static final String LONGEST_FIRST_PROPERTY = "slums.autoqa.longestFirst";

    private AutoQaScheduler() {
    }

    /** Whether the lanes are packed longest-first: {@code -Dslums.autoqa.longestFirst=true}, default false. */
    static boolean longestFirst() {
        return Boolean.parseBoolean(System.getProperty(LONGEST_FIRST_PROPERTY, "false"));
    }

    /** The cap in force: {@code -Dslums.autoqa.parallel=<1..4>}, clamped, default 1. */
    static int parallel() {
        return Math.max(1, Math.min(SyntheticPlayers.MAX_LANES, Integer.getInteger(PROPERTY, 1)));
    }

    /** A lane (1..cap) and the position in the pending list that starts on it now. */
    record Start(int lane, int position) {
    }

    /**
     * The keys a check holds: what its declaration says, nothing more. A lane's synthetic players are its own (a
     * check's player 1 is the GM that types {@code !qa}), and lane occupancy is {@code laneFree}, so no lane key is
     * needed here.
     */
    static Set<String> keys(Check check) {
        return check.holds();
    }

    static boolean disjoint(Set<String> a, Set<String> b) {
        for (String k : a) {
            if (b.contains(k)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when {@code check} may start on {@code lane} now: nothing running holds a key it holds, nothing running is
     * world-wide, and a world-wide check itself waits until the server is idle.
     */
    static boolean canStart(Check check, Set<String> busy) {
        if (busy.contains(Check.WORLD)) {
            return false;
        }
        Set<String> keys = keys(check);
        if (keys.contains(Check.WORLD)) {
            return busy.isEmpty();
        }
        return disjoint(keys, busy);
    }

    /**
     * The pending check this free lane takes now: the first one that can start (the catalogue order), or, with
     * longest-first packing on, the one with the longest declared budget; null when none can (every pending check
     * waits for a running one, or no lane is free).
     */
    static Start pick(List<Check> checks, List<Integer> pending, Set<String> busy, boolean[] laneFree) {
        int lane = 0;
        for (int i = 1; i < laneFree.length; i++) {
            if (laneFree[i]) {
                lane = i;
                break;
            }
        }
        if (lane == 0) {
            return null;
        }
        boolean pack = longestFirst() && laneFree.length > 2;
        int position = pack ? longest(checks, pending, busy) : first(checks, pending, busy);
        return position < 0 ? null : new Start(lane, position);
    }

    /** The first pending check, in list order, that can start now: the order every run took before ticket 163. */
    private static int first(List<Check> checks, List<Integer> pending, Set<String> busy) {
        for (int i = 0; i < pending.size(); i++) {
            if (canStart(checks.get(pending.get(i)), busy)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The pending check that can start now with the longest declared time budget, earliest in list order on a tie.
     * A long check started late is what stretches the tail of a run; taking the longest first keeps the lanes busy
     * to the end, and the budget ({@link Check#minutes()}) is the cost the catalogue already declares for it.
     */
    private static int longest(List<Check> checks, List<Integer> pending, Set<String> busy) {
        int best = -1;
        int budget = -1;
        for (int i = 0; i < pending.size(); i++) {
            Check c = checks.get(pending.get(i));
            if (c.minutes() > budget && canStart(c, busy)) {
                best = i;
                budget = c.minutes();
            }
        }
        return best;
    }
}
