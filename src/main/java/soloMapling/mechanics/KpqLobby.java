package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import net.server.world.Party;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static soloMapling.BotLogger.log;

/**
 * 1.6.1 KPQ automation (ticket 130 phase 6, for KPQ only): bot-only parties start Kerning PQ by themselves. Bots in the
 * KPQ band stand in Kerning City's PQ lobby looking for a party (LifePq, "lf kpq"); once a minute, on each channel
 * whose lobby holds three or four free ones, they make a party and its leader talks to Lakelis (his own script, as a
 * player leader does). The run then plays the whole PQ like any other ({@link MechanicsRuns}), and when it ends the
 * party breaks up and the bots go back to their lives.
 *
 * <p>Caps: at most {@code -Dslums.mechanics.lobbyRuns} (2) bot-only lobby runs at a time world-wide, one start per
 * minute, and KPQ's own one-party-per-channel lobby: a channel whose KPQ already has an instance is skipped, and one
 * where Lakelis refused anyway is left alone for {@link #REFUSED_BACKOFF_MS} (1.6.2, bot audit K9 and the
 * one-lobby rule). Free means: a population bot (never an owned alt), in the content file's level band (1.6.2, B9:
 * was a hardcoded 21-30), no party, no event, no commitment in flight, not logging off.
 *
 * <p>Switch: {@code -Dslums.mechanics.lobby=true} on top of {@code -Dslums.mechanics=true} (both off by default).
 */
public final class KpqLobby {

    public static final String FLAG = "slums.mechanics.lobby";
    static final long TICK_MS = 60_000;
    /** How long a channel is left alone after Lakelis refused its lobby party (K9: was retried every minute). */
    static final long REFUSED_BACKOFF_MS = 10 * 60_000L;

    private static final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Channel (world * 100 + channel) to when its lobby may try again after a refusal. */
    private static final java.util.Map<Integer, Long> refusedUntil = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private KpqLobby() {
    }

    public static boolean enabled() {
        return Mechanics.enabled() && Boolean.getBoolean(FLAG);
    }

    static int maxRuns() {
        return Math.max(0, Integer.getInteger("slums.mechanics.lobbyRuns", 2));
    }

    /** Server boot: the once-a-minute lobby check, only with both switches on. */
    public static synchronized void startIfEnabled() {
        if (started || !enabled()) {
            return;
        }
        started = true;
        ExecutorServiceManager.getScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                tick();
            } catch (Throwable t) {
                log("[Mechanics] KPQ lobby tick threw: " + t);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        log("[Mechanics] KPQ lobby: bot-only parties start Kerning PQ (at most " + maxRuns() + " at a time)");
    }

    /** One bot in the lobby as the picker sees it. */
    record Candidate(int id, int level, boolean free) {
    }

    /**
     * The party to form from the lobby: up to four free bots in the band, the highest level first (it leads, as the
     * strongest player in a PUG usually does), ties by id; empty when fewer than three are free.
     */
    static List<Integer> pickParty(List<Candidate> lobby, int min, int max, int minLevel, int maxLevel) {
        List<Candidate> ok = new ArrayList<>();
        for (Candidate c : lobby) {
            if (c.free() && c.level() >= minLevel && c.level() <= maxLevel) {
                ok.add(c);
            }
        }
        if (ok.size() < min) {
            return List.of();
        }
        ok.sort(Comparator.comparingInt(Candidate::level).reversed().thenComparingInt(Candidate::id));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, ok.size()); i++) {
            out.add(ok.get(i).id());
        }
        return out;
    }

    static void tick() {
        if (!enabled() || BotShutdown.isQuiesced() || running.size() >= maxRuns()) {
            return;
        }
        Content content = MechanicsRuns.contentFor(KpqStaging.EVENT);
        if (content == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Channel ch : soloMapling.ArtificialPlayer.BotChannels.channels()) {
            if (!ch.getMapFactory().isMapLoaded(KpqStaging.RECRUIT_MAP)
                    || !mayTry(channelKey(ch), now, kpqBusy(ch))) {
                continue;
            }
            MapleMap lobby = ch.getMapFactory().getMap(KpqStaging.RECRUIT_MAP);
            List<Character> here = new ArrayList<>();
            List<Candidate> candidates = new ArrayList<>();
            for (Character c : lobby.getAllPlayers()) {
                if (c != null && c.isBot()) {
                    here.add(c);
                    candidates.add(new Candidate(c.getId(), c.getLevel(), free(c)));
                }
            }
            List<Integer> pick = pickParty(candidates, content.minPlayers(), content.maxPlayers(), content.minLevel(),
                    content.maxLevel());
            if (pick.isEmpty()) {
                continue;
            }
            if (start(content, here, pick)) {
                return; // one start a tick
            }
        }
    }

    static boolean free(Character c) {
        try {
            if (c.isOwnedAlt() || c.getParty() != null || c.getEventInstance() != null || c.getHp() <= 0
                    || MechanicsHold.committed(c)) {
                return false;
            }
            BotSM sm = CharacterStorage.getBotById(c.getId());
            return sm != null && sm.getRunning() && !soloMapling.ArtificialPlayer.BotCommitments.inFlight(sm)
                    && !soloMapling.ArtificialPlayer.BotLogout.isLeaving(c.getId());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    static int channelKey(Channel ch) {
        return ch.getWorld() * 100 + ch.getId();
    }

    /** Whether this channel's KPQ already has a live instance (KPQ runs one party per channel: Lakelis would refuse). */
    static boolean kpqBusy(Channel ch) {
        try {
            scripting.event.EventManager em = ch.getEventSM().getEventManager(KpqStaging.EVENT);
            return em == null || em.getInstances().stream().anyMatch(e -> !e.isEventDisposed());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Whether the lobby may try a start on a channel now: its KPQ is free and no refusal backoff is running. */
    static boolean mayTry(int channel, long now, boolean kpqBusy) {
        Long until = refusedUntil.get(channel);
        return !kpqBusy && (until == null || now >= until);
    }

    /** Lakelis refused this channel's party: leave the channel alone for a while (K9). */
    static void refused(int channel, long now) {
        refusedUntil.put(channel, now + REFUSED_BACKOFF_MS);
    }

    static void clearForTest() {
        refusedUntil.clear();
        running.clear();
    }

    /**
     * Forms the party, has its leader talk to Lakelis and starts the run. 1.6.2 (bot audit K7, K8): anything that
     * throws on the way disbands the party (a bot left in a half-made party was never free again), and the run's
     * slot is taken before the run starts (a run that ended on its first tick left its name behind for good).
     */
    static boolean start(Content content, List<Character> here, List<Integer> pick) {
        List<Character> members = new ArrayList<>();
        for (int id : pick) {
            for (Character c : here) {
                if (c.getId() == id) {
                    members.add(c);
                }
            }
        }
        if (members.isEmpty()) {
            return false;
        }
        String[] slot = {null};
        try {
            Character leader = members.get(0);
            if (!Party.createParty(leader, true) || leader.getParty() == null) {
                disband(members);
                return false;
            }
            Party party = leader.getParty();
            for (Character c : members) {
                if (c != leader && !Party.joinParty(c, party.getId(), true)) {
                    disband(members);
                    return false;
                }
            }
            // the leader talks to Lakelis ("I want to participate"): his script checks the party and starts the PQ
            BotNpcTalk.Result r = MechanicsRuns.staging(() -> BotNpcTalk.INSTANCE.converse(leader,
                    KpqStaging.LAKELIS, List.of(0)));
            EventInstanceManager eim = leader.getEventInstance();
            if (eim == null) {
                log("[Mechanics] KPQ lobby: Lakelis did not start a run for " + leader.getName() + "'s party"
                        + (r.ran() ? ": " + r.lastLine() : ": " + r.error()) + "; channel left alone for "
                        + REFUSED_BACKOFF_MS / 60_000 + " min");
                Channel ch = leader.getClient() == null ? null : leader.getClient().getChannelServer();
                if (ch != null) {
                    refused(channelKey(ch), System.currentTimeMillis());
                }
                disband(members);
                return false;
            }
            String name = MechanicsRuns.keyOf(eim); // per channel (b113): every channel's first KPQ is "Kerning0"
            slot[0] = name;
            running.add(name); // K8: before the run can end
            MechanicsRuns.Active a = MechanicsRuns.get().start(eim, content, false, 0, 1, 0, true,
                    line -> log("[Mechanics] lobby " + name + " " + line), done -> {
                        running.remove(name);
                        disband(members);
                    });
            if (a.run().ended()) {
                running.remove(name);
                disband(members);
                return false;
            }
            log("[Mechanics] KPQ lobby: " + members.size() + " bots led by " + leader.getName()
                    + " start Kerning PQ (" + name + ")");
            return true;
        } catch (RuntimeException | LinkageError e) {
            log("[Mechanics] KPQ lobby: starting a run threw, the party breaks up: " + e);
            if (slot[0] != null && MechanicsRuns.get().active().stream().noneMatch(x -> x.instanceName()
                    .equals(slot[0]))) {
                running.remove(slot[0]);
            }
            disband(members);
            return false;
        }
    }

    /** The lobby party breaks up (each bot leaves; the event script ends a PQ that gets too small). */
    static void disband(List<Character> members) {
        for (Character c : members) {
            try {
                if (c.getParty() != null) {
                    Party.leaveParty(c.getParty(), c);
                }
            } catch (RuntimeException e) {
                log("[Mechanics] " + c.getName() + " could not leave the lobby party: " + e);
            }
        }
    }

    /** For !mechanics status. */
    public static int running() {
        return running.size();
    }
}
