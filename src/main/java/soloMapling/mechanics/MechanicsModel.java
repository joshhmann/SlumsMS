package soloMapling.mechanics;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The stage model of ticket 130: a piece of content (a PQ, a boss, a jump quest) is a list of stages; a stage has
 * roles party members claim, objectives each role works on, positions (platforms, ropes, boxes) to occupy, items to
 * collect and hand in, and the answers to its puzzle. Read from YAML by {@link StageData}; immutable.
 *
 * <p>Answers in the data mirror our own scripts (the server is the truth: a solver reads the real answer from the
 * event instance at run time). The YAML copy is for validation, QA and the dashboard.
 */
public final class MechanicsModel {

    private MechanicsModel() {
    }

    /** What sort of content a file describes. */
    public enum Kind { PQ, BOSS, JQ, EXPEDITION }

    /** PQ difficulty tiers (1.7.0 "PQ difficulties"); NORMAL is the stock PQ. */
    public enum Tier {
        NORMAL, HARD, EXPERT, MASTER;

        public static Optional<Tier> parse(String s) {
            if (s == null) {
                return Optional.empty();
            }
            for (Tier t : values()) {
                if (t.name().equalsIgnoreCase(s.trim())) {
                    return Optional.of(t);
                }
            }
            return Optional.empty();
        }
    }

    /** Who an objective or role belongs to. */
    public enum Who { LEADER, NON_LEADER, ANY }

    /** What a party member must do. Each kind has a solver step family (see ticket 130, "Objective kinds"). */
    public enum ObjectiveKind {
        /** Talk to an NPC (get a question, open a stage, check an answer). */
        TALK_NPC,
        /** Kill mobs until {@code count} of an item drops (KPQ coupons, LPQ passes). */
        KILL_FOR_ITEM,
        /** Pick up items from the floor or reactors (OPQ clouds, boxes). */
        COLLECT,
        /** Hand items to an NPC ({@code to}) to be checked. */
        HAND_IN,
        /** Give items to another member (drop for the leader to loot). */
        GIVE_TO_LEADER,
        /** Answer a question: the count to collect, the combination, the sequence. */
        ANSWER,
        /** Stand on or hang from one of the stage's positions. */
        OCCUPY_POSITION,
        /** Hit a reactor (boxes, levers). */
        HIT_REACTOR,
        /** Reach a point by jumping (jump puzzles, JQs). */
        REACH,
        /** Defeat a mob or boss (with phases, see {@link BossPhase}). */
        DEFEAT,
        /** Take the stage portal once the stage is clear. */
        ENTER_PORTAL,
        /** Wait (a timer, a wave, the leader). */
        WAIT
    }

    /**
     * A count in the data: a number, {@code answer} (the member's own answer, KPQ coupons), {@code party} or
     * {@code party-1} (one pass per non-leader).
     */
    public record Count(String expr) {

        public static Optional<Count> parse(String s) {
            if (s == null) {
                return Optional.empty();
            }
            String e = s.trim().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            if (e.equals("answer") || e.equals("party") || e.equals("party-1")) {
                return Optional.of(new Count(e));
            }
            try {
                int n = Integer.parseInt(e);
                return n >= 0 ? Optional.of(new Count(e)) : Optional.empty();
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
        }

        public boolean needsAnswer() {
            return expr.equals("answer");
        }

        /** The number for this party; empty when it needs an answer the member does not have yet. */
        public OptionalInt resolve(int partySize, OptionalInt answer) {
            return switch (expr) {
                case "answer" -> answer;
                case "party" -> OptionalInt.of(partySize);
                case "party-1" -> OptionalInt.of(Math.max(0, partySize - 1));
                default -> OptionalInt.of(Integer.parseInt(expr));
            };
        }
    }

    /** A rectangle members stand in or hang on (KPQ ropes and platforms, LPQ boxes). Script coordinates. */
    public record Position(String id, int x, int y, int w, int h, boolean rope) {
        public boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        public int centerX() {
            return x + w / 2;
        }

        public int centerY() {
            return y + h / 2;
        }
    }

    /** An item the stage uses; {@code cleanup} items are taken away when the run ends (the event script does that). */
    public record ItemUse(int itemId, String name, boolean cleanup) {
    }

    /** A role members claim: how many may take it (a number or party-relative) and who may. */
    public record Role(String id, Who who, Count min, Count max, String note) {
        public int minFor(int partySize) {
            return min.resolve(partySize, OptionalInt.empty()).orElse(0);
        }

        public int maxFor(int partySize) {
            return max.resolve(partySize, OptionalInt.empty()).orElse(0);
        }
    }

    /** One thing a role does. {@code count}, {@code item}, {@code mob}, {@code npc} are optional per kind. */
    public record Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                            int npcId, String note) {
    }

    /** One puzzle answer: a question (or combination) and its answer, mirrored from the stage's script. */
    public record Answer(int id, String question, int value) {
    }

    /** One boss phase: an HP band and what the party does in it (move, dispel, burst, hide). */
    public record BossPhase(String id, int hpFromPct, int hpToPct, List<String> actions) {
    }

    /** How a stage is cleared: the event property the stage script sets (KPQ: {@code 1stageclear}). */
    public record Clear(String eventProperty) {
    }

    /**
     * A stage. Besides the parts every stage has, three optional ones (1.6.1, KPQ stages 2-5):
     * <ul>
     *   <li>{@code answerProperty} + {@code combos}: a combination puzzle (KPQ ropes, platforms, barrels). The stage
     *   script keeps the answer's index in the event property; {@code combos.get(i)} is the 0/1 mask over
     *   {@code positions} (in the script's order) that answer {@code i} wants occupied;</li>
     *   <li>{@code mobs}: the stage's monsters (KPQ stage 5's bosses drop the passes);</li>
     *   <li>{@code nextPortal}: the portal the stage opens on clear, which every member walks into.</li>
     * </ul>
     */
    public record Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                        List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                        List<Answer> answers, List<BossPhase> phases, Clear clear, String answerProperty,
                        List<List<Integer>> combos, List<Integer> mobs, String nextPortal) {

        public Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                     List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                     List<Answer> answers, List<BossPhase> phases, Clear clear) {
            this(index, mapId, name, npcId, solver, roles, objectives, positions, items, answers, phases, clear, null,
                    List.of(), List.of(), null);
        }

        /** How many positions a combination's answer occupies (KPQ: 3), or 0 for a stage without combos. */
        public int occupied() {
            return combos.isEmpty() ? 0 : (int) combos.get(0).stream().filter(v -> v != 0).count();
        }

        /** The position indices (0-based, script order) that combination {@code index} wants occupied. */
        public List<Integer> comboPositions(int index) {
            List<Integer> out = new java.util.ArrayList<>();
            if (index < 0 || index >= combos.size()) {
                return out;
            }
            List<Integer> mask = combos.get(index);
            for (int i = 0; i < mask.size(); i++) {
                if (mask.get(i) != 0) {
                    out.add(i);
                }
            }
            return out;
        }

        public Optional<Role> role(String id) {
            return roles.stream().filter(r -> r.id().equals(id)).findFirst();
        }

        public List<Objective> objectivesFor(String roleId) {
            return objectives.stream().filter(o -> o.role().equals(roleId) || o.role().equals("all")).toList();
        }

        public OptionalInt answer(int questionId) {
            return answers.stream().filter(a -> a.id() == questionId).mapToInt(Answer::value).findFirst();
        }
    }

    /**
     * Per-tier rules (data, owner to grill before Hard and up are built). NORMAL is the stock PQ. Mob multipliers are
     * applied by the event script; {@code botMistakeFactor} scales {@link TryPlanner}'s wrong tries.
     */
    public record TierRules(Tier tier, int minLevel, int maxLevel, int mobLevelBonus, double mobHpMultiplier,
                            double mobDamageMultiplier, int timeLimitMinutes, double botMistakeFactor) {
    }

    /** One piece of content: a PQ, boss or JQ. {@code source} credits where the stage data was checked from. */
    /**
     * What happens after the last stage clears (1.6.1, KPQ): every member talks to {@code rewardNpc} on
     * {@code rewardMap} for the prize (the script then sends him to {@code bonusMap}), hunts there for a while and
     * leaves through {@code exitNpc}. Null when the content simply ends on its last clear.
     */
    public record AfterClear(int rewardNpc, int rewardMap, int bonusMap, int exitNpc, int bonusMinSeconds,
                             int bonusMaxSeconds) {
    }

    public record Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                          int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                          List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers,
                          AfterClear afterClear) {

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, null);
        }

        public Optional<Stage> stage(int index) {
            return stages.stream().filter(s -> s.index() == index).findFirst();
        }

        public Optional<Stage> stageOnMap(int mapId) {
            return stages.stream().filter(s -> s.mapId() == mapId).findFirst();
        }

        /** The tier's rules; NORMAL falls back to the stock numbers above when the file gives none. */
        public Optional<TierRules> rules(Tier tier) {
            TierRules r = tiers.get(tier);
            if (r == null && tier == Tier.NORMAL) {
                r = new TierRules(Tier.NORMAL, minLevel, maxLevel, 0, 1.0, 1.0, timeLimitMinutes, 1.0);
            }
            return Optional.ofNullable(r);
        }
    }
}
