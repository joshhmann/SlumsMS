package soloMapling.mechanics;

import soloMapling.mechanics.BossTargetRules.Kind;
import soloMapling.mechanics.BossTargetRules.Tier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code solver: boss-phases} (ticket 130 phase 5 phase A2): a boss made of parts, Zakum first. The party's bots hit the
 * arms in the order the data gives, never the body while an arm is alive (the server blocks that damage anyway, see
 * {@code MapleMap.bossDamageBlocked}), and the body once no arm is left. Nothing to hit yet is not a failure: the
 * human leader summons the boss at the altar ({@code scripts/reactor/2111001.js}) and the solver waits for the parts
 * to appear.
 *
 * <p>The rules themselves are {@link BossTargetRules} (ported from BotStory); this class only reads the live stage
 * through {@link StageView} and turns the member's first tiers into one {@link SolverStep.Fight}. The step's mob ids
 * are an ordered candidate list, so a part the member cannot walk to falls through to the next one instead of
 * stalling the member.
 */
public final class BossPhasesSolver implements StageSolver {

    public static final String ID = "boss-phases";

    /** An arm whose body box ends at most this far above its origin reaches the floor (BotStory's floorReachPx). */
    static final int FLOOR_REACH_PX = 60;

    /** How many adds alive at once come before the boss parts; fewer tag along after them. */
    static final int ADD_CLEAR_THRESHOLD = 4;

    /** How far from an arm's own {@code at} spot a part still counts as the one to fight (its band). */
    static final int BAND_PX = 450;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        int stageMap = view.stage().mapId();
        if (view.mapOf(member.characterId()) != stageMap) {
            // a boss run's rooms are separate maps and the door portal's name is not in our data (scripts/portal/
            // hontale_BR.js is the script, not the WZ name), so the room is entered by map id. Only across maps.
            return new SolverStep.WarpTo(stageMap, "stage " + view.stage().index() + " is in this room");
        }
        List<BossTargetRules.Part> parts = view.bossParts();
        BossTargetRules.Phase phase = BossTargetRules.phase(parts, summoned(view), view.cleared());
        if (phase == BossTargetRules.Phase.DONE) {
            return new SolverStep.Done();
        }
        if (phase == BossTargetRules.Phase.WAITING_FOR_SUMMON) {
            return new SolverStep.Wait(1_000, "waiting for the boss to be summoned");
        }
        int killTarget = view.stage().killTarget();
        if (phase == BossTargetRules.Phase.ARMS && killTarget > 0
                && parts.stream().noneMatch(p -> p.mobId() == killTarget)) {
            // the data names the part whose death the script turns into the clear (Zakum: 8800002, which has no spawn
            // site anywhere in scripts/ or the server - it is the map's own life). Without it the script never writes
            // the clear property, so the run would fight arms until its time budget ran out.
            return new SolverStep.Abandon("the kill target " + killTarget + " the script waits for is not on the map:"
                    + " the instance can never be cleared, so the run is ended");
        }
        List<Integer> order = order(view, parts, view.fighterOf(member.characterId()), phase);
        if (order.isEmpty()) {
            return new SolverStep.Wait(500, "nothing it may hit while the boss is in " + phase);
        }
        int[] band = band(view, order.get(0));
        return new SolverStep.Fight(order, 0, 0, band[0], band[1], band[0] == 0 && band[1] == 0 ? 0 : BAND_PX);
    }

    /**
     * The stand spot the data gives the first candidate's objective ({@code at:}), so a member works one arm's band
     * instead of one generic approach; {0, 0} when the data has none.
     */
    static int[] band(StageView view, int mobId) {
        for (MechanicsModel.Objective o : view.stage().objectives()) {
            if (o.mobId() == mobId && o.hasAt()) {
                return new int[]{o.atX(), o.atY()};
            }
        }
        return new int[]{0, 0};
    }

    /**
     * The mob ids this member works through, first first: the fight's tiers as {@link BossTargetRules} orders them, the
     * body's tier only once no arm is left (a body offered with an arm alive is damage the server throws away).
     */
    static List<Integer> order(StageView view, List<BossTargetRules.Part> parts, BossTargetRules.Member member,
                               BossTargetRules.Phase phase) {
        List<Tier> tiers = BossTargetRules.tiers(parts, member, FLOOR_REACH_PX, ADD_CLEAR_THRESHOLD);
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (Tier tier : tiers) {
            if (phase == BossTargetRules.Phase.ARMS && tier.kind() == Kind.BODY) {
                continue;
            }
            List<Integer> ids = new ArrayList<>(tier.mobIds());
            ids.sort((a, b) -> Integer.compare(dataOrder(view.stage().mobs(), a), dataOrder(view.stage().mobs(), b)));
            out.addAll(ids);
        }
        return List.copyOf(out);
    }

    /** Where the data lists this part, so the stage's own order decides within a tier (last for an unlisted id). */
    private static int dataOrder(List<Integer> mobs, int mobId) {
        int index = mobs.indexOf(mobId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /**
     * Whether the room's parts were summoned. The data names the property the map's reactor sets ({@code summoned} for
     * Zakum's altar, {@code scripts/reactor/2111001.js:26-29}, and for Horntail's first two rooms,
     * {@code 2408003.js}); a part standing before that is the fake body and the map's own life, not a fight the party
     * started. A stage with no such property (Horntail's body room, where reactor {@code 2401000} spawns everything and
     * sets nothing) is never gated: there the parts' absence is the only signal.
     */
    private static boolean summoned(StageView view) {
        String property = view.stage().summonProperty();
        return property == null || view.eventProperty(property).isPresent();
    }
}
