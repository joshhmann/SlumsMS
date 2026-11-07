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

    /**
     * What the run does when a stage's own clock ({@code timeLimitMinutes:} on the stage) runs out, as the row declares
     * it ({@code onTimeLimit:}). {@link #END} is what every script that times a room separately does
     * ({@code scripts/event/PiratePQ.js:223-225}, {@code scripts/event/CWKPQ.js:246-248}: {@code scheduledTimeout} is
     * {@code end(eim)}) and is the default; {@link #ADVANCE} is the way the one script whose timer is a phase change
     * does it ({@code scripts/event/GuildQuest.js:203-222}: the first expiry closes the join window and starts the real
     * clock): the run goes on to the next stage without a clear.
     */
    public enum OnTimeLimit { END, ADVANCE }

    /**
     * One conditional way on from a stage ({@code branches:} on the stage in a row): when the stage clears and
     * {@code when} holds of the room's state, the run goes to {@code stage} instead of the next index. The condition
     * is one of two kinds a row can already read, never a script: an event property (present, or equal to
     * {@code equals}) or a reactor, named ({@code light}) or by WZ id ({@code 2408001}, all digits), at exactly
     * {@code reactorState}. No condition at all always holds, so an entry like that goes last. {@code stage} is a stage
     * index of the row - at or before this one is a re-entry - or {@link #END}: the row is finished here, cleared.
     * Pre-Horntail PQ is the case for both: {@code scripts/portal/hontale_C.js:30-41} picks one of two caves by a
     * reactor's state, and {@code scripts/portal/hontale_Bopen.js:96-124} sends the party back to a room it has played.
     */
    public record Branch(String eventProperty, String equals, String reactor, int reactorState, int stage) {
        /** The {@code goto:} that finishes the row. */
        public static final int END = 0;

        public boolean end() {
            return stage == END;
        }

        public boolean unconditional() {
            return eventProperty == null && reactor == null;
        }

        /** The condition in the words the history uses ("path = b", "reactor light at state 3"). */
        public String describeWhen() {
            if (eventProperty != null) {
                return equals == null ? eventProperty + " is set" : eventProperty + " = " + equals;
            }
            return reactor == null ? "always" : "reactor " + reactor + " at state " + reactorState;
        }
    }

    /** PQ difficulty tiers (1.6.4 "PQ difficulties"); NORMAL is the stock PQ. */
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
                            int npcId, String note, int atX, int atY, String reactor, int job) {
        /** 1.6.6 b365: {@code job} - the job niche (1 warrior .. 5 pirate) an objective belongs to, or 0. */
        public Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                         int npcId, String note, int atX, int atY, String reactor) {
            this(id, kind, role, count, itemId, mobId, npcId, note, atX, atY, reactor, 0);
        }

        public Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                         int npcId, String note, int atX, int atY) {
            this(id, kind, role, count, itemId, mobId, npcId, note, atX, atY, null, 0);
        }

        public Objective(String id, ObjectiveKind kind, String role, Optional<Count> count, int itemId, int mobId,
                         int npcId, String note) {
            this(id, kind, role, count, itemId, mobId, npcId, note, 0, 0, null, 0);
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
                        int reactorId, int reactorState, int partyOnMap, boolean botsOnly) {
        /** Every clear but {@link #ofBotsOnMap}: a {@code partyOnMap} clear counts the whole party. */
        public Clear(String eventProperty, String equals, boolean onInstance, boolean mapEmpty,
                     int reactorId, int reactorState, int partyOnMap) {
            this(eventProperty, equals, onInstance, mapEmpty, reactorId, reactorState, partyOnMap, false);
        }

        public Clear(String eventProperty, String equals, boolean onInstance, boolean mapEmpty,
                     int reactorId, int reactorState) {
            this(eventProperty, equals, onInstance, mapEmpty, reactorId, reactorState, 0);
        }

        public Clear(String eventProperty, String equals, boolean onInstance, boolean mapEmpty) {
            this(eventProperty, equals, onInstance, mapEmpty, 0, 0, 0);
        }

        /**
         * A stage that ends when the <b>whole</b> party is standing on {@code mapId} - the way a stage that finishes by
         * warping the team somewhere (Ellin PQ's marble hand-in, `2133001.js:93-94`) is cleared. Every registered member
         * has to be there: a member still walking would otherwise fake a clear on the first arrival.
         */
        public static Clear ofPartyOnMap(int mapId) {
            return new Clear(null, null, false, false, 0, 0, mapId);
        }

        /**
         * 1.6.5 b344: a stage that ends when every <b>bot</b> of the run stands on {@code mapId}, wherever the real
         * players are. For a maze the party roams freely (the Zakum PQ mines: no script warps the team, every door is
         * open): the run never steers a real player, so a whole-party clear there waits on whoever walked his own way,
         * and the orchestrator puts a bot that went on ahead back on the stage map. {@link #partyOnMap()} is still the
         * map, so a solver that leaves for it reads it the same way.
         */
        public static Clear ofBotsOnMap(int mapId) {
            return new Clear(null, null, false, false, 0, 0, mapId, true);
        }

        public Clear(String eventProperty, String equals, boolean onInstance) {
            this(eventProperty, equals, onInstance, false, 0, 0, 0);
        }

        public Clear(String eventProperty) {
            this(eventProperty, null, false);
        }

        public Clear(String eventProperty, String equals) {
            this(eventProperty, equals, false, false, 0, 0, 0);
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
     *   <li>{@code summonProperty} (boss rows, phase B): the event property that says the room's parts were summoned
     *   (null = no such gate);</li>
     *   <li>{@code spawnWaitSeconds} (1.6.4 boss rows with no reactor and no summon property): how long the room may
     *   hold none of {@code mobs} and still be a fight the map's own life is about to bring in.
     *   0 = no expectation, and then an empty room is abandoned after the solver's 30 s floor. This is a fact about
     *   the content ("this map brings its mobs in N seconds"), not a tolerance: the tolerance is the solver's, and it
     *   never goes below 30 s ({@code BossPhasesSolver.allowedEmptyMs}) because a fight that gives up on an eight-second
     *   gap is the livelock shape ticket 176 warns about. A row therefore raises the leash and cannot shorten it.</li>
     *   <li>{@code hitReactors} (boss rows behind a reactor): the reactors a bot clicks to open the room, in the order
     *   they must be clicked. Empty = no reactor (the room brings its own fight). A room with two of them (Scarga's
     *   Targa and Scarlion) needs both clicked before either fight is worth starting.</li>
     *   <li>{@code entryItems} (boss rows behind a door that wants an item, 164-bossrows round 3): item ids the run must
     *   have in hand before the stage's first step. <em>One</em> member holding each is enough, because a party door is
     *   opened by one character ({@code scripts/portal/captinsg00.js:4} checks the player who clicks it, and nothing
     *   inside {@code LatanicaBattle.js} re-checks the rest). A run that lacks one is not refused: it waits, naming the
     *   item, until the row's own steps (or the bot's inventory) bring it - the row never warps past a door.</li>
     *   <li>{@code hitReactors} is the one spelling of "the reactor this stage is behind", for both lanes: the boss rows
     *   list the ones they must click (Scarga's Targa and Scarlion), and the party-quest rows - Ellin PQ's spine
     *   ({@code 3009000}) and its stone, Magatia's beaker - list one. The scalar {@code hitReactor} the party-quest lane
     *   grew separately was folded into this list at the 1.6.4 merge, with the three solvers and two rows that read it;</li>
     *   <li>{@code maps} (party-quest side): every further map the stage legitimately covers, used by {@link #mapSet()}
     *   - LudiMaze PQ's one stage spans sixteen maps.</li>
     *   <li>{@code loopTo} (realruns): the stage this one sends a run back to instead of forward - BossRush's waves
     *   repeat until the row's clock runs out. 0 = advance, which is every other row in the folder.</li>
     *   <li>{@code onTimeLimit} (seams): what the run does when this stage's own {@code timeLimitMinutes} runs out -
     *   {@link OnTimeLimit#END} (every script that times a room separately, and the default) or
     *   {@link OnTimeLimit#ADVANCE} (GuildQuest's first expiry is the phase change that starts the real clock);</li>
     *   <li>{@code branches} (seams): the conditional ways on from this stage once it clears ({@link Branch}), read
     *   from the room's own properties and reactors - Pre-Horntail PQ picks one of two caves and can send a party back
     *   to a room it has already played. Empty = always the next index.</li>
     * </ul>
     */
    public record Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                        List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                        List<Answer> answers, List<BossPhase> phases, Clear clear, String answerProperty,
                        List<List<Integer>> combos, List<Integer> mobs, String nextPortal, String summonProperty,
                        int spawnWaitSeconds, int timeLimitMinutes, List<Integer> hitReactors,
                        List<Integer> entryItems, List<Integer> maps, int loopTo, OnTimeLimit onTimeLimit,
                        List<Branch> branches, String opensByHit) {

        /**
         * 1.6.6 b365: {@code opensByHit} - when a reactor this stage opens takes an item (Reactor.wz type 100) and nobody
         * in the run holds it, may it still be opened by a hit? {@code null} (the default): never. {@link #BOT_ONLY}:
         * only in a run with no real player in it (regulars running a boss on their own); with a player in the run the
         * room waits for the item, as it does for a party in the real game.
         */
        public static final String BOT_ONLY = "botOnly";
        /**
         * b365 (Crimsonwood's Relic of Mastery): a reactor whose objective names a {@code job} may open by a hit when no
         * real player of that job is in the run - the job is the regulars' - and never when one is.
         */
        public static final String REGULAR_JOBS_ONLY = "regularJobsOnly";
        /**
         * b365 (owner, 7 Nov: Scarga): the stage's first item reactor takes its item; once one of the stage's reactors has
         * taken it, the others open by a hit. Regulars on their own may hit them all, as with {@link #BOT_ONLY}.
         */
        public static final String AFTER_FIRST_ITEM = "afterFirstItem";

        /** b365: whether this row lets a run of regulars alone open its item reactors by a hit. */
        public boolean botsMayHit() {
            return BOT_ONLY.equals(opensByHit) || AFTER_FIRST_ITEM.equals(opensByHit);
        }

        public Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                     List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                     List<Answer> answers, List<BossPhase> phases, Clear clear, String answerProperty,
                     List<List<Integer>> combos, List<Integer> mobs, String nextPortal, String summonProperty,
                     int spawnWaitSeconds, int timeLimitMinutes, List<Integer> hitReactors,
                     List<Integer> entryItems, List<Integer> maps, int loopTo, OnTimeLimit onTimeLimit,
                     List<Branch> branches) {
            this(index, mapId, name, npcId, solver, roles, objectives, positions, items, answers, phases, clear,
                    answerProperty, combos, mobs, nextPortal, summonProperty, spawnWaitSeconds, timeLimitMinutes,
                    hitReactors, entryItems, maps, loopTo, onTimeLimit, branches, null);
        }

        public Stage(int index, int mapId, String name, int npcId, String solver, List<Role> roles,
                     List<Objective> objectives, List<Position> positions, List<ItemUse> items,
                     List<Answer> answers, List<BossPhase> phases, Clear clear) {
            this(index, mapId, name, npcId, solver, roles, objectives, positions, items, answers, phases, clear, null,
                    List.of(), List.of(), null, null, 0, 0, List.of(), List.of(), List.of(), 0, OnTimeLimit.END,
                    List.of());
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
     * The world queue a content is entered through ({@code queue:} in a row), instead of {@code
     * EventManager.startInstance(party, map)}. Guild Quest is the case: its door NPC ({@code scripts/npc/9040000.js:62-91})
     * registers a <em>guild</em> with {@code em.addGuildToQueue} and the world starts the instance when the guild's turn
     * comes ({@code EventManager.attemptStartGuildInstance} -> {@code startInstance(chr)}) with the guild's master alone
     * inside; every other member then talks to the same NPC to be registered ({@code :54-65}), within the strategy time.
     *
     * <p>{@code register} and {@code join} are the answers a conversation with the row's {@code entryNpc} gives to take
     * each of those two ways (the menu line, then yes); {@code closedProperty}/{@code closedEquals} is the event
     * property that says the join window is over and the members are fixed ({@code GuildQuest.js:206-207} sets {@code
     * canJoin} to 0 when the strategy time ends); {@code waitMinutes} is how long that takes ({@code GuildQuest.js:35}).
     */
    public record Queue(java.util.List<Integer> register, java.util.List<Integer> join, String closedProperty,
                        String closedEquals, int waitMinutes) {
        /** Whether this event property value means the join window is closed. */
        public boolean closed(String value) {
            return value != null && value.trim().equals(closedEquals);
        }
    }

    /**
     * A seat the party has to fill ({@code composition:} in a row), the way {@code minPlayers}/{@code maxPlayers} are
     * the party's size: {@code min} members whose level is {@code minLevel}..{@code maxLevel} ({@code maxLevel} 0 = no
     * cap). Guild Quest's door out of the four-room maze is the case: {@code scripts/portal/under30gate.js:25} lets a
     * member through only at level 30 or below, so a party of high-level members - bots included - can never reach the
     * crown, and the stage can never clear. The row says so as a fact; the run refuses to start without the seat filled
     * and staging seats a member who fits or refuses with the line.
     */
    public record Seat(String id, int minLevel, int maxLevel, int min, String note) {
        public boolean fits(int level) {
            return level > 0 && level >= minLevel && (maxLevel <= 0 || level <= maxLevel);
        }

        /** "level 10-30" / "level 10+" for a line a person reads. */
        public String describeLevels() {
            return maxLevel > 0 ? "level " + minLevel + "-" + maxLevel : "level " + minLevel + "+";
        }
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
                          int entryNpc,
                          /** The seats the party must fill ({@link Seat}); empty for every row that asks nothing of it. */
                          List<Seat> composition,
                          /** The world queue this content is entered through, or null for every row that is not. */
                          Queue queue) {

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers, AfterClear afterClear,
                       String flag, int entryNpc, List<Seat> composition) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, afterClear, flag, entryNpc, composition, null);
        }

        public Content(String id, String name, Kind kind, String eventScript, int recruitMap, int exitMap,
                       int minPlayers, int maxPlayers, int minLevel, int maxLevel, int timeLimitMinutes,
                       List<String> sources, List<Stage> stages, Map<Tier, TierRules> tiers, AfterClear afterClear,
                       String flag, int entryNpc) {
            this(id, name, kind, eventScript, recruitMap, exitMap, minPlayers, maxPlayers, minLevel, maxLevel,
                    timeLimitMinutes, sources, stages, tiers, afterClear, flag, entryNpc, List.of());
        }

        /**
         * Why this party's levels do not fill the row's seats, or null when they do. A level of 0 ("not carried") fits
         * nothing. Each seat counts the members that fit it on its own, so a row with two seats keeps them disjoint.
         */
        public String compositionProblem(java.util.Collection<Integer> levels) {
            for (Seat seat : composition) {
                long fitting = levels.stream().filter(l -> l != null && seat.fits(l)).count();
                if (fitting < seat.min()) {
                    return "the party does not fill the seat \"" + seat.id() + "\": it needs " + seat.min()
                            + " member(s) of " + seat.describeLevels() + (seat.note() == null || seat.note().isBlank()
                            ? "" : " (" + seat.note() + ")") + ", it has " + fitting + " (levels "
                            + levels.stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                            + ")";
                }
            }
            return null;
        }

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
