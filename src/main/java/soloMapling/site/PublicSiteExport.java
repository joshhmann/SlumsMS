package soloMapling.site;

import client.Character;
import client.SyntheticClient;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.dashboard.AtomicFiles;
import soloMapling.server.Json;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 1.6.0 Track D: the server side of the website's news feed, leaderboards and "live world" page. On its own
 * minimum-priority daemon thread it writes the JSON files the site build reads (the contract is in
 * docs/design/track-d-plan.md, "Site export contract"):
 * <pre>
 *   feed.json          every 2 min   newest first, at most 200 items of the last 7 days (megaphones 24 h)
 *   leaderboards.json  every 15 min  level, fame, guilds, traders, Dojo, boss clears; bots and players together
 *   world.json         every 60 s    online per channel, the 10 busiest maps, tonight's headline, running events
 * </pre>
 * Off by default. JVM properties only (no config.yaml keys):
 * <pre>
 *   -Dslums.site.export=true          write the files (and record feed items)
 *   -Dslums.site.dir=Server/data/site  where (default Server/data/site when ./Server exists, else data/site)
 * </pre>
 * Files are written with an atomic rename, mode 644. Names only: no character ids, no account data. The feed comes
 * from {@link SiteFeed}'s in-memory rings (no database); the leaderboards read the database here, off the game thread,
 * with bounded queries. Every failure is logged at most every 15 min and skipped; nothing throws into the game, and a
 * failed file never stops the others.
 */
public final class PublicSiteExport {

    private static final Logger log = LoggerFactory.getLogger(PublicSiteExport.class);

    public static final String ENABLED = "slums.site.export";
    public static final String DIR = "slums.site.dir";
    /** Every file carries {@code "schema": 1}; bump it when a field changes meaning or goes away. */
    public static final int SCHEMA = 1;
    static final long FEED_SECONDS = 120;
    static final long WORLD_SECONDS = 60;
    static final long BOARDS_SECONDS = TimeUnit.MINUTES.toSeconds(15);
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(15);

    public static final List<String> FILES = List.of("feed.json", "leaderboards.json", "world.json");

    private static volatile ScheduledExecutorService executor;
    private static volatile Runner runner;

    private PublicSiteExport() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED);
    }

    public static Path dir() {
        String prop = System.getProperty(DIR);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        return Files.isDirectory(Path.of("Server")) ? Path.of("Server/data/site") : Path.of("data/site");
    }

    /** Starts the exporter when {@code -Dslums.site.export=true}; otherwise does nothing. Never throws. */
    public static synchronized void startIfEnabled() {
        if (!enabled() || executor != null) {
            return;
        }
        try {
            Path dir = dir();
            Runner r = new Runner(dir, new ServerSources(), System::currentTimeMillis);
            ScheduledExecutorService ex = newExecutor("site-export");
            SiteFeed.setRecording(true);
            SiteMobKills.setRecording(true);
            ex.execute(r::seedSafely); // the last feed.json back into memory before the first write
            ex.scheduleWithFixedDelay(r::worldSafely, 30, WORLD_SECONDS, TimeUnit.SECONDS);
            ex.scheduleWithFixedDelay(r::feedSafely, 45, FEED_SECONDS, TimeUnit.SECONDS);
            ex.scheduleWithFixedDelay(r::boardsSafely, 120, BOARDS_SECONDS, TimeUnit.SECONDS);
            runner = r;
            executor = ex;
            log.info("Site export: feed every {} s, world every {} s, leaderboards every {} min, to {}", FEED_SECONDS,
                    WORLD_SECONDS, BOARDS_SECONDS / 60, dir.toAbsolutePath());
        } catch (RuntimeException e) {
            log.warn("Site export did not start; the game is unaffected", e);
        }
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            SiteMobKills.setRecording(false);
            executor = null;
            runner = null;
        }
    }

    private static ScheduledExecutorService newExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * {@code !qa sf-1}: every file now, on the exporter's thread when it runs, else on a one-off low-priority thread,
     * whether or not the switch is on (with it off the feed has recorded nothing and is empty).
     */
    public static CompletableFuture<Report> forceExport() {
        ScheduledExecutorService ex = executor;
        Runner r = runner;
        if (ex != null && r != null) {
            return CompletableFuture.supplyAsync(r::all, ex);
        }
        Runner once = new Runner(dir(), new ServerSources(), System::currentTimeMillis);
        CompletableFuture<Report> f = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                f.complete(once.all());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        }, "site-export-once");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return f;
    }

    /** What a run wrote, under {@code dir}: file names, and what failed (name: reason). */
    public record Report(Path dir, List<String> written, List<String> failed) {
    }

    /**
     * The private {@link TradeRatings#PRIVATE_FILE} from the database, for {@link soloMapling.Economy.board.SiteBoard}
     * to write into the board outbox (never this public folder); null when the ratings cannot be read.
     */
    public static Map<String, Object> tradeRatingsFile(long now) {
        Boards b = new ServerSources().boards(1, 200_000);
        return b.trades() == null ? null : TradeRatings.privateFile(b.everyone(), b.trades(), now);
    }

    // ── what a run reads ────────────────────────────────────────────────────────────────────────

    /** The server's state; tests substitute one. */
    interface Sources {
        SiteWorld.Online online();

        SiteWorld.Headline headline(long now);

        List<SiteWorld.Running> running();

        /** Player characters (filtered by {@link SiteLeaderboards#players}) and bots, ready to rank. */
        Boards boards();

        /** A mob's String.wz name (empty when unknown). */
        default String mobName(int id) {
            return "";
        }
    }

    /**
     * What the leaderboards are built from. {@code excludedIds}: characters (staff, QA, template) whose guilds are left
     * out. {@code bosses} null: the boss tables could not be read.
     */
    record Boards(List<SiteLeaderboards.Person> everyone, List<SiteLeaderboards.GuildRow> guilds,
                  Map<Integer, Integer> members, Set<Integer> excludedIds, List<SiteLeaderboards.BossRow> bosses,
                  List<TradeRatings.Row> trades) {
    }

    // ── one exporter's runs ─────────────────────────────────────────────────────────────────────

    static final class Runner {
        private final Path dir;
        private final Sources sources;
        private final LongSupplier clock;
        private long failures;
        private volatile long lastWarnAt;

        Runner(Path dir, Sources sources, LongSupplier clock) {
            this.dir = dir;
            this.sources = sources;
            this.clock = clock;
        }

        void seedSafely() {
            try {
                Path f = dir.resolve("feed.json");
                if (Files.isRegularFile(f)) {
                    int n = SiteFeed.seed(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
                    log.info("Site export: {} feed items restored from {}", n, f.toAbsolutePath());
                }
            } catch (Throwable t) {
                warn("Site export: the last feed.json could not be read back", t);
            }
        }

        void feedSafely() {
            safely("feed", () -> one("feed.json", this::writeFeed));
        }

        void worldSafely() {
            safely("world", () -> one("world.json", this::writeWorld));
        }

        void boardsSafely() {
            safely("leaderboards", () -> one("leaderboards.json", this::writeBoards));
        }

        private void safely(String what, Supplier<Report> run) {
            try {
                Report r = run.get();
                if (!r.failed().isEmpty()) {
                    warn("Site export (" + what + ") failed for " + r.failed(), null);
                }
            } catch (Throwable t) {
                warn("Site export (" + what + ") failed", t);
                if (t instanceof VirtualMachineError vme && !(t instanceof StackOverflowError)) {
                    throw vme;
                }
            }
        }

        private void warn(String msg, Throwable t) {
            failures++;
            long now = System.currentTimeMillis();
            if (now - lastWarnAt >= WARN_EVERY_MS) {
                lastWarnAt = now;
                log.warn("{} ({} so far); the game is unaffected", msg, failures, t);
            } else {
                log.debug(msg, t);
            }
        }

        /** Every file. */
        synchronized Report all() {
            List<String> written = new ArrayList<>(), failed = new ArrayList<>();
            step(written, failed, "feed.json", this::writeFeed);
            step(written, failed, "world.json", this::writeWorld);
            step(written, failed, "leaderboards.json", this::writeBoards);
            return new Report(dir, written, failed);
        }

        private synchronized Report one(String name, Step s) {
            List<String> written = new ArrayList<>(), failed = new ArrayList<>();
            step(written, failed, name, s);
            return new Report(dir, written, failed);
        }

        private interface Step {
            void run() throws Exception;
        }

        private void step(List<String> written, List<String> failed, String name, Step s) {
            try {
                s.run();
                written.add(name);
            } catch (Exception | LinkageError e) {
                failed.add(name + ": " + e);
                log.debug("site export step {}", name, e);
            }
        }

        void writeFeed() throws IOException {
            long now = clock.getAsLong();
            AtomicFiles.writeJson(dir.resolve("feed.json"), SiteFeed.file(SiteFeed.snapshot(now), now));
        }

        void writeWorld() throws IOException {
            long now = clock.getAsLong();
            SiteWorld.Headline headline;
            try {
                headline = sources.headline(now);
            } catch (RuntimeException e) {
                headline = null; // the event system is not up: the rest of the page still goes out
            }
            List<SiteWorld.Running> running;
            try {
                running = sources.running();
            } catch (RuntimeException e) {
                running = List.of();
            }
            AtomicFiles.writeJson(dir.resolve("world.json"), SiteWorld.file(sources.online(), headline, running, now,
                    SiteMobKills.top(24, now, sources::mobName), SiteMobKills.top(SiteMobKills.HOURS, now, sources::mobName)));
        }

        void writeBoards() throws IOException {
            long now = clock.getAsLong();
            Boards b = sources.boards();
            AtomicFiles.writeJson(dir.resolve("leaderboards.json"),
                    SiteLeaderboards.file(b.everyone(), b.guilds(), b.members(), b.excludedIds(), b.bosses(), b.trades(), now));
        }
    }

    // ── the real server ─────────────────────────────────────────────────────────────────────────

    static final class ServerSources implements Sources {
        /** Every player character with what the boards need; filtered in Java (a friends server has few). */
        static final String PLAYERS = "SELECT c.id, c.accountid, c.name, c.level, c.exp, c.job, c.reborns, c.fame,"
                + " c.guildid, c.gm, a.name AS account, a.banned"
                + " FROM characters c LEFT JOIN accounts a ON a.id = c.accountid LIMIT 20000";
        /** Trade ratings with at least the minimum; over-fetched because excluded characters are dropped after. */
        static final String TRADES = "SELECT CharId, Completed, FellThrough FROM traderatings"
                + " WHERE Completed + FellThrough >= ? ORDER BY Completed DESC LIMIT ?";
        /** Every bot (the roster and the older plan slots): one bounded read; fame is derived from id and level. */
        static final String BOTS = "SELECT s.BotId, s.Name, s.Level, s.Exp, s.Job, m.GuildId"
                + " FROM botstore s LEFT JOIN botguildmember m ON m.BotId = s.BotId LIMIT 50000";
        static final String GUILDS = "SELECT guildid, name, GP, capacity, leader FROM guilds LIMIT 5000";
        /** Expedition clears (append-only; the daily/weekly entry logs are pruned, these are not). */
        static final String BOSSES = "SELECT characterid, bosstype, COUNT(*) AS clears, MIN(NULLIF(duration, 0)) AS fastest"
                + " FROM (SELECT characterid, bosstype, duration FROM bosslog_leaderboard_daily"
                + " WHERE bosstype IN ('ZAKUM', 'HORNTAIL', 'PINKBEAN')"
                + " UNION ALL SELECT characterid, bosstype, duration FROM bosslog_leaderboard_weekly"
                + " WHERE bosstype IN ('ZAKUM', 'HORNTAIL', 'PINKBEAN')) x"
                + " GROUP BY characterid, bosstype LIMIT 20000";

        @Override
        public SiteWorld.Online online() {
            Map<Integer, Integer> perChannel = new HashMap<>();
            Map<Integer, Integer> perMap = new HashMap<>();
            Map<Integer, String> names = new HashMap<>();
            for (World w : Server.getInstance().getWorlds()) {
                for (Channel ch : w.getChannels()) {
                    int n = 0;
                    for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                        try {
                            if (chr == null || chr.getClient() instanceof SyntheticClient || chr.gmLevel() > 0) {
                                continue; // auto-QA pretend players and staff are not "online" for the site
                            }
                            n++;
                            int mapId = chr.getMapId();
                            perMap.merge(mapId, 1, Integer::sum);
                            if (!names.containsKey(mapId) && chr.getMap() != null) {
                                names.put(mapId, chr.getMap().getMapName());
                            }
                        } catch (RuntimeException e) {
                            // a character logging out mid-count: skip it this minute
                        }
                    }
                    perChannel.merge(ch.getId(), n, Integer::sum);
                }
            }
            return new SiteWorld.Online(perChannel, perMap, names);
        }

        @Override
        public String mobName(int id) {
            return server.life.MonsterInformationProvider.getInstance().getMobNameFromId(id);
        }

        @Override
        public SiteWorld.Headline headline(long now) {
            soloMapling.worldevents.WorldEvents ev = soloMapling.worldevents.WorldEvents.getInstance();
            if (!ev.settings().auto()) {
                return null; // no evening headline on a server with the event director off
            }
            soloMapling.worldevents.EventDirector d = ev.director();
            var cfg = d.config();
            var state = d.state();
            java.time.LocalDate today = soloMapling.worldevents.EventDirector.localDate(now, cfg.zone());
            boolean planned = today.toString().equals(state.day());
            String template = null;
            if (planned && !state.headlineTemplate().isEmpty()) {
                var t = soloMapling.worldevents.EventTemplates.byId(state.headlineTemplate());
                template = t == null ? state.headlineTemplate() : t.name();
            }
            boolean running = ev.active().stream().anyMatch(soloMapling.worldevents.ActiveEvent::headline);
            SiteWorld.Headline h = SiteWorld.nextHeadline(planned, template, state.headlineAt(), state.headlineStatus(),
                    running, soloMapling.worldevents.EventDirector.at(today, cfg.headlineAt(), cfg.zone()),
                    soloMapling.worldevents.EventDirector.at(today.plusDays(1), cfg.headlineAt(), cfg.zone()),
                    cfg.graceMs(), now);
            if (h.name() != null && state.headlineMap() > 0) {
                var t = soloMapling.worldevents.EventTemplates.byId(state.headlineTemplate());
                boolean hidden = t != null && t.kind() == soloMapling.worldevents.EventTemplate.Kind.TREASURE_HUNT;
                if (!hidden) {
                    try {
                        h = h.withMap(server.maps.MapFactory.loadPlaceName(state.headlineMap()));
                    } catch (RuntimeException | LinkageError e) {
                        // no name: the site shows the event without a map
                    }
                }
            }
            return h;
        }

        @Override
        public List<SiteWorld.Running> running() {
            List<SiteWorld.Running> out = new ArrayList<>();
            for (soloMapling.worldevents.ActiveEvent e : soloMapling.worldevents.WorldEvents.getInstance().active()) {
                boolean hidden = e.template.kind() == soloMapling.worldevents.EventTemplate.Kind.TREASURE_HUNT;
                out.add(new SiteWorld.Running(e.template.name(), e.template.kind().label(), hidden ? 0 : e.mapId(),
                        hidden ? e.regionName() : e.mapName(), e.channel, e.startedAt, e.endsAt, e.headline()));
            }
            return out;
        }

        @Override
        public Boards boards() {
            return boards(SiteLeaderboards.TRADER_MIN, 1000);
        }

        /** As {@link #boards()}, with the trade ratings of at least {@code tradeMin} trades (at most {@code tradeLimit}). */
        Boards boards(int tradeMin, int tradeLimit) {
            try (Connection con = DatabaseConnection.getConnection()) {
                List<SiteLeaderboards.PlayerRow> players = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(PLAYERS); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        players.add(new SiteLeaderboards.PlayerRow(rs.getInt("id"), rs.getInt("accountid"),
                                rs.getString("name"), rs.getInt("level"), rs.getLong("exp"), rs.getInt("job"),
                                rs.getInt("reborns"), rs.getInt("fame"), rs.getInt("guildid"), rs.getInt("gm"),
                                rs.getString("account"),
                                rs.getInt("banned") != 0));
                    }
                }
                List<TradeRatings.Row> trades = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(TRADES)) {
                    ps.setInt(1, tradeMin);
                    ps.setInt(2, tradeLimit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            trades.add(new TradeRatings.Row(rs.getInt("CharId"), rs.getLong("Completed"),
                                    rs.getLong("FellThrough")));
                        }
                    }
                } catch (SQLException e) {
                    trades = null; // no traderatings table yet (migration 035): the rest of the boards still go out
                    log.debug("site export: trade ratings not readable", e);
                }
                List<SiteLeaderboards.BotRow> bots = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(BOTS); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int charId = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + rs.getInt("BotId");
                        int level = rs.getInt("Level");
                        bots.add(new SiteLeaderboards.BotRow(charId, rs.getString("Name"), level, rs.getLong("Exp"),
                                rs.getInt("Job"),
                                soloMapling.ArtificialPlayer.BotFame.forBot(charId, level), rs.getInt("GuildId")));
                    }
                }
                List<SiteLeaderboards.GuildRow> guilds = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(GUILDS); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        guilds.add(new SiteLeaderboards.GuildRow(rs.getInt("guildid"), rs.getString("name"),
                                rs.getLong("GP"), rs.getInt("capacity"), rs.getInt("leader")));
                    }
                }
                List<SiteLeaderboards.BossRow> bosses = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(BOSSES); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        bosses.add(new SiteLeaderboards.BossRow(rs.getInt("characterid"), rs.getString("bosstype"),
                                rs.getInt("clears"), rs.getLong("fastest")));
                    }
                } catch (SQLException e) {
                    bosses = null; // no boss tables on this database: the rest of the boards still go out
                    log.debug("site export: boss clears not readable", e);
                }
                int template = templateId();
                List<SiteLeaderboards.Person> people = new ArrayList<>(SiteLeaderboards.players(players, template));
                Set<Integer> kept = new HashSet<>();
                for (SiteLeaderboards.Person p : people) {
                    kept.add(p.id());
                }
                Set<Integer> excluded = new HashSet<>();
                for (SiteLeaderboards.PlayerRow r : players) {
                    if (!kept.contains(r.id())) {
                        excluded.add(r.id());
                    }
                }
                people.addAll(SiteLeaderboards.bots(bots));
                Map<Integer, Integer> members = new HashMap<>();
                for (SiteLeaderboards.Person p : people) {
                    if (p.guildId() > 0) {
                        members.merge(p.guildId(), 1, Integer::sum);
                    }
                }
                return new Boards(people, guilds, members, excluded, bosses, trades);
            } catch (SQLException e) {
                throw new IllegalStateException("leaderboards: " + e.getMessage(), e);
            }
        }

        private static int templateId() {
            try {
                return soloMapling.server.MapleRootSolo.isEnabled() ? soloMapling.server.MapleRootSolo.templateId() : 0;
            } catch (RuntimeException | LinkageError e) {
                return 0;
            }
        }
    }
}
