package soloMapling.ArtificialPlayer.BotGrindSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Which map regions training bots may grind in, and where they may relocate to. Data-driven since ticket 03:
// TrainingRegions.yaml lists each region's map-id windows, its hub towns and the levels it attracts bots at.
// The four original regions (Victoria Island + Sleepywood, Orbis, El Nath, the Ludibrium continent) keep their
// windows; the widening adds Aqua Road, Leafre, Mu Lung, the Nihal Desert, Singapore, Masteria and Zipangu,
// reached by the transit in BotTransit.yaml. Checked in TrainingMapFinder before the level band, together with
// the rebirth gates (BotMapGates), so a bot never grinds a map it couldn't legally enter. If the YAML can't be
// read, the original four windows apply. Ours (SoloMapling).
public final class TrainingRegions {

    private TrainingRegions() {}

    private static final String YAML_PATH = "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingRegions.yaml";

    // [min, max) over map ids.
    public record Window(int min, int max) {
        public boolean contains(int mapId) {
            return mapId >= min && mapId < max;
        }
    }

    public record Region(String name, List<Window> windows, List<Window> excludes, List<Integer> hubs,
                         int travelMinLevel, int travelMaxLevel, double weight, boolean enabled) {
        public boolean contains(int mapId) {
            boolean in = false;
            for (Window w : windows) {
                if (w.contains(mapId)) {
                    in = true;
                    break;
                }
            }
            if (!in) {
                return false;
            }
            for (Window w : excludes) {
                if (w.contains(mapId)) {
                    return false;
                }
            }
            return true;
        }

        public boolean attracts(int level) {
            return enabled && level >= travelMinLevel && level <= travelMaxLevel && !hubs.isEmpty();
        }
    }

    // The relocation knobs (see the YAML's `travel` block).
    public record Tuning(double wanderChance, double outgrownChance, int relocationsPerMinute, int hubReachHops) {
    }

    public record Config(List<Region> regions, Tuning tuning) {
    }

    static final Tuning DEFAULT_TUNING = new Tuning(0.05, 0.80, 20, 20);

    // The pre-ticket-03 table, used when the YAML can't be read: grind only, no relocation hubs.
    static final Config FALLBACK = new Config(List.of(
            region("Victoria Island", 100000000, 110000000),
            region("Orbis", 200000000, 201000000),
            region("El Nath", 211000000, 212000000),
            region("Ludibrium", 220000000, 223000000)), DEFAULT_TUNING);

    private static Region region(String name, int min, int max) {
        return new Region(name, List.of(new Window(min, max)), List.of(), List.of(), 1, 255, 1.0, true);
    }

    private static volatile Config cached;

    public static Config config() {
        Config local = cached;
        if (local == null) {
            local = load();
            cached = local;
        }
        return local;
    }

    // Swap in a table (tests). Null drops back to lazily reading the YAML.
    public static void install(Config config) {
        cached = config;
        TrainingTravel.clearCaches();
    }

    public static Config reload() {
        install(load());
        return cached;
    }

    public static List<Region> regions() {
        return config().regions();
    }

    public static Tuning tuning() {
        return config().tuning();
    }

    // The map lies in an enabled region (content filter only - see isAllowed(int, int) for the gates).
    public static boolean isAllowed(int mapId) {
        Region r = regionOf(mapId);
        return r != null && r.enabled();
    }

    // The map lies in an enabled region AND a bot with these rebirths may enter it.
    public static boolean isAllowed(int mapId, int rebirths) {
        return isAllowed(mapId) && BotMapGates.mayEnter(mapId, rebirths);
    }

    // The first region whose windows hold mapId (enabled or not), or null.
    public static Region regionOf(int mapId) {
        for (Region r : regions()) {
            if (r.contains(mapId)) {
                return r;
            }
        }
        return null;
    }

    // ── Loading ──

    private static Config load() {
        try (FileReader in = new FileReader(soloMapling.DataFiles.file(YAML_PATH))) {
            Config c = parse(in);
            if (!c.regions().isEmpty()) {
                return c;
            }
            System.out.println("[TrainingRegions] " + YAML_PATH + " lists no regions - using the original four");
        } catch (Exception e) {
            System.out.println("[TrainingRegions] failed to load " + YAML_PATH + ": " + e.getMessage()
                    + " - using the original four");
        }
        return FALLBACK;
    }

    static Config parse(Reader in) throws Exception {
        Object root = new YamlReader(in).read();
        if (!(root instanceof Map<?, ?> rootMap)) {
            return new Config(List.of(), DEFAULT_TUNING);
        }
        Tuning tuning = DEFAULT_TUNING;
        if (rootMap.get("travel") instanceof Map<?, ?> t) {
            tuning = new Tuning(
                    toDouble(t.get("wander_chance"), DEFAULT_TUNING.wanderChance()),
                    toDouble(t.get("outgrown_chance"), DEFAULT_TUNING.outgrownChance()),
                    toInt(t.get("relocations_per_minute"), DEFAULT_TUNING.relocationsPerMinute()),
                    toInt(t.get("hub_reach_hops"), DEFAULT_TUNING.hubReachHops()));
        }
        List<Region> regions = new ArrayList<>();
        if (rootMap.get("regions") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> r)) {
                    continue;
                }
                List<Window> windows = windows(r.get("windows"));
                if (windows.isEmpty()) {
                    System.out.println("[TrainingRegions] skipped a region without windows: " + r.get("name"));
                    continue;
                }
                List<Integer> hubs = new ArrayList<>();
                if (r.get("hubs") instanceof List<?> hs) {
                    for (Object h : hs) {
                        int id = toInt(h, -1);
                        if (id > 0) {
                            hubs.add(id);
                        }
                    }
                }
                regions.add(new Region(String.valueOf(r.get("name")), windows, windows(r.get("excludes")),
                        List.copyOf(hubs), toInt(r.get("travel_min_level"), 1), toInt(r.get("travel_max_level"), 255),
                        toDouble(r.get("weight"), 1.0), !"false".equalsIgnoreCase(String.valueOf(r.get("enabled")))));
            }
        }
        return new Config(List.copyOf(regions), tuning);
    }

    private static List<Window> windows(Object node) {
        List<Window> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> w) {
                    int min = toInt(w.get("min"), -1);
                    int max = toInt(w.get("max"), -1);
                    if (min >= 0 && max > min) {
                        out.add(new Window(min, max));
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static double toDouble(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o != null) {
            try {
                return Double.parseDouble(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
