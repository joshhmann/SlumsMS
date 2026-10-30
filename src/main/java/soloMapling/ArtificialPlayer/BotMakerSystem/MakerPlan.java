package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.MakerService;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/*
 * Ticket 112 (owner 2026-10-30): the read-only "maker plan" for players, owned alts and population bots: which equips
 * the character could make with the Maker skill right now that would be an upgrade, ranked by how much attack each
 * adds. One implementation for everyone (owner rule). It never crafts, spends or moves anything.
 *
 * "Right now" means its Maker level and character level are enough and it holds the materials and the mesos (the
 * same checks the Maker window makes). Each item is valued plain (no stimulant, no gems: the stock base stats) through
 * MakerGearScore, which 162-gearopt's optimizer plugs into. Recipes are the stock Maker tables (makercreatedata /
 * makerrecipedata), read once.
 *
 * Ported in spirit from nutnnut/Cosmic 356fbfeb1 (branch dev) src/main/java/server/bots/BotMakerPlanner.java (AGPL-3.0): the same
 * question (craftable equips that are upgrades, best first); upstream also samples the stimulant and gem rolls, which
 * this first version leaves out.
 */
public final class MakerPlan {

    /* One craftable upgrade: the item, how much attack it adds, its fee. */
    public record Entry(int itemId, String name, double gain, int mesoCost) {
    }

    /* A stock Maker recipe for an equip. */
    public record Recipe(int itemId, int reqLevel, int reqMakerLevel, int mesoCost, List<int[]> items) {
    }

    /* Below this an "upgrade" is noise. */
    static final double MIN_GAIN = 0.5;

    private static volatile List<Recipe> recipes;

    private MakerPlan() {
    }

    /* The plan for this character, best first, at most {@code limit} entries; empty without the Maker skill. */
    public static List<Entry> plan(Character chr, int limit) {
        if (chr == null || MakerService.getMakerSkillLevel(chr) < 1) {
            return List.of();
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        return rank(chr, MakerService.getMakerSkillLevel(chr), equipRecipes(),
                id -> chr.getInventory(ItemConstants.getInventoryType(id)).countById(id),
                id -> ii.getEquipById(id) instanceof Equip e ? e : null, ii::getName, MakerGearScore.live(), limit);
    }

    /* The ranking with everything handed in (tests). */
    static List<Entry> rank(Character chr, int makerLevel, Collection<Recipe> recipes, ToIntFunction<Integer> held,
                            IntFunction<Equip> items, IntFunction<String> names, MakerGearScore score, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Recipe r : recipes) {
            if (r.reqMakerLevel() > makerLevel || r.reqLevel() > chr.getLevel() || r.mesoCost() > chr.getMeso()
                    || !holds(r, held)) {
                continue;
            }
            Equip candidate = items.apply(r.itemId());
            if (candidate == null) {
                continue;
            }
            double gain = score.upgradeValue(chr, candidate);
            if (gain >= MIN_GAIN) {
                out.add(new Entry(r.itemId(), names.apply(r.itemId()), gain, r.mesoCost()));
            }
        }
        out.sort(Comparator.comparingDouble(Entry::gain).reversed().thenComparingInt(Entry::itemId));
        return out.size() > limit ? List.copyOf(out.subList(0, limit)) : out;
    }

    private static boolean holds(Recipe r, ToIntFunction<Integer> held) {
        for (int[] req : r.items()) {
            if (held.applyAsInt(req[0]) < req[1]) {
                return false;
            }
        }
        return true;
    }

    /* "Abc (+12.3 attack, 25,000 mesos)" lines for a chat reply. */
    public static List<String> lines(List<Entry> plan) {
        List<String> out = new ArrayList<>();
        for (Entry e : plan) {
            out.add(String.format("%s (+%.1f attack, %,d mesos)", e.name(), e.gain(), e.mesoCost()));
        }
        return out;
    }

    /* The stock equip recipes, read once from the Maker tables; empty if the database cannot be read. */
    static List<Recipe> equipRecipes() {
        List<Recipe> cached = recipes;
        if (cached != null) {
            return cached;
        }
        Map<Integer, Recipe> byId = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT itemid, req_level, req_maker_level, req_meso "
                    + "FROM makercreatedata WHERE itemid BETWEEN 1000000 AND 1999999");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("itemid");
                    byId.put(id, new Recipe(id, rs.getInt("req_level"), rs.getInt("req_maker_level"),
                            rs.getInt("req_meso"), new ArrayList<>()));
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT itemid, req_item, count FROM makerrecipedata "
                    + "WHERE itemid BETWEEN 1000000 AND 1999999");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Recipe r = byId.get(rs.getInt("itemid"));
                    if (r != null) {
                        r.items().add(new int[]{rs.getInt("req_item"), rs.getInt("count")});
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            return List.of();   // not cached: the next ask tries again
        }
        List<Recipe> loaded = List.copyOf(byId.values());
        recipes = loaded;
        return loaded;
    }
}
