package soloMapling.mechanics;

/**
 * {@code delli-hold} (ticket 164, DelliBattle, "Save Delli"): one room, one clock, and the only failure is a stray hit.
 *
 * <p>`scripts/event/DelliBattle.js` is not a quest with stages, it is a **survival clock**:
 *
 * <ul>
 *   <li>`setup` (`:96-106`) starts the event timer at six minutes and re-spawns the room every fifteen seconds
 *       (`respawnStages`, `:109-112`).</li>
 *   <li>`monsterKilled` (`:213`) is empty: killing anything advances nothing at all.</li>
 *   <li>`scheduledTimeout` (`:119-124`) is the clear - when the six minutes run out it kills the room's remaining
 *       monsters, shows the clear effect and calls `clearPQ` (`:208-211`), which sets the instance's flag and stops the
 *       timer.</li>
 *   <li>`friendlyKilled` (`:194-199`) ends the run if `9300162` dies - Delli herself.</li>
 * </ul>
 *
 * <p>So the row's `clear: { instance: true }` is written by the server's own clock, and the bots' only job is to keep the
 * room worked without ever touching the friendly, which `BotAttackDriver.botMayTarget` already refuses for WZ-friendly
 * mobs. The row's `timeLimitMinutes` is deliberately longer than the script's six, so the budget never pre-empts the
 * flag that ends the run.
 */
public final class DelliHoldSolver implements StageSolver {

    public static final String ID = "delli-hold";

    /** The room the battle is fought in, and the one friendly in it. */
    static final int ROOM = 925010300;
    static final int DELLI = 9300162;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        if (view.mapOf(member.characterId()) != ROOM) {
            return new SolverStep.WarpTo(ROOM, "into the room Delli is holding");
        }
        if (view.liveMobs() > 0) {
            // any of the room's monsters; the friendly is refused by the attack driver, not by a list here
            return new SolverStep.Fight(0, 0, 0);
        }
        return new SolverStep.Wait(1_500, "holding the room until Delli's clock runs out");
    }
}
