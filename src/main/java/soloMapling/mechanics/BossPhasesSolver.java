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
    /** b365: when the run was told a reactor needs an item nobody holds (once per reactor per stage). */
    private static final String K_NEEDS = "bossReactorNeeds";

    /**
     * Where a listed reactor of a room with no summon property is remembered as spent: its state is past the point a
     * client hits ({@code 2401000} after its sixth hit) or the party cannot reach it. A room with no summon property has
     * no other signal that its summon happened, because the script sets none (scripts/reactor/2401000.js spawns Horntail
     * and touches no event property).
     */
    private static final String K_FIRED = "bossReactorFired";

    /** Where the repeat loop remembers the state it last saw a reactor in, to tell a slow party from a stuck one. */
    private static final String K_STALL = "bossReactorStall";

    /**
     * A listed reactor still hittable this long without its state moving is one no member can reach, so the repeat loop
     * lets it go: the room then falls to the grace as any other room that summoned nothing (QA run
     * horntail-alt-visual-c-20261109T113849Z is the failure this guards, not the fix).
     */
    static final long REACTOR_STALL_MS = 120_000;

    /** The state a repeat loop last saw, and since when it has stayed there. */
    private record Stall(int state, long since) {
    }

    @Override
    public String id() {
        return ID;
    }

    /** b365: whether any member of the run holds the stack a reactor takes. */
    static boolean someoneHolds(StageView view, int[] takes) {
        for (RunMember m : view.members()) {
            if (view.itemCount(m.characterId(), takes[0]) >= takes[1]) {
                return true;
            }
        }
        return false;
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
        boolean summoned = summoned(view, parts);
        BossTargetRules.Phase phase = BossTargetRules.phase(parts, summoned, view.cleared());
        if (phase == BossTargetRules.Phase.DONE) {
            return new SolverStep.Done();
        }
        List<Integer> reactors = view.stage().hitReactors();
        boolean opened = reactors.stream().anyMatch(id -> view.stageMemory().containsKey(K_REACTOR + id));
        // a room that names a summon property is gated by it: its reactors are not clicked before the summon, which is
        // what the early return below did for every room until 1.6.7. A room that names none (Horntail's body room) has
        // nothing to wait for - its reactors ARE the summon - so it is worked first and the wait comes after.
        boolean mayOpen = view.stage().summonProperty() == null || summoned;
        if (mayOpen && (opened || parts.isEmpty())) {
            // the room's fight is behind reactors the party opens itself (Horntail's 2401000, Scarga's 5511000+5511001,
            // Krexel's 5411001, Papulatus' 2201004, Showa's 8001000). The first click happens while the room is empty;
            // after that the rest follow even though the first reactor's boss may already stand, because Scarga's
            // second reactor brings half the fight and a "click it only when the room looks empty" rule would never
            // reach it. The FIRST click is once per stage: a reactor script is not written to be opened twice
            // (scripts/reactor/2201004.js:22-29 has no guard at all), so it is remembered in the stage's scratch
            // memory. A reactor that takes several hits is repeated by keepHitting below, which asks the live state
            // instead of counting.
            for (int reactorId : reactors) {
                if (view.stageMemory().containsKey(K_REACTOR + reactorId)) {
                    continue;
                }
                // 1.6.6 b365: a reactor waiting for an item (Reactor.wz type 100) opens the way a player opens it, by
                // the stack dropped inside its box (Latanica's 5411000 takes the White Essence the door asked for). The
                // member holding it places it; the others wait for it rather than hitting what no client can hit.
                int[] takes = view.reactorTakes(reactorId);
                if (takes != null) {
                    if (view.itemCount(member.characterId(), takes[0]) >= takes[1]) {
                        view.stageMemory().put(K_REACTOR + reactorId, view.nowMs());
                        return new SolverStep.PlaceItemOn(reactorId, takes[0], takes[1],
                                "the reactor opens on " + takes[1] + " x " + takes[0] + ", the way a player opens it");
                    }
                    if (someoneHolds(view, takes)) {
                        return new SolverStep.Wait(1_000, "the member with " + takes[0] + " opens the room");
                    }
                    // nobody has it. The owner's rule (7 Nov): with a real player in the run the room waits for the
                    // item, as in the real game; a run of regulars alone may open it by a hit only where the row says
                    // so (opensByHit: botOnly), so the exception is written down
                    boolean human = view.members().stream().anyMatch(m -> !m.bot());
                    // Scarga (owner, 7 Nov): one Spirit opens both - once a reactor of the stage has taken its item,
                    // the others open by a hit (opensByHit: afterFirstItem)
                    boolean afterFirst = MechanicsModel.Stage.AFTER_FIRST_ITEM.equals(view.stage().opensByHit())
                            && reactors.stream().anyMatch(other -> other != reactorId && view.reactorState(other) > 0);
                    boolean mayHit = human ? afterFirst : view.stage().botsMayHit();
                    if (!mayHit) {
                        if (view.stageMemory().putIfAbsent(K_NEEDS + reactorId, view.nowMs()) == null) {
                            return new SolverStep.Say("You'll need " + view.itemName(takes[0]) + " to open this.");
                        }
                        return new SolverStep.Wait(2_000, "the room opens on " + takes[1] + " x " + takes[0]
                                + " and nobody in the run has it");
                    }
                } else if (view.reactorState(reactorId) > 0 && !view.reactorHittable(reactorId)) {
                    // (d) someone put the item down themselves - a player does, in the real game - and the reactor
                    // moved on to a state no client can hit: it is open, and a hit now would be one no client makes
                    view.stageMemory().put(K_REACTOR + reactorId, view.nowMs());
                    markFired(view, reactorId);
                    continue;
                }
                view.stageMemory().put(K_REACTOR + reactorId, view.nowMs());
                return new SolverStep.HitReactor(reactorId);
            }
            SolverStep again = keepHitting(view, parts, reactors);
            if (again != null) {
                return again;
            }
        }
        if (phase == BossTargetRules.Phase.WAITING_FOR_SUMMON) {
            return new SolverStep.Wait(1_000, "waiting for the boss to be summoned");
        }
        List<Integer> order = order(view, parts, view.fighterOf(member.characterId()), phase);
        if (order.isEmpty()) {
            // nothing it may hit: either a part is there but not hittable by this member (an arm while no human drives
            // it, an immunity), which is a wait, or the room has no arm and no body phase at all after the summon,
            // which is only a moment between phases until the row's own deadline runs out. The clock runs only once the
            // room counts as summoned: a room whose own reactors have not fired yet is before the summon, not after it.
            if (nothingToHit(parts) && summoned) {
                long allowed = allowedEmptyMs(view.stage());
                Object since = view.stageMemory().putIfAbsent(K_NOTHING_SINCE, view.nowMs());
                long waiting = since instanceof Long start ? view.nowMs() - start : 0L;
                if (!view.cleared() && waiting >= allowed) {
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
     * Whether the room's parts were summoned. The data names the property the map's reactor sets ({@code summoned} for
     * Zakum's altar, {@code scripts/reactor/2111001.js:26-29}, and for Horntail's first two rooms,
     * {@code 2408003.js}); a part standing before that is the fake body and the map's own life, not a fight the party
     * started.
     *
     * <p>A stage with no such property has no event to read, so its summons is its own data. Horntail's body room,
     * reactor {@code 2401000} spawns everything and sets nothing (scripts/reactor/2401000.js): there the room counts as
     * summoned once a part stands or the crystal has fired - a reactor at a state no client hits is a reactor at its
     * last state, and that script's act() is what spawns the fight. A room with no property and no reactor of its own
     * (Horntail's first two rooms) stands from setup, so it is summoned at once.
     */
    private static boolean summoned(StageView view, List<BossTargetRules.Part> parts) {
        String property = view.stage().summonProperty();
        if (property != null) {
            return view.eventProperty(property).isPresent();
        }
        List<Integer> reactors = view.stage().hitReactors();
        if (reactors.isEmpty()) {
            return true;
        }
        return !parts.isEmpty() || reactors.stream().anyMatch(id -> fired(view, id));
    }

    /**
     * Whether a listed reactor of a room with no summon property has done its job. Reactor.wz {@code 2401000} has states
     * 0-6 with a hit on each of 0-5 and none on 6 (six type-0 hits, the sixth running
     * scripts/reactor/2401000.js act() and spawning Horntail), so "past its last hittable state" is what its script's
     * spawn means. A one-hit reactor reads the same way after its single hit, and the memory key keeps it read that way
     * if the reactor is later reset.
     */
    private static boolean fired(StageView view, int reactorId) {
        if (view.stageMemory().containsKey(K_FIRED + reactorId)) {
            return true;
        }
        return view.reactorState(reactorId) > 0 && !view.reactorHittable(reactorId);
    }

    private static void markFired(StageView view, int reactorId) {
        view.stageMemory().put(K_FIRED + reactorId, view.nowMs());
        view.stageMemory().remove(K_STALL + reactorId);
    }

    /**
     * The second and later hits a listed reactor needs, one per solver step: a reactor that takes several hits to open
     * is hit again while the room still holds no arm and no body, the reactor is still hittable, and its state is below
     * its last. One click per stage reached state 5 of seven on Horntail's crystal and summoned nothing (QA run
     * horntail-alt-visual-c-20261109T113849Z), and Krexel's {@code 5411001} has the same six-hit shape.
     *
     * <p>This is not a hit count: the reactor's own state says when to stop (after the hit that lands on its last
     * state, {@link StageView#reactorHittable} answers false, and a one-state reactor whose script must never run twice,
     * e.g. {@code 2201004}, is hit exactly once). A reactor whose state has not moved for
     * {@link #REACTOR_STALL_MS} while still hittable is one the party cannot reach: it is let go so the grace ends the
     * run instead of the solver asking for a hit nobody makes.
     *
     * <p>The pace between hits is the executor's own (3 s per member, {@code StepExecutor.hitReactorAt}), so a room of
     * several bots lands at most one hit per member per 3 s. The loop itself costs a handful of stage-memory reads per
     * tick and a write only when a reactor's state moves or it is written down as spent.
     *
     * @return the next hit, or null when no listed reactor wants one
     */
    private static SolverStep keepHitting(StageView view, List<BossTargetRules.Part> parts, List<Integer> reactors) {
        for (int reactorId : reactors) {
            if (!view.stageMemory().containsKey(K_REACTOR + reactorId)) {
                continue; // its first click has not been asked for yet: the loop above owns it
            }
            if (fired(view, reactorId)) {
                if (!view.stageMemory().containsKey(K_FIRED + reactorId)) {
                    markFired(view, reactorId); // once: a live reactor read is enough until it is written down
                }
                continue;
            }
            if (view.reactorTakes(reactorId) != null) {
                continue; // an item reactor opens on its stack, never on a repeat hit
            }
            if (!nothingToHit(parts)) {
                continue; // a part is in the room: the fight is the job, not the reactor
            }
            if (!view.reactorHittable(reactorId)) {
                continue; // no client hits this state
            }
            if (stalled(view, reactorId)) {
                markFired(view, reactorId);
                continue;
            }
            // the reactor is still on its way to its last state: this is the summon in progress, so the empty-room
            // clock has not started and must not be left running from an earlier tick
            view.stageMemory().remove(K_NOTHING_SINCE);
            return new SolverStep.HitReactor(reactorId);
        }
        return null;
    }

    /** Whether a reactor's state has stayed put, without ever leaving it, for a whole {@link #REACTOR_STALL_MS}. */
    private static boolean stalled(StageView view, int reactorId) {
        int state = view.reactorState(reactorId);
        Object seen = view.stageMemory().get(K_STALL + reactorId);
        if (!(seen instanceof Stall stall) || stall.state() != state) {
            view.stageMemory().put(K_STALL + reactorId, new Stall(state, view.nowMs()));
            return false;
        }
        return view.nowMs() - stall.since() >= REACTOR_STALL_MS;
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
            // every tier, body included, follows the row's own `mobs:` order: a row lists the body chain in wake order
            // (zakum.yaml: 8800000, 8800001, 8800002; zakum-chaos.yaml: 8800100, 8800101, 8800102), which is how the
            // chaos ids stop depending on a hardcoded normal-Zakum family
            ids.sort((a, b) -> Integer.compare(dataOrder(view.stage().mobs(), a),
                    dataOrder(view.stage().mobs(), b)));
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
}
