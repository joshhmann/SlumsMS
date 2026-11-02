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

    /** A drop on the stage's floor: where it lies, whose it is, when it fell (the view's clock). */
    record FloorDrop(int x, int y, int ownerId, long droppedAtMs) {
    }

    /**
     * The newest drop of {@code itemId} that this member dropped and nobody has picked up yet (1.6.2: a pass a bot
     * dropped at a real leader's feet), if one lies on the stage's floor.
     */
    default Optional<FloorDrop> dropBy(int dropperId, int itemId) {
        return Optional.empty();
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

    /** The map a member is on (a member may still be on the last stage's map, or already in the bonus). */
    default int mapOf(int characterId) {
        return stage().mapId();
    }

    /**
     * The event state of the reactor with this WZ name on the stage's map, or {@code -1} when the map has no such
     * reactor. OPQ keeps its levers' on/off in it ({@code scripts/reactor/2002005.js}) and the stage script keeps the
     * target the party must match in an event property ({@code stage6_c}), so a solver reads both and says which
     * levers to change.
     */
    default int reactorState(String name) {
        return -1;
    }

    /**
     * The slot the map's event script armed the named item reactor with, or {@code -1}. Orbis PQ's lobby arms the
     * reactor named {@code music} with {@code new Date().getDay()} ({@code scripts/event/OrbisPQ.js:162-163}) and the
     * reactor takes {@code 4001056 + getEventState()} as its item ({@code Reactor.getReactItem(byte)}), so the pass the
     * party has to fetch is the reactor's day, not the bot's own clock.
     */
    default int reactorEventState(String name) {
        return -1;
    }

    /**
     * The map's {@code index}th player area - the rectangles {@code MapFactory.java:273-279} loads from a map's
     * {@code area} nodes and {@code MapleMap.getNumPlayersInArea} counts characters in - or {@code null} when the map
     * has fewer. Orbis PQ's sealed room asks the script for exactly that count on three platforms.
     */
    default java.awt.Rectangle area(int index) {
        return null;
    }

    /** Live monsters on the stage's map, of the stage's {@code mobs} when it lists any. */
    default int liveMobs() {
        return 0;
    }

    /**
     * The live monsters of a part-based boss fight on the stage's map, as {@link BossTargetRules} sees them (id, role,
     * fake, whether a human fights it, its immunities). Empty for every stage that is not a boss, and empty in the fake
     * views tests use.
     */
    default List<BossTargetRules.Part> bossParts() {
        return List.of();
    }

    /** How this member hits (magic or not, melee or from afar): the target order's arms split by the member. */
    default BossTargetRules.Member fighterOf(int characterId) {
        return new BossTargetRules.Member(false, true);
    }

    /** Whether a member is alive (1.6.2, bot audit K5: a dead leader stops the run's driving). */
    default boolean alive(int characterId) {
        return true;
    }

    /** The run's clock for this tick. */
    default long nowMs() {
        return 0;
    }

    /**
     * Scratch memory for the current stage, kept in the run's blackboard ({@link RunState}) and emptied on every
     * stage change: what a solver must remember between ticks (the formation it assigned, when a formation became
     * steady). Solvers stay stateless; this is run state, never game state.
     */
    default java.util.Map<String, Object> stageMemory() {
        return new java.util.HashMap<>();
    }

    /** Whether a real player (not a bot or an owned alt) is standing in this rectangle right now. */
    default boolean playerIn(MechanicsModel.Position p) {
        for (RunMember m : members()) {
            if (!m.bot()) {
                int[] at = positionOf(m.characterId());
                if (mapOf(m.characterId()) == stage().mapId() && p.contains(at[0], at[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether this character is registered with the event instance right now -- not whether it is one of the run's
     * members: a boss run holding itself for a revive has removed that member, so {@link #members()} cannot answer this.
     */
    default boolean registered(int characterId) {
        return members().stream().anyMatch(m -> m.characterId() == characterId);
    }

    /** The character's name, for lines that have to say who is being waited for; empty when it is not in the instance. */
    default java.util.Optional<String> nameOf(int characterId) {
        return java.util.Optional.empty();
    }

    default int partySize() {
        return members().size();
    }

    /** The party leader among the members (the event's leader), if one is still in the stage. */
    default Optional<RunMember> leader() {
        return members().stream().filter(RunMember::leader).findFirst();
    }

    /**
     * Whether the stage is clear: the property its data names is present, or - when the data also gives
     * {@code equals} - holds that value. A boss script writes its property at setup (Zakum: {@code defeatedBoss=0}), so
     * reading presence there would clear the stage before anyone fought.
     */
    default boolean cleared() {
        MechanicsModel.Clear clear = stage().clear();
        if (clear == null || clear.eventProperty() == null) {
            return false;
        }
        Optional<String> value = eventProperty(clear.eventProperty());
        return clear.equals() == null ? value.isPresent() : value.map(v -> v.trim().equals(clear.equals())).orElse(false);
    }
}
