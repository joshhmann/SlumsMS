package soloMapling.Economy.exchange.volatility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1.6.1 volatility (owner 2026-10-28: "base it off guild wars"): the market news from the bot guild wars
 * ({@code GuildWars}). A war declared makes both guilds stock up on Power Elixirs (an ELIXIR demand shock, then steady
 * demand while it lasts); when it ends the winners get greedy (buy LEAF) and the losers panic-sell (LEAF, ELIXIR).
 * <ul>
 *   <li>Trader bots whose guild is in the news act on it directly ({@link #role}, {@link TraderBrain#warActions}),
 *   with their own mesos and items like every other order.</li>
 *   <li>Every other trader reads it like news ({@link #pressure}): ELIXIR up while wars are fresh and running, down
 *   when one ends.</li>
 *   <li>Cody announces it in game (with {@code -Dslums.market.events}), one market notice per 10 minutes at most; the
 *   rumour mill repeats it with the guild names ({@link #facts}).</li>
 * </ul>
 * Owner 2026-10-28 ("guild A is preparing for a zakum ... and boom elixirs go up"): the same for a bot guild getting
 * ready for a boss run or a Guild PQ ({@code GuildBossRuns}, the only group content bots really plan): the call is
 * a "prep" news item, an ELIXIR demand shock sized by the content (Horntail 1.5, Zakum 1, Papulatus 0.8, Guild PQ 0.4)
 * and the crew (out of 6); the crew's traders buy elixirs. After the run: a clear floods the scroll market (CHAOS, WS
 * down; crew traders sell scrolls they hold), a wipe brings more elixir demand for the retry (the crew buys again).
 * Real players' parties are not read (no cheap, reliable signal).
 * <p>
 * In memory: a restart forgets running wars until the next declaration (the wars themselves are GuildWars'). Never
 * throws into the game.
 */
public final class MarketNews {

    private static final Logger log = LoggerFactory.getLogger(MarketNews.class);

    /** How long a declaration is a shock, and an ending news. */
    static final long FRESH_MS = 2 * 3_600_000L;
    static final long END_NEWS_MS = 6 * 3_600_000L;
    static final long NOTICE_GAP_MS = 10 * 60_000L;

    public record War(long id, int a, String aName, int b, String bName, long startedAt, long endsAt,
                      boolean announced) {
        boolean involves(int guild) {
            return guild > 0 && (guild == a || guild == b);
        }
    }

    public record End(long id, int winner, String winnerName, int loser, String loserName, boolean draw, long at) {
    }

    /** A guild getting ready for big content: {@code weight} sizes the shock. */
    public record Prep(int guild, String guildName, String content, double weight, long at, boolean announced) {
    }

    /** How a prepared run went. */
    public record Run(int guild, String guildName, String content, double weight, boolean cleared, long at) {
    }

    public enum Role {NONE, AT_WAR_FRESH, AT_WAR, WINNER, LOSER, PREP, CLEARED, WIPED}

    /** The shock size of a content key ({@code GuildRules.Boss.key}): bigger for the big bosses. */
    public static double contentWeight(String key) {
        return switch (key == null ? "" : key) {
            case "horntail" -> 1.5;
            case "zakum" -> 1.0;
            case "pap" -> 0.8;
            default -> 0.4;
        };
    }

    private static volatile MarketNews instance;

    private final Map<Long, War> wars = new LinkedHashMap<>();
    private final Deque<End> ends = new ArrayDeque<>();
    private final Map<Integer, Prep> preps = new LinkedHashMap<>();
    private final Deque<Run> runs = new ArrayDeque<>();
    private final LongSupplier clock;
    private final Consumer<String> announcer;
    private final java.util.function.BooleanSupplier announce;
    private long lastNotice = Long.MIN_VALUE / 2;

    MarketNews(LongSupplier clock, Consumer<String> announcer, java.util.function.BooleanSupplier announce) {
        this.clock = clock;
        this.announcer = announcer;
        this.announce = announce;
    }

    public static MarketNews get() {
        MarketNews m = instance;
        if (m == null) {
            synchronized (MarketNews.class) {
                if (instance == null) {
                    instance = new MarketNews(System::currentTimeMillis, MarketEvents::serverNotice,
                            () -> MarketEvents.get().enabled());
                }
                m = instance;
            }
        }
        return m;
    }

    /** Test seam. */
    public static MarketNews forTest(LongSupplier clock, Consumer<String> announcer, boolean announce) {
        return new MarketNews(clock, announcer, () -> announce);
    }

    public static void useForTest(MarketNews m) {
        instance = m;
    }

    // ---------------------------------------------------------------- GuildWars' hooks (never throw)

    /** A guild war was declared ({@code GuildWars.declare} / {@code declareOnPlayers}). */
    public static void warDeclared(long warId, int a, String aName, int b, String bName, long endsAt) {
        try {
            get().declared(warId, a, aName, b, bName, endsAt);
        } catch (RuntimeException e) {
            log.debug("market news: war declared", e);
        }
    }

    /** A guild war ended; {@code winner} 0 = a draw, a peace or a betrayal. */
    public static void warEnded(long warId, int winner) {
        try {
            get().ended(warId, winner);
        } catch (RuntimeException e) {
            log.debug("market news: war ended", e);
        }
    }

    public synchronized War declared(long warId, int a, String aName, int b, String bName, long endsAt) {
        long now = clock.getAsLong();
        boolean say = announce.getAsBoolean() && now - lastNotice >= NOTICE_GAP_MS;
        War w = new War(warId, a, aName, b, bName, now, endsAt, announce.getAsBoolean());
        wars.put(warId, w);
        if (say) {
            lastNotice = now;
            notice("[Cody] War! " + aName + " declared war on " + bName
                    + ". Both guilds are stocking up: Power Elixirs are in demand.");
        }
        return w;
    }

    public synchronized End ended(long warId, int winner) {
        War w = wars.remove(warId);
        if (w == null) {
            return null;
        }
        long now = clock.getAsLong();
        boolean draw = winner == 0 || (winner != w.a() && winner != w.b());
        int loser = draw ? 0 : winner == w.a() ? w.b() : w.a();
        End e = new End(warId, draw ? 0 : winner, draw ? w.aName() : winner == w.a() ? w.aName() : w.bName(), loser,
                draw ? w.bName() : loser == w.a() ? w.aName() : w.bName(), draw, now);
        ends.addLast(e);
        while (ends.size() > 20) {
            ends.removeFirst();
        }
        if (announce.getAsBoolean() && now - lastNotice >= NOTICE_GAP_MS) {
            lastNotice = now;
            notice(draw ? "[Cody] The war between " + w.aName() + " and " + w.bName()
                    + " is over. Elixir buyers are standing down."
                    : "[Cody] " + e.winnerName() + " won the war against " + e.loserName() + "! The winners are "
                    + "flush, the losers are selling off.");
        }
        return e;
    }

    /** A bot guild called a boss run / Guild PQ ({@code GuildBossRuns.start}); {@code crew} of up to 6. */
    public static void prepStarted(int guild, String guildName, String contentKey, String contentName, int crew) {
        try {
            get().prep(guild, guildName, contentKey, contentName, crew);
        } catch (RuntimeException e) {
            log.debug("market news: prep", e);
        }
    }

    /** The run came back: cleared or wiped ({@code GuildBossRuns.results}); a stopped run passes null. */
    public static void prepFinished(int guild, Boolean cleared) {
        try {
            get().finished(guild, cleared);
        } catch (RuntimeException e) {
            log.debug("market news: run result", e);
        }
    }

    public synchronized Prep prep(int guild, String guildName, String contentKey, String contentName, int crew) {
        long now = clock.getAsLong();
        double w = contentWeight(contentKey) * Math.max(1, Math.min(6, crew)) / 6.0;
        boolean say = announce.getAsBoolean() && now - lastNotice >= NOTICE_GAP_MS;
        Prep p = new Prep(guild, guildName, contentName, w, now, announce.getAsBoolean());
        preps.put(guild, p);
        if (say) {
            lastNotice = now;
            notice("[Cody] " + guildName + " is gearing up for " + contentName + ": Power Elixirs are in demand.");
        }
        return p;
    }

    public synchronized Run finished(int guild, Boolean cleared) {
        Prep p = preps.remove(guild);
        if (p == null || cleared == null) {
            return null;
        }
        Run r = new Run(guild, p.guildName(), p.content(), p.weight(), cleared, clock.getAsLong());
        runs.addLast(r);
        while (runs.size() > 20) {
            runs.removeFirst();
        }
        return r;
    }

    /** Guilds getting ready now (newest last). */
    public synchronized List<Prep> preps(long now) {
        prune(now);
        return new ArrayList<>(preps.values());
    }

    /** Runs that came back in the last 6 h (newest last). */
    public synchronized List<Run> recentRuns(long now) {
        prune(now);
        return new ArrayList<>(runs);
    }

    // ---------------------------------------------------------------- readings

    private void prune(long now) {
        ends.removeIf(e -> now - e.at() >= END_NEWS_MS);
        runs.removeIf(r -> now - r.at() >= END_NEWS_MS);
        preps.values().removeIf(p -> now - p.at() >= END_NEWS_MS);
        wars.values().removeIf(w -> w.endsAt() > 0 && w.endsAt() < now - END_NEWS_MS);
    }

    /** What a trader in guild {@code guildId} is to the news right now. */
    public synchronized Role role(int guildId, long now) {
        if (guildId <= 0) {
            return Role.NONE;
        }
        prune(now);
        for (End e : ends) {
            if (!e.draw() && e.winner() == guildId) {
                return Role.WINNER;
            }
            if (!e.draw() && e.loser() == guildId) {
                return Role.LOSER;
            }
        }
        if (preps.containsKey(guildId)) {
            return Role.PREP;
        }
        for (Run r : runs) {
            if (r.guild() == guildId && now - r.at() < FRESH_MS) {
                return r.cleared() ? Role.CLEARED : Role.WIPED;
            }
        }
        for (War w : wars.values()) {
            if (w.involves(guildId)) {
                return now - w.startedAt() < FRESH_MS ? Role.AT_WAR_FRESH : Role.AT_WAR;
            }
        }
        return Role.NONE;
    }

    /**
     * The news' pull on a ticker for a trader outside the guilds in it: ELIXIR +weight per guild gearing up and +0.5
     * x weight per wipe (both fading over 2 h), +1 per fresh war declaration (fading) and +0.3 per running war, -0.6
     * per war ended in the last 2 h (fading), all together within -1.5..1.5; CHAOS and WS -0.4 x weight per clear
     * (fading). Not LEAF: the war winners' buying and the losers' selling are the guilds' own orders.
     */
    public synchronized double pressure(String ticker, long now) {
        prune(now);
        if ("CHAOS".equals(ticker) || "WS".equals(ticker)) {
            double p = 0;
            for (Run r : runs) { // a clear floods the scroll market
                long age = now - r.at();
                p -= r.cleared() && age < FRESH_MS ? 0.4 * r.weight() * (1 - (double) age / FRESH_MS) : 0;
            }
            return Math.max(-1.5, p);
        }
        if (!"ELIXIR".equals(ticker)) {
            return 0;
        }
        double p = 0;
        for (Prep pr : preps.values()) { // gearing up: elixirs
            long age = now - pr.at();
            p += age < FRESH_MS ? pr.weight() * (1 - (double) age / FRESH_MS) : 0;
        }
        for (Run r : runs) { // a wipe: the retry needs more
            long age = now - r.at();
            p += !r.cleared() && age < FRESH_MS ? 0.5 * r.weight() * (1 - (double) age / FRESH_MS) : 0;
        }
        for (War w : wars.values()) {
            long age = now - w.startedAt();
            p += age < FRESH_MS ? 1 - (double) age / FRESH_MS : 0;
            p += 0.3;
        }
        for (End e : ends) {
            long age = now - e.at();
            p -= age < FRESH_MS ? 0.6 * (1 - (double) age / FRESH_MS) : 0;
        }
        return Math.max(-1.5, Math.min(1.5, p));
    }

    /** Running wars (newest last). */
    public synchronized List<War> wars(long now) {
        prune(now);
        return new ArrayList<>(wars.values());
    }

    /** Wars ended in the last 6 h (newest last). */
    public synchronized List<End> recentEnds(long now) {
        prune(now);
        return new ArrayList<>(ends);
    }

    /**
     * The announced news for the site's banner ({@code exchange-tickers.json} {@code events}): running wars and guilds
     * gearing up, only when Cody announced them. {@code ends_at} in unix seconds, null when open-ended.
     */
    /** The server's announced guild news (empty when none was ever made). */
    public static List<Map<String, Object>> announcedNow(long now) {
        MarketNews m = instance;
        return m == null ? List.of() : m.announcedEvents(now);
    }

    public synchronized List<Map<String, Object>> announcedEvents(long now) {
        prune(now);
        List<Map<String, Object>> out = new ArrayList<>();
        for (War w : wars.values()) {
            if (w.announced()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "war-" + w.id());
                m.put("name", "Guild war: " + w.aName() + " vs " + w.bName());
                m.put("ticker", "ELIXIR");
                m.put("ends_at", w.endsAt() > 0 ? w.endsAt() / 1000 : null);
                out.add(m);
            }
        }
        for (Prep p : preps.values()) {
            if (p.announced()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", "prep-" + p.guild() + "-" + p.at());
                m.put("name", p.guildName() + " gearing up for " + p.content());
                m.put("ticker", "ELIXIR");
                m.put("ends_at", null);
                out.add(m);
            }
        }
        return out;
    }

    private void notice(String line) {
        try {
            announcer.accept(line);
        } catch (RuntimeException e) {
            log.warn("market news notice failed: {}", line, e);
        }
    }
}
