package soloMapling.mechanics;

import java.util.List;

/**
 * {@code cwkpq-sigils} (ticket 164, CWKPQ stage 1 on `610030400`): five sigils, one hit each, and the counter is the
 * event's.
 *
 * <p>`scripts/reactor/6109016.js` .. `6109020.js` are the five job sigils. Each is the same eleven lines: inside
 * {@code act()} - a <b>hit</b>, not a touch - they add one to `glpq4` while it is below five. Nothing in them reads the
 * hitter's job, so one party member can press all five; what the PQ wants is the counter, not the composition.
 *
 * <p>Why this is a solver and not a row-only stage: the row can name one {@code hitReactor}, and this stage has five
 * ids. The five are spread over the party the way a real party splits them - lowest character id to 6109016 and so on,
 * repeating - which also keeps two bots off the same reactor while a third is free.
 *
 * <p>The shuffle `CWKPQ.js:98-149` performs renames the reactors and moves them; it does not change their ids, which is
 * why this drives them by id and needs no name.
 */
public final class CwkpqSigilSolver implements StageSolver {

    public static final String ID = "cwkpq-sigils";

    /** The five job sigils of the sign-up room, in the order `CWKPQ.js:98-135` deploys them. */
    static final List<Integer> SIGILS = List.of(6109016, 6109017, 6109018, 6109019, 6109020);

    /** This member's sigil: the party's own members sorted by character id, taken round robin. */
    static int sigilFor(StageView view, int characterId) {
        List<Integer> bots = view.members().stream()
                .map(RunMember::characterId)
                .filter(id -> view.roleOf(id) != null)
                .sorted()
                .toList();
        int slot = bots.indexOf(characterId);
        if (slot < 0) {
            slot = 0;
        }
        return SIGILS.get(slot % SIGILS.size());
    }

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
            return new SolverStep.WarpTo(view.stage().mapId(), "back to the keep's sign-up room");
        }
        // act() is unguarded: every hit adds one, so a sixth hit would leave glpq4 at 6 and a "== 5" clear could never
        // match again. Once the counter is lit, stop hitting and let the run advance.
        java.util.Optional<String> lit = view.eventProperty("glpq4");
        if (lit.isPresent()) {
            try {
                if (Integer.parseInt(lit.get().trim()) >= 5) {
                    return new SolverStep.Wait(500, "all five sigils are lit: waiting for the keep's next portal");
                }
            } catch (NumberFormatException ignored) {
                // a property that is not a number is the script's business, not ours: hit and let it count
            }
        }
        return new SolverStep.HitReactor(sigilFor(view, id));
    }
}
