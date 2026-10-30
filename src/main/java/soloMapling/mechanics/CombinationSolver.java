package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Position;
import soloMapling.mechanics.MechanicsModel.Stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;

/**
 * {@code combination} (1.6.1 KPQ automation; KPQ stages 2-4: ropes, platforms, barrels). Everything comes from the
 * stage data, which {@code KpqStageDataTest} keeps equal to our {@code scripts/npc/9020001.js}:
 * <ul>
 *   <li>the <b>leader</b>'s first talk with the NPC opens the stage: the script picks the answer and keeps its index
 *   in the event property ({@code stg2Property}...). Every later talk is a check: the script counts the members
 *   inside each rectangle and accepts only the answer's exact 0/1 pattern;</li>
 *   <li>three members (the non-leaders; in a party of three the leader too) stand on the positions the formation
 *   wants. Nobody else may stand on any position;</li>
 *   <li>once the formation has held still for a moment the leader checks it.</li>
 * </ul>
 * The server is the truth: the answer is read from the event property, never guessed. <b>Visible tries</b> (owner
 * 2026-10-26): a bot leader plans a few wrong formations first, by persona and tier ({@link TryPlanner}), and walks
 * them "one mover" at a time (two members stay, one moves to another position, as players search), the last one
 * being the answer, so the run always finishes. The one-mover idea is from nutnnut/cosmic_agents'
 * {@code AgentKpqCombinationOrder} (Kiwiism/cosmic_agents 3c7e09435, AGPL-3.0), reimplemented here.
 *
 * <p>Mixed parties: a real player is never steered. A position he already stands on counts as filled; when he has
 * not picked one, bots leave him one and the bot leader calls it out. With a real player on the puzzle, or a real
 * leader, the bots form the answer straight away (they cannot make a player try wrong formations), and with a real
 * leader one bot says when the formation is ready for him to check.
 */
public final class CombinationSolver implements StageSolver {

    public static final String ID = "combination";

    /** Most wrong formations a bot leader checks on one stage (each costs a re-shuffle and a click). */
    public static final int MAX_WRONG_CHECKS = 3;
    /** How long a formation must hold still before the leader checks it. */
    static final long STEADY_MS = 2_500;
    /** How far from the NPC a leader standing on a position may click it (a screen away, as players do). */
    static final int REMOTE_CHECK_PX = 1_000;
    /** How far from the NPC a leader not on the puzzle may click it. */
    static final int NEAR_CHECK_PX = 110;
    /** A call-out ("Bob take rope 3") at most this often. */
    static final long SAY_EVERY_MS = 20_000;

    static final String K_ASSIGN = "combination.assign";
    static final String K_ASSIGN_FOR = "combination.assignFor";
    static final String K_STEADY = "combination.steadySince";
    static final String K_SAID = "combination.saidAt";
    static final String K_READY_SAID = "combination.readySaidFor";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        Stage stage = view.stage();
        int id = member.characterId();
        OptionalInt answer = answer(view);
        RunMember leader = view.leader().orElse(null);
        if (answer.isEmpty()) {
            // not opened yet: the leader opens it; everyone else keeps off the positions and waits for him
            if (member.leader()) {
                return new SolverStep.TalkNpc(stage.npcId(), 0, 0);
            }
            SolverStep off = stepOffPositions(view, id);
            return off != null ? off : new SolverStep.Wait(1_500, "waiting for the leader to talk to the NPC");
        }
        int target = target(view, leader, answer.getAsInt());
        List<RunMember> standers = standers(view);
        Map<Integer, Integer> assign = assign(view, standers, target);
        boolean exact = formationExact(view, target);
        long now = view.nowMs();
        Map<String, Object> mem = view.stageMemory();
        if (exact) {
            mem.putIfAbsent(K_STEADY, now);
        } else {
            mem.remove(K_STEADY);
        }
        long steadySince = (Long) mem.getOrDefault(K_STEADY, Long.MAX_VALUE);
        boolean steady = exact && now - steadySince >= STEADY_MS;

        if (member.leader()) {
            SolverStep say = callOut(view, standers, assign, target, now);
            if (say != null) {
                return say;
            }
            Integer mine = assign.get(id);
            if (mine != null && !inside(view, id, stage.positions().get(mine))) {
                return occupy(stage.positions().get(mine));
            }
            if (steady) {
                return new SolverStep.CheckAnswer(stage.npcId(), target, mine != null ? REMOTE_CHECK_PX : NEAR_CHECK_PX);
            }
            if (mine == null) {
                SolverStep off = stepOffPositions(view, id);
                if (off != null) {
                    return off;
                }
            }
            return new SolverStep.Wait(500, exact ? "formation " + target + " settling" : "waiting for formation " + target);
        }
        Integer mine = assign.get(id);
        if (mine != null) {
            Position p = stage.positions().get(mine);
            if (!inside(view, id, p)) {
                return occupy(p);
            }
            if (steady && leader != null && !leader.bot() && !Integer.valueOf(target).equals(mem.get(K_READY_SAID))
                    && firstBotStander(standers) == id) {
                mem.put(K_READY_SAID, target);
                return new SolverStep.Say(READY_LINES.get(Math.floorMod(id + target, READY_LINES.size())));
            }
            return new SolverStep.Wait(500, "on " + p.id());
        }
        SolverStep off = stepOffPositions(view, id);
        return off != null ? off : new SolverStep.Wait(1_500, "not on the puzzle this time");
    }

    @Override
    public java.util.Optional<String> leaderPrompt(StageView view, RunMember leader) {
        OptionalInt answer = answer(view);
        int id = leader.characterId();
        if (answer.isEmpty()) {
            return java.util.Optional.of("@" + id + " talk to Cloto to start this stage");
        }
        Object since = view.stageMemory().get(K_STEADY);
        if (since instanceof Long t && view.nowMs() - t >= STEADY_MS && formationExact(view, answer.getAsInt())) {
            return java.util.Optional.of("@" + id + " we're all in place, click Cloto to check");
        }
        return java.util.Optional.empty();
    }

    static final List<String> READY_LINES = List.of("ok we're on, check it", "k try now", "ready, talk to cloto",
            "all on, check");

    // ---- the plan: answer, target, who stands where ----

    /** The answer's combo index, from the event property the stage script set (absent until the stage is opened). */
    static OptionalInt answer(StageView view) {
        String key = view.stage().answerProperty();
        if (key == null) {
            return OptionalInt.empty();
        }
        return view.eventProperty(key).map(v -> {
            try {
                int i = Integer.parseInt(v.trim());
                return i >= 0 && i < view.stage().combos().size() ? OptionalInt.of(i) : OptionalInt.empty();
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }).orElse(OptionalInt.empty());
    }

    /** The formation to show now: a bot leader's next planned wrong one, else the answer. */
    static int target(StageView view, RunMember leader, int answer) {
        if (leader != null && leader.bot()) {
            List<Integer> wrong = view.wrongTriesLeft(leader.characterId());
            if (!wrong.isEmpty()) {
                return wrong.get(0);
            }
        }
        return answer;
    }

    /**
     * Who stands on the puzzle: the non-leaders in party order, and the leader too when they are too few (a party of
     * three puts everyone on the ropes).
     */
    static List<RunMember> standers(StageView view) {
        int need = view.stage().occupied();
        List<RunMember> out = new ArrayList<>();
        RunMember leader = null;
        for (RunMember m : view.members()) {
            if (view.mapOf(m.characterId()) != view.stage().mapId()) {
                continue;
            }
            if (m.leader()) {
                leader = m;
            } else if (out.size() < need) {
                out.add(m);
            }
        }
        if (out.size() < need && leader != null) {
            out.add(leader);
        }
        return out;
    }

    /**
     * Each stander's position (index into {@code positions}) for formation {@code target}. Real players keep the
     * position they stand on when the formation wants it; bots keep theirs when it is still wanted (so only one bot
     * moves between neighbouring formations); the rest are handed out in position order. A wanted position left over
     * after the bots is the one a player who stands nowhere should take.
     */
    @SuppressWarnings("unchecked")
    static Map<Integer, Integer> assign(StageView view, List<RunMember> standers, int target) {
        Stage stage = view.stage();
        Map<String, Object> mem = view.stageMemory();
        Map<Integer, Integer> previous = (Map<Integer, Integer>) mem.getOrDefault(K_ASSIGN, Map.of());
        List<Integer> wanted = new ArrayList<>(stage.comboPositions(target));
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (RunMember m : standers) {
            if (!m.bot()) {
                int at = positionIndexOf(view, m.characterId());
                if (at >= 0 && wanted.remove(Integer.valueOf(at))) {
                    out.put(m.characterId(), at);
                }
            }
        }
        for (RunMember m : standers) {
            if (m.bot()) {
                Integer prev = previous.get(m.characterId());
                if (prev != null && wanted.remove(prev)) {
                    out.put(m.characterId(), prev);
                }
            }
        }
        List<RunMember> playersWithout = standers.stream().filter(m -> !m.bot() && !out.containsKey(m.characterId()))
                .toList();
        // a player who stands nowhere useful keeps one wanted position for himself: the last one
        int forBots = Math.max(0, wanted.size() - playersWithout.size());
        for (RunMember m : standers) {
            if (m.bot() && !out.containsKey(m.characterId()) && forBots > 0) {
                Integer p = wanted.remove(0);
                out.put(m.characterId(), p);
                forBots--;
            }
        }
        mem.put(K_ASSIGN, Map.copyOf(out));
        mem.put(K_ASSIGN_FOR, target);
        return out;
    }

    /** The positions a player who stands nowhere useful should take (left over after the bots). */
    static List<Integer> leftForPlayers(StageView view, Map<Integer, Integer> assign, int target) {
        List<Integer> wanted = new ArrayList<>(view.stage().comboPositions(target));
        wanted.removeAll(assign.values());
        return wanted;
    }

    /** Exactly the script's test: per position, how many members stand in it, equal to the combo's 0/1 mask. */
    static boolean formationExact(StageView view, int target) {
        Stage stage = view.stage();
        int[] count = new int[stage.positions().size()];
        for (RunMember m : view.members()) {
            int at = positionIndexOf(view, m.characterId());
            if (at >= 0) {
                count[at]++;
            }
        }
        List<Integer> mask = stage.combos().get(target);
        for (int i = 0; i < count.length; i++) {
            if (count[i] != mask.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** The first position (script order) whose rectangle holds this member, or -1 (the script counts it once). */
    static int positionIndexOf(StageView view, int characterId) {
        if (view.mapOf(characterId) != view.stage().mapId()) {
            return -1;
        }
        int[] at = view.positionOf(characterId);
        List<Position> ps = view.stage().positions();
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).contains(at[0], at[1])) {
                return i;
            }
        }
        return -1;
    }

    static boolean inside(StageView view, int characterId, Position p) {
        int[] at = view.positionOf(characterId);
        return view.mapOf(characterId) == view.stage().mapId() && p.contains(at[0], at[1]);
    }

    static SolverStep.Occupy occupy(Position p) {
        return new SolverStep.Occupy(p.id(), p.centerX(), p.centerY(), p.x(), p.y(), p.w(), p.h(), p.rope());
    }

    /** A member who must not count on the puzzle but stands on a position walks to the NPC (off the positions). */
    static SolverStep stepOffPositions(StageView view, int characterId) {
        if (positionIndexOf(view, characterId) < 0) {
            return null;
        }
        var npc = view.npcPosition(view.stage().npcId());
        if (npc.isPresent()) {
            return new SolverStep.MoveTo(npc.get()[0], npc.get()[1], "off the puzzle");
        }
        int[] at = view.positionOf(characterId);
        return new SolverStep.MoveTo(at[0], at[1] + 200, "off the puzzle");
    }

    private static int firstBotStander(List<RunMember> standers) {
        for (RunMember m : standers) {
            if (m.bot()) {
                return m.characterId();
            }
        }
        return -1;
    }

    /**
     * The bot leader's call-outs to real players on the puzzle, at most every {@link #SAY_EVERY_MS}: one who stands on
     * a position the formation does not want, or one who stands nowhere while a position waits for him.
     */
    private static SolverStep callOut(StageView view, List<RunMember> standers, Map<Integer, Integer> assign, int target,
                                      long now) {
        Map<String, Object> mem = view.stageMemory();
        long said = (Long) mem.getOrDefault(K_SAID, Long.MIN_VALUE / 2);
        if (now - said < SAY_EVERY_MS) {
            return null;
        }
        List<Integer> wanted = view.stage().comboPositions(target);
        for (RunMember m : view.members()) {
            if (m.bot()) {
                continue;
            }
            int at = positionIndexOf(view, m.characterId());
            if (at >= 0 && !wanted.contains(at)) {
                mem.put(K_SAID, now);
                return new SolverStep.Say("@" + m.characterId() + " get off " + view.stage().positions().get(at).id()
                        + " pls");
            }
        }
        List<Integer> left = leftForPlayers(view, assign, target);
        for (RunMember m : standers) {
            if (!m.bot() && !assign.containsKey(m.characterId()) && !left.isEmpty()) {
                mem.put(K_SAID, now);
                return new SolverStep.Say("@" + m.characterId() + " u take " + view.stage().positions().get(left.get(0)).id());
            }
        }
        return null;
    }

    // ---- visible tries ----

    @Override
    public List<Integer> planWrongTries(StageView view, RunMember member, int answer, MechanicsPersona persona,
                                        double mistakeFactor, Random rnd) {
        if (!member.leader() || !member.bot()) {
            return List.of(); // the leader checks; a real leader is never given tries
        }
        for (RunMember m : standers(view)) {
            if (!m.bot()) {
                return List.of(); // bots cannot make a player try wrong formations: straight to the answer
            }
        }
        return plannedWrongCombos(view.stage(), answer, persona, mistakeFactor, rnd);
    }

    /**
     * The wrong formations a bot leader checks before the answer, in the order he checks them: a chain in which each
     * formation differs from the next by one mover, the last one differing from the answer by one mover. How many
     * comes from {@link TryPlanner} (persona and tier), at most {@link #MAX_WRONG_CHECKS}.
     */
    public static List<Integer> plannedWrongCombos(Stage stage, int answer, MechanicsPersona persona,
                                                   double mistakeFactor, Random rnd) {
        int n = stage.combos().size();
        if (answer < 0 || answer >= n) {
            return List.of();
        }
        int wrong = TryPlanner.tries(answer, n, persona, mistakeFactor, rnd, MAX_WRONG_CHECKS).size() - 1;
        List<Integer> chain = new ArrayList<>();
        chain.add(answer);
        int cur = answer;
        for (int i = 0; i < wrong; i++) {
            List<Integer> next = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (!chain.contains(j) && oneMover(stage, cur, j)) {
                    next.add(j);
                }
            }
            if (next.isEmpty()) {
                break;
            }
            cur = next.get(rnd.nextInt(next.size()));
            chain.add(cur);
        }
        chain.remove(0);
        Collections.reverse(chain);
        return List.copyOf(chain);
    }

    /** Two formations differ by one mover: they share all but one occupied position. */
    public static boolean oneMover(Stage stage, int a, int b) {
        List<Integer> pa = stage.comboPositions(a);
        List<Integer> pb = stage.comboPositions(b);
        int shared = 0;
        for (int p : pa) {
            if (pb.contains(p)) {
                shared++;
            }
        }
        return a != b && shared == pa.size() - 1;
    }

    /** For tests and the status line: counts per position of who stands where. */
    static Map<String, Integer> occupancy(StageView view) {
        Map<String, Integer> out = new HashMap<>();
        for (RunMember m : view.members()) {
            int at = positionIndexOf(view, m.characterId());
            if (at >= 0) {
                out.merge(view.stage().positions().get(at).id(), 1, Integer::sum);
            }
        }
        return out;
    }
}
