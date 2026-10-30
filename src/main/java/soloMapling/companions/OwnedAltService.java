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
    static {
        INSTANCE.retryWith(r -> soloMapling.server.MethodScheduler.runAfterDelay(
                () -> INSTANCE.runAsync(r), OwnedAltService.RETRY_MS)); // the save is DB I/O: off the scheduler thread
    }
    /** 1.6.0.4: what @botme says while OWNED_ALTS_SUMMON_ENABLED is off (alts are half-built: no fighting yet). */
    public static final String RESTING = "Alts are resting while we finish them - they'll be back soon.";
    /** 1.6.0.4: said on arrival after a channel change sent the owner's alts home. */
    public static final String WENT_HOME = "Your alts went home. Changing channel sends them home (saved); call them again with @botme <name>.";
    /** Audit b132: said on arrival when a channel change could not save an alt. */
    public static final String HOME_FAILED = "Your alts were sent home when you changed channel, but one could not be saved. Please contact a GM before logging into it.";
    /** Audit #6 (b117): a channel change that never arrives (a disconnect mid-change) stops blocking @botme after this. */
    static final long TRANSITION_TTL_MS = 60_000;
    /** Audit #7 (b117): failed save/close attempts before a closing alt is given up on and taken out of the world. */
    static final int SAVE_TRIES = 3;
    /** Audit #7: how long a FAILED alt waits before its save is tried again by itself. */
    static final long RETRY_MS = 30_000;
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
         * Audit B13: the alt is about to be saved: its companion driver stops (waiting out a tick in flight), so
         * nothing it picks up or kills lands after the save reads its bag.
         */
        default void closing(Character alt) {}
    }
    private enum State { LOADING, ACTIVE, SAVING, FAILED, LOGIN }
    private static final class Entry {
        final Character owner;
        Character alt;
        State state;
        boolean saved;
        boolean detached; // audit #7: out of the world (a FAILED entry then holds only the login block)
        int failures;
        Entry(Character owner, State state) { this.owner = owner; this.state = state; }
    }
    private final Map<Integer, Entry> entries = new HashMap<>();
    private final Set<Character> closedOwners = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Integer, Integer> friendReservations = new HashMap<>();
    /**
     * 1.6.0.4: owners mid channel change (owner id -> when it started); a summon still loading must not attach to the
     * channel being left. Audit #6: an entry older than TRANSITION_TTL_MS no longer counts (the owner never arrived).
     */
    private final Map<Integer, Long> transitioning = new HashMap<>();
    /** Audit #5 (b114): owners whose alts a channel change is still saving (off the packet thread). */
    private final Set<Integer> sendingHome = new HashSet<>();
    /** The result of a finished channel-change save (true = all saved), until the owner arrives. */
    private final Map<Integer, Boolean> homeResult = new HashMap<>();
    /** Owners who arrived while their alts were still being saved: told when the save ends. */
    private final Map<Integer, Character> arrivedWaiting = new HashMap<>();
    private final java.util.function.LongSupplier clock;
    /** Audit #7: runs a FAILED alt's retry later (production: after RETRY_MS; tests choose). */
    private java.util.function.Consumer<Runnable> later = r -> {};
    void retryWith(java.util.function.Consumer<Runnable> later) { this.later = later; }
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
        this(backend, enabled, summonEnabled, async, System::currentTimeMillis);
    }
    OwnedAltService(Backend backend, BooleanSupplier enabled, BooleanSupplier summonEnabled, Executor async,
                    java.util.function.LongSupplier clock) {
        this.backend = backend;
        this.enabled = enabled;
        this.summonEnabled = summonEnabled;
        this.async = async;
        this.clock = clock;
    }
    /** Audit #6: the owner is mid channel change (and the change is not older than TRANSITION_TTL_MS). */
    private boolean transitioning(int ownerId) {
        Long since = transitioning.get(ownerId);
        if (since == null) return false;
        if (clock.getAsLong() - since > TRANSITION_TTL_MS) {
            transitioning.remove(ownerId);
            return false;
        }
        return true;
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
            // audit #7: a FAILED alt taken out of the world holds no companion slot
            if (entry.owner != null && entry.owner.getId() == ownerId && !(entry.state == State.FAILED && entry.detached))
                ids.add(id);
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
            if (transitioning(owner.getId()))
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
                if (transitioning(owner.getId()))
                    throw new IllegalStateException("You changed channel while your alt was loading. Call it again.");
                entry.alt = alt;
            }
            // Audit B14: the attach (party, channel/world storage, map, packets) runs outside the service monitor, so
            // the 200 ms driver, @dismiss, catch-up and other owners' summons never wait on it. The entry stays
            // LOADING (a logout or channel change skips it), and the owner is re-checked once it is in.
            backend.attach(owner, alt);
            synchronized (this) {
                if (closedOwners.contains(owner) || !owner.isLoggedin())
                    throw new IllegalStateException("Your session has closed.");
                if (transitioning(owner.getId()))
                    throw new IllegalStateException("You changed channel while your alt was loading. Call it again.");
                entry.state = State.ACTIVE;
                audit(entry, "loaded alt=" + row.id);
            }
            backend.started(owner, alt); // 1.6.1 ticket 13: the companion driver (fight, loot, death, gate)
            return alt;
        } catch (RuntimeException e) {
            synchronized (this) { entry.state = State.SAVING; }
            try {
                if (entry.alt != null) backend.detach(entry.alt);
            } catch (RuntimeException cleanup) {
                synchronized (this) { entry.state = State.FAILED; entry.detached = true; } // audit #7: frees the slot
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
                    .filter(e -> e.getValue().state != State.LOADING)
                    .filter(e -> !(e.getValue().state == State.FAILED && e.getValue().detached)) // already out of the world
                    .map(Map.Entry::getKey).toList();
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
            if (entry.state == State.FAILED && entry.detached)
                throw new IllegalStateException("That character could not be saved when it went home. Please contact a GM before logging into it.");
            if (entry.state != State.ACTIVE && !(entry.state == State.FAILED && !login))
                throw new IllegalStateException("That character is still being loaded or saved. Please try again shortly.");
            entry.state = State.SAVING;
        }
        try {
            if (!entry.saved) {
                backend.closing(entry.alt); // outside the monitor: the tick it waits for may call back into us
                backend.save(entry.alt);
                entry.saved = true;
                audit(entry, "save committed alt=" + id);
            }
            backend.detach(entry.alt);
            entry.detached = true;
            audit(entry, "removed alt=" + id + " takeover=" + login);
            synchronized (this) {
                if (login) entries.put(id, new Entry(null, State.LOGIN));
                else entries.remove(id, entry);
            }
        } catch (RuntimeException e) {
            int failures;
            synchronized (this) { entry.state = State.FAILED; failures = ++entry.failures; }
            audit(entry, "save/cleanup failed alt=" + id + " (" + failures + "/" + SAVE_TRIES + "); login blocked");
            if (failures >= SAVE_TRIES) {
                giveUp(id, entry, e);
            } else {
                later.accept(() -> retryFailed(id, entry)); // audit #7: tried again by itself, not only by a GM
            }
            throw new IllegalStateException("Unable to finish saving that character. Please contact a GM before logging into it.", e);
        }
    }

    /** Audit #7: a FAILED alt's own retry (a later save/close); explicit @dismiss/logout retries count too. */
    private void retryFailed(int id, Entry entry) {
        synchronized (this) {
            if (entries.get(id) != entry || entry.state != State.FAILED || entry.detached) return;
        }
        try {
            finish(id, false);
            if (entry.owner != null) entry.owner.yellowMessage(entry.alt.getName() + " was saved and went home.");
        } catch (RuntimeException ignored) {
            // counted in finish; the next retry or the give-up follows
        }
    }

    /**
     * Audit #7 (b117): FAILED is terminal after SAVE_TRIES failures: the alt leaves the world (no ghost standing
     * untickable on the map) and frees its companion slot; its login stays blocked (its last save is older than what
     * it carried) until a GM looks.
     */
    private void giveUp(int id, Entry entry, RuntimeException why) {
        if (!entry.detached) {
            try {
                backend.detach(entry.alt);
            } catch (RuntimeException e) {
                why.addSuppressed(e);
            }
        }
        synchronized (this) { entry.detached = true; }
        audit(entry, "gave up on alt=" + id + " after " + SAVE_TRIES + " failed saves: out of the world, slot freed, login blocked");
        org.slf4j.LoggerFactory.getLogger(OwnedAltService.class)
                .error("Owned alt {} could not be saved after {} tries; taken out of the world, login blocked", id, SAVE_TRIES, why);
        if (entry.owner != null && entry.alt != null) {
            entry.owner.yellowMessage(entry.alt.getName() + " could not be saved and was sent away. Please contact a GM before logging into it.");
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

    /**
     * Audit 163 (report B16): a Maple Messenger or buddy invite by name to an owned alt (on any channel of the world)
     * would sit pending on a headless character that can never answer it. The refusal line, or null when the name is
     * not an alt that is out.
     */
    public static String inviteRefusalByName(net.server.PlayerStorage world, Character actor, String name, String what) {
        if (world == null || name == null) return null;
        Character target = world.getCharacterByName(name);
        return target == null ? null : interactionRefusal(actor, target, what);
    }

    /** 1.6.0.4: @dismiss on a bot friend's name says how a bot friend is sent off instead. */
    public boolean isBotFriend(Character owner, String name) {
        return backend.botFriendNamed(owner.getId(), name);
    }

    /**
     * 1.6.0.4 (H1): an owner is changing channel (or world). Alts are loaded on the channel being left and cannot
     * cross it, so every one is saved and sent home, exactly as @dismiss does, and the owner hears so on arrival.
     * Audit #5 (b114): the saves run off the packet thread (runAsync, as @botme/@dismiss); the alts' reservations
     * keep them out of a new @botme or a login until they are saved. A save failure is logged, never thrown, so the
     * channel change itself goes ahead, and the owner hears it failed (b132). Returns how many alts are going home.
     */
    public int ownerChangingChannel(Character owner) {
        if (owner == null || owner.isOwnedAlt()) return 0;
        int going;
        synchronized (this) {
            transitioning.put(owner.getId(), clock.getAsLong());
            going = (int) entries.values().stream()
                    .filter(e -> ownedBy(e, owner) && e.state != State.LOADING && !(e.state == State.FAILED && e.detached))
                    .count();
            if (going == 0) return 0;
            sendingHome.add(owner.getId());
            homeResult.remove(owner.getId());
        }
        int ownerId = owner.getId();
        runAsync(() -> {
            boolean ok = true;
            try {
                dismissAll(owner, false);
            } catch (RuntimeException e) {
                ok = false;
                org.slf4j.LoggerFactory.getLogger(OwnedAltService.class)
                        .error("Unable to save the alts of character {} on a channel change (login stays blocked)", ownerId, e);
            }
            Character arrived;
            synchronized (this) {
                sendingHome.remove(ownerId);
                arrived = arrivedWaiting.remove(ownerId);
                if (arrived == null) homeResult.put(ownerId, ok);
            }
            if (arrived != null) arrived.yellowMessage(ok ? WENT_HOME : HOME_FAILED);
        });
        return going;
    }

    /** 1.6.0.4 (H1): the owner arrived on a channel: summons are allowed again; tell them if their alts went home. */
    public void ownerArrived(Character owner) {
        if (owner == null || owner.isOwnedAlt()) return;
        Boolean result;
        synchronized (this) {
            transitioning.remove(owner.getId());
            result = homeResult.remove(owner.getId());
            if (result == null && sendingHome.contains(owner.getId())) arrivedWaiting.put(owner.getId(), owner);
        }
        if (result != null) owner.yellowMessage(result ? WENT_HOME : HOME_FAILED);
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
            }
            backend.bringHere(owner, alt); // audit B14: map moves outside the monitor
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
        }
        backend.bringHere(owner, alt); // audit B14: map moves outside the monitor
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
