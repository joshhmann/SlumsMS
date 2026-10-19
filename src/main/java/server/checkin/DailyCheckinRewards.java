package server.checkin;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * The 28 Daily Check-In rewards. PLACEHOLDER TABLE: every day grants a little meso with a potion icon until the
 * owner chooses the real rewards; change {@link #DAYS} only.
 */
public final class DailyCheckinRewards {

    private static final long ONE_DAY_MS = 86_400_000L;

    /** One item grant; {@code expireDays} 0 = permanent. */
    public record Grant(int itemId, int qty, int expireDays) {
        public Grant(int itemId, int qty) {
            this(itemId, qty, 0);
        }
    }

    /** One day's reward; {@code iconItemId} is the item whose icon the window draws for the day. */
    public record Reward(int iconItemId, int mesos, List<Grant> grants) {
    }

    private static final int POTION_ICON = 2000000;

    static final Reward[] DAYS = new Reward[DailyCheckinStreak.CYCLE_DAYS];

    static {
        for (int d = 0; d < DAYS.length; d++) {
            DAYS[d] = new Reward(POTION_ICON, 1_000 * (d + 1), List.of());   // placeholder: 1k..28k mesos
        }
    }

    private DailyCheckinRewards() {
    }

    public static Reward of(int day) {
        return day >= 1 && day <= DAYS.length ? DAYS[day - 1] : null;
    }

    public static int iconItemId(int day) {
        Reward r = of(day);
        return r == null ? 0 : r.iconItemId();
    }

    /** Whether {@code chr} has room for every part of the day's reward, so a claim never loses any of it. */
    public static boolean canReceive(Character chr, int day) {
        Reward r = of(day);
        if (r == null || !chr.canHoldMeso(r.mesos())) {
            return false;
        }
        if (r.grants().isEmpty()) {
            return true;
        }
        List<Object> ids = new ArrayList<>();
        List<Object> qtys = new ArrayList<>();
        for (Grant g : r.grants()) {
            ids.add(g.itemId());
            qtys.add(g.qty());
        }
        return chr.getAbstractPlayerInteraction().canHoldAll(ids, qtys);
    }

    /** Hands the day's reward to {@code chr}; call after {@link #canReceive} and after the claim is recorded. */
    public static void grant(Character chr, int day) {
        Reward r = of(day);
        if (r == null) {
            return;
        }
        if (r.mesos() > 0) {
            chr.gainMeso(r.mesos(), true);
        }
        for (Grant g : r.grants()) {
            long expiration = g.expireDays() > 0 ? System.currentTimeMillis() + g.expireDays() * ONE_DAY_MS : -1;
            InventoryManipulator.addById(chr.getClient(), g.itemId(), (short) g.qty(), expiration);
        }
    }

    /** The hover text for a day: one line per item, mesos last. */
    public static String tooltip(int day) {
        Reward r = of(day);
        if (r == null) {
            return "";
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        StringBuilder sb = new StringBuilder("Day ").append(day);
        for (Grant g : r.grants()) {
            String name = ii.getName(g.itemId());
            sb.append("\n- ").append(name == null || name.isBlank() ? "Item " + g.itemId() : name);
            if (g.qty() > 1) {
                sb.append(" x").append(g.qty());
            }
            if (g.expireDays() > 0) {
                sb.append(" (").append(g.expireDays()).append(g.expireDays() == 1 ? " day)" : " days)");
            }
        }
        if (r.mesos() > 0) {
            sb.append("\n- ").append(String.format("%,d", r.mesos())).append(" mesos");
        }
        return sb.toString();
    }
}
