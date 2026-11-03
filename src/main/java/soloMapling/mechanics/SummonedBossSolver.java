package soloMapling.mechanics;

/**
 * {@code summoned-boss} (ticket 164, Magatia PQ's last room): an NPC that spawns the boss when the leader talks to it, and
 * a clear that is the instance's own flag because the boss's death calls {@code clearPQ}.
 *
 * <p>`scripts/npc/2112010.js:99-127` is the whole room: on talk, an empty `state` sends the Frankenroid onto
 * `926110401` - {@code 9300151} for a party that did not escort (state 0), {@code 9300152} for one that did (the
 * {@code else}) - and sets {@code statusStg7} to 1 or 2. Talking again after that takes the {@code state == -1} branch,
 * which only speaks, so the talk is idempotent and a bot cannot stack bosses with it.
 *
 * <p>`scripts/event/MagatiaPQ_A.js:435-450`: the Frankenroid's death (`9300151`/`9300152`, and the Zenumist twins
 * `9300139`/`9300140`) shows the clear effect, spawns Juliet back into the room and calls {@code eim.clearPQ()}. No
 * property is written for it, so the row's clear is {@code { instance: true }} - which is why this solver never has to
 * know which form was summoned.
 *
 * <p>Both forms are one {@code Fight}: they are ordinary mobs on the room's ground, so the role/add targeting a boss row
 * uses ({@code boss-phases}) buys nothing here and a phase table would be invented, not read.
 */
public final class SummonedBossSolver implements StageSolver {

    public static final String ID = "summoned-boss";

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
        MechanicsModel.Stage stage = view.stage();
        if (view.mapOf(id) != stage.mapId()) {
            return new SolverStep.WarpTo(stage.mapId(), "into the summoner's room");
        }
        if (view.liveMobs() > 0) {
            return new SolverStep.Fight(0, 0, 0);
        }
        if (stage.npcId() <= 0) {
            return new SolverStep.Wait(1_500, "this stage names no summoner NPC");
        }
        if (member.leader()) {
            if (view.npcPosition(stage.npcId()).isEmpty()) {
                return new SolverStep.Wait(1_500, "the summoner is not in the room");
            }
            return new SolverStep.TalkNpc(stage.npcId(), 0, 0);
        }
        return new SolverStep.Wait(1_500, "the room is empty: waiting for the leader to summon the boss");
    }
}
