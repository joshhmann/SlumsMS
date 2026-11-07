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

    /** b365: the Nependeath's seed, which the garden's seed pots take (Reactor.wz 2001000/2001001 state 0 type 100). */
    static final int SEED = 4001053;
    /** b365: the Royal Nependeath's Transparent Item, which the pot 2001016 takes (state 0 type 100). */
    static final int TRANSPARENT_ITEM = 4001074;
    /** b365: Papa Pixie's Even Stranger Seed (drop_data 9300039, 60%), which the pot 2002003 takes for the Grass. */
    static final int STRANGER_SEED = 4001054;
    /** b365: the pot that grows the Grass of Life (Reactor.wz 2002003 state 0 type 100, 1 x 4001054; reactordrops). */
    static final int GRASS_POT = 2002003;
    /** b365: how long a seed dropped in a pot is waited on (the server takes it 5 s after it lands). */
    static final long SEED_IN_FLIGHT_MS = 8_000;
    /** b365: the seed pots, by WZ id. They share the name "pot" with 2002003 (Grass of Life), so they go by position. */
    static final java.util.Set<Integer> SEED_POTS = java.util.Set.of(2001000, 2001001);

    /**
     * The garden (1.6.6 b365, the players' route). The garden's Nependeaths (9300048) drop seeds; a seed dropped in a
     * seed pot ({@code scripts/reactor/2001000.js}) grows a Nependeath or a Royal Nependeath (9300049), always a Royal
     * after the seventh; the Royal drops the Transparent Item, which, landing in the pot 2001016 at its feet, brings out
     * Papa Pixie ({@code 2001016.js}); he drops the Even Stranger Seed, which the pot 2002003 turns into the Grass of
     * Life ({@code 2002003.js}: drops it and sets statusStg7) for the statue. Every pot is an item reactor: none is hit.
     */
    private static SolverStep garden(StageView view, int id) {
        if (view.floorItems(ROOT_OF_LIFE, id) > 0) {
            return new SolverStep.Loot(ROOT_OF_LIFE);
        }
        if (view.itemCount(id, STRANGER_SEED) > 0) {
            for (StageView.ReactorPlacement pot : view.reactorsOn(GARDEN_MAP)) {
                if (pot.reactorId() == GRASS_POT && pot.state() == 0) {
                    return new SolverStep.PlaceItem(STRANGER_SEED, 1, "", pot.x(), pot.y(),
                            "the Even Stranger Seed in the pot that grows the Grass of Life");
                }
            }
        }
        if (view.floorItems(STRANGER_SEED, id) > 0) {
            return new SolverStep.Loot(STRANGER_SEED);
        }
        if (view.itemCount(id, TRANSPARENT_ITEM) > 0) {
            // a Royal that wandered off its pot before it died: carry its item to a pot by hand, as a player would
            return new SolverStep.PlaceItemOn(POT_REACTOR, TRANSPARENT_ITEM, 1, "the Transparent Item into the pot");
        }
        if (view.floorItems(TRANSPARENT_ITEM, id) > 0) {
            return new SolverStep.Loot(TRANSPARENT_ITEM);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, STRANGER_SEED, 1);
        }
        if (view.itemCount(id, SEED) > 0) {
            for (StageView.ReactorPlacement pot : view.reactorsOn(GARDEN_MAP)) {
                // a pot takes its seed 5 s after it lands (MapleMap.activateItemReactors): one seed on its way is
                // enough, so the next one goes to another pot rather than onto the same one
                String key = "seedAt:" + pot.x() + "," + pot.y();
                Object at = view.stageMemory().get(key);
                boolean pending = at instanceof Long t && view.nowMs() - t < SEED_IN_FLIGHT_MS;
                if (SEED_POTS.contains(pot.reactorId()) && pot.state() == 0 && !pending) {
                    view.stageMemory().put(key, view.nowMs());
                    return new SolverStep.PlaceItem(SEED, 1, "", pot.x(), pot.y(), "a seed in the pot at ("
                            + pot.x() + ", " + pot.y() + ")");
                }
            }
            return new SolverStep.Wait(TALK_GAP_MS, "every seed pot is planted: waiting for what grows");
        }
        if (view.floorItems(SEED, id) > 0) {
            return new SolverStep.Loot(SEED);
        }
        return new SolverStep.Wait(TALK_GAP_MS, "waiting for the garden to grow a Royal Nependeath");
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
