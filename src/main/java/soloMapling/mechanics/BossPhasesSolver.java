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

    /**
     * How long a summoned fight may stand with no arm and no body phase of any id before the run is called off. The
     * body is a chain (Zakum: {@code 8800000 -> 8800001 -> 8800002}, each waking when the one before it falls), so
     * "the id the script waits for is not on the map" is the normal state for the first half of the fight.
     */
    static final long NOTHING_TO_HIT_GRACE_MS = 30_000;

    /** Where the grace's start is remembered between ticks, in the stage's own scratch memory. */
    private static final String K_NOTHING_SINCE = "bossNothingSince";

    /** Where a reactor the bot has already clicked is remembered, so a room with two of them clicks each exactly once. */
    private static final String K_REACTOR = "bossReactorSince";

    /** The body chain in the order its phases wake up: Zakum's three body ids. */
    private static final List<Integer> BODY_FAMILY = List.of(8800000, 8800001, 8800002);

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
        List<Integer> missing = missingEntryItems(view);
        if (!missing.isEmpty()) {
            // the row's door wants something in hand before anything in the room makes sense (Latanica's
            // scripts/portal/captinsg00.js:4 refuses anyone without 4000381). The row does not warp past the door and
            // does not click anything inside: it says what is missing and stands still until the item is there.
            return new SolverStep.Wait(1_000, "the run has no entry item " + missing
                    + " in hand, so stage " + view.stage().index() + " cannot be entered: " + view.stage().name());
        }
        List<BossTargetRules.Part> parts = view.bossParts();
        BossTargetRules.Phase phase = BossTargetRules.phase(parts, summoned(view), view.cleared());
        if (phase == BossTargetRules.Phase.DONE) {
            return new SolverStep.Done();
        }
        if (phase == BossTargetRules.Phase.WAITING_FOR_SUMMON) {
            return new SolverStep.Wait(1_000, "waiting for the boss to be summoned");
        }
        List<Integer> reactors = view.stage().hitReactors();
        boolean opened = reactors.stream().anyMatch(id -> view.stageMemory().containsKey(K_REACTOR + id));
        if (opened || parts.isEmpty()) {
            // the room's fight is behind reactors the party opens itself (Horntail's 2401000, Scarga's 5511000+5511001,
            // Krexel's 5411001, Papulatus' 2201004, Showa's 8001000). The first click happens while the room is empty;
            // after that the rest follow even though the first reactor's boss may already stand, because Scarga's
            // second reactor brings half the fight and a "click it only when the room looks empty" rule would never
            // reach it. Once per stage: a reactor script is not written to be clicked twice (scripts/reactor/
            // 2201004.js:22-29 has no guard at all), so the click is remembered in the stage's own scratch memory.
            for (int reactorId : reactors) {
                if (view.stageMemory().putIfAbsent(K_REACTOR + reactorId, view.nowMs()) == null) {
                    return new SolverStep.HitReactor(reactorId);
                }
            }
        }
        List<Integer> order = order(view, parts, view.fighterOf(member.characterId()), phase);
        if (order.isEmpty()) {
            // nothing it may hit: either a part is there but not hittable by this member (an arm while no human drives
            // it, an immunity), which is a wait, or the room has no arm and no body phase at all after the summon,
            // which is only a moment between phases until the row's own deadline runs out
            if (nothingToHit(parts)) {
                long allowed = allowedEmptyMs(view.stage());
                Object since = view.stageMemory().putIfAbsent(K_NOTHING_SINCE, view.nowMs());
                long waiting = since instanceof Long start ? view.nowMs() - start : 0L;
                if (summoned(view) && !view.cleared() && waiting >= allowed) {
                    return new SolverStep.Abandon("no arm and no body of " + view.stage().mobs()
                            + " has been alive for " + allowed / 1000
                            + " s after the summon (saw " + ids(parts) + "): the instance can never be cleared,"
                            + " so the run is ended");
                }
            }
            return new SolverStep.Wait(500, "nothing it may hit while the boss is in " + phase);
        }
        view.stageMemory().remove(K_NOTHING_SINCE);
        int[] band = band(view, order.get(0));
        return new SolverStep.Fight(order, 0, 0, band[0], band[1], band[0] == 0 && band[1] == 0 ? 0 : BAND_PX);
    }

    /**
     * How long this room may hold no arm and no body phase before the run is called off: the solver's
     * {@link #NOTHING_TO_HIT_GRACE_MS}, or the wait the row declares with {@code spawnWaitSeconds} when that is longer.
     * A row declares it when its targets are the map's own life (the seven {@code 164-bossrows} single-room rows), where
     * an empty room at the instance's first tick says nothing about whether the fight can be won; a row that declares
     * nothing keeps the grace, so a stall still costs its instance no more than that.
     */
    static long allowedEmptyMs(MechanicsModel.Stage stage) {
        return Math.max(NOTHING_TO_HIT_GRACE_MS, stage == null ? 0 : stage.spawnWaitSeconds() * 1000L);
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
     * The row's {@code entryItems} that no member of the run is holding. One holder is enough: a party's door is
     * opened by one character ({@code scripts/portal/captinsg00.js:4} checks the player who clicks it and the event
     * script never re-checks the rest), so a run whose leader has the item is a run that can be inside the instance.
     * Empty = the stage asks for nothing, which is every row but Latanica's.
     */
    static List<Integer> missingEntryItems(StageView view) {
        List<Integer> missing = new ArrayList<>();
        for (int itemId : view.stage().entryItems()) {
            boolean held = view.members().stream().anyMatch(m -> view.itemCount(m.characterId(), itemId) > 0);
            if (!held) {
                missing.add(itemId);
            }
        }
        return List.copyOf(missing);
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
            if (tier.kind() == Kind.BODY) {
                // the body is a chain, so its phases are ordered by when they wake, not by where the data lists them
                ids.sort((a, b) -> Integer.compare(bodyRank(a), bodyRank(b)));
            } else {
                ids.sort((a, b) -> Integer.compare(dataOrder(view.stage().mobs(), a),
                        dataOrder(view.stage().mobs(), b)));
            }
            out.addAll(ids);
        }
        return List.copyOf(out);
    }

    /**
     * Whether the room holds no arm and no body phase of any id at all - the fake body that waits for the arms counts
     * as one, because it is a phase that will become real once the last arm falls.
     */
    private static boolean nothingToHit(List<BossTargetRules.Part> parts) {
        return parts.stream().noneMatch(p -> p.role() == BossTargetRules.Role.ARM
                || p.role() == BossTargetRules.Role.BODY);
    }

    /** The body chain's phase order: 8800000 first, then 8800001, then 8800002 (an unknown body id last). */
    private static int bodyRank(int mobId) {
        int rank = BODY_FAMILY.indexOf(mobId);
        return rank < 0 ? Integer.MAX_VALUE : rank;
    }

    /** The ids of the parts a tick actually saw, for the abandon line. */
    private static String ids(List<BossTargetRules.Part> parts) {
        List<Integer> seen = new ArrayList<>();
        for (BossTargetRules.Part p : parts) {
            if (!seen.contains(p.mobId())) {
                seen.add(p.mobId());
            }
        }
        return seen.isEmpty() ? "no part at all" : seen.toString();
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
