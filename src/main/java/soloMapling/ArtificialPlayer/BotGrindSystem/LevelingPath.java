package soloMapling.ArtificialPlayer.BotGrindSystem;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Ticket 31: the leveling path as data. LevelingPath.yaml lists level bands; each band names, per job group, the
// maps bots of that level and job train on (weighted), with a `general` list for every job. This class only reads
// the file and answers "which entries does a bot of this level and job draw from"; LevelingPathChooser filters
// them against the live world (gates, routes, crowding) and picks one. An unreadable or empty file leaves the
// path empty, and every bot keeps the pre-ticket-31 behaviour. Ours (SoloMapling).
public final class LevelingPath {

    static final String YAML_PATH = "src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.yaml";
    public static final String GENERAL = "general";
    static final int MAX_LEVEL = 255;

    // One spot. weight is the relative pick weight inside the band and group; party = a spot the guides
    // describe as a party map (mode: party).
    public record Entry(int mapId, double weight, boolean party, String name) {
    }

    // Levels [lo, hi); the last band also covers hi itself (255).
    public record Band(int lo, int hi, String label, Map<String, List<Entry>> groups) {
        public List<Entry> group(String name) {
            return groups.getOrDefault(name, List.of());
        }
    }

    // A job-id range [lo, hi] belonging to a group.
    record JobRange(int lo, int hi, String group) {
        boolean contains(int jobId) {
            return jobId >= lo && jobId <= hi;
        }
    }

    public record Path(List<Band> bands, List<JobRange> jobs) {
        public static final Path EMPTY = new Path(List.of(), List.of());

        public boolean isEmpty() {
            return bands.isEmpty();
        }

        // The band holding this level, or -1.
        public int bandIndex(int level) {
            for (int i = 0; i < bands.size(); i++) {
                Band b = bands.get(i);
                boolean last = i == bands.size() - 1;
                if (level >= b.lo() && (level < b.hi() || (last && level <= Math.max(b.hi(), MAX_LEVEL)))) {
                    return i;
                }
            }
            return -1;
        }

        // The job group a job id trains with: the first job_groups range holding it, else an `inherits` range
        // (which maps straight to the group it inherits from), else general.
        public String groupOf(int jobId) {
            for (JobRange r : jobs) {
                if (r.contains(jobId)) {
                    return r.group();
                }
            }
            return GENERAL;
        }

        // The entries a bot of this job draws from in band `index`: its group's entries at full weight, with the
        // band's general entries mixed in at generalMix x their weight (a map in both keeps the larger weight).
        // A group with no entries in the band uses the general list at full weight.
        public List<Entry> candidates(int index, int jobId, double generalMix) {
            if (index < 0 || index >= bands.size()) {
                return List.of();
            }
            Band band = bands.get(index);
            String group = groupOf(jobId);
            List<Entry> own = GENERAL.equals(group) ? List.of() : band.group(group);
            if (own.isEmpty()) {
                return band.group(GENERAL);
            }
            Map<Integer, Entry> byMap = new LinkedHashMap<>();
            for (Entry e : own) {
                byMap.merge(e.mapId(), e, (a, b) -> a.weight() >= b.weight() ? a : b);
            }
            if (generalMix > 0) {
                for (Entry e : band.group(GENERAL)) {
                    Entry mixed = new Entry(e.mapId(), e.weight() * generalMix, e.party(), e.name());
                    byMap.merge(e.mapId(), mixed, (a, b) -> a.weight() >= b.weight() ? a : b);
                }
            }
            return List.copyOf(byMap.values());
        }

        public int entryCount() {
            int n = 0;
            for (Band b : bands) {
                for (List<Entry> es : b.groups().values()) {
                    n += es.size();
                }
            }
            return n;
        }
    }

    private static volatile Path cached;

    private LevelingPath() {
    }

    public static Path path() {
        Path local = cached;
        if (local == null) {
            local = load();
            cached = local;
        }
        return local;
    }

    // Swap in a path (tests). Null drops back to lazily reading the YAML.
    public static void install(Path path) {
        cached = path;
    }

    public static Path reload() {
        install(load());
        return cached;
    }

    // ── Loading ──

    private static Path load() {
        try (FileReader in = new FileReader(YAML_PATH)) {
            Path p = parse(in);
            if (p.isEmpty()) {
                System.out.println("[LevelingPath] " + YAML_PATH + " lists no bands - bots keep the old map choice");
            } else {
                System.out.println("[LevelingPath] loaded " + p.bands().size() + " bands, " + p.entryCount()
                        + " entries");
            }
            return p;
        } catch (Exception e) {
            System.out.println("[LevelingPath] failed to load " + YAML_PATH + ": " + e.getMessage()
                    + " - bots keep the old map choice");
            return Path.EMPTY;
        }
    }

    static Path parse(Reader in) throws Exception {
        Object root = new YamlReader(in).read();
        if (!(root instanceof Map<?, ?> rootMap)) {
            return Path.EMPTY;
        }
        List<JobRange> jobs = new ArrayList<>();
        if (rootMap.get("job_groups") instanceof Map<?, ?> groups) {
            for (Map.Entry<?, ?> g : groups.entrySet()) {
                jobRanges(g.getValue(), String.valueOf(g.getKey()), jobs);
            }
        }
        if (rootMap.get("inherits") instanceof Map<?, ?> inherits) {
            for (Object v : inherits.values()) {
                if (v instanceof Map<?, ?> m && m.get("from") != null) {
                    jobRanges(m.get("jobs"), String.valueOf(m.get("from")).trim(), jobs);
                }
            }
        }
        List<Band> bands = new ArrayList<>();
        if (rootMap.get("bands") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> b) || !(b.get("levels") instanceof List<?> lv) || lv.size() < 2) {
                    continue;
                }
                int lo = toInt(lv.get(0), -1);
                int hi = toInt(lv.get(1), -1);
                if (lo < 0 || hi <= lo) {
                    System.out.println("[LevelingPath] skipped a band with bad levels: " + lv);
                    continue;
                }
                Map<String, List<Entry>> groups = new LinkedHashMap<>();
                if (b.get("groups") instanceof Map<?, ?> gs) {
                    for (Map.Entry<?, ?> g : gs.entrySet()) {
                        List<Entry> entries = entries(g.getValue());
                        if (!entries.isEmpty()) {
                            groups.put(String.valueOf(g.getKey()).trim(), entries);
                        }
                    }
                }
                bands.add(new Band(lo, hi, b.get("label") == null ? "" : String.valueOf(b.get("label")),
                        Map.copyOf(groups)));
            }
        }
        bands.sort((x, y) -> Integer.compare(x.lo(), y.lo()));
        return new Path(List.copyOf(bands), List.copyOf(jobs));
    }

    private static List<Entry> entries(Object node) {
        List<Entry> out = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) {
                    continue;
                }
                int map = toInt(e.get("map"), -1);
                double w = toDouble(e.get("w"), 1.0);
                if (map <= 0 || w <= 0) {
                    continue;
                }
                boolean party = "party".equalsIgnoreCase(String.valueOf(e.get("mode")).trim());
                out.add(new Entry(map, w, party, e.get("name") == null ? "" : String.valueOf(e.get("name"))));
            }
        }
        return List.copyOf(out);
    }

    // "[100-132]" / "[200, 210-212]": single ids and inclusive ranges.
    private static void jobRanges(Object node, String group, List<JobRange> out) {
        if (!(node instanceof List<?> list)) {
            return;
        }
        for (Object o : list) {
            String s = String.valueOf(o).trim();
            int dash = s.indexOf('-', 1);
            int lo = toInt(dash > 0 ? s.substring(0, dash) : s, -1);
            int hi = dash > 0 ? toInt(s.substring(dash + 1), -1) : lo;
            if (lo >= 0 && hi >= lo) {
                out.add(new JobRange(lo, hi, group));
            }
        }
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
