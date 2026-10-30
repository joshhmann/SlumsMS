package soloMapling.mechanics;

/**
 * The next thing a solver wants one member to do. Steps are intents: the bot's own movement (BotNav / GCMovement),
 * attack ({@code BotAttackDriver}) and NPC code carry them out, so a step never moves or hits anything itself.
 */
public sealed interface SolverStep {

    /** Walk/jump/climb to a point (a position's centre, an NPC, a portal). */
    record MoveTo(int x, int y, String why) implements SolverStep {
    }

    /** Fight a mob kind until {@code untilItemCount} of {@code itemId} is held (0 = until the map is clear). */
    record Fight(int mobId, int itemId, int untilItemCount) implements SolverStep {
    }

    /** Pick up an item from the floor or a reactor drop. */
    record Loot(int itemId) implements SolverStep {
    }

    /** Talk to an NPC, optionally holding {@code itemId} x {@code count} to hand in (0 = just talk). */
    record TalkNpc(int npcId, int itemId, int count) implements SolverStep {
    }

    /** Drop items for another member to loot (passes to the leader). */
    record DropFor(int itemId, int count, int toCharacterId) implements SolverStep {
    }

    /** Hit a reactor (box, lever). */
    record HitReactor(int reactorOid) implements SolverStep {
    }

    /** Say something in party chat (a try, a complaint, a callout). */
    record Say(String line) implements SolverStep {
    }

    /** Wait, e.g. for the leader or a timer. */
    record Wait(long millis, String why) implements SolverStep {
    }

    /** Nothing left for this member on this stage. */
    record Done() implements SolverStep {
    }
}
