package soloMapling.mechanics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code gq-earring} (Guild Quest, the outskirts {@code 990000100} and {@code 990000200}): every member earns and puts on
 * its own Protector Rock, then goes on to the statue room.
 *
 * <p>Everything from {@code 990000300} on takes {@code decHP 30000} from a member not wearing item {@code 1032033}
 * ({@code Character.doHurtHp}, {@code Character.java:3606-3609}; {@code GuildQuest.js:236-242} says as much to the party),
 * and the item is one per character ({@code only 1}, {@code Character.wz/Accessory/01032033.img.xml}), so no member can
 * wear another's. It comes from the glowing rocks of these two maps: reactors {@code 9202000} and {@code 9202009}, five
 * plain hits to the last state of their WZ graph, then {@code rm.dropItems()} ({@code scripts/reactor/9202000.js:28-30},
 * {@code 9202009.js:28-30}; {@code reactordrops 9202000/9202009 -> 1032033} is seed data, <b>live DB unverified</b>). The
 * rocks share two ids across twenty-odd reactors, so {@code HitReactor(id)} - which prefers a reactor still at state 0 -
 * would spread a member's hits and finish none: each member sticks to ONE rock by its object id
 * ({@link SolverStep.HitReactorByOid}) until that rock is spent, claimed in the stage's scratch memory so two members do not
 * share one rock.
 *
 * <p>A member without the earring never walks on: the way to {@code 990000300} is closed to it by this solver, not by the
 * map (nothing in the map's portals stops it). The portals are plain ({@code next00} on 100 and on 200, Map9 portal nodes).
 */
public final class GqEarringSolver implements StageSolver {

    public static final String ID = "gq-earring";

    static final int ROCK_A = 9202000;
    static final int ROCK_B = 9202009;
    /** The outskirts' statue-room door, on both maps. */
    static final String DOOR = "next00";
    static final int OUTSKIRTS = 990000100;
    static final int OUTSKIRTS_2 = 990000200;
    static final int STATUES = 990000300;
    /** A rock whose state is past this is spent (five hits take it from 0 to 5; Reactor.wz/9202000.img.xml). */
    static final int LAST_HITTABLE_STATE = 4;

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
        int on = view.mapOf(id);
        boolean worn = view.wearing(id, GqProtection.EARRING);
        if (worn) {
            if (on == STATUES) {
                return new SolverStep.Wait(1_500, "wearing the Protector Rock: waiting for the party on 990000300");
            }
            return new SolverStep.EnterPortal(DOOR);
        }
        if (view.itemCount(id, GqProtection.EARRING) > 0) {
            Optional<String> cannot = view.cannotWear(id, GqProtection.EARRING);
            return cannot.<SolverStep>map(why -> new SolverStep.Abandon("a member cannot wear the Protector Rock, so "
                            + "990000300 and everything after it would take 30000 HP a tick from it: " + why))
                    .orElseGet(() -> new SolverStep.EquipItem(GqProtection.EARRING,
                            "990000300 on takes 30000 HP a tick without it"));
        }
        if (on == STATUES) {
            return new SolverStep.Abandon("a member stands on 990000300 without the Protector Rock: the maps there take"
                    + " 30000 HP a tick from it");
        }
        if (view.floorItemsOn(on, GqProtection.EARRING, id) > 0) {
            return new SolverStep.Loot(GqProtection.EARRING);
        }
        return rock(view, member, on);
    }

    /** The rock this member is working on, or a wait naming the missing rocks. */
    private SolverStep rock(StageView view, RunMember member, int on) {
        int id = member.characterId();
        List<StageView.ReactorPlacement> rocks = new ArrayList<>();
        for (StageView.ReactorPlacement r : view.reactorsOn(on)) {
            if ((r.reactorId() == ROCK_A || r.reactorId() == ROCK_B) && r.state() >= 0 && r.state() <= LAST_HITTABLE_STATE) {
                rocks.add(r);
            }
        }
        String key = "rock:" + id;
        Object mine = view.stageMemory().get(key);
        if (mine instanceof Integer oid) {
            for (StageView.ReactorPlacement r : rocks) {
                if (r.objectId() == oid) {
                    return new SolverStep.HitReactorByOid(oid);
                }
            }
            view.stageMemory().remove(key); // spent (or respawning): another rock
        }
        Set<Object> taken = new java.util.HashSet<>(view.stageMemory().entrySet().stream()
                .filter(e -> e.getKey().startsWith("rock:") && !e.getKey().equals(key)).map(e -> e.getValue()).toList());
        int[] at = view.positionOf(id);
        StageView.ReactorPlacement best = rocks.stream().filter(r -> !taken.contains(r.objectId()))
                .min(Comparator.comparingDouble(r -> Math.hypot(r.x() - at[0], r.y() - at[1]))).orElse(null);
        if (best == null) {
            return new SolverStep.Wait(2_000, "no free rock of " + ROCK_A + "/" + ROCK_B + " on map " + on
                    + " (they respawn after 60 s): the Protector Rock " + GqProtection.EARRING + " drops from them, live "
                    + "reactordrops row unverified");
        }
        view.stageMemory().put(key, best.objectId());
        return new SolverStep.HitReactorByOid(best.objectId());
    }
}
