package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Need;

/**
 * 1.6.4 (owner, 2026-11-03: "all playerbots should be able to switch between those modes, like training or
 * social"): a roster bot changes between {@link Mode#TRAINING}, {@link Mode#SOCIAL} and {@link Mode#TOWN} while it
 * is online, driven by its own needs and personality instead of a draw made once at login.
 *
 * <p><b>Trigger (the brain).</b> The brain already keeps six needs and a personality blend per bot. Each bot asks
 * {@link #maybeSwitch} from its own tick, at most once a minute. {@link #decide} is a pure function of those
 * needs, the bot's blend, how long it has been in this mode and a random roll; it returns the mode the bot would
 * rather be in, or null to stay. A lonely, tired or bored socializer drifts to a social or town mode; a bot with a
 * progression goal or a grinder's blend drifts back to training once its company need is met.
 *
 * <p><b>Executor (existing swap).</b> The swap is {@link BotTypeManager#convertBotType}, the call the staged scenes
 * already use: stop the old state machine, wrap the same {@code Character} in the new one, start it. Everything that
 * matters to the bot lives on the character or is keyed by character id ({@code LifeMemory}, bonds, memories, guild,
 * party, inventory), so it survives. What the old instance held (grind spot, the brain's pending friend or plan) is
 * dropped, which is why the swap only runs at a safe point ({@link #blocker}) and is the last act of the bot's tick.
 *
 * <p>Bounded: a minimum stay in a mode (20 min, jittered per bot), at most {@value #MAX_PER_HOUR} switches an hour,
 * a per-evaluation chance below 50%, and a gate that refuses while anything holds the bot. On by default;
 * {@code -Dslums.modeswitch=false} is the rollback.
 */
public final class ModeSwitch {

    /** The three modes a roster bot can be in, and the bot type that plays each. */
    public enum Mode {
        TRAINING(BotTypeManager.BotType.TRAINING_BOT),
        SOCIAL(BotTypeManager.BotType.SOCIAL_BOT),
        TOWN(BotTypeManager.BotType.TOWN_WANDERER_BOT);

        public final BotTypeManager.BotType type;

        Mode(BotTypeManager.BotType type) {
            this.type = type;
        }

        /** The mode a bot instance is playing now, or null for a type outside the three (a follower, a merchant...). */
        public static Mode of(BotSM bot) {
            if (bot instanceof TrainingBot) return TRAINING;
            if (bot instanceof SocialBot) return SOCIAL;
            if (bot instanceof TownWandererBot) return TOWN;
            return null;
        }
    }

    /** What {@link #decide} reads: the bot's needs and personality blend, nothing else. All 0..1 except times. */
    public record Inputs(Mode current, long inModeMs, long dwellMs, int switchesLastHour,
                         double social, double progress, double fatigue, double boredom,
                         double grinder, double socialite, double explorer, double socialPull) {
    }

    public static final long MIN_DWELL_MS = 20 * 60_000L;
    public static final int MAX_PER_HOUR = 3;
    static final long EVAL_PERIOD_MS = 60_000L;
    static final long HOUR_MS = 3_600_000L;
    /** A mode only changes when its score clears this. */
    static final double THRESHOLD = 0.45;
    /** Cap on the per-evaluation chance, so a bot with a high score still switches over a few minutes, not at once. */
    static final double MAX_CHANCE = 0.5;

    private ModeSwitch() {
    }

    /** On unless {@code -Dslums.modeswitch=false}; needs the brain on town and social bots too. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("slums.modeswitch")) && LifeRuntime.reachesAllBots();
    }

    // ── policy (pure) ──

    /**
     * The mode this bot would rather be in, or null to stay. {@code roll} picks whether a switch happens now and
     * {@code roll2} the choice between the two non-training modes; both are uniform in [0, 1).
     */
    public static Mode decide(Inputs in, double roll, double roll2) {
        if (in.inModeMs() < in.dwellMs() || in.switchesLastHour() >= MAX_PER_HOUR) return null;
        switch (in.current()) {
            case TRAINING -> {
                // Wants company or rest: lonely, tired of the grind, bored. Socializers feel it more, a
                // progression goal or a grinder's blend holds the bot at its work.
                double pull = 0.55 * in.social() + 0.25 * in.fatigue() + 0.20 * in.boredom();
                double weight = 0.5 + in.socialite() + 0.5 * Math.max(0, in.socialPull());
                double score = pull * weight - 0.6 * in.progress() - 0.3 * in.grinder();
                if (score > THRESHOLD && roll < Math.min(MAX_CHANCE, score)) {
                    return in.explorer() > in.socialite() && in.socialPull() < 0.5 ? Mode.TOWN : Mode.SOCIAL;
                }
            }
            case SOCIAL, TOWN -> {
                // Company need met and something to do: a progression goal, a bored grinder.
                double back = 0.6 * in.progress() + 0.3 * in.boredom() + 0.2 * (1 - in.social());
                double score = back * (0.5 + in.grinder()) - 0.5 * in.social() * (0.5 + in.socialite());
                if (score > THRESHOLD && roll < Math.min(MAX_CHANCE, score)) return Mode.TRAINING;
                if (roll2 < 0.3) {
                    if (in.current() == Mode.SOCIAL && in.boredom() > 0.7 && in.explorer() > in.socialite()) {
                        return Mode.TOWN;
                    }
                    if (in.current() == Mode.TOWN && in.social() > 0.6 && in.socialPull() > 0) return Mode.SOCIAL;
                }
            }
        }
        return null;
    }

    // ── executor ──

    /** The swap. Production: {@link BotTypeManager#convertBotType}; a test replaces it. */
    @FunctionalInterface
    public interface Converter {
        boolean convert(Character chr, BotTypeManager.BotType type);
    }

    private static volatile Converter converter = BotTypeManager::convertBotType;

    /** Test seam. Pair with {@link #reset()}. */
    static void useForTest(Converter testConverter) {
        converter = testConverter;
    }

    /** Test seam: forget every bot's mode history and counter, restore the real converter. */
    static void reset() {
        converter = BotTypeManager::convertBotType;
        states.clear();
        evaluations.reset();
        switches.reset();
        refusals.clear();
        pairs.clear();
    }

    private static final class State {
        Mode mode;
        long since;
        long nextEval;
        final long[] stamps = new long[MAX_PER_HOUR];
        int next;

        int lastHour(long now) {
            int n = 0;
            for (long t : stamps) {
                if (t != 0 && now - t < HOUR_MS) n++;
            }
            return n;
        }
    }

    private static final ConcurrentHashMap<Integer, State> states = new ConcurrentHashMap<>();
    private static final LongAdder evaluations = new LongAdder();
    private static final LongAdder switches = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> refusals = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> pairs = new ConcurrentHashMap<>();

    /**
     * From the bot's own tick, as its last act: maybe change this bot's mode. True when it did, in which case
     * {@code bot} is no longer the registered state machine and the caller must return without touching it.
     */
    public static boolean maybeSwitch(BotSM bot, LifeRuntime runtime, long now) {
        if (!enabled() || runtime == null || bot == null) return false;
        Character chr = bot.getChr();
        Mode current = Mode.of(bot);
        if (chr == null || current == null) return false;
        State s = states.computeIfAbsent(chr.getId(), id -> new State());
        if (s.mode != current) { // first sight, or another system (a staged scene) changed the mode
            s.mode = current;
            s.since = now;
            s.nextEval = now + EVAL_PERIOD_MS;
            return false;
        }
        if (now < s.nextEval) return false;
        s.nextEval = now + EVAL_PERIOD_MS;
        LifeRuntime.DecisionSnapshot snap = runtime.lastDecision();
        if (snap == null) return false;
        Inputs in = inputs(current, now - s.since, dwellFor(chr.getId()), s.lastHour(now), snap, runtime.memory());
        evaluations.increment();
        Random rng = SocialSim.random(chr.getId(), "mode-switch");
        Mode target = decide(in, rng.nextDouble(), rng.nextDouble());
        if (target == null) return false;
        String why = blocker(bot, runtime, now);
        if (why != null) {
            refusals.computeIfAbsent(why, k -> new LongAdder()).increment();
            return false;
        }
        if (!converter.convert(chr, target.type)) {
            refusals.computeIfAbsent("swap refused", k -> new LongAdder()).increment();
            return false;
        }
        s.stamps[s.next] = now;
        s.next = (s.next + 1) % s.stamps.length;
        s.mode = target;
        s.since = now;
        s.nextEval = now + EVAL_PERIOD_MS;
        switches.increment();
        pairs.computeIfAbsent(current + ">" + target, k -> new LongAdder()).increment();
        return true;
    }

    /**
     * Per-bot minimum stay: 20 min scaled 0.75..1.5 by a value fixed by the bot id (not a fresh draw per call), so a
     * population does not switch in step and a bot's own stay does not wobble between evaluations.
     */
    static long dwellFor(int botId) {
        long h = botId * 0x9E3779B97F4A7C15L;
        double unit = ((h ^ (h >>> 29)) & 0xFFFF) / 65536.0;
        return (long) (MIN_DWELL_MS * (0.75 + 0.75 * unit));
    }

    static Inputs inputs(Mode current, long inModeMs, long dwellMs, int lastHour,
                         LifeRuntime.DecisionSnapshot snap, LifeMemory memory) {
        Map<Need, Double> needs = snap.report().context().needs().values();
        Map<LifeProfile.Archetype, Double> blend = snap.report().profile().blend();
        return new Inputs(current, inModeMs, dwellMs, lastHour,
                needs.getOrDefault(Need.SOCIAL, 0.0), needs.getOrDefault(Need.PROGRESS, 0.0),
                needs.getOrDefault(Need.FATIGUE, 0.0), needs.getOrDefault(Need.BOREDOM, 0.0),
                blend.getOrDefault(LifeProfile.Archetype.GRINDER, 0.0),
                blend.getOrDefault(LifeProfile.Archetype.SOCIALITE, 0.0),
                blend.getOrDefault(LifeProfile.Archetype.EXPLORER, 0.0),
                memory == null ? 0 : memory.social().socialPull());
    }

    /**
     * Why this bot may not change mode now, or null when it may. The swap drops the old instance's session state,
     * so it needs a bot nothing holds: the brain's own free-phase check ({@link LifeRuntime#commitBlocker}), the
     * log-out gate (party with a real player, scene, ceremony, boss gathering, open trade), no open chat, a town
     * map, no errand, and the registered instance.
     */
    static String blocker(BotSM bot, LifeRuntime runtime, long now) {
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) return "not on a map";
        if (CharacterStorage.getBotById(chr.getId()) != bot) return "not the registered instance";
        if (bot.getState() == BotSM.BotState.TRADING) return "trading";
        String why = runtime.commitBlocker(now);
        if (why != null) return "brain: " + why;
        if (BotCommitments.inFlight(bot) || !BotPopulationDirector.mayLogOut(bot)) return "committed";
        if (chr.getParty() != null) return "in a party";
        if (BotConversation.hasAnyChat(chr.getId())) return "in a chat";
        if (!LifeTowns.isTownMap(chr.getMapId())) return "not in a town";
        if (bot instanceof TrainingBot training && training.onErrand()) return "on an errand";
        return null;
    }

    // ── observability ──

    /** Counters for the dashboard and the QA log: evaluations, switches, per-pair switches, refusals by reason. */
    public static Map<String, Long> stats() {
        Map<String, Long> out = new TreeMap<>();
        out.put("evaluations", evaluations.sum());
        out.put("switches", switches.sum());
        pairs.forEach((k, v) -> out.put("switch " + k, v.sum()));
        refusals.forEach((k, v) -> out.put("refused " + k, v.sum()));
        return out;
    }

    /** How many tracked bots are in each mode now. */
    public static Map<Mode, Integer> census() {
        Map<Mode, Integer> out = new EnumMap<>(Mode.class);
        for (Mode m : Mode.values()) out.put(m, 0);
        states.values().forEach(s -> {
            if (s.mode != null) out.merge(s.mode, 1, Integer::sum);
        });
        return out;
    }

    private static long lastSummaryAt;

    /** From the director's tick: one info line every ten minutes with the census and the counters. */
    public static void maybeLogSummary(long now, org.slf4j.Logger log) {
        if (!enabled() || now - lastSummaryAt < 600_000L) return;
        lastSummaryAt = now;
        log.info("Mode switch: census {} counters {}", census(), stats());
    }
}
