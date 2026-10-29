package soloMapling.companions;

import client.Character;
import config.YamlConfig;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Ticket 13: one lifecycle reservation per real character id, independent of the population roster.
 * Reserve under the monitor, do I/O outside it: login fails immediately during a load/save rather than
 * reading a stale inventory. Login holds its reservation until PlayerLoggedinHandler finishes registering.
 * Inspired by nutnnut/Cosmic 2608eba61, 535152753, e9b063ea2 and its double-register race fix.
 */
public final class OwnedAltService {
    public static final int CAP = 5;
    private static final OwnedAltService INSTANCE = new OwnedAltService(new OwnedAltBackend(),
            () -> YamlConfig.config.server.OWNED_ALTS_ENABLED, () -> YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED);
    /** 1.6.0.4: what @botme says while OWNED_ALTS_SUMMON_ENABLED is off (alts are half-built: no fighting yet). */
    public static final String RESTING = "Alts are resting while we finish them - they'll be back soon.";
    public static OwnedAltService getInstance() { return INSTANCE; }

    public record Candidate(int id, int accountId, int world, String name) {}
    interface Backend {
        List<Candidate> list(Character owner);
        boolean online(int id);
        Character load(Candidate row, Character owner);
        void attach(Character owner, Character alt);
        void save(Character alt);
        void detach(Character alt);
        Set<Integer> companions(int ownerId);
    }
    private enum State { LOADING, ACTIVE, SAVING, FAILED, LOGIN }
    private static final class Entry {
        final Character owner;
        Character alt;
        State state;
        boolean saved;
        Entry(Character owner, State state) { this.owner = owner; this.state = state; }
    }
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final Set<Character> closedOwners = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Integer, Integer> friendReservations = new HashMap<>();
    private final Backend backend;
    private final BooleanSupplier enabled;
    private final BooleanSupplier summonEnabled;

    OwnedAltService(Backend backend, BooleanSupplier enabled) {
        this(backend, enabled, () -> true);
    }
    OwnedAltService(Backend backend, BooleanSupplier enabled, BooleanSupplier summonEnabled) {
        this.backend = backend;
        this.enabled = enabled;
        this.summonEnabled = summonEnabled;
    }
    /** 1.6.0.4: false while OWNED_ALTS_SUMMON_ENABLED is off - @botme answers {@link #RESTING}; @dismiss still works. */
    public boolean summoningOpen() { return summonEnabled.getAsBoolean(); }
    private void requireSummoning() {
        requireEnabled();
        if (!summonEnabled.getAsBoolean()) throw new IllegalStateException(RESTING);
    }
    private void requireEnabled() {
        if (!enabled.getAsBoolean()) throw new IllegalStateException("Alt companions are not available yet.");
    }
    private boolean eligible(Character owner, Candidate row) {
        return row.accountId == owner.getAccountID() && row.world == owner.getWorld()
                && row.id > 0 && row.id < 1_000_000_000 && row.id != owner.getId();
    }
    private synchronized boolean available(int id) {
        return !entries.containsKey(id) && !backend.online(id);
    }
    public List<Candidate> list(Character owner) {
        requireEnabled();
        return backend.list(owner).stream().filter(row -> eligible(owner, row))
                .filter(row -> available(row.id)).toList();
    }
    private Set<Integer> occupied(int ownerId) {
        Set<Integer> ids = new HashSet<>(backend.companions(ownerId));
        friendReservations.forEach((id, leader) -> { if (leader == ownerId) ids.add(id); });
        entries.forEach((id, entry) -> {
            if (entry.owner != null && entry.owner.getId() == ownerId) ids.add(id);
        });
        return ids;
    }
    /** Shared with friend recruitment, including accepts not yet converted into followers. */
    public synchronized boolean hasCompanionRoom(int ownerId) { return occupied(ownerId).size() < CAP; }
    public synchronized boolean reserveCompanion(int ownerId, int companionId) {
        Integer reservedFor = friendReservations.get(companionId);
        if (reservedFor != null && reservedFor != ownerId) return false;
        Set<Integer> ids = occupied(ownerId);
        if (!ids.contains(companionId) && ids.size() >= CAP) return false;
        friendReservations.put(companionId, ownerId);
        return true;
    }
    public synchronized void releaseCompanion(int id) { friendReservations.remove(id); }

    /** Snap the owner's position down onto the foothold below, so the alt stands instead of floating mid-jump. */
    static java.awt.Point groundedSpawn(server.maps.MapleMap map, java.awt.Point ownerPos) {
        java.awt.Point ground = map.getGroundBelow(ownerPos);
        return ground != null ? ground : new java.awt.Point(ownerPos);
    }

    /** v83 stance is (pose << 1) | facing and pose 2 is standing (0 is no pose): stand, facing like the owner. */
    static int standingStance(int ownerStance) {
        return 4 | (ownerStance & 1);
    }

    public Character summon(Character owner, String name) {
        requireSummoning();
        if (owner.isOwnedAlt() || owner.isBot())
            throw new IllegalStateException("Only your logged-in character can call an alt.");
        Candidate row = backend.list(owner).stream().filter(c -> c.name.equalsIgnoreCase(name))
                .filter(c -> eligible(owner, c)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Choose another character on this account and world."));
        Entry entry = new Entry(owner, State.LOADING);
        synchronized (this) {
            if (closedOwners.contains(owner) || !owner.isLoggedin())
                throw new IllegalStateException("Your session is closing. Please log in again.");
            if (entries.containsKey(row.id) || backend.online(row.id))
                throw new IllegalStateException(row.name + " is already online or still being saved.");
            if (occupied(owner.getId()).size() >= CAP)
                throw new IllegalStateException("You can have up to five companions at once.");
            entries.put(row.id, entry);
        }
        try {
            Character alt = backend.load(row, owner);
            entry.alt = alt;
            if (alt.getAccountID() != owner.getAccountID() || alt.getWorld() != owner.getWorld()
                    || alt.getId() != row.id) throw new IllegalStateException("That character is no longer eligible.");
            synchronized (this) {
                if (closedOwners.contains(owner) || !owner.isLoggedin())
                    throw new IllegalStateException("Your session has closed.");
                // Attach is short, with no character DB load or save, and cannot race owner teardown.
                entry.alt = alt;
                backend.attach(owner, alt);
                entry.state = State.ACTIVE;
                audit(entry, "loaded alt=" + row.id);
            }
            return alt;
        } catch (RuntimeException e) {
            synchronized (this) { entry.state = State.SAVING; }
            try {
                if (entry.alt != null) backend.detach(entry.alt);
            } catch (RuntimeException cleanup) {
                synchronized (this) { entry.state = State.FAILED; }
                cleanup.addSuppressed(e);
                throw new IllegalStateException("Unable to finish closing that character. Please contact a GM.", cleanup);
            }
            synchronized (this) { entries.remove(row.id, entry); }
            throw e;
        }
    }
    private boolean ownedBy(Entry entry, Character owner) {
        return entry.owner != null && entry.owner.getId() == owner.getId()
                && entry.owner.getAccountID() == owner.getAccountID() && entry.owner.getWorld() == owner.getWorld();
    }
    /** Headless disconnects (including shutdown) must use the same final-save reservation as owner logout. */
    public void disconnectAlt(Character alt) {
        synchronized (this) {
            Entry entry = entries.get(alt.getId());
            if (entry == null || entry.alt != alt) return;
        }
        finish(alt.getId(), false);
    }
    public boolean dismiss(Character owner, String name) {
        int id;
        synchronized (this) {
            id = entries.entrySet().stream().filter(e -> ownedBy(e.getValue(), owner))
                    .filter(e -> e.getValue().alt != null && e.getValue().alt.getName().equalsIgnoreCase(name))
                    .map(Map.Entry::getKey).findFirst().orElse(-1);
        }
        if (id == -1) return false;
        finish(id, false);
        return true;
    }
    public int dismissAll(Character owner, boolean closing) {
        List<Integer> ids;
        synchronized (this) {
            if (closing) closedOwners.add(owner);
            ids = entries.entrySet().stream().filter(e -> ownedBy(e.getValue(), owner))
                    .filter(e -> e.getValue().state != State.LOADING).map(Map.Entry::getKey).toList();
        }
        RuntimeException failure = null;
        int count = 0;
        for (int id : ids) {
            try { finish(id, false); count++; }
            catch (RuntimeException e) { failure = e; }
        }
        if (failure != null) throw failure;
        return count;
    }
    /** Authentication/transition validation must happen before calling this. */
    public LoginLease beginLogin(int id) {
        finish(id, true);
        return new LoginLease(id);
    }
    private void audit(Entry entry, String message) {
        if (entry.owner != null && soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.OWNED_ALT)) {
            soloMapling.qa.QaLog.owner(soloMapling.qa.QaLog.Topic.OWNED_ALT, entry.owner.getId(), message);
        }
    }
    private void finish(int id, boolean login) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(id);
            if (entry == null) {
                if (login) entries.put(id, new Entry(null, State.LOGIN));
                return;
            }
            if (entry.state != State.ACTIVE && !(entry.state == State.FAILED && !login))
                throw new IllegalStateException("That character is still being loaded or saved. Please try again shortly.");
            entry.state = State.SAVING;
        }
        try {
            if (!entry.saved) {
                backend.save(entry.alt);
                entry.saved = true;
                audit(entry, "save committed alt=" + id);
            }
            backend.detach(entry.alt);
            audit(entry, "removed alt=" + id + " takeover=" + login);
            synchronized (this) {
                if (login) entries.put(id, new Entry(null, State.LOGIN));
                else entries.remove(id, entry);
            }
        } catch (RuntimeException e) {
            synchronized (this) { entry.state = State.FAILED; }
            audit(entry, "save/cleanup failed alt=" + id + "; login blocked");
            throw new IllegalStateException("Unable to finish saving that character. Please contact a GM before logging into it.", e);
        }
    }
    public final class LoginLease implements AutoCloseable {
        private final int id;
        private boolean closed;
        private LoginLease(int id) { this.id = id; }
        @Override public void close() {
            synchronized (OwnedAltService.this) {
                if (!closed) {
                    Entry entry = entries.get(id);
                    if (entry != null && entry.state == State.LOGIN) entries.remove(id);
                    closed = true;
                }
            }
        }
    }
}
