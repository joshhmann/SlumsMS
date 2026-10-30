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

    /**
     * Stand on, or hang from, a puzzle position until the member is inside its rectangle ({@code left, top, w, h},
     * script coordinates); {@code (x, y)} is where to aim. Unlike {@link MoveTo} the rectangle is the goal, so a 4 px
     * rope or a 5 px barrel top is reached exactly.
     */
    record Occupy(String positionId, int x, int y, int left, int top, int w, int h, boolean rope) implements SolverStep {
        public boolean inside(int px, int py) {
            return px >= left && px < left + w && py >= top && py < top + h;
        }
    }

    /**
     * Check a puzzle with its NPC (KPQ: the leader double-clicks Cloto). {@code tryValue} is the answer this formation
     * stands for (the combo index), so the run can count a planned wrong try. {@code reachPx}: how far from the NPC
     * the member may click (a member on a rope clicks from where he hangs, as players do).
     */
    record CheckAnswer(int npcId, int tryValue, int reachPx) implements SolverStep {
    }

    /**
     * A conversation with an NPC through its own script: the first click, then one "next"/"yes"/menu answer per
     * entry of {@code answers} (the selection; 0 for a plain next or yes). KPQ: Cloto's prize ([0]), Nella's exit
     * ([0]), Lakelis' "I want to participate" ([0]).
     */
    record Converse(int npcId, java.util.List<Integer> answers, String why) implements SolverStep {
    }

    /** Walk into a portal on the member's map (the stage's way on; its script's gate still decides). */
    record EnterPortal(String portalName) implements SolverStep {
    }

    /** Nothing left for this member on this stage. */
    record Done() implements SolverStep {
    }
}
