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
 * why this drives them by id and needs no name. The same shape serves the Relic room (`glpq5`, reactors
 * `6109005`..`6109009`): those are also unguarded `act()` counters that must reach five, so one solver covers both rooms
 * and the row decides which reactors it has.
 */
public final class CwkpqSigilSolver implements StageSolver {

    public static final String ID = "cwkpq-sigils";

    /** The five job sigils of the sign-up room, in the order `CWKPQ.js:98-135` deploys them. */
    static final List<Integer> SIGILS = List.of(6109016, 6109017, 6109018, 6109019, 6109020);

    /**
     * The reactors this stage numbers: every {@code HIT_REACTOR} objective's reactor id, in row order, or the five job
     * sigils when the row names none. That is what makes the solver serve both counted rooms - the sign-up's five sigils
     * (`6109016`..`6109020`) and the Relic of Mastery's five weapons (`6109005`..`6109009`, `glpq5`) - without a second
     * class: the room's own row says which reactors it has, and the count it must reach is the row's clear.
     */
    static List<Integer> reactors(StageView view) {
        List<Integer> named = view.stage().objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.HIT_REACTOR
                        || o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM)
                .map(MechanicsModel.Objective::reactor)
                .filter(name -> name != null && name.chars().allMatch(Character::isDigit))
                .map(Integer::parseInt)
                .filter(id -> id > 0)
                .distinct()
                .sorted()
                .toList();
        return named.isEmpty() ? SIGILS : named;
    }

    /** The property the count lives in, when the row's clear names one. */
    static String counter(StageView view) {
        MechanicsModel.Clear clear = view.stage().clear();
        return clear == null ? null : clear.eventProperty();
    }

    /** This member's reactor: the party's own members sorted by character id, taken round robin. */
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
        List<Integer> reactors = reactors(view);
        return reactors.get(slot % reactors.size());
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
        java.util.Optional<String> lit = counter(view) == null
                ? java.util.Optional.empty() : view.eventProperty(counter(view));
        if (lit.isPresent()) {
            try {
                if (Integer.parseInt(lit.get().trim()) >= 5) {
                    return new SolverStep.Wait(500, "the room's count is at " + lit.get().trim()
                            + " of " + view.stage().clear().equals() + ": waiting for the keep's next portal");
                }
            } catch (NumberFormatException ignored) {
                // a property that is not a number is the script's business, not ours: hit and let it count
            }
        }
        List<MechanicsModel.Objective> relics = view.stage().objectives().stream()
                .filter(o -> o.kind() == MechanicsModel.ObjectiveKind.PLACE_ITEM && o.hasReactor() && o.job() > 0)
                .toList();
        if (!relics.isEmpty()) {
            return relic(view, member, relics);
        }
        return new SolverStep.HitReactor(sigilFor(view, id));
    }

    /** b365: when the run was told a weapon needs its relic (once per weapon per stage). */
    private static final String K_NEEDS = "relicNeeds:";

    /**
     * 1.6.6 b365 (Crimsonwood's Relic of Mastery, 610030500): each weapon reactor (6109005-9) is an item reactor that
     * takes its job's relic, which comes from that job's chamber. Whoever holds a relic places it. A weapon whose job a
     * real player in the run has waits for that player's relic - one relic is enough, whichever of them brings it. A
     * weapon whose job no player in the run has may open by a hit where the row says {@code regularJobsOnly}, because
     * regulars do not do the chambers yet (cwkpq-chambers, 1.6.7). Starting from this member's own slot, so a party
     * spreads over the five.
     */
    private static SolverStep relic(StageView view, RunMember member, List<MechanicsModel.Objective> relics) {
        int id = member.characterId();
        for (MechanicsModel.Objective o : relics) {
            int reactor = Integer.parseInt(o.reactor().trim());
            if (view.reactorTakes(reactor) != null && view.itemCount(id, o.itemId()) > 0) {
                return new SolverStep.PlaceItemOn(reactor, o.itemId(), 1, "the relic back on its weapon");
            }
        }
        boolean byHit = MechanicsModel.Stage.REGULAR_JOBS_ONLY.equals(view.stage().opensByHit());
        int start = Math.max(0, reactors(view).indexOf(sigilFor(view, id)));
        for (int k = 0; k < relics.size(); k++) {
            MechanicsModel.Objective o = relics.get((start + k) % relics.size());
            int reactor = Integer.parseInt(o.reactor().trim());
            if (view.reactorTakes(reactor) == null) {
                continue; // restored already
            }
            boolean held = view.members().stream().anyMatch(m -> view.itemCount(m.characterId(), o.itemId()) > 0);
            if (held) {
                continue; // its holder places it
            }
            boolean playerOfJob = view.members().stream()
                    .anyMatch(m -> !m.bot() && m.hasJob() && (m.jobId() / 100) % 10 == o.job());
            if (playerOfJob || !byHit) {
                if (view.stageMemory().putIfAbsent(K_NEEDS + reactor, view.nowMs()) == null) {
                    return new SolverStep.Say("You'll need " + view.itemName(o.itemId()) + " to open this.");
                }
                continue;
            }
            return new SolverStep.HitReactor(reactor);
        }
        return new SolverStep.Wait(1_500, "the weapons left wait for their relics");
    }
}
