package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a solver may read about a running stage. The live adapter ({@link LiveStageView}) wraps the event instance
 * ({@code EventInstanceManager}: properties, grid, players) and the map; tests use a fake. Read-only: solvers decide,
 * they never change game state.
 */
public interface StageView {

    Stage stage();

    /** The run's members still in the stage (real players included). */
    List<RunMember> members();

    /** The role the orchestrator gave this member on this stage. */
    String roleOf(int characterId);

    /** The member's own puzzle answer if the stage gave one (KPQ: Cloto's grid entry mapped to the answer). */
    OptionalInt answerFor(int characterId);

    int itemCount(int characterId, int itemId);

    Optional<String> eventProperty(String key);

    /** Where a member stands, as {x, y}. */
    int[] positionOf(int characterId);

    /**
     * The per-member status the stage script keeps for this member, or -1 when it keeps none. KPQ stage 1 is Cloto's
     * grid ({@code eim.gridCheck}): -1 no question yet, 0 coupons accepted (pass given), 1..7 the question asked.
     */
    default int memberStatus(int characterId) {
        return -1;
    }

    /** Drops of this item on the stage's floor that this member may pick up (KPQ: passes dropped for the leader). */
    default int floorItems(int itemId, int forCharacterId) {
        return 0;
    }

    /**
     * Wrong tries this member still means to show before the right one, in the order it will show them (KPQ stage 1:
     * coupon counts below the answer it hands in on the way, ascending). Planned by the orchestrator with
     * {@link TryPlanner}; empty for a perfect member.
     */
    default List<Integer> wrongTriesLeft(int characterId) {
        return List.of();
    }

    /** Where an NPC stands on the stage map, as {x, y}, if it is there. */
    default Optional<int[]> npcPosition(int npcId) {
        return Optional.empty();
    }

    default int partySize() {
        return members().size();
    }

    /** The party leader among the members (the event's leader), if one is still in the stage. */
    default Optional<RunMember> leader() {
        return members().stream().filter(RunMember::leader).findFirst();
    }

    default boolean cleared() {
        return stage().clear() != null && eventProperty(stage().clear().eventProperty()).isPresent();
    }
}
