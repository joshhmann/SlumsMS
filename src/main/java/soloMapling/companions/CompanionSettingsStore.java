package soloMapling.companions;

// Ported from https://github.com/Incluwin/BotStory.git
//   src/main/java/server/living/roster/CompanionSettingsApplier.java (settings(ownerId, personaId): in memory, else
//   read once, else the defaults) @ 81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364
// and from https://github.com/nutnnut/Cosmic.git
//   src/main/java/server/bots/BotPrefsStore.java (a small per-bot prefs table, upsert on change) @ b684bf7
// changed: our own migration 069 instead of a self-creating table (AGENTS.md), BotStory's JSON settings columns split
// into one table keyed (OwnerId, CompanionId), the read is lazy - on the first player-facing use, or (Bots' audit) on a
// bot's first settings read after a restart, queued off the tick's thread - rather than a boot hook, writes go through BotDbGate and SerialWrites the
// way SocialGate's do, and the in-memory side is indexed by companion so a bot's tick can read it without a query.

import client.Character;
import soloMapling.ArtificialPlayer.BotDbGate;
import soloMapling.ArtificialPlayer.SerialWrites;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;
import soloMapling.server.ExecutorServiceManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 1.6.7 alt-qol-batch group B: the store behind {@link CompanionSettings} - one JSON row per (owner, companion) in
 * {@code companionsettings} (migration 069), read once and written only on a change, always off the game thread.
 *
 * <p>Shaped like {@code SocialGateStore} + {@code SocialGate}: {@link Store} is the seam ({@link Jdbc} in the server,
 * {@link Memory} in tests), and the static methods are the state the shared layer reads. A bot's tick only reads the
 * in-memory map (never the database); the player-facing paths - the card, a chat toggle, {@code @alts} - do the one
 * read. With no migration the server still runs: the store logs once and keeps the settings in memory until a
 * restart.
 *
 * <p>The entry is keyed by companion, because the gate in {@code BotPartySupport} / {@code BotBuffDriver} /
 * {@code BotVitals} holds only the bot. An owned alt has exactly one owner, so the pair is still what is stored; a
 * party regular (settings regulars switch on) has no owner link, so its row is the one its owner last wrote.
 */
public final class CompanionSettingsStore {

    /** One stored companion's settings. */
    public record Row(int ownerId, int companionId, CompanionSettings settings) {
    }

    /** Everything stored. {@code missing} when migration 069 is not applied. */
    public record Snapshot(List<Row> rows, boolean missing) {
    }

    /** The storage seam: the table in the server, a map in tests. */
    public interface Store {
        Snapshot loadAll();

        void save(Row row, long now);

        void delete(int ownerId, int companionId);
    }

    /** The real store: the server's database through the bots' share of the pool. */
    public static final class Jdbc implements Store {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Jdbc.class);

        static final String ALL = "SELECT OwnerId, CompanionId, SettingsJson FROM companionsettings";
        static final String UPSERT = "INSERT INTO companionsettings (OwnerId, CompanionId, SettingsJson, UpdatedAt)"
                + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE SettingsJson = VALUES(SettingsJson),"
                + " UpdatedAt = VALUES(UpdatedAt)";
        static final String DELETE = "DELETE FROM companionsettings WHERE OwnerId = ? AND CompanionId = ?";

        private volatile boolean warned;

        private void failed(SQLException e) {
            if (BotDbGate.refused(e)) {
                log.warn("A companion setting was not stored: the bots' share of the database pool stayed busy ({})",
                        e.getMessage());
                return;
            }
            if (!warned) {
                warned = true;
                log.warn("Companion settings are not stored (is migration 069-companion-settings applied?); keeping"
                        + " them in memory", e);
            } else {
                log.debug("Companion settings store", e);
            }
        }

        static boolean noTable(SQLException e) {
            return e.getErrorCode() == 1146 || "42S02".equals(e.getSQLState());
        }

        @Override
        public Snapshot loadAll() {
            List<Row> rows = new ArrayList<>();
            try (Connection con = BotDbGate.connection();
                 PreparedStatement ps = con.prepareStatement(ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getInt(1), rs.getInt(2), CompanionSettings.parse(rs.getString(3))));
                }
                return new Snapshot(rows, false);
            } catch (SQLException e) {
                failed(e);
                return noTable(e) ? new Snapshot(List.of(), true) : null;
            }
        }

        @Override
        public void save(Row row, long now) {
            try {
                BotDbGate.write(() -> {
                    try (Connection con = BotDbGate.connection();
                         PreparedStatement ps = con.prepareStatement(UPSERT)) {
                        ps.setInt(1, row.ownerId());
                        ps.setInt(2, row.companionId());
                        ps.setString(3, row.settings().toJson());
                        ps.setLong(4, now);
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException e) {
                failed(e);
            }
        }

        @Override
        public void delete(int ownerId, int companionId) {
            try {
                BotDbGate.write(() -> {
                    try (Connection con = BotDbGate.connection();
                         PreparedStatement ps = con.prepareStatement(DELETE)) {
                        ps.setInt(1, ownerId);
                        ps.setInt(2, companionId);
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (SQLException e) {
                failed(e);
            }
        }
    }

    /** Tests: a store in memory that outlives the settings' own memory (a "restart" is a reload from it). */
    public static final class Memory implements Store {
        final Map<Long, Row> rows = new ConcurrentHashMap<>();
        volatile boolean failing;

        static long key(int ownerId, int companionId) {
            return ((long) ownerId << 32) | (companionId & 0xffffffffL);
        }

        @Override
        public Snapshot loadAll() {
            return failing ? null : new Snapshot(List.copyOf(rows.values()), false);
        }

        @Override
        public void save(Row row, long now) {
            rows.put(key(row.ownerId(), row.companionId()), row);
        }

        @Override
        public void delete(int ownerId, int companionId) {
            rows.remove(key(ownerId, companionId));
        }
    }

    // ── the state the shared layer reads ──

    private record Entry(int ownerId, CompanionSettings settings) {
    }

    private static final Map<Integer, Entry> byCompanion = new ConcurrentHashMap<>();
    /** Companions whose settings changed before the read landed (memory wins over the stored row). */
    private static final Map<Integer, Boolean> touchedBeforeLoad = new ConcurrentHashMap<>();

    private static volatile Store store = new Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runBotDb;
    private static final SerialWrites writes = new SerialWrites(r -> writer.execute(r));
    private static volatile boolean loaded;
    private static volatile boolean tablesMissing;
    private static volatile long loadTriedAt;

    private CompanionSettingsStore() {
    }

    // ── what the bots read (memory only: no query in a tick) ──

    /**
     * The stored settings of this companion, whatever the {@link PartyCompanionPolicy.Feature#SETTINGS} switch says.
     * Only for a field another feature owns (group D's loot mode, gated on {@code LOOT}): every behaviour the
     * settings switch itself gates goes through {@link #of}.
     */
    public static CompanionSettings stored(Character companion) {
        if (companion == null) {
            return CompanionSettings.DEFAULT;
        }
        if (!loaded) {
            requestLoad();
        }
        Entry entry = byCompanion.get(companion.getId());
        return entry == null ? CompanionSettings.DEFAULT : entry.settings();
    }

    /** The companion's settings, or the defaults - also the defaults when the feature is off for it. */
    public static CompanionSettings of(Character companion) {
        if (!PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.SETTINGS, companion)) {
            return CompanionSettings.DEFAULT;
        }
        if (!loaded) {
            requestLoad(); // after a restart the bots' own read starts the load; never waits for it
        }
        Entry entry = byCompanion.get(companion.getId());
        return entry == null ? CompanionSettings.DEFAULT : entry.settings();
    }

    /** One queued load at a time, so a bot tick never floods the db executor (Bots' audit, 8 Nov 2026). */
    private static final java.util.concurrent.atomic.AtomicBoolean loadQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Runs the load off the tick's thread: the bot-db executor in game, the caller's in tests. */
    private static volatile Executor loader = ExecutorServiceManager::runBotDb;

    private static void requestLoad() {
        long tried = loadTriedAt;
        if (tried > 0 && System.currentTimeMillis() - tried < 60_000L) {
            return; // a failed read is retried at most once a minute, as ensureLoaded does
        }
        if (loadQueued.compareAndSet(false, true)) {
            try {
                loader.execute(() -> {
                    try {
                        ensureLoaded();
                    } finally {
                        loadQueued.set(false);
                    }
                });
            } catch (RuntimeException e) {
                loadQueued.set(false);
            }
        }
    }

    /** Whether this companion heals the party ({@link #of}). */
    public static boolean heals(Character companion) {
        return of(companion).heals();
    }

    /** Whether this companion gives its party buffs ({@link #of}). */
    public static boolean support(Character companion) {
        return of(companion).support();
    }

    /** Whether this companion drinks its buff potions ({@link #of}). */
    public static boolean buffPots(Character companion) {
        return of(companion).buffPots();
    }

    /** The owner's HP potion line as a percentage, or 0 for the companion's own ({@link #of}). */
    public static int hpPercent(Character companion) {
        return of(companion).hpPercent();
    }

    /** The owner's MP potion line as a percentage, or 0 for the companion's own ({@link #of}). */
    public static int mpPercent(Character companion) {
        return of(companion).mpPercent();
    }

    // ── what the player's path reads and writes ──

    /** The stored settings for this companion, reading them on first use (the card, a toggle, {@code @alts}). */
    public static CompanionSettings get(Character companion) {
        loadNow();
        Entry entry = companion == null ? null : byCompanion.get(companion.getId());
        return entry == null ? CompanionSettings.DEFAULT : entry.settings();
    }

    /** The stored settings for exactly this pair (an owner looking at its alt). */
    public static CompanionSettings get(int ownerId, int companionId) {
        loadNow();
        Entry entry = byCompanion.get(companionId);
        return entry != null && entry.ownerId() == ownerId ? entry.settings() : CompanionSettings.DEFAULT;
    }

    /**
     * Saves one companion's settings and answers the settings now in force. A change back to the defaults deletes the
     * row ("default not written"), so an untouched companion has none.
     */
    public static CompanionSettings save(Character owner, Character companion, CompanionSettings settings) {
        if (owner == null || companion == null || settings == null) {
            return CompanionSettings.DEFAULT;
        }
        loadNow();
        CompanionSettings next = settings.clamped();
        int ownerId = owner.getId();
        int companionId = companion.getId();
        if (!loaded) {
            touchedBeforeLoad.put(companionId, Boolean.TRUE);
        }
        if (next.isDefault()) {
            byCompanion.remove(companionId);
        } else {
            byCompanion.put(companionId, new Entry(ownerId, next));
        }
        Store s = store;
        if (tablesMissing || s == null) {
            return next; // no table: memory until a restart, as the gate's stores do
        }
        long now = System.currentTimeMillis();
        if (next.isDefault()) {
            writes.submit(companionId, () -> s.delete(ownerId, companionId));
        } else {
            Row row = new Row(ownerId, companionId, next);
            writes.submit(companionId, () -> s.save(row, now));
        }
        return next;
    }

    /**
     * Reads the stored settings once, if they have not been read yet. Called from the player's path only: a bot's
     * tick never waits for the database. Runs on a bot-db thread, because the gate refuses a virtual thread.
     */
    public static void loadNow() {
        if (loaded) {
            return;
        }
        ExecutorServiceManager.callBotDb(CompanionSettingsStore::ensureLoaded);
    }

    /** The same, off the caller's thread: {@code @alts} starts it so later orders see the stored settings. */
    public static void startLoading() {
        if (!loaded) {
            ExecutorServiceManager.runBotDb(CompanionSettingsStore::ensureLoaded);
        }
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (CompanionSettingsStore.class) {
            long now = System.currentTimeMillis();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < 60_000L)) {
                return;
            }
            loadTriedAt = now;
            Store s = store;
            Snapshot snap;
            try {
                snap = s == null ? null : s.loadAll();
            } catch (RuntimeException e) {
                snap = null;
            }
            if (snap == null) {
                return; // tried again on the next player-facing use, at most once a minute
            }
            tablesMissing = snap.missing();
            for (Row row : snap.rows()) {
                if (touchedBeforeLoad.containsKey(row.companionId()) || row.settings().isDefault()) {
                    continue;
                }
                byCompanion.put(row.companionId(), new Entry(row.ownerId(), row.settings()));
            }
            loaded = true;
            touchedBeforeLoad.clear();
        }
    }

    // ── test seams ──

    /** Tests: a store and a writer (nulls restore the real ones); a test store is read at once. */
    public static void useForTest(Store testStore, Executor testWriter) {
        store = testStore != null ? testStore : new Jdbc();
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runBotDb;
        loader = testWriter != null ? testWriter : ExecutorServiceManager::runBotDb;
        forgetForTest();
        if (testStore != null) {
            ensureLoaded();
        }
    }

    /** Tests: a restart - memory is forgotten and read back from the store. */
    public static void reloadForTest() {
        forgetForTest();
        ensureLoaded();
    }

    /** Tests: forget the loaded settings and the failed-read timer. */
    public static void forgetForTest() {
        byCompanion.clear();
        touchedBeforeLoad.clear();
        loaded = false;
        tablesMissing = false;
        loadTriedAt = 0;
    }
}
