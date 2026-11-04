package soloMapling.dashboard;

import soloMapling.ArtificialPlayer.BotConvo.BotTalk;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialBudget;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMetrics;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ticket 181 stage 6a: the {@code social} block of {@code live.json}. Read-only static accessors of the social layer
 * and nothing else: no database, no game lock, no walk over the bots. The one thing it remembers is the previous
 * {@code social-tick} phase total, to turn the running totals into a rate over the export interval (like
 * {@link LiveSnapshot} does for the other phases).
 *
 * <pre>
 *   social.modes        {enabled, census: {training, social, town}, stats: ModeSwitch counters}
 *   social.budget       {enabled, counters: SocialBudget counters (allowed, denied per reason, recorded, ...)}
 *   social.talk         {on, ticks, avgTickMs, avgCpuMs, talks, greets, actsOk, actsFailed}  BotTalk, since boot
 *   social.socialTick   {calls, totalMs, msPerS, callsPerS}  the social-tick cost phase, rate over the interval
 *   social.bonds        {bonds, holders, text}  BotBonds totals
 *   social.minds        {memoriesLoaded}  bots whose life memory (and so social mind) is in RAM
 * </pre>
 */
final class SocialExport {

    static final String SOCIAL_TICK = "social-tick";

    private long prevAt;
    private long prevNanos = -1;
    private long prevCalls;

    /** The block at {@code now}; {@code phases} are the running phase totals the export already read. */
    Map<String, Object> build(long now, List<BotPerformanceMonitor.PhaseTotal> phases) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modes", modes());
        out.put("budget", budget());
        out.put("talk", talk());
        out.put("socialTick", socialTick(now, phases));
        out.put("bonds", SocialMetrics.bondTotals());
        Map<String, Object> minds = new LinkedHashMap<>();
        minds.put("memoriesLoaded", LifeMemoryStore.size());
        out.put("minds", minds);
        return out;
    }

    private static Map<String, Object> modes() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", ModeSwitch.enabled());
        Map<String, Object> census = new LinkedHashMap<>();
        ModeSwitch.census().forEach((mode, n) -> census.put(mode.name().toLowerCase(java.util.Locale.ROOT), n));
        m.put("census", census);
        m.put("stats", new LinkedHashMap<>(ModeSwitch.stats()));
        return m;
    }

    private static Map<String, Object> budget() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", SocialBudget.enabled());
        m.put("counters", new LinkedHashMap<>(SocialBudget.counters()));
        return m;
    }

    private static Map<String, Object> talk() {
        Map<String, Object> s = BotTalk.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        long ticks = num(s.get("ticks"));
        m.put("on", s.get("on"));
        m.put("ticks", ticks);
        m.put("avgTickMs", ticks == 0 ? null : round3(num(s.get("cpuNanos")) / 1e6 / ticks));
        m.put("avgCpuMs", ticks == 0 ? null : round3(num(s.get("threadCpuNanos")) / 1e6 / ticks));
        for (String k : List.of("talks", "greets", "actsOk", "actsFailed")) {
            m.put(k, num(s.get(k)));
        }
        return m;
    }

    private Map<String, Object> socialTick(long now, List<BotPerformanceMonitor.PhaseTotal> phases) {
        BotPerformanceMonitor.PhaseTotal total = null;
        for (BotPerformanceMonitor.PhaseTotal p : phases) {
            if (SOCIAL_TICK.equals(p.name())) {
                total = p;
            }
        }
        if (total == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("calls", total.calls());
        m.put("totalMs", round3(total.nanos() / 1e6));
        if (prevNanos < 0 || now <= prevAt || total.nanos() < prevNanos) {
            m.put("msPerS", null);       // the first reading (or a reset): no interval yet
            m.put("callsPerS", null);
        } else {
            double secs = (now - prevAt) / 1000.0;
            m.put("msPerS", round3((total.nanos() - prevNanos) / 1e6 / secs));
            m.put("callsPerS", round3((total.calls() - prevCalls) / secs));
        }
        prevAt = now;
        prevNanos = total.nanos();
        prevCalls = total.calls();
        return m;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
