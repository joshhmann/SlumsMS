package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Role;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Who;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who does what on a stage (ticket 114, "Roles"). Deterministic so every bot in the party computes the same answer
 * from the same inputs (the OPQ bots' "closest unclaimed" handout, made general):
 * <ol>
 *   <li>the party leader takes the stage's LEADER role, if it has one;</li>
 *   <li>each other role, in file order, gets its minimum from the free members it allows, in party order;</li>
 *   <li>free members then fill roles that still have room, in file order;</li>
 *   <li>anyone left is {@value #SUPPORT}: follow, fight, loot, cheer.</li>
 * </ol>
 * A role left below its minimum is reported, and the orchestrator waits, re-claims or abandons.
 */
public final class RoleClaims {

    public static final String SUPPORT = "support";

    public record Claims(Map<Integer, String> roleOf, List<String> unfilled) {
        public boolean complete() {
            return unfilled.isEmpty();
        }
    }

    private RoleClaims() {
    }

    public static Claims claim(Stage stage, List<RunMember> members) {
        int party = members.size();
        Map<Integer, String> roleOf = new LinkedHashMap<>();
        Map<String, Integer> filled = new HashMap<>();
        for (Role r : stage.roles()) {
            if (r.who() == Who.LEADER) {
                for (RunMember m : members) {
                    if (m.leader() && !roleOf.containsKey(m.characterId())) {
                        roleOf.put(m.characterId(), r.id());
                        filled.merge(r.id(), 1, Integer::sum);
                        break;
                    }
                }
            }
        }
        for (Role r : stage.roles()) {
            if (r.who() != Who.LEADER) {
                fill(r, members, roleOf, filled, r.minFor(party));
            }
        }
        for (Role r : stage.roles()) {
            if (r.who() != Who.LEADER) {
                fill(r, members, roleOf, filled, r.maxFor(party));
            }
        }
        for (RunMember m : members) {
            roleOf.putIfAbsent(m.characterId(), SUPPORT);
        }
        List<String> unfilled = new ArrayList<>();
        for (Role r : stage.roles()) {
            int have = filled.getOrDefault(r.id(), 0);
            if (have < r.minFor(party)) {
                unfilled.add(r.id() + " " + have + "/" + r.minFor(party));
            }
        }
        return new Claims(Map.copyOf(roleOf), List.copyOf(unfilled));
    }

    private static void fill(Role r, List<RunMember> members, Map<Integer, String> roleOf, Map<String, Integer> filled,
                             int upTo) {
        for (RunMember m : members) {
            if (filled.getOrDefault(r.id(), 0) >= upTo) {
                return;
            }
            if (roleOf.containsKey(m.characterId())) {
                continue;
            }
            if (r.who() == Who.NON_LEADER && m.leader()) {
                continue;
            }
            roleOf.put(m.characterId(), r.id());
            filled.merge(r.id(), 1, Integer::sum);
        }
    }
}
