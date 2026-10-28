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
 * Who does what on a stage (ticket 130, "Roles"). Deterministic so every bot in the party computes the same answer
 * from the same inputs (the OPQ bots' "closest unclaimed" handout, made general):
 * <ol>
 *   <li>the party leader takes the stage's LEADER role, if it has one;</li>
 *   <li>each other role, in file order, gets its minimum from the free members it allows, in claim order;</li>
 *   <li>free members then fill roles that still have room, in file order;</li>
 *   <li>anyone left is {@value #SUPPORT}: follow, fight, loot, cheer.</li>
 * </ol>
 * Claim order is real players first, then bots, each in party order: in a mixed party the player gets the first claim
 * on every job and bots never take one from him (ticket 130, "Mixed parties"). Bots plan around a player's role, they
 * never steer him. A role left below its minimum is reported, and the orchestrator waits, re-claims or abandons.
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
        List<RunMember> order = claimOrder(members);
        for (Role r : stage.roles()) {
            if (r.who() != Who.LEADER) {
                fill(r, order, roleOf, filled, r.minFor(party));
            }
        }
        for (Role r : stage.roles()) {
            if (r.who() != Who.LEADER) {
                fill(r, order, roleOf, filled, r.maxFor(party));
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

    /** Real players first, then bots, each group in party order. */
    static List<RunMember> claimOrder(List<RunMember> members) {
        List<RunMember> order = new ArrayList<>(members.size());
        for (RunMember m : members) {
            if (!m.bot()) {
                order.add(m);
            }
        }
        for (RunMember m : members) {
            if (m.bot()) {
                order.add(m);
            }
        }
        return order;
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
