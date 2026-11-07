package soloMapling.mechanics;

/**
 * {@code fight-room} (ticket 164, Magatia PQ stage 2 and any "empty the map" stage): fight what is out, and let the
 * script's own {@code monsterKilled} write the clear.
 *
 * <p>Magatia stage 2 is one line in the event - `MagatiaPQ_A.js:418-426`: on `926110001`, once `statusStg1 == 1` and
 * `countMonsters() == 0`, it shows the clear effect and sets `statusStg2 = 1`. There is nothing to fetch and nobody to
 * talk to, so the solver's whole job is to keep the room worked and say what it is waiting for.
 */
public final class FightTheRoomSolver implements StageSolver {

    public static final String ID = "fight-room";

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
            return new SolverStep.WarpTo(view.stage().mapId(), "back into the stage's room");
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        // 1.6.6 b365: a room whose fight is behind an item reactor (Ellin's altar takes the Magic Stone and brings the
        // Poison Golem): whoever holds the item puts it on the reactor while it still waits for one
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.kind() != MechanicsModel.ObjectiveKind.PLACE_ITEM || !o.hasReactor()
                    || !o.reactor().trim().chars().allMatch(Character::isDigit)) {
                continue;
            }
            int reactor = Integer.parseInt(o.reactor().trim());
            int count = o.count().map(c -> c.resolve(view.partySize(), java.util.OptionalInt.empty()).orElse(1)).orElse(1);
            if (view.reactorTakes(reactor) != null && view.itemCount(id, o.itemId()) >= count) {
                return new SolverStep.PlaceItemOn(reactor, o.itemId(), count, o.id());
            }
        }
        return new SolverStep.Wait(1_500, "the room is empty: waiting for " + view.stage().name() + " to be cleared");
    }
}
