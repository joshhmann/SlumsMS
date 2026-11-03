package soloMapling.mechanics;

/**
 * The Pre-Zakum mines: thirty rooms on a hub, a chest in most of them, and one hand-in at the end.
 *
 * <p>Everything the solver decides is read from the row, because the mines are data: a room's chest is the stage's
 * {@link MechanicsModel.Stage#hitReactor() hitReactor}, the ore chest and the ore it wants are constants of the chest
 * that exists once in the whole maze ({@code 2112014} on {@code 280011005}), and <b>where the party goes next is the
 * stage's own clear</b> - {@code clear.partyOnMap} names the map the party walks into, so the solver leaves through
 * whichever door on this map leads there ({@link SolverStep.EnterPortalTo}). That is why a maze of a hub and fifteen
 * numbered doors needs no route in Java: the row is the route.
 *
 * <p>The chest arithmetic is settled offline and is worth stating where the code that relies on it lives:
 * {@code reactordrops} has {@code 2112012}/{@code 2112005} -> {@code 4001015} (the key), {@code 2112004}/{@code 2112011}
 * -> {@code 4001016} (the ore), {@code 2112014} -> {@code 4001018} (the document), all at chance 1. A chest yields its
 * item on the fourth and fifth hit and then goes permanently inert ({@code Reactor.java:246-315}: the end-state branch
 * and the following {@code -1}-type branch are the two hits, and state 5 is not active), which is why the solver keeps
 * hitting until the reactor answers {@code -1} rather than counting hits.
 *
 * <p>Nothing here checks damage, DPS or the party's size: the script has no such check either -
 * {@code scripts/npc/2032002.js:71-127} asks for {@code 4001018}, and the thirty keys are optional and only change the
 * message and the reward.
 */
public final class ZakumMinesSolver implements StageSolver {

    /** The document Adobis takes; it comes out of the one big chest, {@code 2112014} on {@code 280011005}. */
    static final int DOCUMENT = 4001018;
    /** The ore the big chest eats: {@code Reactor.wz/2112014} event 0 is {@code type=100, 0=4001016, 1=7}. */
    static final int ORE = 4001016;
    static final int ORE_NEEDED = 7;
    static final int BIG_CHEST = 2112014;
    /** Adobis's hand-in clerk, standing on the hub. */
    static final int CLERK = 2032002;
    static final int HUB = 280010000;

    @Override
    public String id() {
        return "zakum-mines";
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        int mapId = view.stage().mapId();
        int mine = view.mapOf(member.characterId());
        boolean leader = view.leader().map(l -> l.characterId() == member.characterId()).orElse(false);

        if (view.cleared()) {
            return new SolverStep.Done();
        }
        if (mine != mapId) {
            // the party is between two rooms; the previous stage's clear put it here, so this is a moment, not a fault
            return new SolverStep.Wait(1_000, "the door has not finished taking me to the mine");
        }
        if (isHandIn(view.stage())) {
            int held = view.itemCount(member.characterId(), DOCUMENT);
            if (held <= 0) {
                return new SolverStep.Abandon("no document: the big chest on 280011005 was never opened");
            }
            if (!leader) {
                return new SolverStep.Wait(1_500, "the hand-in is the leader's");
            }
            return new SolverStep.TalkNpc(CLERK, DOCUMENT, 1);
        }

        // a chest that has been hit five times answers -1 for ever; one that was never here also answers -1
        int chest = view.stage().hitReactor();
        boolean chestLive = chest > 0 && view.reactorState(chest) >= 0;
        int floor = view.floorItems(0, member.characterId());

        if (chest == BIG_CHEST) {
            int ore = view.itemCount(member.characterId(), ORE);
            if (ore >= ORE_NEEDED && view.reactorState(BIG_CHEST) >= 0) {
                return new SolverStep.PlaceItemOn(BIG_CHEST, ORE, ORE_NEEDED,
                        "the seven ores the big chest wants");
            }
            if (ore < ORE_NEEDED) {
                return new SolverStep.Wait(1_500, "this chest wants seven ores and I have " + ore
                        + ": the ore is in the rooms we have already walked");
            }
        }
        if (floor > 0) {
            return new SolverStep.Loot(0);
        }
        if (chestLive) {
            return new SolverStep.HitReactor(chest);
        }
        int next = view.stage().clear().partyOnMap();
        if (view.stage().clear().onInstance() || next <= 0) {
            return new SolverStep.Wait(2_000, "the room is spent: waiting at the door");
        }
        return new SolverStep.EnterPortalTo(next);
    }

    /** The hand-in stage is the one whose clear is the instance's, not a map's. */
    static boolean isHandIn(MechanicsModel.Stage stage) {
        return stage.clear() != null && stage.clear().onInstance();
    }
}
