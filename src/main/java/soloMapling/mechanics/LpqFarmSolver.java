package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * {@code lpq-farm} (ticket 164, Ludi PQ): the pass stages, which the generic {@code kill-collect-hand-in} covers only
 * partly. Three things are added on top of it, and the rest is delegated to it unchanged:
 *
 * <ul>
 *   <li><b>Boxes instead of monsters.</b> Stage 2 has no monsters at all - eleven boxes (reactor {@code 2202003}) and a
 *       trap ({@code 2200002}) hold its fifteen passes - and stage 3 has eight monsters <em>and</em> eight boxes. When
 *       nothing is alive and the party is short, the member hits the box the row names ({@code HIT_REACTOR}).</li>
 *   <li><b>Sub-rooms.</b> Stage 4 keeps its six passes in five rooms and stage 5 its twenty-four in six; the passes are
 *       inside those maps, so a member that still needs passes walks in, fights there, and comes back to hand them
 *       over.</li>
 *   <li>Everything else (farming, pooling on the leader, the hand-in, the wait by the NPC) is
 *       {@link KillCollectHandInSolver}, whose data comes from the row's {@code HAND_IN} objective.</li>
 * </ul>
 *
 * Ids and counts are the row's; the room numbers are the maps' own: {@code 922010400} portals {@code in01..in05} lead to
 * {@code 922010401..405} and {@code 922010500} portals {@code in01..in06} to {@code 922010501..506}
 * ({@code Map.wz/Map/Map9/9220104*.img.xml}). Nothing here is Kerning's: that row keeps
 * {@code kill-collect-hand-in}.
 */
public final class LpqFarmSolver implements StageSolver {

    public static final String ID = "lpq-farm";

    /** Falls back to the Ludibrium pass when the stage's {@code HAND_IN} objective does not name an item. */
    static final int PASS = 4001022;

    /** The stage-4 and stage-5 sub-rooms, in the order the map's own portals list them. */
    private static final Map<Integer, List<Integer>> ROOMS = Map.of(
            922010400, List.of(922010401, 922010402, 922010403, 922010404, 922010405),
            922010500, List.of(922010501, 922010502, 922010503, 922010504, 922010505, 922010506));

    private static final KillCollectHandInSolver FARM = new KillCollectHandInSolver();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Set<Integer> extraMaps(StageView view) {
        List<Integer> rooms = ROOMS.get(view.stage().mapId());
        if (rooms != null) {
            return Set.copyOf(rooms);
        }
        if (need(view) <= 0 && view.stage().nextPortal() != null) {
            // a stage whose whole task is its door: the door moves the member into the next room while the property
            // it just wrote is read on the next tick, so that room is part of this stage too
            return Set.of(view.stage().mapId() + 100);
        }
        return Set.of();
    }

    /** The room this member owns, or -1 for a stage that has none: rooms are handed out in character-id order. */
    static int roomFor(StageView view, int characterId) {
        List<Integer> rooms = ROOMS.get(view.stage().mapId());
        if (rooms == null || rooms.isEmpty()) {
            return -1;
        }
        int slot = slotOf(view, characterId);
        return slot < 0 ? -1 : rooms.get(slot % rooms.size());
    }

    static int slotOf(StageView view, int characterId) {
        List<RunMember> bots = view.members().stream().filter(RunMember::bot)
                .sorted(java.util.Comparator.comparingInt(RunMember::characterId)).toList();
        for (int i = 0; i < bots.size(); i++) {
            if (bots.get(i).characterId() == characterId) {
                return i;
            }
        }
        return -1;
    }

    /** The item this stage hands in: stage 9 hands in the key, not a pass. */
    static int item(StageView view) {
        for (Objective o : view.stage().objectives()) {
            if (o.kind() == ObjectiveKind.HAND_IN && o.itemId() > 0) {
                return o.itemId();
            }
        }
        return PASS;
    }

    /** How many passes the leader must hold to clear this stage (the row's {@code HAND_IN} count). */
    static int need(StageView view) {
        for (Objective o : view.stage().objectives()) {
            if (o.kind() == ObjectiveKind.HAND_IN && o.count().isPresent()) {
                return o.count().get().resolve(view.partySize(), OptionalInt.empty()).orElse(0);
            }
        }
        return 0;
    }

    /** The box reactor the row names for this stage, or 0 when it names none. */
    static int boxReactor(Stage stage) {
        for (Objective o : stage.objectives()) {
            if (o.kind() == ObjectiveKind.HIT_REACTOR && o.hasReactor()) {
                try {
                    return Integer.parseInt(o.reactor().trim());
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int need = need(view);
        int item = item(view);
        int have = view.itemCount(id, item);
        if (need <= 0 && view.stage().nextPortal() != null) {
            // stage 6 has no count at all: the door's own script is what writes its clear property
            return new SolverStep.ScriptedPortal(view.stage().nextPortal());
        }
        int room = roomFor(view, id);
        if (room > 0) {
            boolean inside = view.mapOf(id) == room;
            if (!inside && have < need) {
                return new SolverStep.WarpTo(room, "into room " + room + " for passes");
            }
            if (inside && have >= need) {
                return new SolverStep.WarpTo(view.stage().mapId(), "back to the NPC with " + have + " passes");
            }
            if (inside && !member.leader() && have > 0) {
                return new SolverStep.WarpTo(view.stage().mapId(), "back with " + have + " passes");
            }
        }
        int box = boxReactor(view.stage());
        if (box > 0 && view.liveMobs() == 0 && have < need && view.mapOf(id) == view.stage().mapId()) {
            // the passes are in the boxes: hit one, then the generic collector picks the pass up off the floor
            return new SolverStep.HitReactor(box);
        }
        return FARM.next(view, member);
    }
}
