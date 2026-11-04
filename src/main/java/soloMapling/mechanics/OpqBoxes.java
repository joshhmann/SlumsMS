package soloMapling.mechanics;

/**
 * The reward box an Orbis PQ room opens as it clears, and the statue piece inside it.
 *
 * <p>Our scripts are the truth. The lobby's talk, the sealed room's talk and "on the way up"'s talk each end with
 * {@code getReactorByName("stone3"|"stone4"|"stone6").forceHitReactor(1)} followed by {@code clearStage(...)}, and
 * {@code Reactor.forceHitReactor} ({@code src/main/java/server/maps/Reactor.java:196-204}) only resets the reactor's
 * state and broadcasts - it does not run the reactor's script. So the box is open with the piece still in it, and one
 * more hit runs {@code scripts/reactor/2002011.js} ({@code act() { rm.dropItems(); }}) for it.
 *
 * <p>Nobody is left in the room once the stage clears - the next stage's solver is asked for a step straight away - so
 * the stage that follows takes the piece before it warps on. A party that misses it can still finish the tower: each
 * room's box stays on its map.
 */
final class OpqBoxes {

    /** One box: the map it stands on, the WZ name the script uses, and the statue piece it holds. */
    record Box(int mapId, String name, int piece) {
    }

    static final Box LOBBY = new Box(920010400, "stone3", 4001046);
    static final Box SEALED = new Box(920010500, "stone4", 4001047);
    static final Box ON_WAY_UP = new Box(920010700, "stone6", 4001049);

    /** How long a member keeps hitting an open box before going on without the piece (a missing drop row cannot wedge). */
    static final long GIVE_UP_MS = 60_000;

    private OpqBoxes() {
    }

    /**
     * The step that empties {@code box} for this member, or {@code null} when there is nothing left to do there: it is
     * on another map, the piece is already held, or it has been hit for longer than {@link #GIVE_UP_MS}.
     */
    static SolverStep take(StageView view, int characterId, Box box) {
        if (view.mapOf(characterId) != box.mapId() || view.itemCount(characterId, box.piece()) > 0) {
            return null;
        }
        String since = "boxes." + box.name();
        Object previous = view.stageMemory().putIfAbsent(since, view.nowMs());
        long startedAt = previous instanceof Long at ? at : view.nowMs();
        if (view.nowMs() - startedAt > GIVE_UP_MS) {
            return null;
        }
        if (view.floorItems(box.piece(), characterId) > 0) {
            return new SolverStep.Loot(box.piece());
        }
        return new SolverStep.HitReactorNamed(box.name());
    }
}
