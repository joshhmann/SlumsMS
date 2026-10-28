package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What a solver may read about a running stage. The live adapter (a later phase) wraps the event instance
 * ({@code EventInstanceManager}: properties, grid, players) and the map; tests use a fake. Read-only: solvers decide,
 * they never change game state.
 */
public interface StageView {

    Stage stage();

    List<RunMember> members();

    /** The role the orchestrator gave this member on this stage. */
    String roleOf(int characterId);

    /** The member's own puzzle answer if the stage gave one (KPQ: Cloto's grid entry mapped to the answer). */
    OptionalInt answerFor(int characterId);

    int itemCount(int characterId, int itemId);

    Optional<String> eventProperty(String key);

    /** Where a member stands, as {x, y}. */
    int[] positionOf(int characterId);

    default int partySize() {
        return members().size();
    }

    default boolean cleared() {
        return stage().clear() != null && eventProperty(stage().clear().eventProperty()).isPresent();
    }
}
