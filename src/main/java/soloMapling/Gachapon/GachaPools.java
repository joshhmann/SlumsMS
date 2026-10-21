package soloMapling.Gachapon;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.DataFiles;

import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.5.0: the gachapon machines, read from {@code gachapon-pools.yaml} (the owner's data file).
 * <p>
 * A machine belongs to a town. A roll picks a {@link Tier} by its weight, then one item of that tier from the town's
 * list plus the shared list, by the item's own weight. A tier a machine has nothing in is skipped and the other tiers
 * share its weight, so the odds this class reports ({@link #odds}) are exactly the odds a roll has. The same file is
 * checked and its odds printed by {@code tools/gachapon/gachapon_pools.py}.
 * <p>
 * A broken entry (bad syntax, an id the item data does not know) is skipped and reported; the rest still load. A file
 * that cannot be read at all leaves the machines empty (a ticket is never taken for an empty machine).
 */
public final class GachaPools {

    private static final Logger log = LoggerFactory.getLogger(GachaPools.class);

    public static final String FILE = "src/main/java/soloMapling/Gachapon/gachapon-pools.yaml";

    public enum Tier {
        COMMON, UNCOMMON, RARE, JACKPOT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Tier of(String s) {
            for (Tier t : values()) {
                if (t.key().equalsIgnoreCase(s == null ? "" : s.trim())) {
                    return t;
                }
            }
            return null;
        }
    }

    /** One line of a pool: the item, how many a win gives, and its weight inside its tier. */
    public record Entry(int itemId, int quantity, int weight) {
    }

    /** What a roll gave. */
    public record Prize(String town, Tier tier, int itemId, int quantity, boolean announce) {
    }

    /** A machine: the town key, the name the notices use, its NPC, the maps a bot may play it from, and its lists. */
    public record Town(String key, String name, int npcId, List<Integer> maps, Map<Tier, List<Entry>> lists) {
    }

    /** A tier's chance per ticket and each item's chance per ticket, after empty tiers were skipped. */
    public record TierOdds(Tier tier, double chance, List<ItemOdds> items) {
    }

    public record ItemOdds(int itemId, int quantity, double chance) {
    }

    private static final Pattern ENTRY = Pattern.compile("^(\\d{7})(?:\\s+x(\\d+))?(?:\\s+w(\\d+))?$");

    private final Map<Tier, Integer> tierWeights;
    private final Set<Tier> announced;
    private final Map<Tier, List<Entry>> shared;
    private final Map<String, Town> towns;
    private final List<String> problems;

    private GachaPools(Map<Tier, Integer> tierWeights, Set<Tier> announced, Map<Tier, List<Entry>> shared,
                       Map<String, Town> towns, List<String> problems) {
        this.tierWeights = tierWeights;
        this.announced = announced;
        this.shared = shared;
        this.towns = towns;
        this.problems = problems;
    }

    // ---- the shared instance ----

    private static volatile GachaPools current;

    /** The machines the server uses: read on first use, from {@link #FILE} through {@link DataFiles}. */
    public static GachaPools get() {
        GachaPools p = current;
        if (p == null) {
            synchronized (GachaPools.class) {
                p = current;
                if (p == null) {
                    p = loadDefault();
                    current = p;
                }
            }
        }
        return p;
    }

    /** Re-reads the file ({@code !gachareload}); the old machines stay when the new file does not load. */
    public static synchronized GachaPools reload() {
        GachaPools p = loadDefault();
        if (!p.towns.isEmpty()) {
            current = p;
        }
        return p;
    }

    static void setForTest(GachaPools p) {
        current = p;
    }

    private static GachaPools loadDefault() {
        IntPredicate known = id -> {
            try {
                server.ItemInformationProvider ii = server.ItemInformationProvider.getInstance();
                String name = ii.getName(id);
                return name != null && !name.isBlank();
            } catch (LinkageError | RuntimeException noItemData) {
                return true;   // no item data in this process (a tool or a test): the file's own check stands
            }
        };
        try (Reader in = new FileReader(DataFiles.file(FILE))) {
            GachaPools p = parse(in, known);
            for (String s : p.problems) {
                log.warn("[Gachapon] {}", s);
            }
            log.info("[Gachapon] {} machines loaded, {} problems", p.towns.size(), p.problems.size());
            return p;
        } catch (Exception e) {
            log.error("[Gachapon] could not read {}: the machines are empty", FILE, e);
            return new GachaPools(Map.of(), Set.of(), Map.of(), Map.of(), List.of("unreadable: " + e));
        }
    }

    // ---- reading ----

    /** Reads a pool file. {@code known} says whether an item id exists (the item data); unknown ids are skipped. */
    public static GachaPools parse(Reader in, IntPredicate known) throws Exception {
        Object root = new YamlReader(in).read();
        List<String> problems = new ArrayList<>();
        if (!(root instanceof Map<?, ?> rootMap)) {
            problems.add("the file is not a map");
            return new GachaPools(Map.of(), Set.of(), Map.of(), Map.of(), problems);
        }
        Map<Tier, Integer> weights = new EnumMap<>(Tier.class);
        Set<Tier> announced = new HashSet<>();
        if (rootMap.get("tiers") instanceof Map<?, ?> tiers) {
            for (Map.Entry<?, ?> e : tiers.entrySet()) {
                Tier t = Tier.of(String.valueOf(e.getKey()));
                if (t == null) {
                    problems.add("tiers: unknown tier '" + e.getKey() + "'");
                    continue;
                }
                if (e.getValue() instanceof Map<?, ?> m) {
                    int w = intOf(m.get("weight"), 0);
                    if (w <= 0) {
                        problems.add("tiers." + t.key() + ": weight must be > 0");
                        continue;
                    }
                    weights.put(t, w);
                    if ("true".equalsIgnoreCase(String.valueOf(m.get("announce")))) {
                        announced.add(t);
                    }
                }
            }
        }
        if (weights.isEmpty()) {
            problems.add("no 'tiers:' with weights");
        }
        Set<Integer> sharedIds = new HashSet<>();
        Map<Tier, List<Entry>> shared = rootMap.get("shared") instanceof Map<?, ?> s
                ? lists("shared", s, known, problems, sharedIds, Set.of())
                : emptyLists();
        Map<String, Town> towns = new LinkedHashMap<>();
        Set<Integer> npcs = new HashSet<>();
        if (rootMap.get("towns") instanceof Map<?, ?> ts) {
            for (Map.Entry<?, ?> e : ts.entrySet()) {
                String key = String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    problems.add("towns." + key + ": not a map");
                    continue;
                }
                int npc = intOf(m.get("npc"), 0);
                if (npc <= 0 || !npcs.add(npc)) {
                    problems.add("towns." + key + ": missing or repeated npc id '" + m.get("npc") + "'");
                    continue;
                }
                List<Integer> maps = new ArrayList<>();
                if (m.get("maps") instanceof List<?> ml) {
                    for (Object o : ml) {
                        int id = intOf(o, 0);
                        if (id > 0) {
                            maps.add(id);
                        }
                    }
                }
                Object nm = m.get("name");
                Map<Tier, List<Entry>> own = lists("towns." + key, m, known, problems, new HashSet<>(), sharedIds);
                towns.put(key, new Town(key, nm == null ? key : String.valueOf(nm), npc,
                        Collections.unmodifiableList(maps), own));
            }
        }
        if (towns.isEmpty()) {
            problems.add("no 'towns:'");
        }
        return new GachaPools(weights, announced, shared, Collections.unmodifiableMap(towns),
                Collections.unmodifiableList(problems));
    }

    private static Map<Tier, List<Entry>> emptyLists() {
        Map<Tier, List<Entry>> out = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) {
            out.put(t, List.of());
        }
        return out;
    }

    private static Map<Tier, List<Entry>> lists(String where, Map<?, ?> node, IntPredicate known, List<String> problems,
                                                 Set<Integer> seen, Set<Integer> sharedIds) {
        Map<Tier, List<Entry>> out = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) {
            List<Entry> entries = new ArrayList<>();
            if (node.get(t.key()) instanceof List<?> raw) {
                for (Object o : raw) {
                    String s = String.valueOf(o).trim();
                    Matcher mt = ENTRY.matcher(s);
                    if (!mt.matches()) {
                        problems.add(where + "." + t.key() + ": bad entry '" + s + "'");
                        continue;
                    }
                    int id = Integer.parseInt(mt.group(1));
                    int qty = mt.group(2) == null ? 1 : Integer.parseInt(mt.group(2));
                    int w = mt.group(3) == null ? 1 : Integer.parseInt(mt.group(3));
                    if (qty <= 0 || qty > Short.MAX_VALUE || w <= 0) {
                        problems.add(where + "." + t.key() + ": '" + s + "' needs a quantity and weight > 0");
                        continue;
                    }
                    if (!known.test(id)) {
                        problems.add(where + "." + t.key() + ": " + id + " is not a known item, skipped");
                        continue;
                    }
                    if (!seen.add(id) || sharedIds.contains(id)) {
                        problems.add(where + "." + t.key() + ": " + id + " is listed twice, the repeat skipped");
                        continue;
                    }
                    entries.add(new Entry(id, qty, w));
                }
            }
            out.put(t, Collections.unmodifiableList(entries));
        }
        return out;
    }

    private static int intOf(Object o, int fallback) {
        if (o == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- lookups ----

    public List<String> problems() {
        return problems;
    }

    public Map<String, Town> towns() {
        return towns;
    }

    public Town town(String key) {
        return key == null ? null : towns.get(key.trim().toLowerCase(Locale.ROOT));
    }

    public Town byNpc(int npcId) {
        for (Town t : towns.values()) {
            if (t.npcId() == npcId) {
                return t;
            }
        }
        return null;
    }

    /** The machine a bot standing on this map plays (it plays it as a remote ticket would). */
    public Town byMap(int mapId) {
        for (Town t : towns.values()) {
            if (t.maps().contains(mapId)) {
                return t;
            }
        }
        return null;
    }

    /** A town by key, by its name, or by the start of either ("hene", "kerning city", "nlc" for new_leaf_city). */
    public Town find(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Town exact = town(q);
        if (exact != null) {
            return exact;
        }
        for (Town t : towns.values()) {
            String name = t.name().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (name.equals(q) || t.key().startsWith(q) || name.startsWith(q) || initials(t.key()).equals(q)) {
                return t;
            }
        }
        return null;
    }

    private static String initials(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0));
            }
        }
        return sb.toString();
    }

    public boolean announces(Tier tier) {
        return announced.contains(tier);
    }

    /** A town's list of a tier plus the shared list of that tier. */
    public List<Entry> machine(Town town, Tier tier) {
        List<Entry> own = town.lists().getOrDefault(tier, List.of());
        List<Entry> sh = shared.getOrDefault(tier, List.of());
        if (sh.isEmpty()) {
            return own;
        }
        List<Entry> all = new ArrayList<>(own.size() + sh.size());
        all.addAll(own);
        all.addAll(sh);
        return all;
    }

    // ---- rolling ----

    /** One ticket at this machine; null when the machine gives nothing at all. */
    public Prize roll(Town town, Random rng) {
        if (town == null) {
            return null;
        }
        Map<Tier, List<Entry>> live = new EnumMap<>(Tier.class);
        int total = 0;
        for (Tier t : Tier.values()) {
            List<Entry> e = machine(town, t);
            int w = tierWeights.getOrDefault(t, 0);
            if (!e.isEmpty() && w > 0) {
                live.put(t, e);
                total += w;
            }
        }
        if (total <= 0) {
            return null;
        }
        int pick = rng.nextInt(total);
        Tier tier = null;
        for (Tier t : live.keySet()) {
            pick -= tierWeights.get(t);
            if (pick < 0) {
                tier = t;
                break;
            }
        }
        List<Entry> entries = live.get(tier);
        int sum = 0;
        for (Entry e : entries) {
            sum += e.weight();
        }
        int p = rng.nextInt(sum);
        for (Entry e : entries) {
            p -= e.weight();
            if (p < 0) {
                return new Prize(town.key(), tier, e.itemId(), e.quantity(), announces(tier));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** A prize of one given tier at this machine (a QA step that needs a rare on demand); null when it has none. */
    public Prize rollTier(Town town, Tier tier, Random rng) {
        List<Entry> entries = town == null ? List.of() : machine(town, tier);
        if (entries.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (Entry e : entries) {
            sum += e.weight();
        }
        int p = rng.nextInt(sum);
        for (Entry e : entries) {
            p -= e.weight();
            if (p < 0) {
                return new Prize(town.key(), tier, e.itemId(), e.quantity(), announces(tier));
            }
        }
        return null;
    }

    /** The exact odds of one ticket at this machine. */
    public List<TierOdds> odds(Town town) {
        List<TierOdds> out = new ArrayList<>();
        int total = 0;
        for (Tier t : Tier.values()) {
            if (!machine(town, t).isEmpty()) {
                total += tierWeights.getOrDefault(t, 0);
            }
        }
        if (total <= 0) {
            return out;
        }
        for (Tier t : Tier.values()) {
            List<Entry> e = machine(town, t);
            int w = tierWeights.getOrDefault(t, 0);
            if (e.isEmpty() || w <= 0) {
                continue;
            }
            double tp = (double) w / total;
            int sum = 0;
            for (Entry x : e) {
                sum += x.weight();
            }
            List<ItemOdds> items = new ArrayList<>(e.size());
            for (Entry x : e) {
                items.add(new ItemOdds(x.itemId(), x.quantity(), tp * x.weight() / sum));
            }
            out.add(new TierOdds(t, tp, items));
        }
        return out;
    }

    /** Rolls {@code n} tickets without giving anything: how many of each tier and of each item came out. */
    public Simulation simulate(Town town, int n, Random rng) {
        Map<Tier, Integer> tiers = new EnumMap<>(Tier.class);
        Map<Integer, Integer> items = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Prize p = roll(town, rng);
            if (p == null) {
                break;
            }
            tiers.merge(p.tier(), 1, Integer::sum);
            items.merge(p.itemId(), 1, Integer::sum);
        }
        return new Simulation(n, tiers, items);
    }

    public record Simulation(int rolls, Map<Tier, Integer> tiers, Map<Integer, Integer> items) {
    }
}
