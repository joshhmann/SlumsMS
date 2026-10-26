package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.Fads;
import soloMapling.ArtificialPlayer.TrendIndex;
import soloMapling.qa.QaLog;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import static soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.Push;

/**
 * 1.6.0 S9, per-bot outcome learning: slow, bounded updates from what happens to a bot, on top of its seeded self
 * (S1). Two layers, both saved with the bot through {@link LifeMemoryStore} (never {@code ActivityState}):
 *
 * <ol>
 *   <li><b>The point on Bartle's chart.</b> Events become {@link SocialProfile.Push} nudges ({@link LifeMemory#push}):
 *   <table>
 *     <tr><th>What happened</th><th>Push</th></tr>
 *     <tr><td>activity finished well: GRIND / MOVE_MAP / HANG_OUT / HELP_FRIEND / SHOW_OFF / PARTY_QUEST / FISH</td>
 *         <td>GRIND / NEW_MAP / CHAT / HELP_GIVEN / FLEX / PARTY / FISHED</td></tr>
 *     <tr><td>a player partied, chatted kindly, buffed/healed/rescued, gifted or did a favour, famed</td>
 *         <td>PARTY / CHAT / HELPED / GIFTED / FAMED</td></tr>
 *     <tr><td>a player KS'd it or took its drops; defamed it or was rude</td><td>KSED / ROAST</td></tr>
 *     <tr><td>bot-bot (S2): it KS'd another bot on an observed map; another KS'd it; a beef thread; a bond turned
 *         rival</td><td>KS / KSED / ROAST / RIVALRY</td></tr>
 *     <tr><td>S2 bond became FRIEND; S3 PQ clear with a player; S3 scam (their own hooks, routed here)</td>
 *         <td>PARTY or CHAT / PQ_TOGETHER / SCAMMED</td></tr>
 *   </table>
 *   Each push moves the point {@code STEP x plasticity x strength}; the drift is capped at {@code plasticity x 1.2}
 *   (S1). Rate limits per bot per UTC day: {@link #PER_KIND_PER_DAY} of an event kind, {@link #ROUTINE_PER_DAY} of a
 *   routine one ({@link #ROUTINE}), {@link #TOTAL_PER_DAY} in all. Every day the drift shrinks by the bot's stored
 *   pull-back share (2-10%, set-in-their-ways bots snap back faster), so a push not renewed fades.</li>
 *   <li><b>The seven activity likes</b> ({@link LifeMemory#learnChannel}): each finished activity moves its channel by
 *   {@code rate x (outcome - the bot's running success rate)}, so a good PQ after a run of bad ones makes it like PQs
 *   more, a failed restock trip when trips usually work less, and an outcome it always gets moves nothing (no slow
 *   creep toward whatever it does most). Rate {@code 0.02 x (0.5 + plasticity)}, {@link #CHANNEL_PER_DAY} a day per
 *   channel, capped at +-{@link LifeMemory#MAX_CHANNEL_DELTA}, pulled back daily like the drift.</li>
 * </ol>
 *
 * <p><b>Hero to villain, and back.</b> After any move the memory checks its quadrant with hysteresis
 * ({@link #HYSTERESIS}, {@link LifeMemory#checkCrossing}): a point that settles in another quadrant records a
 * QUADRANT_CROSSED life event (from, to, when; saved), logged and told to {@link #onCrossing} listeners once. A point
 * that sits on the border never flaps. {@link CrossingEvent#redemption()} is a crossing back into the seed's quadrant.
 * S4 gossip ("he changed man") and S7 spice read these; nothing here speaks.
 *
 * <p><b>Population learning</b> ({@link TrendIndex}) and <b>bot-made fads</b> ({@link Fads}) are stepped by the same
 * sweep ({@link #maybeStep}, from the population director's tick, every {@link #SWEEP_MS}) and reach the brain
 * through one {@link LifePolicy#addBias} plug-in ({@link #bias}): at most {@link #MAX_BIAS}.
 *
 * <p><b>Hard limits stay outside.</b> Nothing here reads or writes ContentGuard, PG-13, SocialGate's tiers, the scam
 * caps or the never-AI rule; learned values only move the chart point, the seven likes, trend scores and fads.
 *
 * <p>Switches: on with the brain ({@code slums.botlife.enabled/execute}); {@code -Dslums.botlife.learning=false} turns
 * the new taps off (the S2/S3 pushes routed here keep their caps either way).
 */
public final class LifeLearning {
    private static final Logger log = LoggerFactory.getLogger(LifeLearning.class);

    /** Per bot per UTC day: pushes of one event kind, of one routine kind, of all kinds. */
    public static final int PER_KIND_PER_DAY = 6, ROUTINE_PER_DAY = 3, TOTAL_PER_DAY = 16;
    /** Everyday outcomes: they count, but less often a day. */
    public static final Set<Push> ROUTINE = EnumSet.of(Push.GRIND, Push.CHAT, Push.NEW_MAP, Push.HELPED, Push.FLEX,
            Push.FISHED);
    /** How far past an axis the point must settle before its quadrant counts as changed. */
    public static final double HYSTERESIS = .05;
    /** Like changes per channel per day, and the base rate (times 0.5 + plasticity). */
    public static final int CHANNEL_PER_DAY = 6;
    public static final double CHANNEL_RATE = .02;
    /** All of S9's pulls on one decision together (trends + fads), well under LifePolicy's plug-in cap. */
    public static final double MAX_BIAS = .15;
    static final long SWEEP_MS = 5 * 60_000L;

    /** What kind of crossing: away from the seed's quadrant, or back into it. */
    public enum Arc { TURNED, REDEEMED }

    /**
     * A QUADRANT_CROSSED life event as S4/S7 read it: the bot, the crossing, its seed's quadrant. {@link #arc()} says
     * whether it turned (left home) or was redeemed (came back).
     */
    public record CrossingEvent(int botId, LifeMemory.Crossing crossing, SocialProfile.Type seed) {
        public boolean redemption() { return crossing.to() == seed; }

        public Arc arc() { return redemption() ? Arc.REDEEMED : Arc.TURNED; }

        /** "SOCIALIZER->KILLER (turned)": a fact, not a line to say. */
        public String fact() {
            return crossing.from() + "->" + crossing.to() + " (" + arc().name().toLowerCase(Locale.ROOT) + ")";
        }
    }

    @FunctionalInterface
    public interface CrossingListener {
        void onCrossing(CrossingEvent event);
    }

    private static final List<CrossingListener> crossingListeners = new CopyOnWriteArrayList<>();
    /** The newest crossings world-wide (S4 picks stories here), at most 64. */
    private static final ConcurrentLinkedDeque<CrossingEvent> recent = new ConcurrentLinkedDeque<>();
    private static final LongAdder pushes = new LongAdder(), capped = new LongAdder(), likes = new LongAdder(),
            crossingsRun = new LongAdder();
    private static final AtomicLong nextSweep = new AtomicLong();
    private static final AtomicBoolean installed = new AtomicBoolean(), sweeping = new AtomicBoolean();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile Map<String, Object> lastSweep = Map.of();
    private static final LifePolicy.Bias BIAS = LifeLearning::bias;
    private static final LifeOutcomes.Listener OUTCOMES = LifeLearning::onOutcome;

    private LifeLearning() {
    }

    public static boolean enabled() {
        return LifeRuntime.enabled() && !"false".equalsIgnoreCase(System.getProperty("slums.botlife.learning", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    static long day(long now) {
        return Math.floorDiv(now, 86_400_000L);
    }

    /** Hooks itself in once: the outcome listener and the brain bias (the director's first sweep calls it). */
    public static void install() {
        if (!installed.compareAndSet(false, true)) return;
        LifeOutcomes.listen(OUTCOMES);
        LifePolicy.addBias(BIAS);
        try {
            soloMapling.server.EventMessageSystem.EventBus.getInstance().subscribe(
                    soloMapling.server.EventMessageSystem.EventType.PQ_CLEAR, PQ_TREND);
        } catch (RuntimeException | LinkageError unavailable) {
            log.debug("No event bus for PQ trends", unavailable);
        }
    }

    /** Test seam: unhooked, counters cleared, this clock (null: the system's). */
    public static void resetForTest(LongSupplier testClock) {
        LifeOutcomes.remove(OUTCOMES);
        LifePolicy.removeBias(BIAS);
        installed.set(false);
        crossingListeners.clear();
        recent.clear();
        pushes.reset(); capped.reset(); likes.reset(); crossingsRun.reset();
        nextSweep.set(0);
        lastSweep = Map.of();
        clock = testClock != null ? testClock : System::currentTimeMillis;
    }

    public static void onCrossing(CrossingListener listener) {
        crossingListeners.add(Objects.requireNonNull(listener));
    }

    public static void removeCrossingListener(CrossingListener listener) {
        crossingListeners.remove(listener);
    }

    // ── the core: one bounded push ──

    static int capOf(Push push) {
        return ROUTINE.contains(push) ? ROUTINE_PER_DAY : PER_KIND_PER_DAY;
    }

    /**
     * One push on a bot's chart through the day's budget, the daily pull-back first and the crossing check after. The
     * S2/S3 hooks route their pushes here (they used to push uncapped). Loads the memory if needed: call it off the
     * game thread. True when the point moved.
     */
    public static boolean push(int botId, Push push, long now) {
        return apply(botId, LifeMemoryStore.of(botId), push, now, "hook");
    }

    static boolean apply(int botId, LifeMemory memory, Push push, long now, String source) {
        if (memory == null || push == null) return false;
        memory.pullBackTo(day(now));
        if (!memory.takeBudget(push.name(), capOf(push), TOTAL_PER_DAY, now)) {
            capped.increment();
            if (QaLog.on(QaLog.Topic.HABITS)) {
                QaLog.any(QaLog.Topic.HABITS, "LEARN CAPPED bot=" + botId + " kind=" + push + " from=" + source);
            }
            return false;
        }
        boolean moved = memory.push(push);
        pushes.increment();
        if (QaLog.on(QaLog.Topic.HABITS)) {
            double[] d = memory.drift();
            QaLog.any(QaLog.Topic.HABITS, String.format(Locale.ROOT,
                    "LEARN PUSH bot=%d kind=%s from=%s moved=%s point=(%+.3f,%+.3f) drift=%.3f/%.3f", botId, push,
                    source, moved, memory.social().x(), memory.social().y(), Math.hypot(d[0], d[1]),
                    SocialProfile.RADIUS_PER_PLASTICITY * memory.plasticity()));
        }
        afterMove(botId, memory, now);
        return moved;
    }

    /** The crossing check after any move (a push, a day's pull-back, a life event caught by the sweep). */
    static void afterMove(int botId, LifeMemory memory, long now) {
        LifeMemory.Crossing crossing = memory.checkCrossing(HYSTERESIS, now);
        if (crossing == null) return;
        CrossingEvent event = new CrossingEvent(botId, crossing, memory.socialSeed().leading());
        crossingsRun.increment();
        recent.addLast(event);
        while (recent.size() > 64) recent.pollFirst();
        log.info("[BotLife] QUADRANT_CROSSED bot={} {} point=({},{}) crossings={}", botId, event.fact(),
                String.format(Locale.ROOT, "%+.2f", memory.social().x()),
                String.format(Locale.ROOT, "%+.2f", memory.social().y()), memory.crossings());
        if (QaLog.on(QaLog.Topic.HABITS)) {
            QaLog.any(QaLog.Topic.HABITS, "LEARN CROSSED bot=" + botId + " " + event.fact() + " crossings="
                    + memory.crossings());
        }
        for (CrossingListener l : crossingListeners) {
            try {
                l.onCrossing(event);
            } catch (RuntimeException ignored) {
                // a listener never breaks learning
            }
        }
    }

    // ── taps ──

    /** The push a finished activity earns when it went well (null: none; failures move likes only). */
    static Push outcomePush(Activity activity) {
        return switch (activity) {
            case GRIND -> Push.GRIND;
            case MOVE_MAP -> Push.NEW_MAP;
            case HANG_OUT -> Push.CHAT;
            case HELP_FRIEND -> Push.HELP_GIVEN;
            case SHOW_OFF -> Push.FLEX;
            case PARTY_QUEST -> Push.PARTY;
            case FISH -> Push.FISHED;
            default -> null; // restock, sell, market, break, log out, cc: errands, not who the bot is
        };
    }

    /** The like channels an activity's outcome teaches, with weights (as {@link LifeProfile#bias} reads them). */
    static Map<LifeProfile.Archetype, Double> channelsOf(Activity activity) {
        return switch (activity) {
            case GRIND -> Map.of(LifeProfile.Archetype.GRINDER, 1.0);
            case MOVE_MAP -> Map.of(LifeProfile.Archetype.EXPLORER, 1.0);
            case RESTOCK -> Map.of(LifeProfile.Archetype.GRINDER, .5);
            case SELL, FREE_MARKET -> Map.of(LifeProfile.Archetype.MERCHANT, 1.0);
            case HANG_OUT -> Map.of(LifeProfile.Archetype.SOCIALITE, 1.0);
            case HELP_FRIEND -> Map.of(LifeProfile.Archetype.HELPER, 1.0);
            case SHOW_OFF -> Map.of(LifeProfile.Archetype.SHOW_OFF, 1.0);
            case PARTY_QUEST -> Map.of(LifeProfile.Archetype.GRINDER, .5, LifeProfile.Archetype.SOCIALITE, .5);
            case BREAK, FISH -> Map.of(LifeProfile.Archetype.SLACKER, 1.0);
            case LOG_OUT, CHANGE_CHANNEL -> Map.of();
        };
    }

    /** {@link LifeOutcomes} listener: on the bot's update thread, memory only (a bot without a memory is skipped). */
    static void onOutcome(LifeOutcomes.Outcome outcome) {
        if (!enabled()) return;
        LifeMemory memory = LifeMemoryStore.peek(outcome.botId());
        if (memory == null) return;
        observe(outcome.botId(), memory, outcome.activity(), outcome.success(), outcome.at());
    }

    /** One finished activity for this memory: the likes, and on success the activity's push. */
    static void observe(int botId, LifeMemory memory, Activity activity, boolean success, long now) {
        memory.pullBackTo(day(now));
        double rate = CHANNEL_RATE * (.5 + memory.plasticity());
        channelsOf(activity).forEach((channel, weight) -> {
            if (!memory.takeBudget("ch:" + channel.name(), CHANNEL_PER_DAY, Integer.MAX_VALUE, now)) return;
            double moved = memory.learnChannel(channel, success, rate, weight);
            likes.increment();
            if (QaLog.on(QaLog.Topic.HABITS) && moved != 0) {
                QaLog.any(QaLog.Topic.HABITS, String.format(Locale.ROOT, "LEARN LIKE bot=%d %s %s %s %+.4f -> %+.3f",
                        botId, activity, success ? "ok" : "failed", channel, moved,
                        memory.delta().getOrDefault(channel, 0.0)));
            }
        });
        Push push = success ? outcomePush(activity) : null;
        if (push != null) apply(botId, memory, push, now, "outcome");
    }

    /** The push an affinity event from a real player earns (null: none here; SCAM and PQ come from S3's episodes). */
    static Push affinityPush(AffinityEvent event) {
        return switch (event) {
            case PARTY -> Push.PARTY;
            case KIND_CHAT -> Push.CHAT;
            case BUFF, HEAL, RESCUE -> Push.HELPED;
            case GIFT, FAVOR -> Push.GIFTED;
            case FAME -> Push.FAMED;
            case KS, DROP_STEAL -> Push.KSED;
            case DEFAME, RUDE_CHAT -> Push.ROAST;
            default -> null; // TRADE, ROMANCE, APOLOGY, hearsay; SCAM (BotEpisodes pushes SCAMMED)
        };
    }

    /** {@code BotAffinity.record}: a player's event counted for a bot (its cooldown already applied). Memory only. */
    public static void onAffinity(int botId, AffinityEvent event) {
        if (!enabled() || event == null) return;
        Push push = affinityPush(event);
        LifeMemory memory = push == null ? null : LifeMemoryStore.peek(botId);
        if (memory != null) apply(botId, memory, push, now(), "affinity " + event.key());
    }

    /**
     * {@code BotBonds.record}: bot {@code botId}'s feeling about {@code otherId} moved by {@code event}. A KS: the
     * victim ({@code botId}) is KSED, the KSer ({@code otherId}) did a KS. A beef thread: ROAST. A bond turning rival or
     * nemesis: RIVALRY. (A bond turning FRIEND pushes through BotBonds' own hook.) Memory only.
     */
    public static void onBond(int botId, int otherId, BondEvent event, AffinityState before, AffinityState after) {
        if (!enabled() || event == null) return;
        long now = now();
        if (event == BondEvent.KS) {
            apply(botId, LifeMemoryStore.peek(botId), Push.KSED, now, "bond ks");
            apply(otherId, LifeMemoryStore.peek(otherId), Push.KS, now, "bond ks");
        } else if (event == BondEvent.SPAT) {
            apply(botId, LifeMemoryStore.peek(botId), Push.ROAST, now, "bond spat");
        }
        if (after != null && after.hostile() && (before == null || !before.hostile())) {
            apply(botId, LifeMemoryStore.peek(botId), Push.RIVALRY, now, "bond rival");
        }
    }

    /** {@code !qa lr-1}: one staged KS by this bot at {@code at}, through the same budget, pull-back and crossing path. */
    public static boolean stageKs(int botId, long at) {
        return apply(botId, LifeMemoryStore.of(botId), Push.KS, at, "qa ks");
    }

    /** PQ clears with a real player feed the PQ trend (S3's bus event: the character is a real player when there is one). */
    private static final soloMapling.server.EventMessageSystem.EventSubscriber PQ_TREND =
            new soloMapling.server.EventMessageSystem.EventSubscriber() {
                public void onEvent(soloMapling.server.EventMessageSystem.GameEvent event) {
                    client.Character c = event.getMapleCharacter();
                    if (c != null && soloMapling.ArtificialPlayer.BotHelpers.isVisibleRealPlayer(c)
                            && c.gmLevel() <= 0 && !(c.getClient() instanceof client.SyntheticClient)) {
                        TrendIndex.observePq(c.getId(), c.getLevel(), event.getMessage(), now());
                    }
                }

                public boolean matchesFilter(soloMapling.server.EventMessageSystem.GameEvent event) {
                    return true;
                }
            };

    // ── the brain's bias: trends and fads ──

    /** The S9 plug-in pull for one option: trends (MOVE_MAP, PARTY_QUEST) and a SPOT fad (HANG_OUT), 0..MAX_BIAS. */
    static double bias(Activity activity, LifePolicy.Context context, LifePolicy.Situation situation) {
        if (!enabled() || situation == null || situation.botId() == 0) return 0;
        int botId = situation.botId();
        double pull = switch (activity) {
            case MOVE_MAP, PARTY_QUEST -> {
                client.Character chr = chr(botId);
                yield chr == null ? 0 : TrendIndex.bias(activity, chr.getLevel(), chr.getMapId(), now());
            }
            case HANG_OUT -> {
                client.Character chr = chr(botId);
                yield chr != null && chr.getMap() != null
                        && Fads.spotFor(botId, chr.getMap().getChannelId()) > 0 ? Fads.HANGOUT_PULL : 0;
            }
            default -> 0;
        };
        return Math.max(0, Math.min(MAX_BIAS, pull));
    }

    private static client.Character chr(int botId) {
        var bot = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(botId);
        return bot == null ? null : bot.getChr();
    }

    // ── the sweep ──

    /**
     * From the population director's tick: at most every {@link #SWEEP_MS}, off-thread. Every memory gets its daily
     * pull-back and a crossing check (catching moves from life events too), the trend index looks at real players, and
     * fads step. Cheap otherwise.
     */
    public static void maybeStep(long now) {
        if (!enabled()) return;
        long due = nextSweep.get();
        if (now < due || !nextSweep.compareAndSet(due, now + SWEEP_MS)) return;
        install();
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            if (!sweeping.compareAndSet(false, true)) return;
            try {
                sweep(now);
                TrendIndex.maybeSample(now,
                        mapId -> soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(mapId));
                Fads.step(now);
            } catch (RuntimeException | LinkageError failure) {
                log.warn("[BotLife] learning sweep failed: {}", failure.toString());
            } finally {
                sweeping.set(false);
            }
        });
    }

    /** Pull-back and crossing check for every memory; the health numbers for bot-life.json. */
    static Map<String, Object> sweep(long now) {
        long today = day(now);
        int bots = 0, learned = 0, overCap = 0, flipped = 0;
        double driftSum = 0, maxShare = 0, channelMax = 0;
        for (var entry : LifeMemoryStore.view().entrySet()) {
            LifeMemory memory = entry.getValue();
            bots++;
            if (memory.pristine()) continue;
            memory.pullBackTo(today);
            afterMove(entry.getKey(), memory, now);
            double[] d = memory.drift();
            double drift = Math.hypot(d[0], d[1]);
            double radius = SocialProfile.RADIUS_PER_PLASTICITY * memory.plasticity();
            if (drift > 0 || !memory.delta().isEmpty()) learned++;
            driftSum += drift;
            if (radius > 0) maxShare = Math.max(maxShare, drift / radius);
            if (drift > radius + 1e-9) overCap++;
            for (double v : memory.delta().values()) {
                channelMax = Math.max(channelMax, Math.abs(v));
                if (Math.abs(v) > LifeMemory.MAX_CHANNEL_DELTA + 1e-9) overCap++;
            }
            if (memory.social().leading() != memory.socialSeed().leading()) flipped++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now);
        out.put("bots", bots);
        out.put("learned", learned);
        out.put("meanDrift", learned == 0 ? 0 : round(driftSum / learned));
        out.put("maxDriftOfCap", round(maxShare));
        out.put("maxChannelDelta", round(channelMax));
        out.put("overCap", overCap);
        out.put("leadingFlippedFromSeed", flipped);
        lastSweep = out;
        return out;
    }

    /** Crossings in the last 24 hours (from the newest 64 kept). */
    static long crossingsLastDay(long now) {
        return recent.stream().filter(e -> now - e.crossing().at() <= 86_400_000L).count();
    }

    /** For bot-life.json: learning health, fads and trends. */
    public static Map<String, Object> report(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled());
        out.put("pushes", pushes.sum());
        out.put("capped", capped.sum());
        out.put("likeUpdates", likes.sum());
        out.put("crossingsThisRun", crossingsRun.sum());
        out.put("crossingsLastDay", crossingsLastDay(now));
        out.put("sweep", lastSweep);
        out.put("fads", Fads.report());
        out.put("trends", TrendIndex.report(now));
        return out;
    }

    // ── reads for S4 / S7 and !botwhy ──

    /** The newest crossings world-wide since {@code sinceMs} (oldest first): stories for gossip. */
    public static List<CrossingEvent> recentCrossings(long sinceMs) {
        return recent.stream().filter(e -> e.crossing().at() >= sinceMs).toList();
    }

    /** This bot's last QUADRANT_CROSSED (saved with the bot), or null. Memory only: null when not loaded. */
    public static CrossingEvent lastCrossing(int botId) {
        LifeMemory memory = LifeMemoryStore.peek(botId);
        if (memory == null) return null;
        List<LifeMemory.Crossing> crossed = memory.recentCrossings();
        return crossed.isEmpty() ? null : new CrossingEvent(botId, crossed.getLast(), memory.socialSeed().leading());
    }

    /** The lines {@code !botwhy} adds: quadrant and crossings, today's budget, fads, the band's trends. */
    public static List<String> describe(int botId, int level, long now) {
        List<String> lines = new ArrayList<>();
        LifeMemory memory = LifeMemoryStore.peek(botId);
        if (memory != null) {
            List<LifeMemory.Crossing> crossed = memory.recentCrossings();
            StringBuilder c = new StringBuilder();
            for (LifeMemory.Crossing x : crossed) {
                if (!c.isEmpty()) c.append(", ");
                c.append(x.from()).append("->").append(x.to()).append(' ')
                        .append(Math.max(0, now - x.at()) / 3_600_000L).append(" h ago");
            }
            lines.add(String.format(Locale.ROOT, "Learning: quadrant %s (seed %s), %d crossing%s%s; pull-back %.1f%%/day;"
                            + " today %s", memory.quadrant(), memory.socialSeed().leading(), memory.crossings(),
                    memory.crossings() == 1 ? "" : "s", c.isEmpty() ? "" : " (" + c + ")", 100 * memory.pullBack(),
                    memory.budgetToday(now).isEmpty() ? "nothing yet" : new TreeMap<>(memory.budgetToday(now))));
        }
        List<Fads.View> fads = Fads.follows(botId);
        if (!fads.isEmpty()) {
            lines.add("Fads: " + fads.stream().map(f -> f.kind() + " '" + f.label() + "' (" + f.followers()
                    + " followers)").toList());
        }
        if (level > 0 && TrendIndex.enabled()) {
            var maps = TrendIndex.topMaps(level, now, 3);
            var pq = TrendIndex.topPq(level, now);
            if (!maps.isEmpty() || pq != null) {
                lines.add("Trends for lv " + level + " now: maps " + maps.stream().map(e -> e.getKey() + " "
                        + String.format(Locale.ROOT, "%.2f", e.getValue())).toList()
                        + (pq == null ? "" : String.format(Locale.ROOT, ", pq %s %.2f", pq.getKey(), pq.getValue())));
            }
        }
        return lines;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
