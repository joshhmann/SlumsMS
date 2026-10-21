package server.checkin;

import com.esotericsoftware.yamlbeans.YamlReader;
import server.checkin.DailyCheckinRewards.Grant;
import server.checkin.DailyCheckinRewards.Reward;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Reads the Daily Check-In reward table from yaml. Strict: any unknown key, missing day, bad number or unknown item id
 * rejects the whole file (the caller then keeps the built-in table), so a typo can never hand out a wrong reward.
 *
 * <pre>
 * days:
 *   - day: 7
 *     icon: 5211046          # item whose icon the window shows (defaults to the first item)
 *     nx: 5000               # NX Credit
 *     mesos: 0
 *     slots: equip 4         # equip | use | setup | etc, then the count
 *     items:
 *       - id: 5211046
 *         qty: 1
 *         days: 1            # expires a day after the claim; leave out for permanent
 * </pre>
 */
final class DailyCheckinRewardsYaml {

    private static final Set<String> DAY_KEYS = Set.of("day", "icon", "nx", "mesos", "slots", "items");
    private static final Set<String> ITEM_KEYS = Set.of("id", "qty", "days");
    private static final int MAX_NX = 1_000_000;
    private static final int MAX_MESOS = 100_000_000;

    private DailyCheckinRewardsYaml() {
    }

    static List<Reward> read(Path file, IntPredicate knownItem) throws IOException {
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(new YamlReader(in).read(), knownItem);
        }
    }

    static List<Reward> parse(Object root, IntPredicate knownItem) {
        if (!(root instanceof Map<?, ?> top) || !(top.get("days") instanceof List<?> list)) {
            throw new IllegalArgumentException("expected a top-level 'days:' list");
        }
        if (top.size() != 1) {
            throw new IllegalArgumentException("unknown top-level keys " + top.keySet());
        }
        if (list.size() != DailyCheckinStreak.CYCLE_DAYS) {
            throw new IllegalArgumentException("expected " + DailyCheckinStreak.CYCLE_DAYS + " days, found " + list.size());
        }
        List<Reward> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            out.add(day(i + 1, list.get(i), knownItem));
        }
        return List.copyOf(out);
    }

    private static Reward day(int expected, Object node, IntPredicate knownItem) {
        String where = "day " + expected;
        Map<?, ?> m = map(node, where, DAY_KEYS);
        int day = number(m.get("day"), where + " 'day'", 1, DailyCheckinStreak.CYCLE_DAYS, -1);
        if (day != expected) {
            throw new IllegalArgumentException("days must be listed 1.." + DailyCheckinStreak.CYCLE_DAYS
                    + " in order; entry " + expected + " says day " + day);
        }
        int nx = number(m.get("nx"), where + " 'nx'", 0, MAX_NX, 0);
        int mesos = number(m.get("mesos"), where + " 'mesos'", 0, MAX_MESOS, 0);

        int slotType = 0;
        int slotCount = 0;
        if (m.get("slots") != null) {
            String[] parts = m.get("slots").toString().trim().toLowerCase().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException(where + " 'slots' must look like 'equip 4'");
            }
            slotType = switch (parts[0]) {
                case "equip" -> DailyCheckinRewards.EQUIP;
                case "use" -> DailyCheckinRewards.USE;
                case "setup", "set-up" -> DailyCheckinRewards.SETUP;
                case "etc" -> DailyCheckinRewards.ETC;
                default -> throw new IllegalArgumentException(where + " 'slots' tab must be equip, use, setup or etc");
            };
            slotCount = number(parts[1], where + " 'slots' count", 1, DailyCheckinRewards.MAX_SLOTS, -1);
        }

        List<Grant> grants = new ArrayList<>();
        if (m.get("items") != null) {
            if (!(m.get("items") instanceof List<?> items)) {
                throw new IllegalArgumentException(where + " 'items' must be a list");
            }
            for (Object it : items) {
                Map<?, ?> im = map(it, where + " item", ITEM_KEYS);
                int id = number(im.get("id"), where + " item 'id'", 1, Integer.MAX_VALUE, -1);
                if (!knownItem.test(id)) {
                    throw new IllegalArgumentException(where + ": item " + id + " is not in the server's data");
                }
                int qty = number(im.get("qty"), where + " item " + id + " 'qty'", 1, Short.MAX_VALUE, 1);
                int expire = number(im.get("days"), where + " item " + id + " 'days'", 0, 3650, 0);
                grants.add(new Grant(id, qty, expire));
            }
        }
        int icon = m.get("icon") != null
                ? number(m.get("icon"), where + " 'icon'", 1, Integer.MAX_VALUE, -1)
                : grants.isEmpty() ? 0 : grants.get(0).itemId();
        if (icon == 0 || !knownItem.test(icon)) {
            throw new IllegalArgumentException(where + " needs an 'icon' item id the server knows");
        }
        if (nx == 0 && mesos == 0 && grants.isEmpty() && slotCount == 0) {
            throw new IllegalArgumentException(where + " grants nothing");
        }
        return new Reward(icon, nx, mesos, grants, slotType, slotCount);
    }

    private static Map<?, ?> map(Object node, String where, Set<String> allowed) {
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(where + " must be a mapping");
        }
        for (Object k : m.keySet()) {
            if (!allowed.contains(String.valueOf(k))) {
                throw new IllegalArgumentException(where + ": unknown key '" + k + "' (allowed " + allowed + ")");
            }
        }
        return m;
    }

    /** {@code fallback} -1 = required. Accepts 1500 or 1,500 / 1_500. */
    private static int number(Object value, String what, int min, int max, int fallback) {
        if (value == null) {
            if (fallback < 0) {
                throw new IllegalArgumentException(what + " is required");
            }
            return fallback;
        }
        long n;
        try {
            n = Long.parseLong(value.toString().trim().replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a whole number: '" + value + "'");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(what + " must be " + min + ".." + max + ", was " + n);
        }
        return (int) n;
    }
}
