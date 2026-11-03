package soloMapling.mechanics;

import java.util.List;

/**
 * {@code cwkpq-boss} (ticket 164, CWKPQ's last room, `610030800`): the host walks the kill through him, one talk at a
 * time.
 *
 * <p>`scripts/npc/9201115.js:28-94` is the whole room, and every step of it is the NPC's own {@code status}:
 *
 * <ul>
 *   <li>{@code glpq6 == 0}: status 0 greets, status 1 mocks, then status 2 spawns ten Master Guardians (9400594) and
 *       twenty more mobs (9400582) and sets {@code glpq6 = 1}.</li>
 *   <li>{@code glpq6 == 1}: once the map holds no monster, status 1 spawns the four Twisted Masters (9400590 Margana,
 *       9400591 Red Nirg, 9400592 Rellik, 9400593 Hsalf) and sets {@code glpq6 = 2}.</li>
 *   <li>{@code glpq6 == 2}: once the map is empty again, it sets {@code glpq6 = 3}, shows the clear effect and calls
 *       {@code eim.clearPQ()}.</li>
 * </ul>
 *
 * <p>So the clear is the instance's flag (that is what {@code clearPQ} sets and it is reachable only through
 * {@code glpq6 == 3}), and the step between fights is a two-answer talk: `sendNext` at status 0, the action at status 1.
 * Fighting is ordinary, so the four masters are not a tier of their own in the row - the spawner decides when they
 * exist, and the solver simply fights whatever is out before it talks again.
 */
public final class CwkpqBossSolver implements StageSolver {

    public static final String ID = "cwkpq-boss";

    static final int HOST = 9201115;
    static final int DONE = 3;

    @Override
    public String id() {
        return ID;
    }

    static int stage(StageView view) {
        return CwkpqDoorSolver.count(view, "glpq6");
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into the keep's last room");
        }
        int glpq6 = stage(view);
        if (glpq6 >= DONE) {
            return new SolverStep.Wait(500, "glpq6 is " + glpq6 + ": the host has already cleared the instance");
        }
        if (glpq6 < 0) {
            return new SolverStep.Wait(1_000, "the event has not seeded glpq6 yet");
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        if (!member.leader()) {
            return new SolverStep.Wait(1_500, "the room is empty: waiting for the leader's talk to the host");
        }
        // status 0 greets, status 1 acts: two answers take the host from one step to the next
        return new SolverStep.Converse(HOST, List.of(0, 0), "ask the host what is next (glpq6 " + glpq6 + ")");
    }
}
