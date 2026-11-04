package soloMapling.mechanics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code gq-statues} (Guild Quest stage 1, the Gatekeeper's test on {@code 990000300}): the statues show a pattern and
 * the party hits them back in the same order, three rounds of 4, 5 and 6 statues.
 *
 * <p>The pattern is published, not hidden. The Gatekeeper ({@code scripts/npc/9040009.js}) picks the statues and has each
 * one hit on a timer ({@code :106-116}); every statue's {@code act()} appends its own <b>map object id modulo 1000</b>,
 * zero-padded to three digits, to the event property {@code stage1combo} while {@code stage1status} is {@code display}
 * and to {@code stage1guess} while it is {@code active} ({@code scripts/reactor/9208000.js:34-62}, identical in
 * {@code 9208001.js} and {@code 9208002.js}); the Gatekeeper compares the two strings when the leader talks to him in
 * the active state ({@code 9040009.js:76-98}) and clears the stage on the third correct round ({@code :80-86}: sets
 * {@code stage1clear}, force-hits the {@code statuegate}). So the answer is an event property, in full, once the display
 * has finished: the solver reads it and hits the statues whose object ids end that way
 * ({@link SolverStep.HitReactorByOid}, the ids read from {@link StageView#reactors()}).
 *
 * <p>The order of the round:
 * <ol>
 *   <li>status absent or {@code waiting}: the leader clicks the Gatekeeper and takes his first menu line
 *   ({@code Converse(9040009, [0])}: the click shows the instruction, the answer starts the display);</li>
 *   <li>{@code display}: nobody touches a statue - a hit by a bot would be appended to {@code stage1combo} too
 *   ({@code 9208000.js:41-44}: a hit that is not an attack counts), so the whole party waits;</li>
 *   <li>{@code active}: ONE bot, the lowest id, hits the statues of {@code stage1combo} in order, reading
 *   {@code stage1guess} to see how far it got (hits by two bots at once could swap two statues); when the guess is full
 *   the leader clicks the Gatekeeper once ({@code TalkNpc}) and the script judges it.</li>
 * </ol>
 * A guess a stray hit has spoiled (it is not a prefix of the combo) is filled up with any statue so that the leader can
 * submit it and the round starts again (the script resets to round 1 on a wrong answer).
 *
 * <p>UNPROVEN: the leader being a human is handled by {@link #leaderPrompt}; the three-digit chunks are the two reactor
 * scripts' format, not checked against a running server (the parity test runs the shipped scripts).
 */
public final class GqStatuesSolver implements StageSolver {

    public static final String ID = "gq-statues";
    static final int GATEKEEPER = 9040009;
    static final String GATE = "statuegate";

    @Override
    public String id() {
        return ID;
    }

    /** The whole round's length in digits: three per statue, {@code phase + 3} statues. */
    static int roundLength(int phase) {
        return 3 * (phase + 3);
    }

    static String prop(StageView view, String key) {
        return view.eventProperty(key).map(String::trim).orElse(null);
    }

    static int phase(StageView view) {
        String p = prop(view, "stage1phase");
        try {
            return p == null ? 1 : Integer.parseInt(p);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The statues the script can pick: every reactor of the room but the gate, nearest first from a point. */
    static List<StageView.ReactorPlacement> statues(StageView view) {
        List<StageView.ReactorPlacement> out = new ArrayList<>();
        for (StageView.ReactorPlacement r : view.reactors()) {
            if (!GATE.equals(r.name())) {
                out.add(r);
            }
        }
        return out;
    }

    /** The statue whose object id ends in this chunk ({@code objectId % 1000}), nearest to {@code from}, or null. */
    static StageView.ReactorPlacement statueFor(StageView view, int chunk, int[] from) {
        return statues(view).stream().filter(r -> r.objectId() % 1000 == chunk)
                .min(Comparator.comparingDouble(r -> Math.hypot(r.x() - from[0], r.y() - from[1]))).orElse(null);
    }

    /** The bot that hits the statues: the lowest id of the bot members (so only one bot ever does). */
    static Optional<RunMember> hitter(StageView view) {
        return view.members().stream().filter(RunMember::bot).min(Comparator.comparingInt(RunMember::characterId));
    }

    /** Whether it is the leader's turn to click the Gatekeeper, and what for ("start" or "submit"). */
    static String leaderTurn(StageView view) {
        String status = prop(view, "stage1status");
        if (status == null || status.equals("waiting")) {
            return "start";
        }
        if (status.equals("active")) {
            String guess = prop(view, "stage1guess");
            if (guess != null && guess.length() >= roundLength(phase(view))) {
                return "submit";
            }
        }
        return null;
    }

    @Override
    public Optional<String> leaderPrompt(StageView view, RunMember leader) {
        String turn = leaderTurn(view);
        if (turn == null) {
            return Optional.empty();
        }
        return Optional.of("@" + leader.characterId() + (turn.equals("start")
                ? " talk to the Gatekeeper and press next, he starts the pattern"
                : " the pattern is in, talk to the Gatekeeper"));
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        SolverStep protect = GqProtection.step(view, member);
        if (protect != null) {
            return protect;
        }
        int id = member.characterId();
        String turn = leaderTurn(view);
        if (member.leader() && turn != null) {
            return turn.equals("start")
                    ? new SolverStep.Converse(GATEKEEPER, List.of(0), "the Gatekeeper starts the pattern")
                    : new SolverStep.TalkNpc(GATEKEEPER, 0, 0);
        }
        String status = prop(view, "stage1status");
        if (!"active".equals(status)) {
            return new SolverStep.Wait(1_000, status == null || status.equals("waiting")
                    ? "the leader has not started the Gatekeeper's pattern"
                    : "the statues are showing the pattern: a hit now would be written into it (9208000.js:41-44)");
        }
        if (hitter(view).map(h -> h.characterId() != id).orElse(true)) {
            return new SolverStep.Wait(1_000, "the party's hitter is repeating the pattern");
        }
        String combo = prop(view, "stage1combo");
        String guess = prop(view, "stage1guess");
        if (combo == null || guess == null) {
            return new SolverStep.Wait(1_000, "the Gatekeeper's pattern is not written yet (stage1combo / stage1guess)");
        }
        int round = roundLength(phase(view));
        if (guess.length() >= round) {
            return new SolverStep.Wait(1_000, "the whole pattern is in: waiting for the leader to submit it");
        }
        int[] from = view.positionOf(id);
        if (combo.length() >= guess.length() + 3 && combo.startsWith(guess)) {
            int chunk;
            try {
                chunk = Integer.parseInt(combo.substring(guess.length(), guess.length() + 3));
            } catch (NumberFormatException e) {
                return new SolverStep.Wait(1_000, "stage1combo is not digits: " + combo);
            }
            StageView.ReactorPlacement next = statueFor(view, chunk, from);
            if (next == null) {
                return new SolverStep.Wait(1_000, "no statue on this map has an object id ending " + chunk
                        + " (stage1combo " + combo + ")");
            }
            return new SolverStep.HitReactorByOid(next.objectId());
        }
        // a stray hit spoiled the guess: fill it up with the nearest statue so the leader can submit it and start over
        return statues(view).stream().min(Comparator.comparingDouble(r -> Math.hypot(r.x() - from[0], r.y() - from[1])))
                .<SolverStep>map(r -> new SolverStep.HitReactorByOid(r.objectId()))
                .orElseGet(() -> new SolverStep.Wait(1_000, "no statue to hit"));
    }
}
