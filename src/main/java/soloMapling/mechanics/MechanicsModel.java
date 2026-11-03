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
        /**
         * Put items on the ground inside a reactor's box (OPQ: the twenty clouds on Eak's altar, the Root of Life at
         * the statue's base): the server matches the reactor's item and <em>quantity</em> exactly, so the stack must
         * land whole and on the reactor ({@code MapleMap.activateItemReactors}).
         */
        PLACE_ITEM,
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

    /**
     * One thing a role does. {@code count}, {@code item}, {@code mob}, {@code npc} are optional per kind. {@code at}
     * is the stand spot the objective is worked from when it matters (a boss part's band); {0, 0} is none.
     * {@code reactor} names the reactor the objective works on (OPQ's levers, scars and altar): a WZ name such as
     * {@code scar1}, or - all digits - a reactor id such as {@code 2006000}.
     */
    public record Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                            int npcId, String note, int atX, int atY, String reactor) {
        public Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                         int npcId, String note, int atX, int atY) {
            this(id, kind, role, count, itemId, mobId, npcId, note, atX, atY, null);
        }

        public Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                         int npcId, String note) {
            this(id, kind, role, count, itemId, mobId, npcId, note, 0, 0, null);
        }

        public boolean hasAt() {
            return atX != 0 || atY != 0;
        }

        public boolean hasReactor() {
            return reactor != null && !reactor.isBlank();
        }
    }

    /** One puzzle answer: a question (or combination) and its answer, mirrored from the stage's script. */
    public record Answer(int id, String question, int value) {
    }

    /** One boss phase: an HP band and what the party does in it (move, dispel, burst, hide). */
    public record BossPhase(String id, int hpFromPct, int hpToPct, List<String> actions) {
    }

    /**
     * How a stage is cleared: the event property the stage script sets. Alone, the property's <em>presence</em> is the
     * clear (KPQ: {@code 1stageclear}). With {@code equals}, the property must hold that value - a boss script sets its
     * property to 0 at setup (Zakum: {@code defeatedBoss=0}, {@code scripts/event/ZakumBattle.js:101}), so presence alone
     * would clear the stage on its first tick.
     *
     * <p>Some stages write no property at all and only call {@code eim.clearPQ()}: Cafe PQ's, Treasure PQ's and
     * LudiMaze PQ's final stage end that way, and {@code EventInstanceManager.isEventCleared()} is the only signal there
     * is. {@link #instance()} is that clear form - it is not an event property, it is the instance's own flag.
     */
    public record Clear(String eventProperty, String equals, boolean onInstance, boolean mapEmpty,
                        int reactorId, int reactorState) {
        public Clear(String eventProperty, String equals, boolean onInstance, boolean mapEmpty) {
            this(eventProperty, equals, onInstance, mapEmpty, 0, 0);
        }

        public Clear(String eventProperty, String equals, boolean onInstance) {
            this(eventProperty, equals, onInstance, false, 0, 0);
        }

        public Clear(String eventProperty) {
            this(eventProperty, null, false);
        }

        public Clear(String eventProperty, String equals) {
            this(eventProperty, equals, false, false, 0, 0);
        }

        /**
         * The clear of a stage whose script writes no property: cleared when the instance says it is
         * ({@code clear: { instance: true }} in a row, {@link StageView#eventCleared()} in the engine).
         */
        public static Clear instance() {
            return new Clear(null, null, true, false, 0, 0);
        }

        /**
         * The clear of a stage whose script never says "done" at all: the stage is over when the map holds nothing left
         * to kill ({@code clear: { mapEmpty: true }}). Ellin PQ's first stage is the case - its passage portal refuses
         * while {@code cm.getMap().getMonsters()} is non-empty and no property is ever written.
         */
        public static Clear emptyMap() {
            return new Clear(null, null, false, true, 0, 0);
        }

        /**
         * The clear of a stage whose authority is a <b>reactor's state</b>: Ellin PQ's second stage is over when the spine
         * reactor {@code 3009000} is at state 4, and the event writes no property for it. Reaching a later state counts as
         * well - the reactor only advances - so the comparison is {@code >=}.
         */
        public static Clear ofReactorState(int reactorId, int state) {
            return new Clear(null, null, false, false, reactorId, state);
        }
    }

    /**
     * A stage. Besides the parts every stage has, three optional ones (1.6.1, KPQ stages 2-5):
     * <ul>
     *   <li>{@code answerProperty} + {@code combos}: a combination puzzle (KPQ ropes, platforms, barrels). The stage
     *   script keeps the answer's index in the event property; {@code combos.get(i)} is the 0/1 mask over
     *   {@code positions} (in the script's order) that answer {@code i} wants occupied;</li>
     *   <li>{@code mobs}: the stage's monsters (KPQ stage 5's bosses drop the passes);</li>
     *   <li>{@code nextPortal}: the portal the stage opens on clear, which every member walks into.</li>
     *   <li>{@code summonProperty} + {@code killTarget} (boss rows, phase B): the event property that says the room's
     *   parts were summoned (null = no such gate), and the part whose death the script turns into the clear
     *   (0 = none, and then nothing is abandoned for its absence).</li>
     * </ul>
     */
    public record Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                        List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                        List<Answer> answers, List<BossPhase> phases, Clear clear, String answerProperty,
                        List<List<Integer>> combos, List<Integer> mobs, String nextPortal, String summonProperty,
                        int killTarget, int timeLimitMinutes, int hitReactor, List<Integer> maps) {

        public Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                     List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                     List<Answer> answers, List<BossPhase> phases, Clear clear) {
            this(index, mapId, name, npcId, solver, roles, objectives, positions, items, answers, phases, clear, null,
                    List.of(), List.of(), null, null, 0, 0, 0, List.of());
        }

        /**
         * Every map this stage legitimately covers: its own first, then the ones its data declares ({@code maps:}).
         * LudiMaze PQ's one stage spans sixteen maps - {@code playerEntry} drops each member on a random one of
         * fifteen ({@code LudiMazePQ.js:144-150}) and the goal is the sixteenth - so a member standing on any of them is
         * where the stage means it to be, not a stray to be dragged to the leader.
         */
        public java.util.Set<Integer> mapSet() {
            java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
            out.add(mapId);
            out.addAll(maps);
            return out;
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

    /**
     * What happens after the last stage clears (1.6.1, KPQ): every member talks to {@code rewardNpc} on
     * {@code rewardMap} for the prize (the script then sends him to {@code bonusMap}), hunts there for a while and
     * leaves through {@code exitNpc}. Null when the content simply ends on its last clear.
     */
    public record AfterClear(int rewardNpc, int rewardMap, int bonusMap, int exitNpc, int bonusMinSeconds,
                             int bonusMaxSeconds) {
    }

    /**
     * One piece of content: a PQ, boss or JQ. {@code sources} credit where the stage data was checked from.
     * {@code flag} is the content's own switch when it has one (OPQ: {@code slums.mechanics.opq}): a row with a flag
     * runs only when both {@code -Dslums.mechanics} and that flag are on, so one unfinished row can stay off while
     * the rest of the package runs. Null means {@code -Dslums.mechanics} alone.
     */
    public record Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                          int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                          List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers,
                          AfterClear afterClear, String flag,
                          /**
                           * The NPC a bot leading a party accepts the quest from (Lakelis {@code 9020000}, Wonky
                           * {@code 2013000}); 0 when the row does not say. The staging talks to it instead of pressing
                           * the event's own button, so a bot party goes in the way a player's does.
                           */
                          int entryNpc) {

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers, AfterClear afterClear,
                       String flag) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, afterClear, flag, 0);
        }

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers, AfterClear afterClear) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, afterClear, null, 0);
        }

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, null, null, 0);
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
