package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code hpq-beds} (ticket 164, Henesys PQ stage 1): plant one primrose seed on each of the six beds.
 *
 * <p>The map {@code 910010000} carries six item reactors named {@code moonflower1..6}
 * ({@code Map.wz/Map/Map9/910010000.img.xml}), ids {@code 9108000..9108005}, and each one's own WZ state names the seed
 * it takes - {@code Reactor.wz/9108000..9108005.img.xml:11-19}: one type-100 event, item {@code 4001095..4001100} in
 * bed order, quantity 1, hit box {@code lt(-19,-42) rb(20,16)} around the reactor's own position. That is the
 * <em>accept</em> side, and it is all the client data says: nothing in WZ binds a dropped seed to a bed, which is why
 * this solver picks the bed by the seed it holds rather than trusting a binding - and why dropping at the bed's own
 * position is all the placement that is needed (the box contains it). {@code MapleMap.activateItemReactors} takes a drop only when the item
 * <em>and</em> the whole quantity match and the drop lies inside that reactor's area, so the row names both per bed
 * ({@code PLACE_ITEM} with {@code reactor: "9108000"} and {@code item: 4001095}) and this solver drops exactly that
 * stack on exactly that bed ({@link SolverStep.PlaceItemOn}).
 *
 * <p>Why it cannot be the generic {@code kill-collect-hand-in}: that solver hands a count to an NPC, and here nothing
 * is handed in. The seeds are planted, each bed raises the instance's {@code stage} property by one
 * ({@code scripts/reactor/9108000.js:6-8}), and the stage clears when the property reads {@code "6"} - which is also why
 * no bed may ever be planted twice (a seventh activation skips {@code "6"} and the run can no longer clear).
 *
 * <p>Seeds come from the map's own monsters ({@code 9300062/3/4}, {@code 9300081/2/3}); each drop is exclusive to the
 * instance ({@code HenesysPQ.js:73-76}), so there is no competition with another party. The Moon Bunny
 * ({@code 9300061}) is not on this map yet - it is spawned by the sixth bed ({@code scripts/reactor/9108000.js:10-14}) -
 * and this solver never names it: killing it ends the run ({@code HenesysPQ.js:251-255}).
 */
public final class HpqBedsSolver implements StageSolver {

    public static final String ID = "hpq-beds";

    /** The instance property the beds raise; the stage clears at exactly this value ({@code scripts/reactor/910800*.js}). */
    static final String STAGE_PROPERTY = "stage";

    static final String STAGE_DONE = "6";

    /**
     * How long one member owns a bed: long enough to walk to it, drop, and see the server's 5000 ms activation land
     * ({@code MapleMap.activateItemReactors}). Two members holding the same seed must not both drop on the same bed -
     * both drops would be inside the activation window and both would activate, and {@code stage} would skip "6".
     */
    static final long CLAIM_MS = 12_000;

    /** The stage-memory key a bed's claim lives under; {@code stageMemory()} is per run and cleared per stage. */
    static final String CLAIM_PREFIX = "hpq.bed.claim.";

    /**
     * The pairing the beds' own WZ event slots carry, in bed order: reactor id to the one seed it accepts
     * ({@code Map.wz/Reactor.wz/9108000.img.xml`..`9108005.img.xml}, each {@code event/0} type 100, quantity 1).
     */
    static final Map<Integer, Integer> BEDS = new LinkedHashMap<>();

    static {
        BEDS.put(9108000, 4001095);
        BEDS.put(9108001, 4001096);
        BEDS.put(9108002, 4001097);
        BEDS.put(9108003, 4001098);
        BEDS.put(9108004, 4001099);
        BEDS.put(9108005, 4001100);
    }

    @Override
    public String id() {
        return ID;
    }

    /** How long a member waits while another one walks to the bed and the server's activation lands. */
    static final long ACTIVATION_WAIT_MS = 2_000;

    /** Whether anybody in the party already holds this seed, so farming more of it would be waste. */
    static boolean partyHolds(StageView view, int seed) {
        for (RunMember m : view.members()) {
            if (view.itemCount(m.characterId(), seed) > 0) {
                return true;
            }
        }
        return false;
    }

    /** The seed a bed takes, or {@code 0} when the id is not one of the six. */
    static int seedFor(int reactorId) {
        return BEDS.getOrDefault(reactorId, 0);
    }

    /**
     * The pairing this stage actually plants: the row's own {@code PLACE_ITEM} objectives, which is where the six pairs
     * live (data, not code); {@link #BEDS} is the WZ truth a test pins them against, never a second runtime source.
     */
    static Map<Integer, Integer> beds(StageView view) {
        Map<Integer, Integer> row = fromRow(view);
        return row.isEmpty() ? BEDS : row;
    }

    /** How many beds have been planted: the ones whose reactor state has left zero. */
    static int planted(StageView view) {
        int planted = 0;
        for (int bed : beds(view).keySet()) {
            if (view.reactorState(bed) > 0) {
                planted++;
            }
        }
        return planted;
    }

    /** The stage property as the script wrote it, or {@code null} while setup still has it missing. */
    static String stageProperty(StageView view) {
        return view.eventProperty(STAGE_PROPERTY).orElse(null);
    }

    /**
     * The bed the party should fill next - the first one still empty - or {@code 0} when all six are planted. Its seed
     * is what every member farms for, so nobody has to guess which of the six colours is still missing.
     */
    static int nextEmptyBed(StageView view) {
        for (int bed : beds(view).keySet()) {
            if (view.reactorState(bed) == 0) {
                return bed;
            }
        }
        return 0;
    }

    /**
     * Take the bed for this member until {@code nowMs + CLAIM_MS}, or say it is already taken. A claim that ran out is
     * free again, so a member that died or walked away does not leave a bed unusable.
     */
    static boolean claim(StageView view, int reactorId) {
        Object now = view.nowMs() + CLAIM_MS;
        Object previous = view.stageMemory().putIfAbsent(CLAIM_PREFIX + reactorId, now);
        if (previous == null) {
            return true;
        }
        if (previous instanceof Long until && until > view.nowMs()) {
            return false;
        }
        view.stageMemory().put(CLAIM_PREFIX + reactorId, now);
        return true;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "up to the primrose hill");
        }
        if (STAGE_DONE.equals(stageProperty(view))) {
            // all six are planted: the sixth bed's script has just spawned the Moon Bunny, and the stage is clear
            return new SolverStep.Wait(1_000, "all six beds are planted");
        }
        // 1. a seed I hold whose bed is still empty: plant it, on that bed, exactly that stack
        for (Map.Entry<Integer, Integer> bed : beds(view).entrySet()) {
            int reactorId = bed.getKey();
            int seed = bed.getValue();
            if (view.reactorState(reactorId) == 0 && view.itemCount(id, seed) > 0 && claim(view, reactorId)) {
                return new SolverStep.PlaceItemOn(reactorId, seed, 1, "plant seed " + seed + " on bed " + reactorId);
            }
        }
        // 2. a seed of the next empty bed lying on the floor: take it, then plant it on the next tick
        int wanted = nextEmptyBed(view);
        if (wanted > 0) {
            int seed = beds(view).getOrDefault(wanted, 0);
            if (view.floorItems(seed, id) > 0) {
                return new SolverStep.Loot(seed);
            }
            if (partyHolds(view, seed)) {
                // somebody already has it and is walking to the bed, or waiting out the last activation: killing more
                // of the hill's leaves would only spend the map's monsters, and the hill re-spawns on a 15 s timer
                return new SolverStep.Wait(ACTIVATION_WAIT_MS, "waiting for the party to plant bed " + wanted);
            }
            // 3. farm the seed the next empty bed wants: any monster of the map may drop it
            if (view.liveMobs() > 0) {
                return new SolverStep.Fight(0, seed, 1);
            }
            return new SolverStep.Wait(1_500, "waiting for the primrose leaves to grow back for bed " + wanted);
        }
        // every bed is planted but the property has not been read yet: the activation is on its way
        return new SolverStep.Wait(1_000, "waiting for the sixth bed to bloom");
    }

    /** The {@code PLACE_ITEM} objectives the row gives this stage, as reactor id to item id, for the tests to pin. */
    static Map<Integer, Integer> fromRow(StageView view) {
        Map<Integer, Integer> pairs = new LinkedHashMap<>();
        for (Objective o : view.stage().objectives()) {
            if (o.kind() != ObjectiveKind.PLACE_ITEM || !o.hasReactor() || o.itemId() <= 0) {
                continue;
            }
            try {
                pairs.put(Integer.parseInt(o.reactor().trim()), o.itemId());
            } catch (NumberFormatException e) {
                // a named reactor wants PlaceItem, not this step: leave it to the generic solver
            }
        }
        return pairs;
    }
}
