package server.setitem;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Equipment sets and their bonuses (ticket 18, SetEquipV3 addon).
 *
 * <p>v83 has no {@code Etc.wz/SetItemInfo.img}, so the table lives in {@value #DATA_PATH} (resolved by
 * {@link soloMapling.DataFiles}; generated from a later GMS SetItemInfo.img by
 * {@code tools/setequip/gen_set_items.py}, which keeps only items the server's Character.wz has). It is read once,
 * on first use. A set with a mistake (unknown stat key, not an equip id, item already in another set, bad number) is
 * skipped and logged; the rest load.
 * A missing or unreadable file gives an empty catalog, so no set bonuses, never a failed login.
 *
 * <pre>
 * sets:
 *   - id: 6
 *     name: "Marx Von Leon Set"
 *     complete: 6            # pieces for the whole set (only one weapon can be worn)
 *     items:
 *       - 1003154
 *       - 1402091
 *     tiers:
 *       4: {incAllStat: 4}   # gained at 4 pieces; tiers stack
 *       5: {incAllStat: 6, incPAD: 10, incMAD: 10}
 * </pre>
 */
public final class SetItemCatalog {

    private static final Logger log = LoggerFactory.getLogger(SetItemCatalog.class);

    static final String DATA_PATH = "src/main/java/soloMapling/SetEquip/set-items.yaml";

    /** Stat keys {@link client.Character#recalcEquipStats} applies. The file may use only these. */
    public static final Set<String> APPLIED_KEYS = Set.of(
            "incSTR", "incDEX", "incINT", "incLUK", "incAllStat", "incMHP", "incMMP", "incPAD", "incMAD");

    private static final Set<String> SET_KEYS = Set.of("id", "name", "complete", "items", "tiers");
    private static final int MAX_STAT = 10_000;
    private static final int MAX_PIECES = 64;

    public static final SetItemCatalog EMPTY = new SetItemCatalog(List.of());

    private static volatile SetItemCatalog instance;

    private final Map<Integer, ItemSetInfo> setsById;
    private final Map<Integer, Integer> setIdByItem;

    SetItemCatalog(List<ItemSetInfo> sets) {
        Map<Integer, ItemSetInfo> byId = new LinkedHashMap<>();
        Map<Integer, Integer> byItem = new HashMap<>();
        for (ItemSetInfo set : sets) {
            byId.put(set.id(), set);
            for (int item : set.itemIds()) {
                byItem.put(item, set.id());
            }
        }
        this.setsById = Collections.unmodifiableMap(byId);
        this.setIdByItem = Collections.unmodifiableMap(byItem);
    }

    public static SetItemCatalog getInstance() {
        SetItemCatalog c = instance;
        if (c == null) {
            synchronized (SetItemCatalog.class) {
                c = instance;
                if (c == null) {
                    c = load();
                    instance = c;
                }
            }
        }
        return c;
    }

    /** Tests: use {@code catalog} instead of the file ({@code null} reloads it on next use). */
    public static void useForTest(SetItemCatalog catalog) {
        instance = catalog;
    }

    private static SetItemCatalog load() {
        Path file = soloMapling.DataFiles.path(DATA_PATH);
        if (!Files.isRegularFile(file)) {
            log.error("Set items: {} not found, no set bonuses", DATA_PATH);
            return EMPTY;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Item ids are not looked up here: the first recalcEquipStats may run before (or, in tests, without) the
            // item data, and tools/setequip/gen_set_items.py only writes items the server's Character.wz has.
            List<String> problems = new ArrayList<>();
            SetItemCatalog catalog = parse(new YamlReader(in).read(), id -> true, problems);
            problems.forEach(p -> log.warn("Set items: {}", p));
            log.info("Set items: {} sets, {} pieces loaded from {}", catalog.setsById.size(), catalog.setIdByItem.size(), file);
            return catalog;
        } catch (Exception e) {
            log.error("Set items: could not read {}, no set bonuses", file, e);
            return EMPTY;
        }
    }

    /**
     * Builds a catalog from the parsed yaml. Every set that fails a check is left out and described in
     * {@code problems}; a top level that is not {@code sets:} gives an empty catalog.
     */
    static SetItemCatalog parse(Object root, IntPredicate knownItem, List<String> problems) {
        if (!(root instanceof Map<?, ?> top) || !(top.get("sets") instanceof List<?> list)) {
            problems.add("expected a top-level 'sets:' list");
            return EMPTY;
        }
        List<ItemSetInfo> sets = new ArrayList<>();
        Set<Integer> ids = new LinkedHashSet<>();
        Map<Integer, Integer> owner = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                ItemSetInfo set = set(list.get(i), knownItem);
                if (!ids.add(set.id())) {
                    throw new IllegalArgumentException("set id " + set.id() + " is listed twice");
                }
                for (int item : set.itemIds()) {
                    Integer other = owner.get(item);
                    if (other != null) {
                        throw new IllegalArgumentException("item " + item + " is already in set " + other);
                    }
                }
                set.itemIds().forEach(item -> owner.put(item, set.id()));
                sets.add(set);
            } catch (IllegalArgumentException e) {
                problems.add("entry " + (i + 1) + " skipped: " + e.getMessage());
            }
        }
        return new SetItemCatalog(sets);
    }

    private static ItemSetInfo set(Object node, IntPredicate knownItem) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("not a map");
        }
        for (Object key : m.keySet()) {
            if (!SET_KEYS.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("unknown key '" + key + "'");
            }
        }
        int id = number(m.get("id"), "'id'", 0, Integer.MAX_VALUE);
        String where = "set " + id;
        Object nameNode = m.get("name");
        String name = nameNode == null ? "" : nameNode.toString().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException(where + " needs a 'name'");
        }
        if (!(m.get("items") instanceof List<?> itemList) || itemList.isEmpty()) {
            throw new IllegalArgumentException(where + " needs an 'items' list");
        }
        List<Integer> items = new ArrayList<>();
        for (Object it : itemList) {
            int item = number(it, where + " item", 1, Integer.MAX_VALUE);
            if (item / 1_000_000 != 1) {
                throw new IllegalArgumentException(where + ": " + item + " is not an equip");
            }
            if (!knownItem.test(item)) {
                throw new IllegalArgumentException(where + ": item " + item + " is not in the server's data");
            }
            if (items.contains(item)) {
                throw new IllegalArgumentException(where + ": item " + item + " is listed twice");
            }
            items.add(item);
        }
        if (!(m.get("tiers") instanceof Map<?, ?> tierMap) || tierMap.isEmpty()) {
            throw new IllegalArgumentException(where + " needs a 'tiers' map");
        }
        NavigableMap<Integer, Map<String, Integer>> tiers = new TreeMap<>();
        for (Map.Entry<?, ?> tier : tierMap.entrySet()) {
            int pieces = number(tier.getKey(), where + " tier", 1, MAX_PIECES);
            if (!(tier.getValue() instanceof Map<?, ?> statMap) || statMap.isEmpty()) {
                throw new IllegalArgumentException(where + " tier " + pieces + " needs stats");
            }
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (Map.Entry<?, ?> stat : statMap.entrySet()) {
                String key = String.valueOf(stat.getKey());
                if (!APPLIED_KEYS.contains(key)) {
                    throw new IllegalArgumentException(where + " tier " + pieces + ": unknown stat '" + key + "'");
                }
                stats.put(key, number(stat.getValue(), where + " tier " + pieces + " " + key, -MAX_STAT, MAX_STAT));
            }
            if (tiers.put(pieces, stats) != null) {
                throw new IllegalArgumentException(where + " tier " + pieces + " is listed twice");
            }
        }
        int complete = m.get("complete") == null ? tiers.lastKey() : number(m.get("complete"), where + " 'complete'", 1, MAX_PIECES);
        if (complete < tiers.lastKey()) {
            throw new IllegalArgumentException(where + ": 'complete' " + complete + " is below its last tier " + tiers.lastKey());
        }
        return new ItemSetInfo(id, name, complete, items, tiers);
    }

    private static int number(Object value, String what, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(what + " is missing");
        }
        int n;
        try {
            n = Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " '" + value + "' is not a number");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(what + " " + n + " is outside " + min + ".." + max);
        }
        return n;
    }

    /** The set {@code itemId} belongs to, or null. */
    public ItemSetInfo setOf(int itemId) {
        Integer id = setIdByItem.get(itemId);
        return id == null ? null : setsById.get(id);
    }

    public ItemSetInfo get(int setId) {
        return setsById.get(setId);
    }

    public Collection<ItemSetInfo> sets() {
        return setsById.values();
    }

    public boolean isEmpty() {
        return setsById.isEmpty();
    }

    /**
     * Which pieces of each set are worn: set id -> worn item ids, in set order. An item id worn twice (two of the
     * same ring) is one piece, as in GMS.
     */
    public Map<Integer, List<Integer>> wornPieces(Collection<Integer> equippedItemIds) {
        Map<Integer, List<Integer>> worn = new LinkedHashMap<>();
        for (int item : new LinkedHashSet<>(equippedItemIds)) {
            Integer setId = setIdByItem.get(item);
            if (setId != null) {
                worn.computeIfAbsent(setId, k -> new ArrayList<>()).add(item);
            }
        }
        return worn;
    }

    /** Every worn set's stacked tiers, summed over sets. */
    public SetBonus bonusFor(Collection<Integer> equippedItemIds) {
        if (setIdByItem.isEmpty()) {
            return SetBonus.NONE;
        }
        Map<String, Integer> total = new HashMap<>();
        wornPieces(equippedItemIds).forEach((setId, pieces) ->
                setsById.get(setId).cumulativeEffectFor(pieces.size()).forEach((k, v) -> total.merge(k, v, Integer::sum)));
        return total.isEmpty() ? SetBonus.NONE : SetBonus.of(total);
    }
}
