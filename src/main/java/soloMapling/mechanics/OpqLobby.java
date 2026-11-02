package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.server.BotShutdown;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static soloMapling.BotLogger.log;

/**
 * Orbis PQ's own lobby (ticket 164, 1.6.4): bot parties that assemble at Wonky on {@code 200080101} and play the
 * tower without a player. Off unless the switch is given - {@code -Dslums.mechanics.opq.lobby=true} on top of the two
 * the row itself needs ({@code -Dslums.mechanics=true} and {@code -Dslums.mechanics.opq=true}) - so a server that ran
 * Kerning PQ automation before behaves exactly as it did.
 *
 * <p>Why it is not {@link KpqLobby} with a different map: Kerning's lobby picks a party out of the bots already
 * standing at Lakelis (a population this fork spawns and keeps there), while the tower has no such population - so this
 * one <em>spawns</em> its party on the row's own recruit map and then hands it to the same staging
 * ({@link KpqStaging#start(MechanicsModel.Content, Channel, Character, boolean, int, boolean, long, int, int, Consumer,
 * Consumer)}) every other party quest uses. The staging is shared; only the trigger is Orbis'.
 *
 * <p>What it will not do: take a channel that already holds an Orbis instance (a real party, or a run of ours still
 * going), start a second run on a channel it is backing off from, or talk to Wonky on behalf of a party that the
 * script's own {@code getEligibleParty} would refuse - the staging refuses that with the count.
 */
public final class OpqLobby {

    /** The lobby's own switch. There is no default-on path: an Orbis run spawns five bots. */
    public static final String FLAG = "slums.mechanics.opq.lobby";

    static final long TICK_MS = 60_000;
    static final long REFUSED_BACKOFF_MS = 10 * 60_000L;

    /** The event script whose row this lobby plays; the row is looked up by it ({@code content/opq.yaml}). */
    static final String EVENT = "OrbisPQ";

    private static final Set<EventInstanceManager> running = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Long> refusedUntil = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private OpqLobby() {
    }

    /** Both the row's switches and the lobby's own: three flags, no defaults turned around. */
    public static boolean enabled() {
        MechanicsModel.Content content = MechanicsRuns.contentFor(EVENT);
        return Mechanics.enabled() && Boolean.getBoolean(FLAG) && content != null && Mechanics.enabledFor(content);
    }

    static int maxRuns() {
        return Math.max(0, Integer.getInteger("slums.mechanics.opq.runs", 1));
    }

    /** Server boot: the once-a-minute check, only with all three switches on. */
    public static synchronized void startIfEnabled() {
        if (started || !enabled()) {
            return;
        }
        started = true;
        TimerManager.getInstance().register(() -> {
            try {
                tick();
            } catch (Throwable t) {
                log("[Mechanics] Orbis PQ lobby tick threw: " + t);
            }
        }, TICK_MS, TICK_MS);
        log("[Mechanics] Orbis PQ lobby: bot-only parties start Orbis PQ at Wonky (at most " + maxRuns() + " at a time)");
    }

    static void tick() {
        if (!enabled() || BotShutdown.isQuiesced() || running.size() >= maxRuns()) {
            return;
        }
        MechanicsModel.Content content = MechanicsRuns.contentFor(EVENT);
        if (content == null || content.entryNpc() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Channel ch : BotChannels.channels()) {
            if (!mayTry(ch.getId(), now) || !ch.getMapFactory().isMapLoaded(content.recruitMap())) {
                continue;
            }
            if (busy(ch, content)) {
                continue;
            }
            int want = content.minPlayers();
            KpqStaging.Staged staged = KpqStaging.start(content, ch, null, false, want, true, 0, 1, 0,
                    line -> log("[Mechanics] OPQ " + line), done -> {
                        if (done.instance() != null) {
                            running.remove(done.instance());
                        }
                    });
            if (staged.active() == null) {
                refused(ch.getId(), now);
                log("[Mechanics] OPQ lobby: no run on channel " + ch.getId() + ": " + staged.error());
                continue;
            }
            running.add(staged.active().instance());
            log("[Mechanics] OPQ lobby: a party of " + staged.active().bots().size() + " started "
                    + staged.active().instanceName() + " on channel " + ch.getId());
            return; // one start a tick, as Kerning's lobby does
        }
    }

    /** A channel that already holds an Orbis instance holds the quest: a real party's or one of ours. */
    static boolean busy(Channel ch, MechanicsModel.Content content) {
        var em = ch.getEventSM() == null ? null : ch.getEventSM().getEventManager(content.eventScript());
        return em == null || !em.getInstances().isEmpty();
    }

    static boolean mayTry(int channel, long now) {
        Long until = refusedUntil.get(channel);
        return until == null || now >= until;
    }

    static void refused(int channel, long now) {
        refusedUntil.put(channel, now + REFUSED_BACKOFF_MS);
    }

    static void clearForTest() {
        running.clear();
        refusedUntil.clear();
        started = false;
    }

    static void markRunningForTest(EventInstanceManager eim) {
        running.add(eim);
    }
}
