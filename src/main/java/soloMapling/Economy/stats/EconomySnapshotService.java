package soloMapling.Economy.stats;

import client.Character;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.Economy.PriceBook;
import soloMapling.server.History;
import soloMapling.server.Json;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Economy snapshot service (1.6.0 Track D):
 * Computes daily and on-demand macro economy stats (meso supply by level band, wealth distribution
 * including Gini and percentiles, source/sink/transfer flows, market item prices, Maple Leaf & NX flows).
 *
 * <p>Separates output into:
 * <ul>
 *   <li>Private file (audit view, character IDs allowed, no secret credentials)</li>
 *   <li>Public file (Basil Market & Hidden Street, strictly aggregates, zero player names, zero IDs)</li>
 * </ul>
 */
public final class EconomySnapshotService {

    private static final Logger log = LoggerFactory.getLogger(EconomySnapshotService.class);

    public static final String DIR_PROPERTY = "slums.econ.dir";
    public static final String DEFAULT_DIR = "Server/data/econ";
    private static final int BOT_ID_BASE = 1_000_000_000;
    private static final int DEFAULT_WORLD_ID = 1; // SlumsMS Bera

    private static final EconomySnapshotService INSTANCE = new EconomySnapshotService(resolveDir());
    private static ScheduledExecutorService scheduler;

    /** How often the day's counters are copied to the database ({@code -Dslums.econ.counterSaveSeconds}). */
    static final long COUNTER_SAVE_SECONDS = Math.max(30, Long.getLong("slums.econ.counterSaveSeconds", 300));

    private final Path outputDir;
    private volatile EconomyCounterPersistence persistence;

    public record CharacterRecord(int id, int level, long meso, boolean isBot) {
    }

    public record SnapshotResult(
            long totalHumanMeso,
            long totalBotMeso,
            double humanGini,
            double botGini,
            long totalSources,
            long totalSinks,
            Path privateFile,
            Path publicFile
    ) {
    }

    public static EconomySnapshotService getInstance() {
        return INSTANCE;
    }

    public EconomySnapshotService(Path outputDir) {
        this.outputDir = outputDir;
    }

    public EconomySnapshotService(Path outputDir, EconomyCounterPersistence persistence) {
        this.outputDir = outputDir;
        this.persistence = persistence;
    }

    /** Where the snapshots go ({@code -Dslums.econ.dir}); the market export reads latest-public.json from here. */
    public Path outputDir() {
        return outputDir;
    }

    private static Path resolveDir() {
        String prop = System.getProperty(DIR_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.trim());
        }
        // If Server directory exists in current working dir, use Server/data/econ, else data/econ
        if (Files.isDirectory(Path.of("Server"))) {
            return Path.of(DEFAULT_DIR);
        }
        return Path.of("data/econ");
    }

    /**
     * Starts the nightly scheduled snapshot job (runs at UTC midnight).
     */
    public static synchronized void startNightlyJob() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "economy-snapshot");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // Compute delay until next UTC midnight
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        long initialDelaySeconds = Duration.between(now, nextMidnight).getSeconds();

        // The day's counters survive a restart (owner 2026-10-26): today's stored totals are added back first, then
        // copied to the database every few minutes, all on this thread (never the game thread).
        EconomyCounterPersistence counters = new EconomyCounterPersistence(new EconomyCounterPersistence.Jdbc());
        getInstance().persistence = counters;
        scheduler.execute(() -> {
            try {
                counters.boot(LocalDate.now(ZoneOffset.UTC));
            } catch (RuntimeException e) {
                log.error("Economy counters: restore at boot failed", e);
            }
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                counters.save(System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.warn("Economy counters: periodic save failed", e);
            }
        }, COUNTER_SAVE_SECONDS, COUNTER_SAVE_SECONDS, TimeUnit.SECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                getInstance().takeNightlySnapshot(LocalDateTime.now(ZoneOffset.UTC));
            } catch (Exception e) {
                log.error("Nightly economy snapshot failed", e);
            }
        }, initialDelaySeconds, 24 * 3600L, TimeUnit.SECONDS);

        log.info("Nightly economy snapshot job scheduled to run in {}s, output dir: {}",
                initialDelaySeconds, getInstance().outputDir.toAbsolutePath());
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * At server shutdown: stops the jobs, waits briefly for a running one, then writes the day's counters one last
     * time so a restart picks them up.
     */
    public static void shutdown() {
        ScheduledExecutorService s;
        synchronized (EconomySnapshotService.class) {
            s = scheduler;
            scheduler = null;
        }
        if (s != null) {
            s.shutdown();
            try {
                s.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        EconomyCounterPersistence p = getInstance().persistence;
        if (p != null) {
            try {
                p.save(System.currentTimeMillis());
                log.info("Economy counters saved for {}", p.windowDay());
            } catch (RuntimeException e) {
                log.warn("Economy counters: shutdown save failed", e);
            }
        }
    }

    /**
     * The nightly snapshot: closes the day's flow window (resets the counters) and writes the dated and latest
     * files. It fires at UTC midnight, so the flows it closes belong to the day that just ended: the file is
     * labelled with that day (half a day back absorbs scheduler drift either side of midnight).
     */
    public SnapshotResult takeNightlySnapshot(LocalDateTime nowUtc) throws IOException {
        return executeSnapshot(nightlyLabel(nowUtc), this::loadHumans, this::loadBots, this::loadItemPrices);
    }

    static LocalDate nightlyLabel(LocalDateTime nowUtc) {
        return nowUtc.minusHours(12).toLocalDate();
    }

    /**
     * The GM's on-demand view (!econstats): reads the flows so far without resetting them and writes only
     * {@code ondemand-*.json}, so it never eats the nightly window or overwrites a dated / latest file.
     */
    public SnapshotResult takeOnDemandSnapshot() throws IOException {
        return executeSnapshot(LocalDate.now(ZoneOffset.UTC), this::loadHumans, this::loadBots,
                this::loadItemPrices, false);
    }

    /**
     * Core snapshot execution with pluggable suppliers (enabling pure unit testing).
     */
    public SnapshotResult executeSnapshot(
            LocalDate date,
            Supplier<List<CharacterRecord>> humanSupplier,
            Supplier<List<CharacterRecord>> botSupplier,
            Supplier<Map<Integer, List<Long>>> itemPriceSupplier
    ) throws IOException {
        return executeSnapshot(date, humanSupplier, botSupplier, itemPriceSupplier, true);
    }

    /**
     * @param nightly true closes the flow window (resets the counters) and writes the dated and latest files;
     *                false only peeks at the counters and writes the {@code ondemand-*} files.
     */
    public SnapshotResult executeSnapshot(
            LocalDate date,
            Supplier<List<CharacterRecord>> humanSupplier,
            Supplier<List<CharacterRecord>> botSupplier,
            Supplier<Map<Integer, List<Long>>> itemPriceSupplier,
            boolean nightly
    ) throws IOException {
        Files.createDirectories(outputDir);

        List<CharacterRecord> humans = humanSupplier.get();
        List<CharacterRecord> bots = botSupplier.get();
        Map<Integer, List<Long>> itemPriceSamples = itemPriceSupplier.get();

        // 1. Meso supply & level bands
        var humanSupply = computeMesoSupply(humans);
        var botSupply = computeMesoSupply(bots);
        long totalHumanMeso = (long) humanSupply.get("totalMeso");
        long totalBotMeso = (long) botSupply.get("totalMeso");
        long totalMeso = totalHumanMeso + totalBotMeso;

        Map<String, Object> mesoSupplyMap = new LinkedHashMap<>();
        mesoSupplyMap.put("totalMeso", totalMeso);
        mesoSupplyMap.put("humans", humanSupply);
        mesoSupplyMap.put("bots", botSupply);

        // 2. Wealth distribution
        var humanWealth = computeWealthDistribution(humans);
        var botWealth = computeWealthDistribution(bots);
        double humanGini = (double) humanWealth.get("gini");
        double botGini = (double) botWealth.get("gini");

        Map<String, Object> wealthMap = new LinkedHashMap<>();
        wealthMap.put("humans", humanWealth);
        wealthMap.put("bots", botWealth);

        // 3. Sources, sinks & transfers (from EconomyCounters)
        EconomyCounters.Snapshot counterSnap = nightly ? EconomyCounters.snapshotAndReset() : EconomyCounters.peek();
        EconomyCounterPersistence counters = persistence;
        if (nightly && counters != null) {
            counters.closeDay(date, counterSnap, System.currentTimeMillis());
        }
        Map<String, Object> flowsMap = formatFlows(counterSnap);

        // 4. Item prices
        Map<Integer, ItemPriceStats.ItemStat> itemStats = ItemPriceStats.calculate(itemPriceSamples);
        Map<String, Object> itemPricesJson = new LinkedHashMap<>();
        for (var entry : itemStats.entrySet()) {
            itemPricesJson.put(String.valueOf(entry.getKey()), entry.getValue().toMap());
        }

        // Build Public Report (AGGREGATES ONLY, ZERO IDS, ZERO PLAYER NAMES)
        Map<String, Object> publicDoc = new LinkedHashMap<>();
        publicDoc.put("date", date.toString());
        publicDoc.put("timestamp", System.currentTimeMillis());
        publicDoc.put("mesoSupply", mesoSupplyMap);
        publicDoc.put("wealthDistribution", wealthMap);
        publicDoc.put("flows", flowsMap);
        publicDoc.put("itemPrices", itemPricesJson);

        // Build Private Report (includes character IDs for top holders and audit view)
        Map<String, Object> privateDoc = new LinkedHashMap<>(publicDoc);
        // 1.6.0: bot-to-bot trades are a transfer between two bots, shown in the audit view only (the public file the
        // site reads keeps its shape, and no player-facing number moves with them)
        privateDoc.put("flows", formatFlows(counterSnap, true));
        privateDoc.put("topHolders", computeTopHolders(humans, bots));

        // Write files
        String dateStr = date.toString();
        if (!nightly) {
            publicDoc.put("partialDay", true);
            privateDoc.put("partialDay", true);
        }
        String prefix = nightly ? dateStr : "ondemand";
        Path privateFile = outputDir.resolve(prefix + "-private.json");
        Path publicFile = outputDir.resolve(prefix + "-public.json");

        writeJsonAtomic(privateFile, privateDoc);
        writeJsonAtomic(publicFile, publicDoc);
        if (nightly) {
            writeJsonAtomic(outputDir.resolve("latest-private.json"), privateDoc);
            writeJsonAtomic(outputDir.resolve("latest-public.json"), publicDoc);
            pruneOldSnapshots(date);
        }

        log.info("Economy snapshot complete: {} (Humans: {}, Bots: {}, Gini: H={} B={})",
                prefix, totalHumanMeso, totalBotMeso, humanGini, botGini);

        return new SnapshotResult(
                totalHumanMeso,
                totalBotMeso,
                humanGini,
                botGini,
                counterSnap.totalSources(),
                counterSnap.totalSinks(),
                privateFile,
                publicFile
        );
    }

    /**
     * History is kept (owner 2026-10-27): the dated {@code yyyy-mm-dd-public.json} / {@code -private.json} files are
     * never deleted by default; those older than {@code slums.history.gzipDays} (30) are gzipped to {@code .json.gz}.
     * {@code -Dslums.history.economyKeepDays=N} brings back deleting after N days. The latest and on-demand files are
     * not history and stay as they are. Returns the files deleted.
     */
    int pruneOldSnapshots(LocalDate newest) {
        return History.sweep(outputDir, DATED_SNAPSHOT, newest, History.gzipDays("economy"), History.keepDays("economy"))
                .deleted();
    }

    private static final java.util.regex.Pattern DATED_SNAPSHOT =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}-(public|private)\\.json");

    private Map<String, Object> computeMesoSupply(List<CharacterRecord> list) {
        long total = 0;
        Map<String, long[]> bands = new LinkedHashMap<>();
        bands.put("1-30", new long[2]);   // [count, totalMeso]
        bands.put("31-70", new long[2]);
        bands.put("71-120", new long[2]);
        bands.put("121-200", new long[2]);

        for (CharacterRecord r : list) {
            long meso = Math.max(0, r.meso());
            total += meso;
            String band = EconomyMath.levelBand(r.level());
            long[] b = bands.get(band);
            if (b != null) {
                b[0]++;
                b[1] += meso;
            }
        }

        Map<String, Object> bandsJson = new LinkedHashMap<>();
        for (var e : bands.entrySet()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("count", e.getValue()[0]);
            b.put("totalMeso", e.getValue()[1]);
            bandsJson.put(e.getKey(), b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", list.size());
        out.put("totalMeso", total);
        out.put("levelBands", bandsJson);
        return out;
    }

    private Map<String, Object> computeWealthDistribution(List<CharacterRecord> list) {
        Map<String, Object> out = new LinkedHashMap<>();
        int count = list.size();
        if (count == 0) {
            out.put("count", 0);
            out.put("total", 0L);
            out.put("mean", 0L);
            out.put("median", 0L);
            out.put("p25", 0L);
            out.put("p75", 0L);
            out.put("top1PercentShare", 0.0);
            out.put("top10PercentShare", 0.0);
            out.put("gini", 0.0);
            return out;
        }

        long[] mesos = new long[count];
        long sum = 0;
        for (int i = 0; i < count; i++) {
            long m = Math.max(0, list.get(i).meso());
            mesos[i] = m;
            sum += m;
        }
        Arrays.sort(mesos);

        out.put("count", count);
        out.put("total", sum);
        out.put("mean", Math.round((double) sum / count));
        out.put("median", EconomyMath.percentile(mesos, 0.5));
        out.put("p25", EconomyMath.percentile(mesos, 0.25));
        out.put("p75", EconomyMath.percentile(mesos, 0.75));
        out.put("top1PercentShare", Math.round(EconomyMath.topShare(mesos, 0.01) * 100.0) / 100.0);
        out.put("top10PercentShare", Math.round(EconomyMath.topShare(mesos, 0.10) * 100.0) / 100.0);
        out.put("gini", Math.round(EconomyMath.gini(mesos) * 1000.0) / 1000.0);
        return out;
    }

    private List<Map<String, Object>> computeTopHolders(List<CharacterRecord> humans, List<CharacterRecord> bots) {
        List<CharacterRecord> combined = new ArrayList<>(humans.size() + bots.size());
        combined.addAll(humans);
        combined.addAll(bots);
        combined.sort(Comparator.comparingLong(CharacterRecord::meso).reversed());

        int limit = Math.min(25, combined.size());
        List<Map<String, Object>> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            CharacterRecord r = combined.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("level", r.level());
            m.put("meso", r.meso());
            m.put("isBot", r.isBot());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> formatFlows(EconomyCounters.Snapshot snap) {
        return formatFlows(snap, false);
    }

    /** {@code audit}: the private file's flows, with the bot-to-bot trade transfers. */
    private Map<String, Object> formatFlows(EconomyCounters.Snapshot snap, boolean audit) {
        Map<String, Object> out = new LinkedHashMap<>();

        // Sources
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("mobDropsHuman", snap.mobMesoDropsHuman());
        sources.put("mobDropsBot", snap.mobMesoDropsBot());
        sources.put("mobDropsTotal", snap.totalMobMesoDrops());
        sources.put("npcSales", snap.npcSellMesos());
        sources.put("questRewards", snap.questRewardMesos());
        out.put("sources", sources);
        out.put("totalSources", snap.totalSources());

        // Sinks
        Map<String, Object> sinks = new LinkedHashMap<>();
        sinks.put("npcBuys", snap.npcBuyMesos());
        sinks.put("jobMasterFees", snap.jobMasterFees());
        sinks.put("npcFees", snap.npcFeeMesos());
        sinks.put("upkeep", snap.upkeepMesos());
        sinks.put("fmTax", snap.fmTaxMesos());
        out.put("sinks", sinks);
        out.put("totalSinks", snap.totalSinks());

        out.put("netMesoDelta", snap.netMesoDelta());

        // Transfers
        Map<String, Object> transfers = new LinkedHashMap<>();
        transfers.put("trades", snap.tradeMesos());
        transfers.put("fmSales", snap.fmSaleMesos());
        if (audit) {
            transfers.put("botToBot", snap.botTransferMesos());
        }
        out.put("transfers", transfers);

        // Currencies
        Map<String, Object> mapleLeaf = new LinkedHashMap<>();
        mapleLeaf.put("gained", snap.mapleLeafGained());
        mapleLeaf.put("spent", snap.mapleLeafSpent());
        mapleLeaf.put("net", snap.mapleLeafNet());
        out.put("mapleLeaf", mapleLeaf);

        Map<String, Object> nx = new LinkedHashMap<>();
        nx.put("earned", snap.nxEarned());
        nx.put("spent", snap.nxSpent());
        nx.put("net", snap.nxNet());
        out.put("nx", nx);

        return out;
    }

    private void writeJsonAtomic(Path file, Object json) throws IOException {
        String content = Json.write(json);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Files.createTempFile(parent, "." + file.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ---- Live Data Loaders ----

    private List<CharacterRecord> loadHumans() {
        Map<Integer, CharacterRecord> map = new HashMap<>();

        // 1. Database query
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT id, level, meso FROM characters WHERE world = ? AND gm < 2"
                             + " AND name NOT LIKE 'autoqa%'")) { // 1.6.0: no pretend players or observers
            ps.setInt(1, DEFAULT_WORLD_ID);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int level = rs.getInt("level");
                    long meso = rs.getLong("meso");
                    map.put(id, new CharacterRecord(id, level, meso, false));
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("DB query for characters failed (may be offline / test): {}", e.getMessage());
        }

        // 2. Online override from memory
        try {
            World world = Server.getInstance().getWorld(DEFAULT_WORLD_ID);
            if (world != null) {
                for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                    if (chr != null && !BotHelpers.isBot(chr) && !(chr.getClient() instanceof client.SyntheticClient)) {
                        map.put(chr.getId(), new CharacterRecord(chr.getId(), chr.getLevel(), chr.getMeso(), false));
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }

        return new ArrayList<>(map.values());
    }

    private List<CharacterRecord> loadBots() {
        Map<Integer, CharacterRecord> map = new HashMap<>();

        // 1. Database query
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT BotId, Level, Meso FROM botstore")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int botId = rs.getInt("BotId");
                    int charId = BOT_ID_BASE + botId;
                    int level = rs.getInt("Level");
                    long meso = rs.getLong("Meso");
                    map.put(charId, new CharacterRecord(charId, level, meso, true));
                }
            }
        } catch (SQLException | RuntimeException e) {
            log.debug("DB query for botstore failed (may be offline / test): {}", e.getMessage());
        }

        // 2. Online override from memory
        try {
            for (BotSM sm : CharacterStorage.getAllBots().values()) {
                Character chr = sm == null ? null : sm.getChr();
                if (chr != null) {
                    map.put(chr.getId(), new CharacterRecord(chr.getId(), chr.getLevel(), chr.getMeso(), true));
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }

        return new ArrayList<>(map.values());
    }

    private Map<Integer, List<Long>> loadItemPrices() {
        Map<Integer, List<Long>> samples = new HashMap<>();

        // 1. Active Free Market shops
        try {
            World world = Server.getInstance().getWorld(DEFAULT_WORLD_ID);
            if (world != null) {
                for (HiredMerchant hm : world.getActiveMerchants()) {
                    if (hm != null) {
                        for (PlayerShopItem psi : hm.getItems()) {
                            if (psi != null && psi.isExist() && psi.getItem() != null) {
                                int id = psi.getItem().getItemId();
                                long price = psi.getPrice();
                                int qty = Math.max(1, (int) psi.getItem().getQuantity());
                                samples.computeIfAbsent(id, k -> new ArrayList<>()).add(price / qty);
                            }
                        }
                    }
                }
                for (PlayerShop ps : world.getActivePlayerShops()) {
                    if (ps != null) {
                        for (PlayerShopItem psi : ps.getItems()) {
                            if (psi != null && psi.isExist() && psi.getItem() != null) {
                                int id = psi.getItem().getItemId();
                                long price = psi.getPrice();
                                int qty = Math.max(1, (int) psi.getItem().getQuantity());
                                samples.computeIfAbsent(id, k -> new ArrayList<>()).add(price / qty);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }

        // 2. Samples from PriceBook
        try {
            for (int itemId : PriceBook.trackedItemIds()) {
                var list = PriceBook.recentSamples(itemId);
                for (var s : list) {
                    if (s != null && s.unitPrice() > 0) {
                        samples.computeIfAbsent(itemId, k -> new ArrayList<>()).add(s.unitPrice());
                    }
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }

        return samples;
    }
}
