package soloMapling.mechanics;

/**
 * {@code opq-sealed} (Orbis PQ stage 5, the sealed room 920010500): stand exactly as many players on each of the three
 * platforms as Eak's random split says.
 *
 * <p>Our script is the truth ({@code scripts/npc/2013001.js:920010500}): the first talk splits three over the three
 * platforms at random and writes {@code stage4_0..stage4_2} plus {@code statusStg4 = 0}; every later talk counts the
 * players standing in each platform ({@code cm.getMap().getNumPlayersInArea(i)}, which is
 * {@code MapleMap.getNumPlayersInRect(getArea(i))}), refuses while the three counts do not add up to three, and clears
 * the stage ({@code clearStage(4)}) only when every count equals its {@code stage4_i}. So the party of three has to be
 * split 1/1/1, a party of four leaves one member off the platforms, and a party of six leaves three off.
 *
 * <p>The rectangles are the map's own {@code area} nodes ({@code MapFactory.java:273-279} hands them to
 * {@code MapleMap.addMapleArea}), so the solver reads the geometry from the map instead of keeping a copy of it, and the
 * split from the instance, which is re-rolled per run - both are read every tick.
 */
public final class OpqSealedSolver implements StageSolver {

    public static final String ID = "opq-sealed";

    public static final String STAGE_PROPERTY = "statusStg4";
    /** Where the script keeps the split: one event property per platform, 0..3 players each. */
    public static final String SPLIT_PREFIX = "stage4_";
    public static final int PLATFORMS = 3;
    static final int EAK_NPC = 2013001;
    static final long TALK_GAP_MS = 1_500;

    @Override
    public String id() {
        return ID;
    }

    /** the lobby is still this stage's business: its clear opened the box that holds our fourth piece */
    @Override
    public java.util.Set<Integer> extraMaps(StageView view) {
        return java.util.Set.of(920010400);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        SolverStep lobbyBox = OpqBoxes.take(view, id, OpqBoxes.LOBBY);
        if (lobbyBox != null) {
            return lobbyBox; // the lobby's clear opened the box that holds the third scar: take it on the way up
        }
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "onto the sealed platforms");
        }
        if (view.eventProperty(STAGE_PROPERTY).isEmpty() || "-1".equals(view.eventProperty(STAGE_PROPERTY).orElse(null))) {
            // the first talk is what picks the split; until then there is nothing to stand on
            return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                    : new SolverStep.Wait(2_000, "waiting for Eak's platform split");
        }
        int[] split = split(view);
        if (split == null) {
            return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                    : new SolverStep.Wait(TALK_GAP_MS, "waiting for Eak's platform split");
        }
        int slot = slotOf(view, id);
        int platform = platformFor(split, slot);
        if (platform < 0) {
            return standClear(view, member);
        }
        java.awt.Rectangle area = view.area(platform);
        if (area == null) {
            return member.leader() ? new SolverStep.Wait(2_000, "this map has no platform " + (platform + 1) + " to stand on")
                    : new SolverStep.Wait(2_000, "waiting for the platforms");
        }
        int[] at = view.positionOf(id);
        if (at == null || !area.contains(at[0], at[1])) {
            return new SolverStep.MoveTo(area.x + area.width / 2, area.y + area.height / 2,
                    "stand on platform " + (platform + 1) + " of " + java.util.Arrays.toString(split));
        }
        if (member.leader() && standingRight(view, split)) {
            return new SolverStep.TalkNpc(EAK_NPC, 0, 0);
        }
        return new SolverStep.Wait(TALK_GAP_MS, "holding platform " + (platform + 1) + " of " + java.util.Arrays.toString(split));
    }

    /**
     * The split Eak rolled: {@code stage4_0..2}, or {@code null} while any of them is missing or not a number (the talk
     * that rolls them is the talk that writes all three).
     */
    static int[] split(StageView view) {
        int[] out = new int[PLATFORMS];
        for (int i = 0; i < PLATFORMS; i++) {
            String raw = view.eventProperty(SPLIT_PREFIX + i).orElse(null);
            if (raw == null) {
                return null;
            }
            try {
                out[i] = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] < 0) {
                return null;
            }
        }
        return out;
    }

    /** The platform this member stands on: the split handed out down the party in character-id order. */
    static int platformFor(int[] split, int slot) {
        int left = slot;
        for (int i = 0; i < PLATFORMS; i++) {
            if (left < split[i]) {
                return i;
            }
            left -= split[i];
        }
        return -1;
    }

    /** This member's place in the party: bots in character-id order first, then everybody else, so it is stable. */
    static int slotOf(StageView view, int characterId) {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (RunMember m : view.members()) {
            ids.add(m.characterId());
        }
        ids.sort(Integer::compareTo);
        int slot = ids.indexOf(characterId);
        return slot < 0 ? ids.size() : slot;
    }

    /**
     * Whether every platform now holds exactly its number of party members. The script counts every character in the
     * rectangle, so this does too - a passer-by standing on a platform makes the check refuse, which is the script's
     * behaviour and not something to paper over.
     */
    static boolean standingRight(StageView view, int[] split) {
        int[] seen = new int[PLATFORMS];
        for (RunMember m : view.members()) {
            if (view.mapOf(m.characterId()) != view.stage().mapId()) {
                continue; // still in the room below: the script runs on this map only
            }
            int[] at = view.positionOf(m.characterId());
            if (at == null) {
                continue;
            }
            for (int i = 0; i < PLATFORMS; i++) {
                java.awt.Rectangle area = view.area(i);
                if (area != null && area.contains(at[0], at[1])) {
                    seen[i]++;
                }
            }
        }
        return java.util.Arrays.equals(seen, split);
    }

    /** A member the split does not want on any platform waits beside Eak, who stands clear of all three. */
    private static SolverStep standClear(StageView view, RunMember member) {
        java.util.Optional<int[]> eak = view.npcPosition(EAK_NPC);
        if (eak.isEmpty()) {
            return new SolverStep.Wait(2_000, "keeping off the platforms");
        }
        int[] target = eak.get();
        int[] at = view.positionOf(member.characterId());
        if (at != null && Math.abs(at[0] - target[0]) <= 30 && Math.abs(at[1] - target[1]) <= 40) {
            return new SolverStep.Wait(TALK_GAP_MS, "waiting beside Eak, off the platforms");
        }
        return new SolverStep.MoveTo(target[0], target[1], "waiting beside Eak, off the platforms");
    }
}
