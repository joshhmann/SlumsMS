package soloMapling.companions;

import client.Character;
import config.YamlConfig;

import java.util.*;
import java.util.concurrent.Executor;
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
            () -> YamlConfig.config.server.OWNED_ALTS_ENABLED, () -> YamlConfig.config.server.OWNED_ALTS_SUMMON_ENABLED,
            r -> server.ThreadManager.getInstance().newTask(r));
    /** 1.6.0.4: what @botme says while OWNED_ALTS_SUMMON_ENABLED is off (alts are half-built: no fighting yet). */
    public static final String RESTING = "Alts are resting while we finish them - they'll be back soon.";
    /** 1.6.0.4: said on arrival after a channel change sent the owner's alts home. */
    public static final String WENT_HOME = "Your alts went home. Changing channel sends them home (saved); call them again with @botme <name>.";
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
        /** 1.6.0.4 @botme here: put the alt down beside its owner (same channel, no instance) and follow again. */
        default void bringHere(Character owner, Character alt) {}
        /** 1.6.0.4: whether this owner's bot friend (a recruited follower, not an alt) has this name. */
        default boolean botFriendNamed(int ownerId, String name) { return false; }
        /** 1.6.1 ticket 13: the alt is out: its companion driver takes it (fight, loot, death, the activity gate). */
        default void started(Character owner, Character alt) {}
        /**
         * 1.6.2 (bot audit K6): the alt leaves the event instance it is in the way its script lets a member go
         * ({@code playerExit}: out to the event's exit map), before it is saved and detached. Without it the alt was
         * saved on the PQ map and its detach ran {@code playerDisconnected}, which ends a KPQ that drops below 3.
         */
        default void leaveEvent(Character alt) {}
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
    /** 1.6.0.4: owners mid channel change; a summon still loading must not attach to the channel being left. */
    private final Set<Integer> transitioning = new HashSet<>();
    /** 1.6.0.4: owners whose alts a channel change sent home, told so on arrival. */
    private final Set<Integer> wentHome = new HashSet<>();
    private final Backend backend;
    private final BooleanSupplier enabled;
    private final BooleanSupplier summonEnabled;
    private final Executor async;

    OwnedAltService(Backend backend, BooleanSupplier enabled) {
        this(backend, enabled, Runnable::run);
    }
    OwnedAltService(Backend backend, BooleanSupplier enabled, Executor async) {
        this(backend, enabled, () -> true, async);
    }
    OwnedAltService(Backend backend, BooleanSupplier enabled, BooleanSupplier summonEnabled, Executor async) {
        this.backend = backend;
        this.enabled = enabled;
        this.summonEnabled = summonEnabled;
        this.async = async;
    }
    /** 1.6.0.4: false while OWNED_ALTS_SUMMON_ENABLED is off - @botme answers {@link #RESTING}; @dismiss still works. */
    public boolean summoningOpen() { return summonEnabled.getAsBoolean(); }
    private void requireSummoning() {
        requireEnabled();
        if (!summonEnabled.getAsBoolean()) throw new IllegalStateException(RESTING);
    }
    /**
     * 1.6.0.4 (M1): @botme and @dismiss load and save characters (database I/O); they run here, off the packet
     * thread, and reply when done. The lifecycle reservation still decides every race (two summons of one alt).
     */
    public void runAsync(Runnable task) {
        async.execute(task);
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
            if (transitioning.contains(owner.getId()))
                throw new IllegalStateException("You are changing channel. Call your alt again when you arrive.");
            if (occupied(owner.getId()).size() >= CAP)
                throw new IllegalStateException("You can have up to five companions at once (alts and bot friends together).");
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
                if (transitioning.contains(owner.getId()))
                    throw new IllegalStateException("You changed channel while your alt was loading. Call it again.");
                // Attach is short, with no character DB load or save, and cannot race owner teardown.
                entry.alt = alt;
                backend.attach(owner, alt);
                entry.state = State.ACTIVE;
                audit(entry, "loaded alt=" + row.id);
                backend.started(owner, alt); // 1.6.1 ticket 13: the companion driver (fight, loot, death, gate)
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
                backend.leaveEvent(entry.alt); // K6: out of a PQ first, so it is saved in town and ends nobody's run
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
    /**
     * 1.6.0.4 (L2): an owned alt is headless - nobody answers an invitation sent to it, so a trade, party, guild or
     * ring invite would sit pending on it. The line to refuse with, or null when the target is not an owned alt.
     */
    public static String interactionRefusal(Character actor, Character target, String what) {
        if (target == null || !target.isOwnedAlt()) return null;
        Character owner = target.getOwnedAltOwner();
        // 1.6.2 ticket 99: sibling alts share supplies and gear through a normal trade, both sides driven by the
        // companion handover (BotGiveTrade presses accept and Trade for the headless one); nothing sits pending.
        if ("trade".equals(what) && actor != null && actor.isOwnedAlt() && owner != null
                && actor.getOwnedAltOwner() != null && actor.getOwnedAltOwner().getId() == owner.getId()
                && soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.isBusy(actor)) return null;
        if (actor != null && owner != null && owner.getId() == actor.getId())
            return target.getName() + " is your alt and can't " + what + " while it travels with you."
                    + " Use @dismiss " + target.getName() + " and log in on it.";
        return target.getName() + " is an alt traveling with its owner and can't " + what + ".";
    }

    /** 1.6.0.4: @dismiss on a bot friend's name says how a bot friend is sent off instead. */
    public boolean isBotFriend(Character owner, String name) {
        return backend.botFriendNamed(owner.getId(), name);
    }

    /**
     * 1.6.0.4 (H1): an owner is changing channel (or world). Alts are loaded on the channel being left and cannot
     * cross it, so every one is saved and sent home, exactly as @dismiss does, and the owner hears so on arrival.
     * A save failure keeps that alt's reservation (login stays blocked), as on logout; it is logged, never thrown,
     * so the channel change itself goes ahead.
     */
    public int ownerChangingChannel(Character owner) {
        if (owner == null || owner.isOwnedAlt()) return 0;
        synchronized (this) {
            transitioning.add(owner.getId());
            boolean any = entries.values().stream().anyMatch(e -> ownedBy(e, owner));
            if (!any) return 0;
        }
        int count = 0;
        boolean tell;
        try {
            count = dismissAll(owner, false);
            tell = count > 0;
        } catch (RuntimeException e) {
            tell = true;
            org.slf4j.LoggerFactory.getLogger(OwnedAltService.class)
                    .error("Unable to save the alts of character {} on a channel change (login stays blocked)", owner.getId(), e);
        }
        if (tell) synchronized (this) { wentHome.add(owner.getId()); }
        return count;
    }

    /** 1.6.0.4 (H1): the owner arrived on a channel: summons are allowed again; tell them if their alts went home. */
    public void ownerArrived(Character owner) {
        if (owner == null || owner.isOwnedAlt()) return;
        boolean tell;
        synchronized (this) {
            transitioning.remove(owner.getId());
            tell = wentHome.remove(owner.getId());
        }
        if (tell) owner.yellowMessage(WENT_HOME);
    }

    /**
     * 1.6.0.4 (M2): a party changed. An alt that left or was expelled from its owner's party, an owner who left the
     * party their alts are in, or a disband sends those alts home (saved, as @dismiss), off the calling thread: this
     * runs inside World.updateParty, and the dismissal's own party leave comes back through here (that alt is then
     * no longer ACTIVE and is skipped).
     */
    public void onPartyChange(net.server.world.PartyOperation op, int targetId, Collection<Integer> memberIds) {
        if (op != net.server.world.PartyOperation.LEAVE && op != net.server.world.PartyOperation.EXPEL
                && op != net.server.world.PartyOperation.DISBAND) return;
        List<Integer> going = new ArrayList<>();
        synchronized (this) {
            entries.forEach((id, e) -> {
                if (e.state != State.ACTIVE || e.owner == null || e.alt == null) return;
                boolean member = memberIds.contains(id);
                boolean hit = switch (op) {
                    case DISBAND -> member || memberIds.contains(e.owner.getId());
                    default -> id == targetId || (e.owner.getId() == targetId && member);
                };
                if (hit) going.add(id);
            });
        }
        for (int id : going) runAsync(() -> sendHomeAfterPartyChange(id));
    }

    private void sendHomeAfterPartyChange(int id) {
        Entry entry;
        synchronized (this) {
            entry = entries.get(id);
            if (entry == null || entry.state != State.ACTIVE) return; // already dismissed, or being saved
        }
        String name = entry.alt.getName();
        try {
            finish(id, false);
            entry.owner.yellowMessage(name + " is no longer in your party, so it went home (saved).");
        } catch (RuntimeException e) {
            entry.owner.yellowMessage(e.getMessage());
        }
    }

    /**
     * 1.6.0.4: @botme here - put the owner's alts down beside them and follow again. Same channel only and never into
     * an instance (an alt that is itself inside one stays, as the event decides who is in it). Names of those moved.
     */
    public List<String> bringHere(Character owner) {
        requireSummoning();
        if (owner.getEventInstance() != null || (owner.getMap() != null && owner.getMap().getEventInstance() != null))
            throw new IllegalStateException("Your alts can't join you inside an instance. Call them before you enter.");
        List<Entry> mine = new ArrayList<>();
        synchronized (this) {
            entries.values().stream().filter(e -> ownedBy(e, owner) && e.state == State.ACTIVE && e.alt != null)
                    .forEach(mine::add);
        }
        if (mine.isEmpty()) throw new IllegalStateException("None of your alts are with you. Call one with @botme <name>.");
        List<String> moved = new ArrayList<>();
        for (Entry e : mine) {
            Character alt = e.alt;
            if (alt.getEventInstance() != null) {
                owner.yellowMessage(alt.getName() + " is inside an instance and stays there until it ends.");
                continue;
            }
            if (alt.getClient() == null || owner.getClient() == null
                    || alt.getClient().getChannel() != owner.getClient().getChannel()) {
                owner.yellowMessage(alt.getName() + " is on another channel.");
                continue;
            }
            synchronized (this) {
                if (e.state != State.ACTIVE) continue;
                backend.bringHere(owner, alt);
            }
            moved.add(alt.getName());
        }
        return moved;
    }

    /** 1.6.1 ticket 13: an alt out in the world with its owner (the companion driver's view). */
    public record Active(Character owner, Character alt) {}

    /** 1.6.1 ticket 13: every alt that is out and not being loaded or saved, with its owner. */
    public synchronized List<Active> active() {
        List<Active> out = new ArrayList<>();
        entries.values().forEach(e -> {
            if (e.state == State.ACTIVE && e.owner != null && e.alt != null) out.add(new Active(e.owner, e.alt));
        });
        return out;
    }

    /** 1.6.1 ticket 13: true while this alt is out and not being saved. */
    public synchronized boolean isActive(Character alt) {
        Entry e = alt == null ? null : entries.get(alt.getId());
        return e != null && e.alt == alt && e.state == State.ACTIVE;
    }

    /**
     * 1.6.1 ticket 13: the alt died (and respawned in town): save it and send it home, as @dismiss does, off the
     * calling thread; the owner hears {@code line}. It waits there until it is called again.
     */
    public void sendHomeAfterDeath(Character alt, String line) {
        runAsync(() -> {
            Entry entry;
            synchronized (this) {
                entry = entries.get(alt.getId());
                if (entry == null || entry.alt != alt || entry.state != State.ACTIVE) return;
            }
            try {
                finish(alt.getId(), false);
                entry.owner.yellowMessage(line);
            } catch (RuntimeException e) {
                entry.owner.yellowMessage(e.getMessage());
            }
        });
    }

    /**
     * 1.6.1 ticket 13: an alt left behind (a boat, a dead end) is put down beside its owner, as @botme here does.
     * Same channel, never into or out of an instance. True when it moved.
     */
    public boolean catchUp(Character owner, Character alt) {
        if (owner == null || alt == null || owner.getMap() == null) return false;
        if (owner.getEventInstance() != null || owner.getMap().getEventInstance() != null || alt.getEventInstance() != null)
            return false;
        if (alt.getClient() == null || owner.getClient() == null
                || alt.getClient().getChannel() != owner.getClient().getChannel()) return false;
        synchronized (this) {
            Entry e = entries.get(alt.getId());
            if (e == null || e.alt != alt || e.state != State.ACTIVE || !ownedBy(e, owner)) return false;
            backend.bringHere(owner, alt);
        }
        return true;
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
