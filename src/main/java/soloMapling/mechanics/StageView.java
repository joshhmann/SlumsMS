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

    /** One standing of an NPC on a map: the object id the client would send, and where it stands. */
    record NpcPlacement(int objectId, int x, int y) {
    }

    /**
     * Every standing of an NPC on the stage map, in the map's own order - the same NPC id can stand in 26 places
     * (Magatia's books) and the script reads {@code cm.getNpcObjectId()} to tell them apart, so addressing "the NPC" by
     * id alone is addressing the first one only.
     */
    default java.util.List<NpcPlacement> npcPlacements(int npcId) {
        return java.util.List.of();
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
     * The state of the reactor with this id on the stage's map, or {@code -1}. Henesys PQ's six seed beds share one
     * script and one id range ({@code 9108000..9108005}) and none of them has a WZ name in {@code scripts/}, so a
     * solver that must not plant the same bed twice reads them by id ({@link SolverStep.PlaceItemOn}).
     */
    default int reactorState(int reactorId) {
        return -1;
    }

    /**
     * 1.6.5 b331: whether the reactor with this id stands on the stage's map in a state a real client could hit
     * ({@link StepExecutor#clientCanHit}). A spent ZPQ chest sits in a state with no hit; a solver that reads only
     * {@link #reactorState(int)} {@code >= 0} would keep asking to hit it.
     */
    default boolean reactorHittable(int reactorId) {
        return reactorState(reactorId) >= 0;
    }

    /**
     * 1.6.6 b365: the item and quantity the reactor with this id takes in its current state, when that state is an item
     * trigger (Reactor.wz type 100: a player opens it by dropping the stack inside its box, never by hitting it), or
     * {@code null}. {@code [itemId, quantity]}, as {@code MapleMap.activateItemReactors} matches it.
     */
    default int[] reactorTakes(int reactorId) {
        return null;
    }

    /**
     * One reactor standing on the stage's map, by everything a script can tell it apart with: the map's own object id
     * (the key {@link SolverStep.HitReactorByOid} addresses it by), its WZ id, its WZ name ({@code ""} when it has
     * none), its current state and where it stands.
     */
    record ReactorPlacement(int objectId, int reactorId, String name, int state, int x, int y) {
    }

    /**
     * Every reactor on the stage's map, in the map's own order; empty when the map has none or is not in this instance.
     * Guild Quest's twenty statues share three WZ ids and the script names them by object id, so a solver that has to
     * hit one of them reads their object ids here.
     */
    default List<ReactorPlacement> reactors() {
        return stage() == null ? List.of() : reactorsOn(stage().mapId());
    }

    /**
     * Every reactor on one map of the instance (a stage that spans several maps - Guild Quest's spear hall is seven -
     * is read room by room); the stage's own map answers {@link #reactors()}. Empty for a map the instance does not hold.
     * A fake view that only knows the stage's map answers nothing for another.
     */
    default List<ReactorPlacement> reactorsOn(int mapId) {
        return List.of();
    }

    /** Drops of this item lying on one map of the instance that this member may pick up (see {@link #floorItems}). */
    default int floorItemsOn(int mapId, int itemId, int forCharacterId) {
        return 0;
    }

    /** How many live monsters with this id stand on one map of the instance. */
    default int mobsAliveOn(int mapId, int mobId) {
        return 0;
    }

    /** Where an NPC stands on one map of the instance, as {x, y}, if it is there. */
    default Optional<int[]> npcPositionOn(int mapId, int npcId) {
        return Optional.empty();
    }

    /**
     * The state of the reactor with this map <em>object id</em>, or {@code -1} when the map holds no reactor by that
     * id: {@link #reactorState(int)} reads by WZ id, which names one reactor out of twenty for a map that stands them in
     * rows.
     */
    default int reactorStateByOid(int objectId) {
        return -1;
    }

    /**
     * Whether this member has the item on its body (the equipped inventory), not merely in its bag. A map that hurts
     * whoever is not wearing its protect item asks exactly this ({@code Character.doHurtHp}).
     */
    default boolean wearing(int characterId, int itemId) {
        return false;
    }

    /**
     * Why this member may not put the carried item on yet ("1032033 needs level 10, the bot is level 8"), or empty when
     * it may (or when nothing can be said). The wear rules are the gear system's.
     */
    default Optional<String> cannotWear(int characterId, int itemId) {
        return Optional.empty();
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

    /**
     * The map-to-map portals of {@code mapId}, by portal name (the name a step walks into, {@code SolverStep
     * .EnterPortal}) to the map it leads to; empty when the map has none or is not in this instance. LudiMaze PQ's
     * sixteen maps are joined only by these ({@code Map.wz/Map/Map8/8090500*.img.xml}, {@code pt 2} portals named
     * {@code t000}..{@code t003}), so a solver that has to find its way there reads them rather than assuming a layout.
     */
    default java.util.Map<String, Integer> portalsOn(int mapId) {
        return java.util.Map.of();
    }

    /**
     * The members still in this instance that are standing somewhere other than {@code mapId}: empty when everyone is
     * there. The scripts' {@code isEventTeamTogether()} is exactly this question ({@code scripts/npc/9103000.js:57-61}
     * refuses the leader while one member is missing), so a solver that needs the party on one map asks it instead of
     * counting members the instance has already let go.
     */
    default java.util.List<Integer> missingFrom(int mapId) {
        java.util.List<Integer> missing = new java.util.ArrayList<>();
        for (RunMember m : members()) {
            int id = m.characterId();
            if (registered(id) && alive(id) && mapOf(id) != mapId) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** {@code isEventTeamTogether()}: no member of this instance is anywhere else. */
    default boolean teamTogether(int mapId) {
        return missingFrom(mapId).isEmpty();
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
     * Whether the instance itself says the event is cleared ({@code EventInstanceManager.isEventCleared()}). The only
     * signal a stage has when its script clears without writing a property.
     */
    default boolean eventCleared() {
        return false;
    }

    /**
     * Whether a branch's condition holds of the room now ({@code branches:} on a stage): an event property present, or
     * equal to a value, or a reactor - by WZ name, or by WZ id when the name is all digits - at exactly the state it
     * names. A branch with no condition always holds. A reactor the map does not hold answers {@code -1} and so never
     * matches (a state is 0 or more).
     */
    default boolean holds(MechanicsModel.Branch branch) {
        if (branch.eventProperty() != null) {
            Optional<String> value = eventProperty(branch.eventProperty());
            return branch.equals() == null ? value.isPresent()
                    : value.map(v -> v.trim().equals(branch.equals())).orElse(false);
        }
        if (branch.reactor() != null) {
            String key = branch.reactor();
            boolean byId = key.chars().allMatch(c -> c >= '0' && c <= '9');
            int state = byId ? reactorState(Integer.parseInt(key)) : reactorState(key);
            return state == branch.reactorState();
        }
        return true;
    }

    /**
     * The first of the stage's branches that holds now, or null when none does (the run then goes to the next index, as
     * a stage without branches always did).
     */
    default MechanicsModel.Branch branchTaken() {
        for (MechanicsModel.Branch b : stage().branches()) {
            if (holds(b)) {
                return b;
            }
        }
        return null;
    }

    /**
     * Whether the stage is clear: the property its data names is present, or - when the data also gives
     * {@code equals} - holds that value, or - when the data says {@code instance: true} - once {@link #eventCleared()}. A boss script writes its property at setup (Zakum: {@code defeatedBoss=0}), so
     * reading presence there would clear the stage before anyone fought.
     */
    default boolean cleared() {
        MechanicsModel.Clear clear = stage().clear();
        if (clear == null) {
            return false;
        }
        if (clear.onInstance()) {
            // no property to read: the script only called eim.clearPQ() (Cafe PQ, Treasure PQ, LudiMaze PQ finales)
            return eventCleared();
        }
        if (clear.mapEmpty()) {
            // no property and no talk: the script's own passage refuses while the map has monsters (Ellin PQ stage 1)
            return liveMobs() == 0;
        }
        if (clear.reactorId() > 0) {
            // the reactor itself is the signal (Ellin PQ stage 2: the spine at state 4, no property written)
            return reactorState(clear.reactorId()) >= clear.reactorState();
        }
        if (clear.partyOnMap() > 0) {
            // the whole party standing on the map the script warps them to (Ellin PQ: 2133001.js:93-94)
            // 1.6.5 b344: a botsOnMap clear counts the run's bots only (the Zakum PQ mines), never zero of them
            List<RunMember> all = clear.botsOnly()
                    ? members().stream().filter(RunMember::bot).toList()
                    : members();
            return !all.isEmpty() && all.stream().allMatch(m -> mapOf(m.characterId()) == clear.partyOnMap());
        }
        if (clear.eventProperty() == null) {
            return false;
        }
        Optional<String> value = eventProperty(clear.eventProperty());
        return clear.equals() == null ? value.isPresent() : value.map(v -> v.trim().equals(clear.equals())).orElse(false);
    }
}
