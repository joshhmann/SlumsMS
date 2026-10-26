package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.server.Json;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.0 S1: the durable home of every bot's {@link LifeMemory} (Bartle seed, seven-channel seed, bounded delta, goals,
 * life events), table {@code botlifeprofile} (migration 028), one row per roster slot.
 *
 * <p>Before this, the memory rode inside {@code botroster.ActivityState.life}, which only {@code TrainingBot} wrote:
 * a SOCIAL or TOWN session's snapshot is an empty map, so the next roster write wiped what the bot had learned. Now
 * the roster's session sweep ({@link BotRoster#recordSessions}) writes the dirty memories of every member it records,
 * whatever its bot type, in one batch, and before its own batch, so v2 is durable before the roster row stops
 * carrying v1.
 *
 * <p>One memory per character lives here while the server runs (about 1 KB each, bounded by the roster), so a bot
 * keeps the same memory across bot types and sessions. Rows are read once, all together, the first time a roster
 * member's memory is asked for, like the roster itself.
 *
 * <p>Without migration 028 (the table is missing) nothing breaks: the memory is written back into
 * {@code ActivityState.life} for every bot type instead, which also fixes the wipe, and read from there.
 */
public final class LifeMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(LifeMemoryStore.class);
    /** The key under {@code botroster.ActivityState} that held v1 (and holds v2 when the table is missing). */
    public static final String STATE_KEY = "life";

    public static final String SELECT_ALL = "SELECT BotId, Profile FROM botlifeprofile";
    public static final String SELECT_ONE = "SELECT Profile FROM botlifeprofile WHERE BotId = ?";
    public static final String UPSERT = "INSERT INTO botlifeprofile (BotId, Version, Profile, UpdatedAt) VALUES (?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE Version = VALUES(Version), Profile = VALUES(Profile), "
            + "UpdatedAt = VALUES(UpdatedAt)";
    /** After a failed read, how long the store runs on the ActivityState fallback before trying the table again. */
    static final long RETRY_MS = 10 * 60_000L;

    private static final Object LOAD_LOCK = new Object();
    /** The memory of every character asked about this run. */
    private static final Map<Integer, LifeMemory> memories = new ConcurrentHashMap<>();
    /** Rows read from the table and not yet turned into a memory (removed once parsed). */
    private static final Map<Integer, String> rows = new ConcurrentHashMap<>();
    private static volatile boolean loaded, available;
    private static volatile long retryAt;
    private static volatile Connections connectionSource;

    /** Where connections come from (the real pool, or a mock in tests). */
    @FunctionalInterface
    public interface Connections {
        Connection get() throws SQLException;
    }

    private LifeMemoryStore() {
    }

    /** Whether the table answered (migration 028 applied); false means the ActivityState fallback is in use. */
    public static boolean available() {
        ensureLoaded();
        return available;
    }

    /** This character's memory, loaded or made on first use and the same object for the rest of the run. */
    public static LifeMemory of(int characterId) {
        LifeMemory known = memories.get(characterId);
        if (known != null) {
            return known;
        }
        // Built outside the map (load may read the table once); the first one in wins and consumes the row.
        LifeMemory made = load(characterId);
        LifeMemory raced = memories.putIfAbsent(characterId, made);
        if (raced != null) {
            return raced;
        }
        rows.remove(characterId - BotStore.BOT_ID_BASE);
        return made;
    }

    /** Memory-only: this character's memory if something already asked for it, else null. Never touches the DB. */
    public static LifeMemory peek(int characterId) {
        return memories.get(characterId);
    }

    private static LifeMemory load(int characterId) {
        int slot = characterId - BotStore.BOT_ID_BASE;
        BotRoster.Member member = characterId >= BotStore.BOT_ID_BASE ? BotRoster.loadedMember(slot) : null;
        if (member == null) {
            // Staff, on-demand bots and players: a seeded memory that lives for the run (as before 1.6.0 S1).
            return new LifeMemory(characterId, null);
        }
        ensureLoaded();
        String row = rows.get(slot);
        LifeMemory stored = row == null ? null : parse(characterId, row);
        BotRoster.Activity last = member.lastActivity();
        Object inState = last == null ? null : last.state().get(STATE_KEY);
        LifeMemory fromState = inState instanceof Map<?, ?> ? new LifeMemory(characterId, inState) : null;
        if (stored != null && stored.restored()
                && (fromState == null || !fromState.restored() || stored.savedAt() >= fromState.savedAt())) {
            return stored;
        }
        if (fromState != null && fromState.restored()) {
            // v1 from the sprint (migrated() is set), or a v2 the fallback wrote while the table was missing.
            fromState.markDirty();
            return fromState;
        }
        return new LifeMemory(characterId, null);
    }

    private static LifeMemory parse(int characterId, String json) {
        try {
            return new LifeMemory(characterId, Json.parse(json));
        } catch (IllegalArgumentException unreadable) {
            log.warn("Ignoring an unreadable life profile for bot {}", characterId);
            return null;
        }
    }

    private static void ensureLoaded() {
        if (loaded && (available || System.currentTimeMillis() < retryAt)) {
            return;
        }
        synchronized (LOAD_LOCK) {
            if (loaded && (available || System.currentTimeMillis() < retryAt)) {
                return;
            }
            // Straight to the pool, as the roster does: a spawning bot may be the first to ask (see BotDbGate).
            Connections source = connectionSource;
            Map<Integer, String> read = new java.util.HashMap<>();
            try (Connection con = source != null ? source.get() : DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(SELECT_ALL);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    read.put(rs.getInt("BotId"), rs.getString("Profile"));
                }
                read.forEach((slot, json) -> {
                    if (!memories.containsKey(BotStore.BOT_ID_BASE + slot)) rows.putIfAbsent(slot, json);
                });
                if (!available) {
                    log.info("Bot life profiles: {} saved", read.size());
                }
                available = true;
            } catch (SQLException | RuntimeException e) {
                if (!loaded || available) {
                    log.warn("Bot life profiles unavailable (migration 028 not applied?); keeping them in the roster's"
                            + " activity state until the table answers: {}", e.getMessage());
                }
                available = false;
                retryAt = System.currentTimeMillis() + RETRY_MS;
            }
            loaded = true;
        }
    }

    /** One member's memory as the session sweep sees it: what to write now (null = unchanged) and the memory. */
    public record Pending(int botId, LifeMemory memory, Map<String, Object> save) {
    }

    /**
     * The memory a roster member's session record carries, or null when there is none: the one in use this run, or
     * a v1 copy still in the member's stored activity state (migrated here, so a bot that never ran the brain this
     * run keeps its sprint drift). Called by the roster sweep, never per tick.
     */
    public static Pending prepare(int characterId, BotRoster.Member member, long now) {
        LifeMemory memory = peek(characterId);
        if (memory == null) {
            BotRoster.Activity last = member == null ? null : member.lastActivity();
            if (last == null || !(last.state().get(STATE_KEY) instanceof Map<?, ?>)) {
                return null;
            }
            memory = of(characterId);
        }
        if (memory.pristine()) {
            return null; // the plain seed: no row, no ActivityState copy (live runs with the brain off)
        }
        return new Pending(characterId - BotStore.BOT_ID_BASE, memory, memory.takeDirty(now));
    }

    /**
     * Writes the changed memories in one batch. True when every one is durable in the table (also when there was
     * nothing to write and the table is there); false sends the caller to the ActivityState fallback and keeps the
     * memories dirty for the next sweep.
     */
    public static boolean write(Collection<Pending> pending, long now) {
        if (!available()) {
            pending.forEach(p -> { if (p.save() != null) p.memory().markDirty(); });
            return false;
        }
        List<Pending> changed = new ArrayList<>();
        for (Pending p : pending) {
            if (p.save() != null) changed.add(p);
        }
        if (changed.isEmpty()) {
            return true;
        }
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(UPSERT)) {
            for (Pending p : changed) {
                ps.setInt(1, p.botId());
                ps.setInt(2, LifeMemory.VERSION);
                ps.setString(3, Json.write(p.save()));
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
            return true;
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not write the life profiles of {} bots; keeping them in the roster row this time",
                    changed.size(), e);
            changed.forEach(p -> p.memory().markDirty());
            return false;
        }
    }

    /**
     * The activity state the roster row gets: with the table, {@code life} is left out (it lives in the table); without
     * it, or when this sweep's table write failed, the memory goes in under {@code life} so no bot type wipes it.
     */
    public static BotRoster.Activity attach(BotRoster.Activity activity, Pending pending, boolean stored, long now) {
        if (pending == null) {
            return activity;
        }
        Map<String, Object> state = new LinkedHashMap<>(activity.state());
        if (stored) {
            state.remove(STATE_KEY);
        } else {
            state.put(STATE_KEY, pending.save() != null ? pending.save() : pending.memory().save(now));
        }
        return new BotRoster.Activity(activity.name(), activity.homeMapId(), state);
    }

    /** {@code !qa bl-4}: reads one slot's row straight from the table (no cache), or null when there is none. */
    public static String readRow(int botId) throws SQLException {
        try (Connection con = connections().get(); PreparedStatement ps = con.prepareStatement(SELECT_ONE)) {
            ps.setInt(1, botId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("Profile") : null;
            }
        }
    }

    private static Connections connections() {
        Connections source = connectionSource;
        return source != null ? source : soloMapling.ArtificialPlayer.BotDbGate::connection;
    }

    /** How many memories this run holds (GM read-outs, tests). */
    public static int size() {
        return memories.size();
    }

    /** Test seam: a mock pool (null restores the real one); forgets every memory and row. */
    public static void useConnectionSourceForTest(Connections source) {
        synchronized (LOAD_LOCK) {
            connectionSource = source;
            memories.clear();
            rows.clear();
            loaded = false;
            available = false;
            retryAt = 0;
        }
    }
}
