package soloMapling.mechanics;

import net.server.channel.Channel;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.server.BotShutdown;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.BotLogger.log;

/**
 * Party-quest lobbies that staff themselves (ticket 164, 1.6.4): for each row listed in {@link #rows()}, when that row's
 * own lobby switch is given, spawn {@code content.minPlayers()} bots at the row's {@code entryNpc} on the row's
 * {@code recruitMap}, hand them to the one staging every party quest uses ({@link KpqStaging#start(MechanicsModel.Content,
 * Channel, Character, boolean, int, boolean, long, int, int, java.util.function.Consumer,
 * java.util.function.Consumer)}), and let the bot leader accept the quest from the NPC the row names.
 *
 * <p>This is deliberately not {@link KpqLobby}: Kerning's lobby <em>picks</em> a party out of the bots already standing
 * at Lakelis, because this fork keeps a population on {@code 103000000}. The Ludibrium and Orbis towers have no such
 * population, so their lobbies create one. Kerning keeps its own lobby, unchanged.
 *
 * <p>Switches, all off by default, one per row on top of the two a row itself needs
 * ({@code -Dslums.mechanics=true} and {@code -Dslums.mechanics.<row>=true}):
 * {@code -Dslums.mechanics.<row>.lobby=true} starts the lobby, and {@code -Dslums.mechanics.<row>.runs=N} caps how many
 * runs of that row are in flight (1). A channel that refused a start is left alone for
 * {@link #REFUSED_BACKOFF_MS}. Nothing here spawns a bot unless its row's lobby switch is on.
 */
public final class ContentLobby {

    /** Switch suffix; the full switch is {@code slums.mechanics.<row id>.lobby}. */
    public static final String SWITCH_PREFIX = "slums.mechanics.";
    public static final String SWITCH_SUFFIX = ".lobby";

    static final long TICK_MS = 60_000;
    static final long REFUSED_BACKOFF_MS = 10 * 60_000L;

    /**
     * The rows whose NPC handshake is "leader talks to the front door" and whose party a bot lobby can create, by event
     * script name. A row joins this list when its content lands: every entry must have a content row, and
     * {@code ContentLobbyTest} checks that for each of them. Kerning's {@code kpq} row is not here: it has
     * {@link KpqLobby}, which picks its party out of the bots already at Lakelis instead of spawning one.
     */
    private static final List<String> ROWS = List.of("OrbisPQ", "LudiPQ", "HenesysPQ",
            "CafePQ_1", "CafePQ_2", "CafePQ_3", "CafePQ_4", "CafePQ_5", "CafePQ_6", "LudiMazePQ");

    private static final Set<EventInstanceManager> running = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> refusedUntil = new ConcurrentHashMap<>();
    private static volatile boolean started;

    private ContentLobby() {
    }

    /** The event script names this lobby staffs, in the order it tries them. */
    public static List<String> rows() {
        return ROWS;
    }

    /** {@code slums.mechanics.<row id>.lobby}. */
    public static String switchFor(MechanicsModel.Content content) {
        return SWITCH_PREFIX + content.id().toLowerCase(Locale.ROOT) + SWITCH_SUFFIX;
    }

    /** The row's own lobby switch: on top of {@code -Dslums.mechanics} and {@code -Dslums.mechanics.<row>}. */
    public static boolean enabledFor(MechanicsModel.Content content) {
        return content != null && Mechanics.enabled() && Boolean.getBoolean(switchFor(content))
                && Mechanics.enabledFor(content);
    }

    static int maxRuns(MechanicsModel.Content content) {
        String row = content.id().toLowerCase(Locale.ROOT);
        return Math.max(0, Integer.getInteger(SWITCH_PREFIX + row + ".runs", 1));
    }

    /** Server boot: the once-a-minute check. Does nothing unless at least one row's lobby is switched on. */
    public static synchronized void startIfEnabled() {
        if (started) {
            return;
        }
        List<String> on = ROWS.stream().filter(ContentLobby::enabledFor).toList();
        if (on.isEmpty()) {
            return;
        }
        started = true;
        TimerManager.getInstance().register(() -> {
            try {
                tick();
            } catch (Throwable t) {
                log("[Mechanics] party quest lobby tick threw: " + t);
            }
        }, TICK_MS, TICK_MS);
        log("[Mechanics] party quest lobbies on: " + String.join(", ", on));
    }

    private static boolean enabledFor(String eventScript) {
        return enabledFor(MechanicsRuns.contentFor(eventScript));
    }

    static void tick() {
        if (BotShutdown.isQuiesced()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String eventScript : ROWS) {
            MechanicsModel.Content content = MechanicsRuns.contentFor(eventScript);
            if (content == null || content.entryNpc() <= 0 || !enabledFor(content)
                    || running.size() >= maxRuns(content)) {
                continue;
            }
            if (!tickRow(content, now)) {
                continue;
            }
            return; // one start a tick, as Kerning's lobby does
        }
    }

    /** One row, every channel: the first channel that can take a party gets one. */
    static boolean tickRow(MechanicsModel.Content content, long now) {
        for (Channel ch : BotChannels.channels()) {
            if (!mayTry(key(content, ch), now) || !ch.getMapFactory().isMapLoaded(content.recruitMap())) {
                continue;
            }
            if (busy(ch, content)) {
                continue;
            }
            int want = content.minPlayers();
            KpqStaging.Staged staged = KpqStaging.start(content, ch, null, false, want, true, 0, 1, 0,
                    line -> log("[Mechanics] " + content.id().toUpperCase(Locale.ROOT) + " " + line), done -> {
                        if (done.instance() != null) {
                            running.remove(done.instance());
                        }
                    });
            if (staged.active() == null) {
                refuse(key(content, ch), now);
                log("[Mechanics] " + content.name() + " lobby: no run on channel " + ch.getId() + ": " + staged.error());
                continue;
            }
            running.add(staged.active().instance());
            log("[Mechanics] " + content.name() + " lobby: a party of " + staged.active().bots().size() + " started "
                    + staged.active().instanceName() + " on channel " + ch.getId());
            return true;
        }
        return false;
    }

    /** A channel that already holds an instance of this row holds the quest: a real party's, or one of ours. */
    static boolean busy(Channel ch, MechanicsModel.Content content) {
        var em = ch.getEventSM() == null ? null : ch.getEventSM().getEventManager(content.eventScript());
        return em == null || !em.getInstances().isEmpty();
    }

    static String key(MechanicsModel.Content content, Channel ch) {
        return content.id().toLowerCase(Locale.ROOT) + ":" + ch.getId();
    }

    static boolean mayTry(String key, long now) {
        Long until = refusedUntil.get(key);
        return until == null || now >= until;
    }

    static void refuse(String key, long now) {
        refusedUntil.put(key, now + REFUSED_BACKOFF_MS);
    }

    /** Test hook: drop every piece of state this class keeps. */
    static void clearForTest() {
        running.clear();
        refusedUntil.clear();
        started = false;
    }

    static void markRunningForTest(EventInstanceManager eim) {
        running.add(eim);
    }

    static int runningForTest() {
        return running.size();
    }
}
