package soloMapling.mechanics;

import soloMapling.mechanics.RealRunPool.RunKey;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Result;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * What a simulated run is sampled from: the last {@link #KEEP} real bot-only runs of each content and tier, taken from
 * {@link RunOutcomes} as they are recorded ({@link #onOutcome}), so they do not depend on how much of the outcome ring a
 * busy row has pushed out.
 *
 * <p><b>Only runs that say something about the content count.</b> A sample is a {@link Mode#REAL} run that ended
 * {@link Result#WON} or {@link Result#LOST}, with no human in it ({@link RunOutcome#humanAtStart}: a human changes the
 * odds), that a lobby or an unstaged party made ({@link RealRunPool.Origin#governed}: a GM's or QA's staged "perfect" run
 * or a run started on stage 4 is not what a population of bots does). A simulated run is never a sample: nothing is ever
 * drawn from an invention.
 *
 * <p><b>The floor.</b> {@link #draw} returns nothing for a key with fewer than {@code floor} samples, and that is the whole
 * of "below the floor, wait rather than fake". Memory only, as {@link RunOutcomes}: after a restart every key is below the
 * floor again until it has been played for real.
 */
final class RunSamples {

    /** How many real runs are kept per content and tier. */
    static final int KEEP = 20;

    /** One real run, as much of it as a simulated run copies. */
    record Sample(Result result, long durationMs, int stageReached, int stagesToPlay, String reason) {
    }

    /** What was drawn, and what it rests on. */
    record Drawn(Sample sample, RunOutcome.Basis basis) {
    }

    private static final RunSamples INSTANCE = new RunSamples();

    static RunSamples get() {
        return INSTANCE;
    }

    private final Map<RunKey, Deque<Sample>> by = new HashMap<>();

    /** The outcome listener ({@link RunOutcomes#listen}): keeps the runs a simulated one may be drawn from. */
    synchronized void onOutcome(RunOutcome o) {
        if (o.mode() != Mode.REAL || o.result() == Result.ABANDONED || o.humanAtStart() || !o.origin().governed()) {
            return;
        }
        Deque<Sample> q = by.computeIfAbsent(new RunKey(o.contentId(), o.tier()), k -> new ArrayDeque<>());
        q.addLast(new Sample(o.result(), o.durationMs(), o.stageReached(), o.stagesToPlay(), o.reason()));
        while (q.size() > KEEP) {
            q.pollFirst();
        }
    }

    /** How many real runs this key can be sampled from now. */
    synchronized int count(RunKey key) {
        Deque<Sample> q = by.get(key);
        return q == null ? 0 : q.size();
    }

    /**
     * One simulated result for {@code key}: win or lose with the share of wins among its real runs, then one of the real
     * runs that ended that way - its duration, the stage it got to, its reason. Empty when the key has fewer than
     * {@code floor} real runs: the run waits.
     */
    synchronized Optional<Drawn> draw(RunKey key, int floor, Random rng) {
        Deque<Sample> q = by.get(key);
        if (q == null || q.size() < Math.max(1, floor)) {
            return Optional.empty();
        }
        List<Sample> won = new ArrayList<>();
        List<Sample> lost = new ArrayList<>();
        for (Sample s : q) {
            (s.result() == Result.WON ? won : lost).add(s);
        }
        double pWin = (double) won.size() / q.size();
        List<Sample> pool = rng.nextDouble() < pWin ? won : lost;
        Sample picked = pool.get(rng.nextInt(pool.size()));
        return Optional.of(new Drawn(picked, new RunOutcome.Basis(q.size(), pWin)));
    }

    /** Test hook. */
    synchronized void clearForTest() {
        by.clear();
    }
}
