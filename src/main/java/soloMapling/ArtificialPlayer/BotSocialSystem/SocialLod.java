package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Stage 6b of ticket 271: the social <b>level of detail</b>. Every roster bot is in one of three tiers, and the
 * social layer spends work on it accordingly.
 *
 * <ul>
 *   <li>{@link Tier#ACTIVE}: a real player can see the bot's map instance ({@link GCMovement#isMapObserved}).
 *       Every current behaviour stays exactly as it is; the tier only says "this bot may be watched, keep it
 *       detailed". The hold keeps a bot ACTIVE for {@value #ACTIVE_HOLD_MS} ms / 30 s after the last observer left,
 *       so a player walking past does not make bots flicker in and out of detail (the same idea as the observer
 *       tracker's own 4 s dwell, at the scale of the social layer).</li>
 *   <li>{@link Tier#BACKGROUND}: online, nobody watching. The bot gets its slow social maintenance (its
 *       companionship and belonging needs, a stand-in for the contact it would have had) at most once every
 *       {@value #MAINTENANCE_PERIOD_MS} ms / 5 min, never per tick (see {@link #maybeStep}).</li>
 *   <li>{@link Tier#DORMANT}: not online. Nothing runs, and nothing is stored per tick; when the bot comes back,
 *       {@link #catchUpOnLogin} replays a bounded amount of what it missed, once per login.</li>
 * </ul>
 *
 * <p><b>Shape.</b> The tier is a pure function of (observed, online, lastActiveAt, now) - {@link #tier} - so it is
 * testable without a world. The live accessor is {@link #tierOf}; the sweep that refreshes every online bot's tier
 * and does the BACKGROUND maintenance is {@link #maybeStep}, called from the population director's tick, never per
 * bot tick. The whole pass is timed into the existing social-tick phase
 * ({@link BotPerformanceMonitor#recordSocialTick}), so the cost of this stage shows up where the rest of the social
 * layer's cost already shows up.
 *
 * <p><b>Catch-up bounds.</b> The offline span is capped at {@value #CATCH_UP_CAP_MS} ms (a day), anything under a
 * minute or with no known logout time catches up nothing, the applied relief never takes a need below zero (the
 * mind clamps), and the same login cannot be caught up twice.
 *
 * <p><b>Switch.</b> On by default; {@code -Dslums.sociallod=false} is the rollback and makes every bot ACTIVE, so
 * the sweep does no work and existing behaviour is what ran before this stage.
 */
public final class SocialLod {

    /** The three levels of detail. */
    public enum Tier {
        ACTIVE, BACKGROUND, DORMANT
    }

    /**
     * What a bot gains from a stretch of time in the background or away: how far its companionship and belonging
     * needs fall. Both are 0..1 need values, like {@link SocialMind#satisfy}.
     */
    public record Relief(double companionship, double belonging) {
    }

    /** How long a bot stays ACTIVE after the last observer left (hysteresis). */
    public static final long ACTIVE_HOLD_MS = 30_000L;
    /** The soonest a BACKGROUND bot gets its slow social maintenance again. */
    public static final long MAINTENANCE_PERIOD_MS = 300_000L;
    /** The longest stretch of time one catch-up or maintenance may cover. */
    public static final long CATCH_UP_CAP_MS = 24 * 3_600_000L;
    /** Below this, a login catches up nothing: too short to notice, and it keeps the boot cheap. */
    public static final long CATCH_UP_FLOOR_MS = 60_000L;
    /** Needs fall this far per hour of background or offline time (a stand-in for the contact there would have been). */
    static final double COMPANIONSHIP_PER_HOUR = 0.03;
    static final double BELONGING_PER_HOUR = 0.012;
    static final long SUMMARY_PERIOD_MS = 600_000L;

    private static final long HOUR_MS = 3_600_000L;
    private static final Logger log = LoggerFactory.getLogger(SocialLod.class);

    private static final Map<Integer, State> states = new ConcurrentHashMap<>();
    private static final LongAdder tierChanges = new LongAdder();
    private static final LongAdder maintained = new LongAdder();
    private static final LongAdder maintenanceSkipped = new LongAdder();
    private static final LongAdder catchUps = new LongAdder();
    private static final LongAdder catchUpSkipped = new LongAdder();
    private static final LongAdder reliefMs = new LongAdder();
    private static long lastSummaryAt;

    private SocialLod() {
    }

    /** On unless {@code -Dslums.sociallod=false}, in which case every bot is ACTIVE and this stage does nothing. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("slums.sociallod"));
    }

    // ── policy (pure) ──

    /**
     * The tier for one bot. Offline wins over everything (a bot that is not in the world is DORMANT wherever it
     * logged out); a watched map is ACTIVE; otherwise the bot stays ACTIVE until {@link #ACTIVE_HOLD_MS} has passed
     * since the last moment it was watched.
     *
     * @param lastActiveAt when this bot was last watched (0 when never this run)
     */
    public static Tier tier(boolean observed, boolean online, long lastActiveAt, long now) {
        if (!online) return Tier.DORMANT;
        if (observed) return Tier.ACTIVE;
        return lastActiveAt > 0 && now - lastActiveAt < ACTIVE_HOLD_MS ? Tier.ACTIVE : Tier.BACKGROUND;
    }

    /** The bounded relief for {@code elapsedMs} of background or offline time: proportional to the time, capped. */
    public static Relief relief(long elapsedMs) {
        long ms = Math.min(Math.max(0, elapsedMs), CATCH_UP_CAP_MS);
        double hours = ms / (double) HOUR_MS;
        return new Relief(COMPANIONSHIP_PER_HOUR * hours, BELONGING_PER_HOUR * hours);
    }

    // ── the single accessor ──

    /** This bot's tier now, refreshing what the sweep needs to know. Never per-tick work: a map lookup and a field. */
    public static Tier tierOf(BotSM bot) {
        if (!enabled()) return Tier.ACTIVE;
        Character chr = bot == null ? null : bot.getChr();
        if (chr == null) return Tier.DORMANT;
        return refresh(chr, SocialSim.now());
    }

    private static Tier refresh(Character chr, long now) {
        int charId = chr.getId();
        State s = state(charId);
        boolean observed = GCMovement.isMapObserved(chr);
        if (observed) {
            s.lastActiveAt = now;
        }
        Tier tier = tier(observed, true, s.lastActiveAt, now);
        if (s.tier != tier) {
            s.tier = tier;
            tierChanges.increment();
            if (tier == Tier.BACKGROUND) {
                // Its maintenance clock starts when the detail drops, so the first pass waits the full period.
                s.maintainedAt = now;
                s.nextMaintenanceAt = now + MAINTENANCE_PERIOD_MS;
            }
        }
        return tier;
    }

    // ── the director's pass ──

    /**
     * From the population director's tick: refresh every online bot's tier, give the BACKGROUND ones their slow
     * maintenance, and count the ones that left as DORMANT. Timed into the social-tick phase.
     */
    public static void maybeStep(long now, Collection<BotSM> online) {
        if (!enabled()) return;
        long started = System.nanoTime();
        Set<Integer> seen = new HashSet<>();
        if (online != null) {
            for (BotSM bot : online) {
                Character chr = bot == null ? null : bot.getChr();
                if (chr == null) continue;
                seen.add(chr.getId());
                if (refresh(chr, now) == Tier.BACKGROUND) {
                    maintain(chr.getId(), now);
                }
            }
        }
        for (Map.Entry<Integer, State> e : states.entrySet()) {
            if (!seen.contains(e.getKey())) e.getValue().tier = Tier.DORMANT;
        }
        BotPerformanceMonitor.recordSocialTick(System.nanoTime() - started);
        maybeLogSummary(now);
    }

    private static void maintain(int charId, long now) {
        State s = states.get(charId);
        if (s == null || now < s.nextMaintenanceAt) return;
        long since = s.maintainedAt == 0 ? now : s.maintainedAt;
        s.maintainedAt = now;
        s.nextMaintenanceAt = now + MAINTENANCE_PERIOD_MS;
        long elapsed = now - since;
        if (elapsed <= 0) return;
        LifeMemory memory = LifeMemoryStore.peek(charId);
        if (memory == null) {
            // Nothing in use for this bot this run: do not build a mind (and a row) just to nudge it.
            maintenanceSkipped.increment();
            return;
        }
        apply(memory, relief(Math.min(elapsed, CATCH_UP_CAP_MS)), now);
        maintained.increment();
        reliefMs.add(Math.min(elapsed, CATCH_UP_CAP_MS));
    }

    // ── the login catch-up ──

    /**
     * A roster member has just logged in: replay a bounded amount of what it missed while it was away, once for this
     * login. {@code since} is when its last session ended (0 when unknown, or when it never logged out).
     */
    public static void catchUpOnLogin(int charId, long loginAt, long since, long now) {
        if (!enabled() || charId <= 0) return;
        State s = state(charId);
        if (s.catchUpAt == loginAt) {
            catchUpSkipped.increment();
            return;
        }
        s.catchUpAt = loginAt;
        long away = now - since;
        if (since <= 0 || away < CATCH_UP_FLOOR_MS) {
            catchUpSkipped.increment();
            return;
        }
        long covered = Math.min(away, CATCH_UP_CAP_MS);
        apply(LifeMemoryStore.of(charId), relief(covered), now);
        catchUps.increment();
        reliefMs.add(covered);
    }

    private static void apply(LifeMemory memory, Relief relief, long now) {
        memory.mind(mind -> {
            mind.satisfy(SocialMind.Need.COMPANIONSHIP, relief.companionship(), now);
            mind.satisfy(SocialMind.Need.BELONGING, relief.belonging(), now);
            return Boolean.TRUE;
        });
    }

    // ── census, counters, logging ──

    /** Per-tier counters for the dashboard and the QA log, next to the mode switch's own. */
    public static Map<String, Long> stats() {
        Map<String, Long> out = new TreeMap<>();
        out.put("tier changes", tierChanges.sum());
        out.put("maintained", maintained.sum());
        out.put("maintenance skipped", maintenanceSkipped.sum());
        out.put("catch-ups", catchUps.sum());
        out.put("catch-up skipped", catchUpSkipped.sum());
        out.put("relief minutes", reliefMs.sum() / 60_000L);
        census().forEach((tier, n) -> out.put("census " + tier.name().toLowerCase(java.util.Locale.ROOT), (long) n));
        return out;
    }

    /** How many tracked bots are in each tier now (bots never seen this run are not in it). */
    public static Map<Tier, Integer> census() {
        Map<Tier, Integer> out = new EnumMap<>(Tier.class);
        for (Tier tier : Tier.values()) out.put(tier, 0);
        states.values().forEach(s -> {
            if (s.tier != null) out.merge(s.tier, 1, Integer::sum);
        });
        return out;
    }

    /** From the director's tick: one info line every ten minutes with the census and the counters. */
    static void maybeLogSummary(long now) {
        if (now - lastSummaryAt < SUMMARY_PERIOD_MS) return;
        lastSummaryAt = now;
        log.info("Social LOD: census {} counters {}", census(), stats());
    }

    /** Test seam: forget every bot's tier and the counters. */
    public static void reset() {
        states.clear();
        tierChanges.reset();
        maintained.reset();
        maintenanceSkipped.reset();
        catchUps.reset();
        catchUpSkipped.reset();
        reliefMs.reset();
        lastSummaryAt = 0;
    }

    private static State state(int charId) {
        return states.computeIfAbsent(charId, id -> new State());
    }

    private static final class State {
        Tier tier = Tier.DORMANT;
        long lastActiveAt;
        long maintainedAt;
        long nextMaintenanceAt;
        long catchUpAt;
    }
}
