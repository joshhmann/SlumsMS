package server.checkin;

import client.Character;
import client.inventory.manipulator.InventoryManipulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The 28 Daily Check-In rewards and how they are handed out.
 *
 * <p>The table is read from {@value #DATA_PATH} (resolved by {@link soloMapling.DataFiles}, so the owner can edit the
 * copy in the server's source tree or a {@code -Dsolomapling.dataDir} override and restart; no rebuild). When that
 * file is missing or invalid the built-in {@link #DEFAULT_DAYS} below is used and the reason is logged.
 * {@code DailyCheckinRewardsTest} keeps the shipped yaml and the built-in table identical.
 *
 * <p>A day can grant NX Credit, mesos, items (optionally timed) and an inventory slot expansion. Slots never go past
 * {@link #MAX_SLOTS}; slots that do not fit are paid out as {@link #NX_PER_MISSING_SLOT} NX each and the player is
 * told so.
 */
public final class DailyCheckinRewards {

    private static final Logger log = LoggerFactory.getLogger(DailyCheckinRewards.class);

    static final String DATA_PATH = "src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml";

    private static final long ONE_DAY_MS = 86_400_000L;

    /** CashShop cash type 1 is NX Credit (2 = Maple Points, 4 = NX Prepaid). */
    static final int NX_CREDIT = 1;
    /** The client's and {@link Character#canGainSlots}'s limit for one inventory tab. */
    static final int MAX_SLOTS = 96;
    /** What a slot that cannot be added (tab already at 96) is worth instead. */
    static final int NX_PER_MISSING_SLOT = 500;

    /** Inventory tabs a day may expand (Character.gainSlots types). Cash is left out. */
    public static final int EQUIP = 1, USE = 2, SETUP = 3, ETC = 4;

    /** One item grant; {@code expireDays} 0 = permanent. */
    public record Grant(int itemId, int qty, int expireDays) {
        public Grant(int itemId, int qty) {
            this(itemId, qty, 0);
        }
    }

    /**
     * One day's reward.
     *
     * @param iconItemId the item whose icon the window draws for the day
     * @param nxCredit   NX Credit added to the account (0 = none)
     * @param mesos      mesos (0 = none)
     * @param grants     items
     * @param slotType   0 = none, else {@link #EQUIP}/{@link #USE}/{@link #SETUP}/{@link #ETC}
     * @param slotCount  slots added to that tab
     */
    public record Reward(int iconItemId, int nxCredit, int mesos, List<Grant> grants, int slotType, int slotCount) {
        public Reward {
            grants = List.copyOf(grants);
        }
    }

    // Every id below was checked against the server's v83 data (String.wz name + Item.wz info/icon).
    static final int ELIXIR = 2000004;
    static final int POWER_ELIXIR = 2000005;
    static final int ONYX_APPLE = 2022179;
    static final int WHITE_SCROLL = 2340000;
    static final int TELEPORT_ROCK = 5040000;
    static final int VIP_TELEPORT_ROCK = 5041000;
    static final int AP_RESET = 5050000;
    static final int SUPER_MEGAPHONE = 5072000;
    static final int ITEM_MEGAPHONE = 5076000;
    static final int SAFETY_CHARM = 5130000;
    static final int EXP_COUPON_2X = 5211046;   // "1day 2x EXP Special Coupon", Item.wz time 00-24 every day
    static final int GACHAPON_TICKET = 5220000;
    static final int OWL_OF_MINERVA = 5230000;

    private static Reward day(int icon, int nx, Grant... grants) {
        return new Reward(icon, nx, 0, List.of(grants), 0, 0);
    }

    private static Reward milestone(int icon, int nx, int slotType, int slotCount, Grant... grants) {
        return new Reward(icon, nx, 0, List.of(grants), slotType, slotCount);
    }

    private static Grant g(int itemId, int qty) {
        return new Grant(itemId, qty);
    }

    /** The 2x EXP coupon, expiring one day after the claim. */
    private static Grant expCoupon() {
        return new Grant(EXP_COUPON_2X, 1, 1);
    }

    /*
     * ======================== DRAFT FOR OWNER APPROVAL ========================
     * "We could be pretty generous": NX every day (1,500-3,000), bigger NX plus an inventory expansion on the weekly
     * milestones (7/14/21/28), useful v83 items in between. Per 28-day cycle: 89,000 NX and +20 slots
     * (+4 Equip, +4 Use, +4 Set-up, +8 Etc). Once live, edit the yaml (same content) instead of this table.
     * ==========================================================================
     */
    static final List<Reward> DEFAULT_DAYS = List.of(
            day(POWER_ELIXIR, 1_500, g(POWER_ELIXIR, 20)),                                       // 1
            day(TELEPORT_ROCK, 1_500, g(TELEPORT_ROCK, 3)),                                      // 2
            day(ELIXIR, 2_000, g(ELIXIR, 50)),                                                   // 3
            day(SAFETY_CHARM, 2_000, g(SAFETY_CHARM, 1)),                                        // 4
            day(GACHAPON_TICKET, 2_000, g(GACHAPON_TICKET, 2)),                                  // 5
            day(ONYX_APPLE, 2_500, g(ONYX_APPLE, 5)),                                            // 6
            milestone(EXP_COUPON_2X, 5_000, EQUIP, 4, expCoupon()),                              // 7
            day(POWER_ELIXIR, 1_500, g(POWER_ELIXIR, 30)),                                       // 8
            day(AP_RESET, 1_500, g(AP_RESET, 1)),                                                // 9
            day(VIP_TELEPORT_ROCK, 2_000, g(VIP_TELEPORT_ROCK, 1)),                              // 10
            day(SUPER_MEGAPHONE, 2_000, g(SUPER_MEGAPHONE, 3)),                                  // 11
            day(ONYX_APPLE, 2_000, g(ONYX_APPLE, 5)),                                            // 12
            day(SAFETY_CHARM, 2_500, g(SAFETY_CHARM, 2)),                                        // 13
            milestone(GACHAPON_TICKET, 7_500, USE, 4, g(GACHAPON_TICKET, 5)),                    // 14
            day(ELIXIR, 2_000, g(ELIXIR, 50)),                                                   // 15
            day(TELEPORT_ROCK, 2_000, g(TELEPORT_ROCK, 5)),                                      // 16
            day(AP_RESET, 2_000, g(AP_RESET, 2)),                                                // 17
            day(EXP_COUPON_2X, 2_500, expCoupon()),                                              // 18
            day(POWER_ELIXIR, 2_500, g(POWER_ELIXIR, 50)),                                       // 19
            day(ONYX_APPLE, 2_500, g(ONYX_APPLE, 10)),                                           // 20
            milestone(SAFETY_CHARM, 10_000, SETUP, 4, g(SAFETY_CHARM, 3), g(AP_RESET, 3)),       // 21
            day(GACHAPON_TICKET, 2_000, g(GACHAPON_TICKET, 3)),                                  // 22
            day(ITEM_MEGAPHONE, 2_000, g(ITEM_MEGAPHONE, 2)),                                    // 23
            day(SAFETY_CHARM, 2_500, g(SAFETY_CHARM, 2)),                                        // 24
            day(OWL_OF_MINERVA, 2_500, g(OWL_OF_MINERVA, 3)),                                    // 25
            day(ONYX_APPLE, 3_000, g(POWER_ELIXIR, 50), g(ONYX_APPLE, 5)),                       // 26
            day(EXP_COUPON_2X, 3_000, expCoupon()),                                              // 27
            milestone(WHITE_SCROLL, 15_000, ETC, 8, g(WHITE_SCROLL, 1), g(GACHAPON_TICKET, 5))); // 28

    /** Item room and delivery; a seam so the grant rules are testable without a live inventory. */
    interface Hands {
        boolean canHold(Character chr, List<Grant> grants);

        void give(Character chr, Grant grant, long expiration);
    }

    static final Hands INVENTORY = new Hands() {
        @Override
        public boolean canHold(Character chr, List<Grant> grants) {
            List<Object> ids = new ArrayList<>();
            List<Object> qtys = new ArrayList<>();
            for (Grant g : grants) {
                ids.add(g.itemId());
                qtys.add(g.qty());
            }
            return chr.getAbstractPlayerInteraction().canHoldAll(ids, qtys);
        }

        @Override
        public void give(Character chr, Grant grant, long expiration) {
            InventoryManipulator.addById(chr.getClient(), grant.itemId(), (short) grant.qty(), expiration);
        }
    };

    private static volatile Hands hands = INVENTORY;
    private static volatile List<Reward> days;

    private DailyCheckinRewards() {
    }

    /** The table in use: the yaml when it is valid, else {@link #DEFAULT_DAYS}. Loaded once. */
    static List<Reward> days() {
        List<Reward> d = days;
        if (d == null) {
            synchronized (DailyCheckinRewards.class) {
                d = days;
                if (d == null) {
                    d = loadTable();
                    days = d;
                }
            }
        }
        return d;
    }

    private static List<Reward> loadTable() {
        File file = soloMapling.DataFiles.optional(DATA_PATH);
        if (!file.isFile()) {
            log.info("Daily Check-In: {} not found, using the built-in reward table", DATA_PATH);
            return DEFAULT_DAYS;
        }
        try {
            List<Reward> loaded = DailyCheckinRewardsYaml.read(file.toPath(), knownItem());
            log.info("Daily Check-In: loaded {} reward days from {}", loaded.size(), file);
            return loaded;
        } catch (Exception e) {
            log.error("Daily Check-In: {} is invalid ({}); using the built-in reward table", file, e.getMessage());
            return DEFAULT_DAYS;
        }
    }

    /** An item id the server's data knows (has a name), so a typo in the yaml cannot hand out a bad item. */
    private static IntPredicate knownItem() {
        return id -> {
            String name = ItemInformationProvider.getInstance().getName(id);
            return name != null && !name.isBlank();
        };
    }

    public static Reward of(int day) {
        List<Reward> d = days();
        return day >= 1 && day <= d.size() ? d.get(day - 1) : null;
    }

    public static int iconItemId(int day) {
        Reward r = of(day);
        return r == null ? 0 : r.iconItemId();
    }

    /** Whether {@code chr} has room for every item and the mesos of the day, so a claim never loses any of it. */
    public static boolean canReceive(Character chr, int day) {
        Reward r = of(day);
        if (r == null || (r.mesos() > 0 && !chr.canHoldMeso(r.mesos()))) {
            return false;
        }
        return r.grants().isEmpty() || hands.canHold(chr, r.grants());
    }

    /**
     * Hands the day's reward to {@code chr}; call after {@link #canReceive} and after the claim is recorded.
     * {@link DailyCheckin} never calls it for a bot.
     */
    public static void grant(Character chr, int day) {
        Reward r = of(day);
        if (r == null) {
            return;
        }
        int nx = r.nxCredit();
        if (r.slotType() > 0 && r.slotCount() > 0) {
            int room = Math.max(0, MAX_SLOTS - chr.getSlots(r.slotType()));
            int added = Math.min(room, r.slotCount());
            int missing = r.slotCount() - added;
            if (added > 0) {
                chr.gainSlots(r.slotType(), added, true);
            }
            if (missing > 0) {
                nx += missing * NX_PER_MISSING_SLOT;
                chr.dropMessage(6, "Daily Check-In: your " + slotTabName(r.slotType()) + " inventory is at the "
                        + MAX_SLOTS + "-slot maximum, so " + missing + " slot" + (missing == 1 ? "" : "s")
                        + " became " + String.format("%,d", missing * NX_PER_MISSING_SLOT) + " NX instead.");
            }
        }
        if (nx > 0) {
            chr.getCashShop().gainCash(NX_CREDIT, nx);
            chr.dropMessage(6, "Daily Check-In: you received " + String.format("%,d", nx) + " NX Credit.");
        }
        if (r.mesos() > 0) {
            chr.gainMeso(r.mesos(), true);
        }
        long now = System.currentTimeMillis();
        for (Grant g : r.grants()) {
            hands.give(chr, g, g.expireDays() > 0 ? now + g.expireDays() * ONE_DAY_MS : -1);
        }
        chr.saveCharToDB();   // NX sits on the account row: persist it with the items now, not at the next autosave
    }

    static String slotTabName(int type) {
        return switch (type) {
            case EQUIP -> "Equip";
            case USE -> "Use";
            case SETUP -> "Set-up";
            case ETC -> "Etc";
            default -> "";
        };
    }

    /** The hover text for a day: NX first, then items, mesos and slots, one per line. */
    public static String tooltip(int day) {
        Reward r = of(day);
        if (r == null) {
            return "";
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        return tooltip(day, r, ii::getName);
    }

    static String tooltip(int day, Reward r, IntFunction<String> names) {
        StringBuilder sb = new StringBuilder("Day ").append(day);
        if (r.nxCredit() > 0) {
            sb.append("\n- ").append(String.format("%,d", r.nxCredit())).append(" NX");
        }
        for (Grant g : r.grants()) {
            String name = names.apply(g.itemId());
            sb.append("\n- ").append(name == null || name.isBlank() ? "Item " + g.itemId() : name.trim());
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
        if (r.slotType() > 0 && r.slotCount() > 0) {
            sb.append("\n- +").append(r.slotCount()).append(' ').append(slotTabName(r.slotType())).append(" slots");
        }
        return sb.toString();
    }

    /** Test seam: replacement item hands and table (nulls restore the inventory and a fresh load). */
    static void useForTest(Hands testHands, List<Reward> testDays) {
        hands = testHands == null ? INVENTORY : testHands;
        days = testDays;
    }
}
