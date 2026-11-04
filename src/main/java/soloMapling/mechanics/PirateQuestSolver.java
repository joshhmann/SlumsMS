package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * {@code pirate-quest} (ticket 164, Pirate PQ, the Lord Pirate's ship): six maps in one stage, a chain rather than a
 * count. Every step is the script's own, read from our tree:
 *
 * <ol>
 *   <li><b>925100000, the deck.</b> Empty the map, then the passage opens: {@code scripts/portal/davy_next0.js} refuses
 *       while {@code getMonsters().size() > 0}.</li>
 *   <li><b>925100100, the medals.</b> The guide {@code 2094002} asks for twenty Rookie Medals {@code 4001120}
 *       ({@code scripts/npc/2094002.js:47-57}), then twenty Rising {@code 4001121} ({@code :58-67}), then twenty Veteran
 *       {@code 4001122} ({@code :68-78}); each talk sets {@code stage2} to 1, 2, 3, and the passage
 *       {@code davy_next1.js} opens at exactly {@code stage2 === "3"}. The leader hands them in; the medals themselves
 *       are the map's own drops.</li>
 *   <li><b>925100200 and 925100300.</b> Empty the map ({@code davy_next2.js}, {@code davy_next3.js}). Both also ask
 *       {@code passedGrindMode}, which is true unless the instance's {@code grindMode} is set, and the scripts set it to
 *       {@code 0}.</li>
 *   <li><b>925100400, the four doors.</b> {@code sMob1..sMob4} are item reactors {@code 2519000..2519003}, each taking
 *       exactly one Old Metal Key {@code 4001117} ({@code Reactor.wz/2519000..2519003.img.xml}: type 100, quantity 1);
 *       {@code davy_next4.js} opens only when all four have left state 0 and no monster is left, and the keys come from
 *       this map's own monsters ({@code 4001117} is one of the instance's exclusives,
 *       {@code PiratePQ.js:73-76}).</li>
 *   <li><b>925100500, the Lord Pirate.</b> {@code davy_next4.js} spawns him on the way in (9300119, or 9300105/9300106
 *       when chests were opened), and the rescued leader {@code 2094001} clears the quest with a talk
 *       ({@code scripts/npc/2094001.js:45-58}), which calls {@code eim.clearPQ()}: the row clears on the instance's own
 *       flag, because {@code PiratePQ.js:337-344} writes no property.</li>
 * </ol>
 *
 * <p>The row gives this solver the maps ({@code maps:}), the items and the clear; the ids below are the scripts' and are
 * pinned by {@code PiratePqSolversTest}.
 */
public final class PirateQuestSolver implements StageSolver {

    public static final String ID = "pirate-quest";

    static final int DECK = 925100000;
    static final int MEDALS_MAP = 925100100;
    static final int GUARDS_MAP = 925100200;
    static final int CAPTAINS_MAP = 925100300;
    static final int DOORS_MAP = 925100400;
    static final int BOSS_MAP = 925100500;

    /** The guide on the medals map, and the rescued leader on the boss map ({@code 2094002.js}, {@code 2094001.js}). */
    static final int GUIDE = 2094002;
    static final int RESCUED = 2094001;

    /** Rookie, Rising, Veteran: the three medals in the order the guide asks for them ({@code 2094002.js:47-78}). */
    static final Map<String, Integer> MEDALS = new LinkedHashMap<>();

    static {
        MEDALS.put("0", 4001120);
        MEDALS.put("1", 4001121);
        MEDALS.put("2", 4001122);
    }

    static final int MEDAL_COUNT = 20;

    /** The Old Metal Key, and the four doors it opens, in WZ id order. */
    static final int KEY = 4001117;
    static final List<Integer> DOORS = List.of(2519000, 2519001, 2519002, 2519003);

    /** The passages, by the name the map's own portal carries. */
    static final Map<Integer, String> NEXT_PORTAL = Map.of(
            DECK, "davy_next0",
            MEDALS_MAP, "davy_next1",
            GUARDS_MAP, "davy_next2",
            CAPTAINS_MAP, "davy_next3",
            DOORS_MAP, "davy_next4");

    /** The item the guide is asking for right now, from the instance's {@code stage2} property. */
    static int medalWanted(StageView view) {
        String stage = view.eventProperty("stage2").orElse("0");
        return MEDALS.getOrDefault(stage.trim(), 0);
    }

    /** How many doors are open: the ones whose state has left zero. */
    static int doorsOpen(StageView view) {
        int open = 0;
        for (int door : DOORS) {
            if (view.reactorState(door) > 0) {
                open++;
            }
        }
        return open;
    }

    /** The first door still shut, or {@code 0} when all four are open. */
    static int firstShutDoor(StageView view) {
        for (int door : DOORS) {
            if (view.reactorState(door) == 0) {
                return door;
            }
        }
        return 0;
    }

    /** The row's {@code HAND_IN} count (twenty medals), so the row and the script cannot drift apart. */
    static int handInCount(StageView view) {
        for (Objective o : view.stage().objectives()) {
            if (o.kind() == ObjectiveKind.HAND_IN && o.count().isPresent()) {
                return o.count().get().resolve(view.partySize(), OptionalInt.empty()).orElse(MEDAL_COUNT);
            }
        }
        return MEDAL_COUNT;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public java.util.Set<Integer> extraMaps(StageView view) {
        return java.util.Set.of(DECK, MEDALS_MAP, GUARDS_MAP, CAPTAINS_MAP, DOORS_MAP, BOSS_MAP);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int here = view.mapOf(id);
        if (!extraMaps(view).contains(here)) {
            return new SolverStep.WarpTo(DECK, "onto the pirate ship's deck");
        }
        return switch (here) {
            case DECK, GUARDS_MAP, CAPTAINS_MAP -> emptyTheMap(view, here);
            case MEDALS_MAP -> medals(view, member);
            case DOORS_MAP -> doors(view, member);
            default -> boss(view, member);
        };
    }

    /** "Empty the map, then the passage opens": the three maps that are nothing but a kill and a door. */
    private SolverStep emptyTheMap(StageView view, int here) {
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        String portal = NEXT_PORTAL.get(here);
        return portal == null ? new SolverStep.Wait(1_000, "waiting on map " + here)
                : new SolverStep.EnterPortal(portal);
    }

    /** The three medals, in the guide's order, pooled on the leader and handed over twenty at a time. */
    private SolverStep medals(StageView view, RunMember member) {
        int wanted = medalWanted(view);
        if (wanted <= 0) {
            String portal = NEXT_PORTAL.get(MEDALS_MAP);
            return portal == null ? new SolverStep.Wait(1_000, "the medals are done")
                    : new SolverStep.EnterPortal(portal);
        }
        int need = handInCount(view);
        int id = member.characterId();
        int have = view.itemCount(id, wanted);
        if (member.leader() && have >= need) {
            return new SolverStep.TalkNpc(GUIDE, wanted, need);
        }
        if (!member.leader() && have >= need) {
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id) {
                return PassHandover.give(view, id, wanted, have, leader);
            }
        }
        if (view.floorItems(wanted, id) > 0) {
            return new SolverStep.Loot(wanted);
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, wanted, need);
        }
        if (member.leader()) {
            return new SolverStep.Wait(1_500, "waiting for the ship's monsters to respawn for " + wanted);
        }
        RunMember leader = view.leader().orElse(null);
        if (leader != null && leader.characterId() != id) {
            int[] at = view.positionOf(leader.characterId());
            return new SolverStep.MoveTo(at[0], at[1], "back to the leader with the medals");
        }
        return new SolverStep.Wait(1_500, "waiting for the ship's monsters to respawn for " + wanted);
    }

    /** Four doors, one key each: fight for keys, drop one on the first shut door, then walk through. */
    private SolverStep doors(StageView view, RunMember member) {
        int id = member.characterId();
        int shut = firstShutDoor(view);
        if (shut > 0) {
            if (view.itemCount(id, KEY) > 0) {
                return new SolverStep.PlaceItemOn(shut, KEY, 1, "an old metal key on door " + shut);
            }
            if (view.floorItems(KEY, id) > 0) {
                return new SolverStep.Loot(KEY);
            }
            if (view.liveMobs() > 0) {
                return new SolverStep.Fight(0, KEY, 1);
            }
            if (member.leader()) {
                return new SolverStep.Wait(1_500, "waiting for the ship's guards to drop a key");
            }
            RunMember leader = view.leader().orElse(null);
            if (leader != null && leader.characterId() != id) {
                int[] at = view.positionOf(leader.characterId());
                return new SolverStep.MoveTo(at[0], at[1], "back to the leader for a key");
            }
            return new SolverStep.Wait(1_500, "waiting for the ship's guards to drop a key");
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        String portal = NEXT_PORTAL.get(DOORS_MAP);
        return portal == null ? new SolverStep.Wait(1_000, "all four doors are open")
                : new SolverStep.EnterPortal(portal);
    }

    /** The Lord Pirate, then the rescued leader's talk: the only thing in the ship that writes the clear. */
    private SolverStep boss(StageView view, RunMember member) {
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        if (member.leader()) {
            return new SolverStep.TalkNpc(RESCUED, 0, 0);
        }
        return new SolverStep.Wait(1_500, "the ship is cleared: waiting for the leader's talk");
    }
}
