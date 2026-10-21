package soloMapling.dashboard;

import client.Character;
import net.server.Server;
import net.server.world.World;
import server.maps.MapFactory;
import soloMapling.ArtificialPlayer.BotDbGate;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts;
import tools.DatabaseConnection;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/**
 * The live server, as the exporter reads it. Every read is a plain field or counter read from the exporter's own
 * thread: no game lock, no database, no packet. A read that fails falls back to an empty value.
 */
final class ServerSources implements DashboardExporter.Sources {

    /** A grind bot's macro phase. Read reflectively so the bot class needs no change for the dashboard. */
    private static final Field TRAINING_PHASE = phaseField();
    private static final int MAX_NAMES = 20_000;

    private final Map<Integer, String> names = new ConcurrentHashMap<>();

    @Override
    public List<LiveSnapshot.BotSample> bots() {
        List<LiveSnapshot.BotSample> out = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot == null ? null : bot.getChr();
            if (chr == null) {
                continue;
            }
            try {
                out.add(new LiveSnapshot.BotSample(chr.getId(), chr.getName(), chr.getLevel(), jobName(chr),
                        chr.getMapId(), typeOf(bot), activityOf(bot), chr.getMeso()));
            } catch (RuntimeException e) {
                // a bot logging out mid-read: skip it this minute
            }
        }
        return out;
    }

    private static String jobName(Character chr) {
        return chr.getJob() == null ? "?" : chr.getJob().name();
    }

    static String typeOf(BotSM bot) {
        String name = bot.getClass().getSimpleName().replace("Bot", "");
        return name.isEmpty() ? "bot" : name.toLowerCase(Locale.ROOT);
    }

    /** grind / travel / town / break for a grind bot, from its phase; other bots by their kind. */
    static String activityOf(BotSM bot) {
        if (bot instanceof TrainingBot && TRAINING_PHASE != null) {
            try {
                Object phase = TRAINING_PHASE.get(bot);
                return phase == null ? "idle" : activityOfPhase(phase.toString());
            } catch (IllegalAccessException | RuntimeException e) {
                return "grind?";
            }
        }
        return typeOf(bot);
    }

    static String activityOfPhase(String phase) {
        if (phase.contains("BREAK")) {
            return "break";
        }
        if (phase.equals("GRIND")) {
            return "grind";
        }
        if (phase.contains("TRAVEL") || phase.startsWith("GO_") || phase.contains("RETURN") || phase.equals("DECIDE")) {
            return "travel";
        }
        if (phase.contains("TOWN") || phase.contains("DWELL") || phase.contains("SHOP")) {
            return "town";
        }
        return phase.equals("INIT") ? "idle" : phase.toLowerCase(Locale.ROOT);
    }

    static Field phaseField() {
        try {
            Field f = TrainingBot.class.getDeclaredField("phase");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public LiveSnapshot.Population population() {
        int roster = 0;
        int target = 0;
        int peak = 0;
        String director = "off";
        List<Integer> byHour = List.of();
        try {
            roster = BotRoster.members().size();
            if (BotPopulationDirector.enabled()) {
                director = BotPopulationDirector.isRunning() ? "on" : "paused";
                target = BotPopulationDirector.targetNow();
                peak = BotPopulationDirector.directorPeak(); // ticket 57: after the population cap
                byHour = new ArrayList<>(BotPopulationDirector.todaysTargets().values());
            }
        } catch (RuntimeException e) {
            // no roster or config yet
        }
        int players = 0;
        try {
            for (World world : Server.getInstance().getWorlds()) {
                if (world != null) {
                    for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                        if (chr != null && !BotHelpers.isBot(chr)) {
                            players++;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // worlds not up
        }
        int[] lod = null;
        try {
            lod = new int[]{LodCounts.fullMaps(), LodCounts.haloMaps(), LodCounts.activeMaps(), LodCounts.dynamicBots()};
        } catch (RuntimeException | LinkageError e) {
            // movement system not loaded
        }
        return new LiveSnapshot.Population(roster, target, peak, director, byHour, players, lod);
    }

    @Override
    public LiveSnapshot.Health health() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long gcCount = 0;
        long gcMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
        }
        double cpu = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpu = os.getProcessCpuLoad();
        }
        int[] pool = null;
        try {
            pool = DatabaseConnection.poolStats();
        } catch (RuntimeException e) {
            // pool shutting down
        }
        int[] gate = null;
        try {
            gate = BotDbGate.stats();
        } catch (RuntimeException | LinkageError e) {
            // bots not loaded
        }
        return new LiveSnapshot.Health(ManagementFactory.getRuntimeMXBean().getUptime(),
                mem.getHeapMemoryUsage().getUsed(), mem.getHeapMemoryUsage().getMax(),
                mem.getNonHeapMemoryUsage().getUsed(), cpu,
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage(),
                ManagementFactory.getThreadMXBean().getThreadCount(), gcCount, gcMs, pool, gate,
                BotPerformanceMonitor.totals());
    }

    @Override
    public IntFunction<String> mapNames() {
        return mapId -> {
            String known = names.get(mapId);
            if (known != null) {
                return known;
            }
            String street = MapFactory.loadStreetName(mapId);
            String place = MapFactory.loadPlaceName(mapId);
            String name = street.isEmpty() ? place : place.isEmpty() ? street : street + " : " + place;
            if (names.size() < MAX_NAMES) {
                names.put(mapId, name);
            }
            return name;
        };
    }
}
