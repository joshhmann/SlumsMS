package soloMapling.mechanics;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code opq-levers} (Orbis PQ stage 7, "on the way up" 920010700): exactly two of the five levers must be pushed on,
 * and which two is Eak's secret, held in the event property {@code stage6_c}.
 *
 * <p>Our script is the truth: {@code scripts/npc/2013001.js:920010700} builds {@code stage6_c} as a five-character
 * {@code 0}/1 string on the first talk ({@code rnd1 < rnd2}, two ones), then on every later talk reads the five
 * reactors <b>by name</b> ({@code getReactorByName("" + i)}, i = 1..5), requires exactly two pushed on, and clears the
 * stage when all five characters match. So the party splits the five levers between the bots, each one only ever
 * clicking a lever whose state differs from its character, and the leader talks with Eak once they all match.
 *
 * <p>The lever reactors are named {@code "1"} to {@code "5"} in the map, so {@link SolverStep.HitReactorNamed} is what
 * clicks them; {@link SolverStep.HitReactor} with an id would resolve to a reactor whose id is 1..5, which no map has.
 */
public final class OpqLeversSolver implements StageSolver {

    public static final String ID = "opq-levers";

    /** Where the script keeps the combination: five characters, {@code '1'} = that lever must be pushed on. */
    public static final String COMBINATION_PROPERTY = "stage6_c";
    static final int LEVERS = 5;
    static final int EAK_NPC = 2013001;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "up to the levers");
        }
        String comb = view.eventProperty(COMBINATION_PROPERTY).orElse(null);
        if (comb == null || comb.length() != LEVERS) {
            // the first talk is what picks the combination (statusStg6 -1 -> 0), so somebody has to open the stage
            return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                    : new SolverStep.Wait(2_000, "waiting for Eak's combination");
        }
        for (int lever : mine(view, id)) {
            boolean wanted = comb.charAt(lever - 1) == '1';
            int state = view.reactorState(String.valueOf(lever));
            if (state < 0) {
                continue; // no such lever on this map: nothing to do from here
            }
            boolean pushedOn = state > 0;
            if (wanted != pushedOn) {
                return new SolverStep.HitReactorNamed(String.valueOf(lever));
            }
        }
        return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                : new SolverStep.Wait(1_500, "holding my levers for the check");
    }

    /**
     * The levers this member clicks: the five are shared out over the bots in character-id order, so no lever is ever
     * clicked by two bots at once (a second click would toggle it straight back).
     */
    static List<Integer> mine(StageView view, int characterId) {
        List<Integer> bots = new ArrayList<>();
        for (RunMember m : view.members()) {
            if (m.bot()) {
                bots.add(m.characterId());
            }
        }
        bots.sort(Integer::compareTo);
        int slot = bots.indexOf(characterId);
        if (slot < 0 || bots.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int lever = 1; lever <= LEVERS; lever++) {
            if ((lever - 1) % bots.size() == slot) {
                out.add(lever);
            }
        }
        return out;
    }
}
