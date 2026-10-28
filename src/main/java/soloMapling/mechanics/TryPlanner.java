package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Puzzles acted out (owner 2026-10-26): the bot knows the real answer from the run, but shows a believable number of
 * wrong tries first, by persona and tier, and the last try is always right so the run always finishes.
 *
 * <p>Tries are option indices; how many and which is decided here, for any solver. A solver with a natural search
 * order maps them onto it: KPQ's combination stages should walk wrong combinations "one mover" at a time (one member
 * steps from one rope to another, as players search them), an idea credited to nutnnut/cosmic_agents'
 * {@code AgentKpqCombinationOrder} (AGPL-3.0) to be reimplemented in the combination solver, not copied.
 */
public final class TryPlanner {

    /** Never more visible wrong tries than this on one puzzle, whatever the persona: the PQ timer must not run out. */
    public static final int MAX_WRONG_TRIES = 4;

    private TryPlanner() {
    }

    /** How strongly each tier scales a persona's wrong tries (draft; the tier's data may override). */
    public static double tierFactor(Tier tier) {
        return switch (tier) {
            case NORMAL -> 1.0;
            case HARD -> 1.25;
            case EXPERT -> 1.5;
            case MASTER -> 2.0;
        };
    }

    /**
     * The tries one bot shows on a puzzle with {@code optionCount} options whose right answer is {@code correct}:
     * distinct option indices, ending with {@code correct}.
     *
     * @param mistakeFactor the tier's {@code botMistakeFactor}
     */
    public static List<Integer> tries(int correct, int optionCount, MechanicsPersona persona, double mistakeFactor,
                                      Random rnd) {
        if (optionCount < 1 || correct < 0 || correct >= optionCount) {
            throw new IllegalArgumentException("correct " + correct + " not in 0.." + (optionCount - 1));
        }
        double expected = persona.wrongTryRate() * Math.max(0, mistakeFactor);
        int wrong = (int) Math.floor(expected);
        if (rnd.nextDouble() < expected - wrong) {
            wrong++;
        }
        wrong = Math.min(wrong, Math.min(MAX_WRONG_TRIES, optionCount - 1));
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < optionCount; i++) {
            if (i != correct) {
                pool.add(i);
            }
        }
        List<Integer> out = new ArrayList<>(wrong + 1);
        for (int i = 0; i < wrong; i++) {
            out.add(pool.remove(rnd.nextInt(pool.size())));
        }
        out.add(correct);
        return List.copyOf(out);
    }
}
