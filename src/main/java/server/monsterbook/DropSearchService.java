package server.monsterbook;

import client.Character;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.life.MonsterInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server half of the Monster Book addon (ticket 19): everything the book cannot know from its own WZ data.
 *
 * <ul>
 *   <li>{@link #mobDropChances} - one mob's drop table, with each chance at the asking player's live rates
 *       (the % printed under each Dropping icon);</li>
 *   <li>{@link #findBookItems} - item-name search, limited to items a carded mob drops;</li>
 *   <li>{@link #itemDroppers} - the carded mobs that drop one item, each with its own chance.</li>
 * </ul>
 *
 * <p>Everything is answered from two in-memory indexes, so a query never touches the database:
 * <ul>
 *   <li>a drop snapshot: {@code drop_data} and {@code monstercarddata}, read once in two queries on first use;</li>
 *   <li>a name index of every item String.wz names, built once.</li>
 * </ul>
 * {@code !reloaddrops} calls {@link #clearCaches()}, so an edited drop table is current from the next query.
 * The names never change while the server runs.
 *
 * <p>The chance math is the roll in {@code MapleMap.dropItemsFromMonsterOnMap}: {@code chance x (boss ? bossDropRate
 * : dropRate) x itemRate}, out of 1,000,000. It leaves out the per-kill modifiers (Showdown, quest gating, global
 * drops), which belong to a kill, not to the monster.
 */
public final class DropSearchService {
    private static final Logger log = LoggerFactory.getLogger(DropSearchService.class);

    /** The roll's denominator: {@code Randomizer.nextInt(999999) < chance}. */
    public static final int DROP_DENOMINATOR = 1_000_000;
    public static final int MIN_QUERY_LENGTH = 3;
    public static final int MAX_RESULTS = 200;

    /** One {@code drop_data} row worth showing: a real item with a real chance. */
    public record DropRow(int mobId, int itemId, int chance) {
    }

    /** Where the indexes come from. The live one reads the database and WZ; tests hand in plain data. */
    public interface Source {
        /** Every {@code drop_data} row with {@code itemid > 0} and {@code chance > 0}. */
        List<DropRow> drops() throws SQLException;

        /** Mob id to card id: {@code monstercarddata} plus the Item.wz card list. */
        Map<Integer, Integer> cardByMob() throws SQLException;

        /** Every named item: id and String.wz name. */
        List<Pair<Integer, String>> itemNames();

        boolean isBoss(int mobId);
    }

    /** The asking player's live drop rates. */
    public interface Rates {
        int dropRate();

        int bossDropRate();

        /** The item-up multiplier for one item (1.0 without a buff), as {@code Character.getCardRate}. */
        double itemRate(int itemId);

        static Rates of(Character chr) {
            return new Rates() {
                @Override
                public int dropRate() {
                    return chr.getDropRate();
                }

                @Override
                public int bossDropRate() {
                    return chr.getBossDropRate();
                }

                @Override
                public double itemRate(int itemId) {
                    return chr.getCardRate(itemId);
                }
            };
        }
    }

    private record Snapshot(Map<Integer, List<DropRow>> byMob, Map<Integer, List<DropRow>> byItem,
                            Map<Integer, Integer> cardByMob, Set<Integer> bookItems) {
    }

    private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Set.of());

    private record NamedItem(int id, String name, String lower) {
    }

    private static final DropSearchService INSTANCE = new DropSearchService(new LiveSource());

    public static DropSearchService getInstance() {
        return INSTANCE;
    }

    private final Source source;
    private volatile Snapshot snapshot;
    private volatile List<NamedItem> names;
    private volatile Map<Integer, String> nameById;
    private final Map<Integer, Boolean> bossByMob = new ConcurrentHashMap<>();

    public DropSearchService(Source source) {
        this.source = source;
    }

    /** Drops the drop snapshot (for {@code !reloaddrops}); the next query re-reads the tables. */
    public void clearCaches() {
        snapshot = null;
    }

    /**
     * One mob's drops as item id to chance in parts per million, best chance first (item id breaks ties).
     * An item listed under several quests keeps its best row. Items with no name are left out: the client
     * could not draw them.
     */
    public LinkedHashMap<Integer, Integer> mobDropChances(Rates rates, int mobId) {
        Snapshot s = snapshot();
        Map<Integer, String> named = nameById();
        int rate = isBoss(mobId) ? rates.bossDropRate() : rates.dropRate();
        Map<Integer, Integer> best = new HashMap<>();
        for (DropRow row : s.byMob().getOrDefault(mobId, List.of())) {
            if (!named.containsKey(row.itemId())) {
                continue;
            }
            best.merge(row.itemId(), ppm(row.chance(), rate, rates.itemRate(row.itemId())), Math::max);
        }
        return sorted(best);
    }

    /**
     * Mobs with a monster card that drop {@code itemId}, as mob id to chance in parts per million, best first,
     * at most {@link #MAX_RESULTS}. Card-less mobs are left out because the book draws the answer as card icons.
     */
    public LinkedHashMap<Integer, Integer> itemDroppers(Rates rates, int itemId) {
        Snapshot s = snapshot();
        double itemRate = rates.itemRate(itemId);
        Map<Integer, Integer> best = new HashMap<>();
        for (DropRow row : s.byItem().getOrDefault(itemId, List.of())) {
            if (!s.cardByMob().containsKey(row.mobId())) {
                continue;
            }
            int rate = isBoss(row.mobId()) ? rates.bossDropRate() : rates.dropRate();
            best.merge(row.mobId(), ppm(row.chance(), rate, itemRate), Math::max);
        }
        LinkedHashMap<Integer, Integer> all = sorted(best);
        if (all.size() <= MAX_RESULTS) {
            return all;
        }
        LinkedHashMap<Integer, Integer> capped = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : all.entrySet()) {
            if (capped.size() == MAX_RESULTS) {
                break;
            }
            capped.put(e.getKey(), e.getValue());
        }
        return capped;
    }

    /**
     * Item ids whose name contains {@code query} (case-insensitive), limited to items a carded mob drops, so a hit
     * never opens an empty card grid. Exact names first, then names starting with the query, then the rest; at most
     * {@link #MAX_RESULTS}. A query shorter than {@link #MIN_QUERY_LENGTH} matches nothing.
     */
    public int[] findBookItems(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < MIN_QUERY_LENGTH) {
            return new int[0];
        }
        Set<Integer> allowed = snapshot().bookItems();
        List<NamedItem> hits = new ArrayList<>();
        for (NamedItem item : names()) {
            if (allowed.contains(item.id()) && item.lower().contains(needle)) {
                hits.add(item);
            }
        }
        hits.sort(Comparator.<NamedItem>comparingInt(i -> rank(i.lower(), needle))
                .thenComparing(NamedItem::lower).thenComparingInt(NamedItem::id));
        return hits.stream().limit(MAX_RESULTS).mapToInt(NamedItem::id).toArray();
    }

    private boolean isBoss(int mobId) {
        return bossByMob.computeIfAbsent(mobId, source::isBoss);
    }

    static int ppm(int chance, int rate, double itemRate) {
        double eff = (double) chance * rate * itemRate;
        long rounded = Math.round(eff);
        return (int) Math.max(1, Math.min(DROP_DENOMINATOR, rounded));
    }

    private static int rank(String name, String needle) {
        if (name.equals(needle)) {
            return 0;
        }
        return name.startsWith(needle) ? 1 : 2;
    }

    private static LinkedHashMap<Integer, Integer> sorted(Map<Integer, Integer> chances) {
        List<Map.Entry<Integer, Integer>> rows = new ArrayList<>(chances.entrySet());
        rows.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        LinkedHashMap<Integer, Integer> ret = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> row : rows) {
            ret.put(row.getKey(), row.getValue());
        }
        return ret;
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (snapshot != null) {
                return snapshot;
            }
            Snapshot loaded = load();
            if (loaded != null) {
                snapshot = loaded;
                return loaded;
            }
            return EMPTY;
        }
    }

    private Snapshot load() {
        List<DropRow> rows;
        Map<Integer, Integer> cards;
        try {
            rows = source.drops();
            cards = source.cardByMob();
        } catch (SQLException e) {
            // not cached: the next query tries again instead of the book staying empty until a reload
            log.error("Monster Book: could not read drop_data / monstercarddata", e);
            return null;
        }
        Map<Integer, List<DropRow>> byMob = new HashMap<>();
        Map<Integer, List<DropRow>> byItem = new HashMap<>();
        Set<Integer> bookItems = new HashSet<>();
        for (DropRow row : rows) {
            if (row.itemId() <= 0 || row.chance() <= 0) {
                continue;
            }
            byMob.computeIfAbsent(row.mobId(), k -> new ArrayList<>()).add(row);
            byItem.computeIfAbsent(row.itemId(), k -> new ArrayList<>()).add(row);
            if (cards.containsKey(row.mobId())) {
                bookItems.add(row.itemId());
            }
        }
        log.info("Monster Book: indexed {} drop rows over {} mobs, {} card mobs", rows.size(), byMob.size(), cards.size());
        return new Snapshot(byMob, byItem, Map.copyOf(cards), bookItems);
    }

    private List<NamedItem> names() {
        List<NamedItem> n = names;
        if (n != null) {
            return n;
        }
        synchronized (this) {
            if (names == null) {
                List<NamedItem> list = new ArrayList<>();
                Map<Integer, String> byId = new HashMap<>();
                for (Pair<Integer, String> item : source.itemNames()) {
                    String name = item.getRight();
                    if (name == null || name.isBlank() || name.equals("NO-NAME") || byId.containsKey(item.getLeft())) {
                        continue;
                    }
                    byId.put(item.getLeft(), name);
                    list.add(new NamedItem(item.getLeft(), name, name.toLowerCase(Locale.ROOT)));
                }
                nameById = Collections.unmodifiableMap(byId);
                names = Collections.unmodifiableList(list);
            }
            return names;
        }
    }

    private Map<Integer, String> nameById() {
        names();
        return nameById;
    }

    /** The real server: {@code drop_data} and {@code monstercarddata} from the database, names from String.wz. */
    static final class LiveSource implements Source {
        @Override
        public List<DropRow> drops() throws SQLException {
            List<DropRow> rows = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT dropperid, itemid, chance FROM drop_data WHERE itemid > 0 AND chance > 0");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DropRow(rs.getInt(1), rs.getInt(2), rs.getInt(3)));
                }
            }
            return rows;
        }

        @Override
        public Map<Integer, Integer> cardByMob() throws SQLException {
            Map<Integer, Integer> cards = new HashMap<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement("SELECT cardid, mobid FROM monstercarddata");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (ItemId.isMonsterCard(rs.getInt(1))) {
                        cards.putIfAbsent(rs.getInt(2), rs.getInt(1));
                    }
                }
            }
            // The client draws every card in Item/Consume/0238.img, and monstercarddata can lag behind it
            // (a card with art but no row), so the WZ card list counts too.
            try {
                Data consume = DataProviderFactory.getDataProvider(WZFiles.ITEM).getData("Consume/0238.img");
                for (Data card : consume == null ? List.<Data>of() : consume.getChildren()) {
                    int mob = DataTool.getInt("info/mob", card, 0);
                    if (mob > 0) {
                        cards.putIfAbsent(mob, Integer.parseInt(card.getName()));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Monster Book: could not read the card list from Item.wz", e);
            }
            return cards;
        }

        @Override
        public List<Pair<Integer, String>> itemNames() {
            return ItemInformationProvider.getInstance().getAllItems();
        }

        @Override
        public boolean isBoss(int mobId) {
            return MonsterInformationProvider.getInstance().isBoss(mobId);
        }
    }
}
