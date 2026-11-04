package soloMapling.mechanics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code magatia-books} (ticket 164, Magatia PQ stage 1): twenty-six identical books, and only one of them is the pass.
 *
 * <p>`scripts/npc/2112013.js` (the Alcadno bookshelf - `2112007.js` is its Zenumist twin) keys each book on the <b>object
 * id</b> it was clicked at:
 *
 * <pre>
 *   var book = "stg1_b" + (cm.getNpcObjectId() % 26);   // :29-32
 *   ... a consumed book answers from the property that is already there
 *   a fresh book rolls its own reward and, if it rolls the pass, sets "statusStg1" = 1 and hits the reactor d00 (:56-61)
 * </pre>
 *
 * <p>So the stage is not "talk to the NPC": it is "walk the map and read every book once", and a solver that talks to
 * {@code TalkNpc(2112013)} reads the same book for ever - which is what a bot does today, because
 * {@code MapleMap.getNPCById} returns the first standing.
 *
 * <p>Each member claims the next book nobody has read yet, in the map's own order, and the claim lives in
 * {@code stageMemory()} (per instance, cleared when the stage changes). Once every standing has been read and the stage
 * has still not cleared, the run says so and gives up instead of looping: twenty-six books is a bound the script itself
 * guarantees.
 */
public final class MagatiaBooksSolver implements StageSolver {

    public static final String ID = "magatia-books";

    /** Where the bought-and-consumed books are recorded, per NPC (the two variants have different NPC ids). */
    static final String TRIED_PREFIX = "books.tried.";

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
            return new SolverStep.WarpTo(view.stage().mapId(), "back to the shelf");
        }
        int npcId = view.stage().npcId();
        java.util.List<StageView.NpcPlacement> books = view.npcPlacements(npcId);
        if (books.isEmpty()) {
            return new SolverStep.Wait(1_500, "no book of NPC " + npcId + " stands on this map");
        }
        @SuppressWarnings("unchecked")
        Set<Integer> tried = (Set<Integer>) view.stageMemory()
                .computeIfAbsent(TRIED_PREFIX + npcId, k -> ConcurrentHashMap.<Integer>newKeySet());
        // claim the first book nobody has read yet; a book already read twice answers from its own property and is wasted
        for (StageView.NpcPlacement p : books) {
            if (tried.add(p.objectId())) {
                return new SolverStep.TalkNpcAt(p.objectId(), npcId);
            }
        }
        return new SolverStep.Abandon("all " + books.size() + " of NPC " + npcId
                + "'s books were read and the shelf has no pass left");
    }
}
