package soloMapling.mechanics;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code lpq-combo} (ticket 164, Ludi PQ's eighth stage): the room's nine boxes and the five the run's leader was told
 * to stand on. {@code scripts/npc/2040043.js:95-160} keeps the answer in the event property {@code stage8combo} - nine
 * characters, five of them {@code 1} - counts the party members standing in each of the map's nine areas, and clears
 * only when exactly five are standing and every one of them is on a {@code 1}. A wrong try shows
 * {@code showWrongEffect} and the party tries again, so the solver re-reads the property and the areas every tick.
 *
 * <p>Why the generic {@code combination} solver could not do it: that one matches a sequence of position <em>indices</em>
 * the row declares for the stage ({@code combos:}), while this answer is written into the instance by the NPC at the
 * moment the stage opens and never appears in our data.
 */
public final class LpqComboSolver implements StageSolver {

    public static final String ID = "lpq-combo";

    static final String COMBO_PROPERTY = "stage8combo";
    static final String STAGE_PROPERTY = "statusStg8";
    static final int AREAS = 9;
    static final int WANTED = 5;
    static final long TALK_GAP_MS = 1_500;

    @Override
    public String id() {
        return ID;
    }

    /** The nine-digit answer as area indices (0-based), empty while the stage has not been opened. */
    static List<Integer> want(StageView view) {
        Optional<String> prop = view.eventProperty(COMBO_PROPERTY);
        if (prop.isEmpty()) {
            return List.of();
        }
        String combo = prop.get().trim();
        if (combo.length() != AREAS) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < AREAS; i++) {
            char c = combo.charAt(i);
            if (c == '1') {
                out.add(i);
            } else if (c != '0' && c != ',' && c != ' ') {
                return List.of();
            }
        }
        return out.size() == WANTED ? out : List.of();
    }

    /** The area this member owns, or -1 when the party is more members than the answer needs. */
    static int areaFor(StageView view, int characterId) {
        List<Integer> want = want(view);
        int slot = LpqFarmSolver.slotOf(view, characterId);
        if (slot < 0 || slot >= want.size()) {
            return -1;
        }
        return want.get(slot);
    }

    static boolean standingRight(StageView view) {
        List<Integer> want = want(view);
        if (want.isEmpty()) {
            return false;
        }
        int onCombo = 0;
        for (RunMember m : view.members()) {
            if (!m.bot() && view.mapOf(m.characterId()) != view.stage().mapId()) {
                continue;
            }
            int[] at = view.positionOf(m.characterId());
            for (int a = 0; a < AREAS; a++) {
                Rectangle r = view.area(a);
                if (r != null && r.contains(at[0], at[1])) {
                    if (want.contains(a)) {
                        onCombo++;
                    }
                    break;
                }
            }
        }
        return onCombo == WANTED;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "to the boxes of stage 8");
        }
        String status = view.eventProperty(STAGE_PROPERTY).orElse("");
        List<Integer> want = want(view);
        if (want.isEmpty() || status.isEmpty() || status.equals("-1")) {
            return member.leader()
                    ? new SolverStep.TalkNpc(view.stage().npcId(), 0, 0)
                    : new SolverStep.Wait(2_000, "waiting for the box combination");
        }
        int area = areaFor(view, id);
        if (area < 0) {
            Optional<int[]> by = view.npcPosition(view.stage().npcId());
            if (by.isPresent()) {
                return new SolverStep.MoveTo(by.get()[0], by.get()[1], "standing clear of the boxes");
            }
            return new SolverStep.Wait(1_500, "standing clear of the boxes");
        }
        Rectangle r = view.area(area);
        if (r == null) {
            return new SolverStep.Wait(1_500, "waiting for the map's nine areas");
        }
        int[] at = view.positionOf(id);
        int cx = (int) r.getCenterX();
        int cy = (int) r.getCenterY();
        if (!r.contains(at[0], at[1])) {
            return new SolverStep.MoveTo(cx, cy, "onto box " + (area + 1) + " of the combination");
        }
        if (member.leader() && standingRight(view)) {
            return new SolverStep.TalkNpc(view.stage().npcId(), 0, 0);
        }
        return new SolverStep.Wait(TALK_GAP_MS, "holding box " + (area + 1) + " of " + want.size());
    }
}
