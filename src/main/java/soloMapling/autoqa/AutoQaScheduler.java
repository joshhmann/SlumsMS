package soloMapling.autoqa;

import java.util.List;
import java.util.Set;

/**
 * Decides which check may start next when a run overlaps checks (ticket 167): the first check in catalogue order
 * whose declared resources are all free, on the first free lane. A check that declares nothing beyond its own lane
 * ({@link Check#laneOnly()}) never blocks anyone; a check that declares {@link Check#WORLD} — or that declares
 * nothing at all, which reads as world-wide — runs alone.
 * <p>
 * Pure decisions on purpose: the runner owns the threads and the players, this owns the packing, so the packing can be
 * tested without a server.
 */
final class AutoQaScheduler {

    /** How many checks a run may overlap: 1 (the default) = one after another, exactly as before ticket 167. */
    static final String PROPERTY = "slums.autoqa.parallel";

    private AutoQaScheduler() {
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
     * The first pending check, in list order, that can start now on the first free lane; null when none can (every
     * pending check waits for a running one, or no lane is free).
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
        for (int i = 0; i < pending.size(); i++) {
            if (canStart(checks.get(pending.get(i)), busy)) {
                return new Start(lane, i);
            }
        }
        return null;
    }
}
