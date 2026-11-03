package soloMapling.mechanics;

import java.util.List;

/**
 * The next thing a solver wants one member to do. Steps are intents: the bot's own movement (BotNav / GCMovement),
 * attack ({@code BotAttackDriver}) and NPC code carry them out, so a step never moves or hits anything itself.
 */
public sealed interface SolverStep {

    /** Walk/jump/climb to a point (a position's centre, an NPC, a portal). */
    record MoveTo(int x, int y, String why) implements SolverStep {
    }

    /**
     * Step into the next room (boss rows only, phase B): a boss run's stages live on different maps and no portal name
     * in our data is knowable (the door script {@code hontale_BR.js} is not its WZ portal name), so the solver says the
     * map and the executor warps straight into the instance's own map. Only ever across maps: a member already in the
     * room is never moved, so nothing is teleported inside a fight.
     */
    record WarpTo(int mapId, String why) implements SolverStep {
    }

    /**
     * Fight a mob kind until {@code untilItemCount} of {@code itemId} is held (0 = until the map is clear). 1.6.2: with
     * {@code leashPx} above 0, only mobs within that many px of ({@code nearX}, {@code nearY}) (a helper hunts by the
     * member it helps, so its kills' drops land within his reach).
     */
    record Fight(List<Integer> mobIds, int itemId, int untilItemCount, int nearX, int nearY, int leashPx)
            implements SolverStep {
        public Fight {
            mobIds = List.copyOf(mobIds);
        }

        /** One mob kind (the party quests): a list of one. 0 means "any". */
        public Fight(int mobId, int itemId, int untilItemCount, int nearX, int nearY, int leashPx) {
            this(mobId <= 0 ? List.of() : List.of(mobId), itemId, untilItemCount, nearX, nearY, leashPx);
        }

        public Fight(int mobId, int itemId, int untilItemCount) {
            this(mobId, itemId, untilItemCount, 0, 0, 0);
        }

        public Fight(List<Integer> mobIds, int itemId, int untilItemCount) {
            this(mobIds, itemId, untilItemCount, 0, 0, 0);
        }

        /** The first mob kind to try (0 for "any"): what the single-mob call sites mean. */
        public int mobId() {
            return mobIds.isEmpty() ? 0 : mobIds.get(0);
        }

        /** Whether a mob of this id is one this step fights. */
        public boolean ofKind(int mobId) {
            return mobIds.isEmpty() || mobIds.contains(mobId);
        }

        /** The mob at (x, y) is one this step may fight. */
        public boolean onLeash(int x, int y) {
            return leashPx <= 0 || java.awt.Point.distance(x, y, nearX, nearY) <= leashPx;
        }
    }

    /** Pick up an item from the floor or a reactor drop. */
    record Loot(int itemId) implements SolverStep {
    }

    /**
     * Talk to one particular standing of an NPC. The same NPC can stand in 26 places on one map, and the script reads
     * {@code cm.getNpcObjectId()} to decide which one it is (Magatia's seven books): a plain {@link TalkNpc} always
     * reaches the first.
     */
    record TalkNpcAt(int objectId, int npcId) implements SolverStep {
    }

    /** Talk to an NPC, optionally holding {@code itemId} x {@code count} to hand in (0 = just talk). */
    record TalkNpc(int npcId, int itemId, int count) implements SolverStep {
    }

    /** Drop items for another member to loot (passes to the leader). */
    record DropFor(int itemId, int count, int toCharacterId) implements SolverStep {
    }

    /**
     * Pick its own drop back up (1.6.2: a pass it dropped at a real leader's feet that he left lying while he walked
     * off): only the drop this member dropped, once the game's ownership window let anyone take it.
     */
    record TakeBack(int itemId, String why) implements SolverStep {
    }

    /** Hit a reactor (box, lever). */
    record HitReactor(int reactorId) implements SolverStep {
    }

    /**
     * Hit a reactor the map knows by name, not by id (OPQ stage 7: the five levers are reactors named {@code "1"} to
     * {@code "5"} in {@code Map.wz/Map9/920010700.img}, and the script counts them by name:
     * {@code 2013001.js} "getReactorByName(\"\" + i)").
     */
    record HitReactorNamed(String reactorName) implements SolverStep {
    }

    /**
     * Put {@code count} of an item on the ground inside a reactor's box (OPQ, stages 1, 3, 9): the server matches a
     * type-100 reactor's item and <em>quantity</em> exactly and needs the drop's position inside the reactor's area
     * ({@code MapleMap.activateItemReactors}), so the stack lands on the reactor itself: {@code reactor} is its WZ
     * name ({@code scar1}) or, all digits, its id ({@code 2006000}), and {@code x}/{@code y} is the fallback spot when
     * the map has no such reactor. The whole stack goes down as one drop, because a count is matched, not a sum.
     */
    record PlaceItem(int itemId, int count, String reactor, int x, int y, String why) implements SolverStep {
    }

    /**
     * Drop exactly {@code count} of {@code itemId} on the reactor with this <em>id</em>, and only there. Henesys PQ's
     * six seed beds are {@code 9108000..9108005} and nothing in {@code scripts/} ever names them: the pairing lives in
     * the reactors' own WZ event slots ({@code event/0: type 100, state 1, item, quantity 1}) and
     * {@code MapleMap.activateItemReactors} takes a drop only when the item and the whole quantity match and the drop
     * lies inside that reactor's area - so the bed has to be named by id, and a bed already planted must not be fed
     * again: its script adds one to the instance's {@code stage} on every activation, and the stage clears at exactly
     * {@code "6"}. The server fires that activation 5000 ms after the drop lands
     * ({@code MapleMap.java:2665-2680}), which the executor honours as a settle delay rather than a failure.
     */
    /**
     * Use one {@code itemId} on a monster of this kind ({@code mobId}) - Ellin PQ's purifier marble on a Poison Flower
     * {@code 9300174}, which is how the party makes the twenty marbles {@code 4001169} the stage wants
     * (`scripts/npc/2133001.js:60-84`). The rule itself is `server.life.MonsterCatch`; the step only says "try one now".
     * The monster has to be weak enough (below 40% HP) for the attempt to land, so a solver keeps fighting and re-tries.
     */
    /**
     * Touch a reactor - the {@code touch} callback, not {@code act}. A reacto r's {@code touch} is dispatched only from a
     * client packet (`TouchReactorHandler.java:34-46`), so a bot standing on it does nothing; Crimsonwood Keep's sign-up
     * is exactly this (`CWKPQ.js:98-135` hands each member a reactor by job class on touch), and Horntail's alternate head
     * spawn (`2408003.js:30-34`) is the other.
     */
    record TouchReactor(int reactorId) implements SolverStep {
    }

    /**
     * Touch the reactor <b>named</b> this - the sign-up's own addressing. `CWKPQ.js:106-118` deploys its reactors by name
     * (`4skill<jobClass><slot>`, and `4fake*` decoys), so the name is the only thing that says which member belongs where.
     */
    record TouchReactorNamed(String reactorName) implements SolverStep {
    }

    record UseItemOnMob(int itemId, int mobId) implements SolverStep {
    }

    record PlaceItemOn(int reactorId, int itemId, int count, String why) implements SolverStep {
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
    record Converse(int npcId, List<Integer> answers, String why) implements SolverStep {
    }

    /** Walk into a portal on the member's map (the stage's way on; its script's gate still decides). */
    /**
     * Take a door by <em>running the portal's own script</em>, the way a player does, instead of the plain
     * {@link EnterPortal} warp. Ludi PQ needs it twice: its doors ({@code scripts/portal/lpq0..lpq7.js}) refuse until
     * the stage's {@code Nstageclear} property is set, and the door out of stage 6 is what <em>writes</em> that
     * property for stage 6 ({@code lpq5.js:33-41}) - a plain {@code changeMap} would leave the stage unclearable.
     * {@code scripting.portal.PortalScriptManager.runPortalScript} is the entry point the staging already uses.
     */
    record ScriptedPortal(String portalName) implements SolverStep {
        @Override
        public String toString() {
            return "ScriptedPortal[portalName=" + portalName + "]";
        }
    }

    record EnterPortal(String portalName) implements SolverStep {
    }

    /**
     * The portal on this map that leads to a given map, whichever of the map's portals carries that target. A maze hub
     * has fifteen doors and no reason for the solver to know which one is called {@code in07}: the row already says
     * where the party goes next, so the target map id is the whole of the information.
     */
    record EnterPortalTo(int mapId) implements SolverStep {
        @Override
        public String toString() {
            return "EnterPortalTo[mapId=" + mapId + "]";
        }
    }

    /** Nothing left for this member on this stage. */
    /**
     * The step the content cannot go on with: the run ends with this reason instead of the member standing still or
     * spinning. A solver uses it for a fact of the room it cannot fix (a missing kill target), not for a slow fight.
     */
    record Abandon(String why) implements SolverStep {
    }

    record Done() implements SolverStep {
    }
}
