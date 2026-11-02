package soloMapling.mechanics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Orbis PQ's last two stages as one: the six scars in the centre tower and the Root of Life.
 *
 * <p>The script splits them into two, but only the second half has a property to watch: the six scars are counted
 * with {@code getReactorByName("scar" + i).getState() >= 1} ({@code scripts/npc/2013001.js:11-19}), while
 * {@code scripts/reactor/2006001.js} is what writes {@code statusStg8 = "1"} and ends the quest. So the stage's clear
 * is that property - the same one {@code OrbisPQ.js:197} reads to send the team to the bonus map - and the solver
 * walks the party from the statue to the garden and back inside it.
 *
 * <p>Which piece goes on which scar is not the piece's own number: ours is a permutation, and it is read from
 * {@code Reactor.wz/2008000-2008005.img.xml}, where each scar carries exactly one item trigger.
 */
final class OpqStatueSolver implements StageSolver {

    static final String ID = "opq-statue";

    static final int STATUE_MAP = 920010100;
    static final int GARDEN_MAP = 920010800;
    static final int EAK_NPC = 2013001;
    static final int ROOT_OF_LIFE = 4001055;
    /** The statue's base reactor, named {@code minerva} on the map; its script ends the quest. */
    static final String BASE_REACTOR = "minerva";
    /** The Nependeath pot: {@code scripts/reactor/2001016.js} clears the garden and spawns Papa Pixie. */
    static final int POT_REACTOR = 2001016;
    static final long TALK_GAP_MS = 1_500;
    /** After the pot has been hit the garden is empty until Papa Pixie is spawned, so one hit per gap is enough. */
    static final long RESPAWN_MS = 20_000;
    private static final String PIXIE_AT = "statue.pixieAt";

    /** scar name -> the one piece its item trigger takes ({@code Reactor.wz/2008000-2008005.img.xml}). */
    static final Map<String, Integer> SCAR_PIECE = scarPieces();

    private static Map<String, Integer> scarPieces() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("scar1", 4001046);
        m.put("scar2", 4001049);
        m.put("scar3", 4001047);
        m.put("scar4", 4001044);
        m.put("scar5", 4001048);
        m.put("scar6", 4001045);
        return java.util.Collections.unmodifiableMap(m);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public java.util.Set<Integer> extraMaps(StageView view) {
        // the garden the statue's talk sends the team to, and the levers' room, whose clear opened our sixth piece
        return java.util.Set.of(GARDEN_MAP, 920010700);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();

        // the levers' clear opens the box that holds the sixth piece; take it before leaving that room
        SolverStep box = OpqBoxes.take(view, id, OpqBoxes.ON_WAY_UP);
        if (box != null) {
            return box;
        }

        if (view.itemCount(id, ROOT_OF_LIFE) > 0) {
            return view.mapOf(id) == STATUE_MAP
                    ? new SolverStep.PlaceItem(ROOT_OF_LIFE, 1, BASE_REACTOR, 0, 0,
                    "put the Root of Life at the base of the statue")
                    : new SolverStep.WarpTo(STATUE_MAP, "back to the statue with the Root of Life");
        }

        if (view.mapOf(id) == GARDEN_MAP) {
            return garden(view, id);
        }
        if (view.mapOf(id) != STATUE_MAP) {
            return new SolverStep.WarpTo(STATUE_MAP, "to the statue in the centre tower");
        }

        for (Map.Entry<String, Integer> scar : SCAR_PIECE.entrySet()) {
            if (view.reactorState(scar.getKey()) <= 0 && view.itemCount(id, scar.getValue()) > 0) {
                return new SolverStep.PlaceItem(scar.getValue(), 1, scar.getKey(), 0, 0,
                        "put the statue piece on " + scar.getKey());
            }
        }
        if (!complete(view)) {
            return new SolverStep.Wait(TALK_GAP_MS, "waiting for the party to fill the six scars");
        }
        if (!member.leader()) {
            return new SolverStep.Wait(TALK_GAP_MS, "waiting for Eak to open the garden");
        }
        return new SolverStep.TalkNpc(EAK_NPC, 0, 0);
    }

    /** The garden: the pot spawns Papa Pixie, he drops the Root of Life, and the party carries it back. */
    private static SolverStep garden(StageView view, int id) {
        if (view.floorItems(ROOT_OF_LIFE, id) > 0) {
            return new SolverStep.Loot(ROOT_OF_LIFE);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, ROOT_OF_LIFE, 1);
        }
        // no timestamp yet means the pot has not been hit in this instance; a subtraction against a sentinel
        // would overflow (nowMs() is small, Long.MIN_VALUE is not)
        Object at = view.stageMemory().get(PIXIE_AT);
        if (!(at instanceof Long last) || view.nowMs() - last >= RESPAWN_MS) {
            view.stageMemory().put(PIXIE_AT, view.nowMs());
            return new SolverStep.HitReactor(POT_REACTOR);
        }
        return new SolverStep.Wait(TALK_GAP_MS, "waiting for Papa Pixie to come out of the pot");
    }

    /** {@code scripts/npc/2013001.js:11-19}: every scar has been used at least once. */
    static boolean complete(StageView view) {
        for (String scar : SCAR_PIECE.keySet()) {
            if (view.reactorState(scar) < 1) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Optional<String> leaderPrompt(StageView view, RunMember leader) {
        if (complete(view)) {
            return Optional.of("all six pieces are in the statue, take the party to the garden");
        }
        return Optional.of("fill the six scars with the pieces the tower gave us");
    }
}
