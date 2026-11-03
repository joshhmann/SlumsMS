package soloMapling.mechanics;

/**
 * {@code cwkpq-door} (ticket 164, CWKPQ stage 2 on `610030300`): five sigils' worth of reactors, then five members
 * through one door.
 *
 * <p>Two counters, both the event's own, and the exit is a third thing - so this stage's clear is the pair the exit
 * checks, quoted from `scripts/portal/glpqPortal3.js:4`: {@code glpq3 < 5 || glpq3_p < 5} refuses, else it warps to
 * `610030400`. The row's clear is therefore {@code glpq3_p == 5} and the solver keeps `glpq3` moving itself.
 *
 * <p>`glpq3` comes from the map's own reactors (`6109003`/`6109004`, both `act()`, one adding to `glpq2` and `glpq3`);
 * `glpq3_p` counts <b>passers</b> - `scripts/portal/glpqPortal2.js:11` increments it and warps the passer back onto the
 * same map, so a member can go through more than once. At five it stops counting and says "The portal at the bottom has
 * already been opened", so five is a ceiling, not a target to overshoot: the solver waits once it is there.
 */
public final class CwkpqDoorSolver implements StageSolver {

    public static final String ID = "cwkpq-door";

    /** The two reactors that raise glpq3 on this map, and the door every passer takes. */
    static final int REACTOR_A = 6109003;
    static final int REACTOR_B = 6109004;
    static final String DOOR = "next00";
    static final int PASSERS = 5;
    static final int SIGILS = 5;

    @Override
    public String id() {
        return ID;
    }

    /** The property value as an int, or -1 when the event has not written it. */
    static int count(StageView view, String property) {
        return view.eventProperty(property).map(v -> {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }).orElse(-1);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "back to the door room");
        }
        int sigils = count(view, "glpq3");
        if (sigils < SIGILS) {
            // the two reactors alternate by member so nobody is waiting on somebody else's click
            return new SolverStep.HitReactor(member.leader() ? REACTOR_A : REACTOR_B);
        }
        int passers = count(view, "glpq3_p");
        if (passers >= PASSERS) {
            return new SolverStep.Wait(500, "the door has counted " + passers + " of " + PASSERS
                    + ": it refuses more, so this stage's clear is already written");
        }
        return new SolverStep.ScriptedPortal(DOOR);
    }
}
