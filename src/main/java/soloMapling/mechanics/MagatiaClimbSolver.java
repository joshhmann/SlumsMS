package soloMapling.mechanics;

/**
 * {@code magatia-climb} (ticket 164, Magatia PQ's stages 5 and 6): four rooms, ten rows each, and the combination is
 * published in the event.
 *
 * <p>This is the stage the row used to hold, and reading the <b>exit</b> instead of the write is what unwound it:
 *
 * <ul>
 *   <li>`scripts/portal/jnr4_r1..r4.js` OR one bit each into `statusStg5` and warp into rooms `926110301`..`926110304`.
 *       The bits only stop a room being re-entered ("This room is already being explored") - they are not the stage's
 *       finish line.</li>
 *   <li>Each room is a 10x4 climb. `scripts/portal/jnr5_rp.js:25-60` reads the row and column out of the portal's own
 *       name (`pt<row><col>`), rolls or reads `stage6_comb<room digit>` - ten digits, one per row - and compares the
 *       column with that row's digit. Right: the next row. Wrong: back to the bottom. On row 9 the right column sets
 *       `statusStg6 = 1` and warps to portal 1.</li>
 *   <li>`out00` on any room map leads to `926110400`, Yulete's room.</li>
 * </ul>
 *
 * <p>So the combination is not a secret the bot has to guess: it is an event property the server itself publishes
 * (`jnr5_rp.js:31-40`), and the row's clear is `statusStg6 == "1"` (`:52-57`). The solver reads the property, works out
 * which row the member stands on from the client's own portal heights, and takes <b>that</b> portal by name.
 *
 * <p>Every step is {@code ScriptedPortal}: `PqGates` refuses a scripted portal with side effects for a bot's plain
 * {@code changeMap}, and this portal's whole job is its side effects.
 */
public final class MagatiaClimbSolver implements StageSolver {

    public static final String ID = "magatia-climb";

    /** The hub the four rooms hang off, and the rooms themselves (`jnr4_r1..r4` targets `926110301 + bit`). */
    static final int HUB = 926110300;
    static final int[] ROOMS = {926110301, 926110302, 926110303, 926110304};

    /**
     * The ten row heights of the climb, read from the client (`Map.wz/Map9/926110301.img.xml`: `pt00` y=-52, `pt10`
     * -352, `pt20` -656, `pt30` -944, `pt40` -1250, `pt50` -1534, `pt60` -1842, `pt70` -2128, `pt80` -2434, `pt90`
     * -2720). A member at the "fail" portal (y=120) is nearest row 0, which is where the wrong-column warp puts him.
     */
    static final int[] ROW_Y = {-52, -352, -656, -944, -1250, -1534, -1842, -2128, -2434, -2720};

    /** The property the event publishes this room's ten-digit combination under (`jnr5_rp.js:27`). */
    static String combKey(int mapId) {
        return "stage6_comb" + (mapId % 10);
    }

    /** The row this height is on, or -1 when it is not near any of them. */
    static int rowOf(int y) {
        int best = -1;
        int bestGap = 200; // rows are ~300 px apart, and the bottom of the room (the "fail" portal, y=120) is 172
        // px from row 0's -52, so the reach has to cover it without touching a neighbour
        for (int row = 0; row < ROW_Y.length; row++) {
            int gap = Math.abs(y - ROW_Y[row]);
            if (gap < bestGap) {
                bestGap = gap;
                best = row;
            }
        }
        return best;
    }

    /** The portal to take from this row: `pt<row><digit>`, or null when the combination is not readable yet. */
    static String portalFor(StageView view, int mapId, int y) {
        int row = rowOf(y);
        if (row < 0) {
            return null;
        }
        String comb = view.eventProperty(combKey(mapId)).orElse(null);
        if (comb == null || comb.length() < ROW_Y.length) {
            return null;
        }
        char digit = comb.charAt(row);
        if (digit < '0' || digit > '3') {
            return null;
        }
        return "pt" + row + digit;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        int map = view.mapOf(id);
        if (map == HUB) {
            // jnr4_r1 sets bit 0 and warps into room 1; a member already in a room never comes back here
            return new SolverStep.ScriptedPortal("pt00");
        }
        boolean inRoom = false;
        for (int room : ROOMS) {
            inRoom |= map == room;
        }
        if (!inRoom) {
            return new SolverStep.WarpTo(HUB, "back to the four rooms");
        }
        int[] at = view.positionOf(id);
        String portal = portalFor(view, map, at[1]);
        if (portal != null) {
            return new SolverStep.ScriptedPortal(portal);
        }
        if (!view.eventProperty(combKey(map)).isPresent()) {
            // the first take of any ptXY rolls the room's combination (jnr5_rp.js:25-40), so row 0 is always safe
            return new SolverStep.ScriptedPortal("pt00");
        }
        return new SolverStep.Wait(500, "no row of " + map + "'s climb is at height " + at[1]);
    }
}
