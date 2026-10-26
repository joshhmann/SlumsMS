package soloMapling.Economy.market;

import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.Economy.PriceBook;
import soloMapling.dashboard.AtomicFiles;
import soloMapling.server.Json;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * 1.6.0 Track D: the server side of the website's Market. On its own minimum-priority daemon thread it writes the
 * JSON files the site build reads (the contract is in docs/design/track-d-plan.md, "Market export contract"):
 * <pre>
 *   listings.json           every 15 min  every item in an open FM shop (bots' and players', with seller IGNs)
 *   sales.json              every 15 min  the newest 500 price-board samples, times cut to the hour
 *   nx-rate.json            every 15 min  mesos per 1,000 NX: up to 365 daily rows plus today so far
 *   economy-public.json     every 15 min  the nightly economy snapshot with the human-bucket floor (>= 5)
 *   prices/YYYY-MM-DD.json  00:05 UTC     the UTC day's per-item OHLC, median, count, player share (+ that day's NX
 *                                         rate); kept 365 days, missing recent days are back-filled from
 *                                         pricesamples (which keeps 14)
 *   prices/index.json       with the days the dates available
 * </pre>
 * Off by default. JVM properties only (no config.yaml keys):
 * <pre>
 *   -Dslums.market.export=true          write the files
 *   -Dslums.market.dir=Server/data/market  where (default Server/data/market when ./Server exists, else data/market)
 * </pre>
 * Files are written with an atomic rename, mode 644. Nothing waits on a game lock: shops are copied item by item and
 * a shop that changes under the copy is skipped for that run. Every failure is logged and skipped; nothing throws
 * into the game, and a failed file never stops the others.
 */
public final class PublicMarketExport {

    private static final Logger log = LoggerFactory.getLogger(PublicMarketExport.class);

    public static final String ENABLED = "slums.market.export";
    public static final String DIR = "slums.market.dir";
    public static final int KEEP_DAYS = 365;
    /** Every file carries {@code "schema": 1}; bump it when a field changes meaning or goes away. */
    public static final int SCHEMA = 1;
    /** Days before yesterday that a run fills in when their file is missing (pricesamples keeps 14). */
    static final int BACKFILL_DAYS = 12;
    static final long PERIOD_MINUTES = 15;
    static final int NIGHTLY_MINUTE = 5;   // 00:05 UTC
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(15);

    public static final List<String> FILES = List.of("listings.json", "sales.json", "nx-rate.json",
            "economy-public.json", "prices/index.json");

    private static volatile ScheduledExecutorService executor;
    private static volatile Runner runner;

    private PublicMarketExport() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED);
    }

    public static Path dir() {
        String prop = System.getProperty(DIR);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        return Files.isDirectory(Path.of("Server")) ? Path.of("Server/data/market") : Path.of("data/market");
    }

    static long startOf(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** Starts the exporter when {@code -Dslums.market.export=true}; otherwise does nothing. Never throws. */
    public static synchronized void startIfEnabled() {
        if (!enabled() || executor != null) {
            return;
        }
        try {
            Path dir = dir();
            Runner r = new Runner(dir, new ServerSources(), System::currentTimeMillis);
            ScheduledExecutorService ex = newExecutor();
            ex.scheduleWithFixedDelay(r::quarterSafely, 90, PERIOD_MINUTES * 60, TimeUnit.SECONDS);
            ex.scheduleAtFixedRate(r::nightlySafely, secondsUntilNightly(LocalDateTime.now(ZoneOffset.UTC)),
                    TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
            runner = r;
            executor = ex;
            log.info("Market export every {} min (prices nightly at 00:{} UTC) to {}", PERIOD_MINUTES,
                    String.format("%02d", NIGHTLY_MINUTE), dir.toAbsolutePath());
        } catch (RuntimeException e) {
            log.warn("Market export did not start; the game is unaffected", e);
        }
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            runner = null;
        }
    }

    private static ScheduledExecutorService newExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "market-export");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    static long secondsUntilNightly(LocalDateTime nowUtc) {
        LocalDateTime next = nowUtc.toLocalDate().atTime(0, NIGHTLY_MINUTE);
        if (!next.isAfter(nowUtc)) {
            next = next.plusDays(1);
        }
        return Math.max(1, Duration.between(nowUtc, next).getSeconds());
    }

    /**
     * {@code !qa mk-1}: one full export now (yesterday's rollup, every 15-minute file, pruning, index), on the
     * exporter's thread when it runs, else on a one-off low-priority thread, whether or not the switch is on.
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
        }, "market-export-once");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return f;
    }

    /** Every open FM shop now, as listings.json lists them (the site board's price alerts read the same shops). */
    public static List<MarketListings.Shop> liveShops() {
        return new ServerSources().shops();
    }

    /** What a run wrote, under {@code dir}: paths relative to it, and what failed (path: reason). */
    public record Report(Path dir, List<String> written, List<String> failed) {
    }

    // ── what a run reads ────────────────────────────────────────────────────────────────────────

    /** The server's state; tests substitute one. */
    interface Sources {
        List<MarketListings.Shop> shops();

        List<PriceBook.Sample> latestSamples(int limit);

        /** Samples with {@code from <= At < to}, oldest first. */
        List<PriceBook.Sample> samplesBetween(long from, long to);

        NxRate.Prices nxPrices();

        /** The latest nightly public economy snapshot, or null when there is none yet. */
        Map<String, Object> latestPublicEconomy();

        /** Each nightly snapshot's total sinks ({@code flows.totalSinks}) by the day it covers. */
        Map<LocalDate, Long> dailySinks();

        /** Bots online now. */
        int botsOnline();

        IntFunction<String> names();
    }

    // ── one exporter's runs ─────────────────────────────────────────────────────────────────────

    static final class Runner {
        private final Path dir;
        private final Sources sources;
        private final LongSupplier clock;
        /** Each day's NX rate entry, from the day files (read once) and kept current as days are written. */
        private TreeMap<LocalDate, Map<String, Object>> nxDays;
        /** Back-fill days already found to have no samples (no file is written for them). */
        private final Set<LocalDate> emptyDays = new HashSet<>();
        private long failures;
        private volatile long lastWarnAt;
        /** {time, bots online} at each 15-minute run of the last 24 h. */
        private final java.util.ArrayDeque<long[]> botCounts = new java.util.ArrayDeque<>();

        /** The most bots online at any 15-minute run in the last 24 h; null before the first count. */
        Integer botsPeak(long now) {
            while (!botCounts.isEmpty() && botCounts.peekFirst()[0] < now - TimeUnit.DAYS.toMillis(1)) {
                botCounts.pollFirst();
            }
            return botCounts.stream().mapToLong(c -> c[1]).max().stream().mapToObj(v -> (int) v).findFirst()
                    .orElse(null);
        }

        /** Mesos the sinks removed over the last 7 nightly snapshots (the 7 days before today); null when none. */
        Long sinks7d(LocalDate today) {
            Map<LocalDate, Long> sinks = sources.dailySinks();
            long sum = 0;
            boolean any = false;
            for (int back = 1; back <= 7; back++) {
                Long v = sinks.get(today.minusDays(back));
                if (v != null) {
                    sum += v;
                    any = true;
                }
            }
            return any ? sum : null;
        }

        Runner(Path dir, Sources sources, LongSupplier clock) {
            this.dir = dir;
            this.sources = sources;
            this.clock = clock;
        }

        Path prices() {
            return dir.resolve("prices");
        }

        void quarterSafely() {
            safely("15-minute", this::quarter);
        }

        void nightlySafely() {
            safely("nightly", this::nightly);
        }

        private void safely(String what, java.util.function.Supplier<Report> run) {
            try {
                Report r = run.get();
                if (!r.failed().isEmpty()) {
                    warn("Market export (" + what + ") failed for " + r.failed(), null);
                }
            } catch (Throwable t) {
                warn("Market export (" + what + ") failed", t);
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

        /** Everything: yesterday's rollup and pruning, then the 15-minute files. */
        synchronized Report all() {
            Report a = nightly();
            Report b = quarter();
            List<String> w = new ArrayList<>(a.written());
            w.addAll(b.written());
            List<String> f = new ArrayList<>(a.failed());
            f.addAll(b.failed());
            return new Report(dir, w, f);
        }

        /** 00:05 UTC: yesterday's file (always written, even with no sales), pruning, the index, nx-rate.json. */
        synchronized Report nightly() {
            List<String> written = new ArrayList<>(), failed = new ArrayList<>();
            long now = clock.getAsLong();
            LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC);
            NxRate.Prices nx = sources.nxPrices();
            LocalDate yesterday = today.minusDays(1);
            step(written, failed, "prices/" + yesterday + ".json", () -> writeDay(yesterday, nx, now, true));
            step(written, failed, "prices (prune)", () -> prune(today));
            step(written, failed, "prices/index.json", () -> writeIndex(now));
            step(written, failed, "nx-rate.json", () -> writeNxRate(today, nx, now));
            return new Report(dir, written, failed);
        }

        /** Every 15 minutes: listings, sales, NX rate, economy; plus any missing recent day files and the index. */
        synchronized Report quarter() {
            List<String> written = new ArrayList<>(), failed = new ArrayList<>();
            long now = clock.getAsLong();
            LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC);
            NxRate.Prices nx = sources.nxPrices();
            IntFunction<String> names = sources.names();
            int[] shopsOpen = {0};
            step(written, failed, "listings.json", () -> {
                Map<String, Object> listings = MarketListings.build(sources.shops(), names, now);
                shopsOpen[0] = (Integer) listings.get("shops");
                AtomicFiles.writeJson(dir.resolve("listings.json"), listings);
                return true;
            });
            step(written, failed, "bots online", () -> {
                int n = sources.botsOnline();
                botCounts.addLast(new long[]{now, n});
                return false;
            });
            step(written, failed, "sales.json", () -> {
                AtomicFiles.writeJson(dir.resolve("sales.json"),
                        MarketSales.build(sources.latestSamples(MarketSales.LIMIT), names, now));
                return true;
            });
            boolean filled = false;
            for (int back = BACKFILL_DAYS + 1; back >= 1; back--) {
                LocalDate d = today.minusDays(back);
                if (Files.exists(dayFile(d)) || emptyDays.contains(d)) {
                    continue;
                }
                boolean[] wrote = {false};
                int failedBefore = failed.size();
                step(written, failed, "prices/" + d + ".json", () -> wrote[0] = writeDay(d, nx, now, false));
                if (failed.size() > failedBefore) {
                    continue; // e.g. the database is busy: try again next run
                }
                if (wrote[0]) {
                    filled = true;
                } else {
                    emptyDays.add(d); // no sales that day: don't ask again every run
                }
            }
            if (filled || !Files.exists(prices().resolve("index.json"))) {
                step(written, failed, "prices/index.json", () -> writeIndex(now));
            }
            step(written, failed, "nx-rate.json", () -> writeNxRate(today, nx, now));
            step(written, failed, "economy-public.json", () -> {
                Integer trades;
                try {
                    trades = sources.samplesBetween(now - TimeUnit.DAYS.toMillis(1), now + 1).size();
                } catch (RuntimeException e) {
                    trades = null; // the database is busy: the rest of the headline still goes out
                }
                PublicEconomy.Live live = new PublicEconomy.Live(trades, shopsOpen[0], sinks7d(today),
                        botsPeak(now));
                AtomicFiles.writeJson(dir.resolve("economy-public.json"),
                        PublicEconomy.file(sources.latestPublicEconomy(), live, now));
                return true;
            });
            return new Report(dir, written, failed);
        }

        private interface Step {
            boolean run() throws Exception;
        }

        private void step(List<String> written, List<String> failed, String name, Step s) {
            try {
                if (s.run()) {
                    written.add(name);
                }
            } catch (Exception | LinkageError e) {
                failed.add(name + ": " + e);
                log.debug("market export step {}", name, e);
            }
        }

        Path dayFile(LocalDate d) {
            return prices().resolve(d + ".json");
        }

        /** Writes a day's file; with {@code always} false a day without samples writes nothing (false). */
        boolean writeDay(LocalDate d, NxRate.Prices nx, long now, boolean always) throws IOException {
            List<PriceBook.Sample> samples = sources.samplesBetween(startOf(d), startOf(d.plusDays(1)));
            if (samples.isEmpty() && !always) {
                return false;
            }
            Map<String, Object> entry = NxRate.entry(d, samples, nx);
            AtomicFiles.writeJson(dayFile(d), PriceRollup.day(d, samples, sources.names(), entry, now));
            nxDays().put(d, entry);
            emptyDays.remove(d);
            return true;
        }

        /** Deletes day files older than {@link #KEEP_DAYS}. True when it ran. */
        boolean prune(LocalDate today) throws IOException {
            LocalDate oldest = today.minusDays(KEEP_DAYS);
            for (LocalDate d : dayFiles()) {
                if (d.isBefore(oldest)) {
                    Files.deleteIfExists(dayFile(d));
                    nxDays().remove(d);
                }
            }
            return true;
        }

        /** The dates that have a file, oldest first. */
        List<LocalDate> dayFiles() throws IOException {
            List<LocalDate> out = new ArrayList<>();
            if (!Files.isDirectory(prices())) {
                return out;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(prices(), "????-??-??.json")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    try {
                        out.add(LocalDate.parse(n.substring(0, n.length() - 5)));
                    } catch (DateTimeParseException e) {
                        // not a day file
                    }
                }
            }
            out.sort(null);
            return out;
        }

        boolean writeIndex(long now) throws IOException {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("schema", SCHEMA);
            m.put("generated_at", Instant.ofEpochMilli(now).toString());
            m.put("keep_days", KEEP_DAYS);
            m.put("dates", dayFiles().stream().map(LocalDate::toString).toList());
            AtomicFiles.writeJson(prices().resolve("index.json"), m);
            return true;
        }

        boolean writeNxRate(LocalDate today, NxRate.Prices nx, long now) throws IOException {
            List<PriceBook.Sample> todays = sources.samplesBetween(startOf(today), now + 1);
            Map<String, Object> t = NxRate.entry(today, todays, nx);
            List<Map<String, Object>> days = new ArrayList<>();
            LocalDate oldest = today.minusDays(KEEP_DAYS);
            nxDays().forEach((d, e) -> {
                if (!d.isBefore(oldest) && d.isBefore(today)) {
                    days.add(e);
                }
            });
            AtomicFiles.writeJson(dir.resolve("nx-rate.json"), NxRate.file(days, t, now));
            return true;
        }

        /** The NX history, read from the day files the first time it is needed. */
        @SuppressWarnings("unchecked")
        TreeMap<LocalDate, Map<String, Object>> nxDays() throws IOException {
            if (nxDays == null) {
                TreeMap<LocalDate, Map<String, Object>> m = new TreeMap<>();
                for (LocalDate d : dayFiles()) {
                    try {
                        Object doc = Json.parse(Files.readString(dayFile(d), StandardCharsets.UTF_8));
                        if (doc instanceof Map<?, ?> map && map.get("nx_rate") instanceof Map<?, ?> e) {
                            m.put(d, (Map<String, Object>) e);
                        }
                    } catch (IOException | RuntimeException e) {
                        log.debug("market export: unreadable day file {}", d, e);
                    }
                }
                nxDays = m;
            }
            return nxDays;
        }
    }

    // ── the real server ─────────────────────────────────────────────────────────────────────────

    static final class ServerSources implements Sources {
        static final String LATEST = "SELECT ItemId, UnitPrice, NormPrice, Quantity, Source, PlayerSide, At"
                + " FROM pricesamples ORDER BY At DESC LIMIT ?";
        static final String BETWEEN = "SELECT ItemId, UnitPrice, NormPrice, Quantity, Source, PlayerSide, At"
                + " FROM pricesamples WHERE At >= ? AND At < ? ORDER BY At";

        @Override
        public List<MarketListings.Shop> shops() {
            List<MarketListings.Shop> out = new ArrayList<>();
            for (World w : Server.getInstance().getWorlds()) {
                for (HiredMerchant hm : w.getActiveMerchants()) {
                    try {
                        out.add(merchant(hm));
                    } catch (RuntimeException e) {
                        log.debug("market export: merchant skipped this run", e);
                    }
                }
                for (PlayerShop ps : w.getActivePlayerShops()) {
                    try {
                        out.add(playerShop(ps));
                    } catch (RuntimeException e) {
                        log.debug("market export: player shop skipped this run", e);
                    }
                }
            }
            return out;
        }

        private static MarketListings.Shop merchant(HiredMerchant hm) {
            boolean bot = hm instanceof soloMapling.FreeMarket.HiredMerchantArtificial
                    || soloMapling.ArtificialPlayer.BotHelpers.isArtificial(hm.getOwnerId());
            int mapId = 0;
            if (hm instanceof soloMapling.FreeMarket.HiredMerchantArtificial a) {
                mapId = a.getMapIdArtificial();
            }
            if (mapId <= 0 && hm.getMap() != null) {
                mapId = hm.getMapId();
            }
            return new MarketListings.Shop(hm.getChannel(), mapId, hm.getDescription(), hm.getOwner(), bot, true,
                    rows(hm.getItems()));
        }

        private static MarketListings.Shop playerShop(PlayerShop ps) {
            client.Character owner = ps.getOwner();
            boolean bot = owner != null && (soloMapling.ArtificialPlayer.BotHelpers.isBot(owner)
                    || soloMapling.ArtificialPlayer.BotHelpers.isArtificial(owner.getId()));
            int channel = 0;
            try {
                channel = ps.getChannel();
            } catch (RuntimeException e) {
                // a bot's shared client may not know its channel
            }
            return new MarketListings.Shop(channel, owner == null ? 0 : owner.getMapId(), ps.getDescription(),
                    owner == null ? "" : owner.getName(), bot, false, rows(ps.getItems()));
        }

        private static List<MarketListings.Row> rows(List<PlayerShopItem> items) {
            List<MarketListings.Row> out = new ArrayList<>();
            for (PlayerShopItem psi : new ArrayList<>(items)) {
                if (psi == null || !psi.isExist() || psi.getItem() == null) {
                    continue;
                }
                int id = psi.getItem().getItemId();
                out.add(new MarketListings.Row(id, Math.max(1, (int) psi.getItem().getQuantity()), psi.getBundles(),
                        psi.getPrice(), constants.inventory.ItemConstants.isRechargeable(id)));
            }
            return out;
        }

        @Override
        public List<PriceBook.Sample> latestSamples(int limit) {
            List<PriceBook.Sample> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(LATEST)) {
                ps.setInt(1, limit);
                read(ps, out);
            } catch (SQLException e) {
                throw new IllegalStateException("pricesamples: " + e.getMessage(), e);
            }
            return out;
        }

        @Override
        public List<PriceBook.Sample> samplesBetween(long from, long to) {
            List<PriceBook.Sample> out = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection(); PreparedStatement ps = con.prepareStatement(BETWEEN)) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                read(ps, out);
            } catch (SQLException e) {
                throw new IllegalStateException("pricesamples: " + e.getMessage(), e);
            }
            return out;
        }

        private static void read(PreparedStatement ps, List<PriceBook.Sample> out) throws SQLException {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PriceBook.Source src = PriceBook.Source.parse(rs.getString("Source"));
                    if (src != null) {
                        out.add(new PriceBook.Sample(rs.getInt("ItemId"), rs.getLong("UnitPrice"),
                                rs.getLong("NormPrice"), rs.getInt("Quantity"), src, rs.getInt("PlayerSide") != 0,
                                rs.getLong("At")));
                    }
                }
            }
        }

        @Override
        public NxRate.Prices nxPrices() {
            return NxRate.Prices.of(NxRate.cashPrices(server.cashshop.CashShopCatalog.file()));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> latestPublicEconomy() {
            Path f = soloMapling.Economy.stats.EconomySnapshotService.getInstance().outputDir()
                    .resolve("latest-public.json");
            if (!Files.isRegularFile(f)) {
                return null;
            }
            try {
                Object doc = Json.parse(Files.readString(f, StandardCharsets.UTF_8));
                return doc instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            } catch (IOException e) {
                throw new IllegalStateException(f + ": " + e.getMessage(), e);
            }
        }

        /** Each dated snapshot's sinks, read once per file (a nightly file never changes). */
        private final Map<String, Long> sinksByFile = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Map<LocalDate, Long> dailySinks() {
            Map<LocalDate, Long> out = new TreeMap<>();
            Path econ = soloMapling.Economy.stats.EconomySnapshotService.getInstance().outputDir();
            if (!Files.isDirectory(econ)) {
                return out;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(econ, "????-??-??-public.json")) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    LocalDate d;
                    try {
                        d = LocalDate.parse(n.substring(0, 10));
                    } catch (DateTimeParseException e) {
                        continue;
                    }
                    if (d.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(8))) {
                        continue;
                    }
                    Long v = sinksByFile.computeIfAbsent(n, k -> {
                        try {
                            Object doc = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                            if (doc instanceof Map<?, ?> m && m.get("flows") instanceof Map<?, ?> f
                                    && f.get("totalSinks") instanceof Number s) {
                                return s.longValue();
                            }
                        } catch (IOException | RuntimeException e) {
                            log.debug("market export: unreadable snapshot {}", p, e);
                        }
                        return -1L;
                    });
                    if (v >= 0) {
                        out.put(d, v);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(econ + ": " + e.getMessage(), e);
            }
            return out;
        }

        @Override
        public int botsOnline() {
            int n = 0;
            for (var sm : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
                if (sm != null && sm.getChr() != null) {
                    n++;
                }
            }
            return n;
        }

        @Override
        public IntFunction<String> names() {
            return MarketNames::wz;
        }
    }
}
