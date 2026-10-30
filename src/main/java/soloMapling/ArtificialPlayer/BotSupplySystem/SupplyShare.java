package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 1.6.2 ticket 99: supplies shared between companions and with the player they follow - after nutnnut/Cosmic
 * {@code BotPotionManager}, {@code BotAmmoManager} and {@code BotRockManager}: "need pot" / "need ammo" / "need rocks"
 * from the player, and a companion running low asking its siblings. The one with the most to spare hands some over
 * in a normal trade ({@link soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade}); a donor always keeps
 * enough for itself ({@link #keep}). Potions are the ones the bots drink (BotVitals), ammo is what the needy one's
 * weapon fires (BotAmmo; arrows and bolts only, as upstream: stars and bullets are recharged, not shared), rocks are
 * the Magic and Summoning Rocks skills consume.
 */
public final class SupplyShare {

    public enum Kind { HP, MP, AMMO, ROCKS }

    /** Magic Rock, Summoning Rock. */
    public static final Set<Integer> ROCKS = Set.of(4006000, 4006001);

    /** What a donor keeps for itself, by kind. */
    static final int KEEP_POTS = 50;
    static final int KEEP_AMMO = 500;
    static final int KEEP_ROCKS = 20;
    /** At most this much changes hands in one trade. */
    static final int GIVE_POTS = 100;
    static final int GIVE_AMMO = 1000;
    static final int GIVE_ROCKS = 30;
    /** Below this a companion counts as low and asks its siblings. */
    static final int LOW_POTS = 15;
    static final int LOW_AMMO = 150;
    static final int LOW_ROCKS = 5;

    /** Where the stacks of a kind are (the real inventory in game; a fake in tests). */
    public interface Stock {
        List<Item> stacks(Character holder, Kind kind, Character needy);
    }

    static final Stock LIVE = (holder, kind, needy) -> switch (kind) {
        case HP -> BotVitals.potionStacks(holder, true);
        case MP -> BotVitals.potionStacks(holder, false);
        case AMMO -> {
            WeaponType weapon = BotAttack.resolveEquippedWeaponType(needy);
            if (weapon != WeaponType.BOW && weapon != WeaponType.CROSSBOW) {
                yield List.of();
            }
            List<Item> out = new ArrayList<>();
            for (Item item : BotAmmo.ammoStacks(holder, weapon)) {
                if (!ItemConstants.isRechargeable(item.getItemId())) {
                    out.add(item);
                }
            }
            yield out;
        }
        case ROCKS -> rockStacks(holder);
    };

    private static volatile Stock stock = LIVE;

    private SupplyShare() {
    }

    public static void useForTest(Stock s) {
        stock = s == null ? LIVE : s;
    }

    /** The kinds a "need ..." word asks for: "hp", "mp", "pots" (HP then MP), "ammo", "rocks". */
    public static List<Kind> kindsOf(String need) {
        if (need == null) {
            return List.of();
        }
        return switch (need) {
            case "hp" -> List.of(Kind.HP);
            case "mp" -> List.of(Kind.MP);
            case "pots" -> List.of(Kind.HP, Kind.MP);
            case "ammo" -> List.of(Kind.AMMO);
            case "rocks" -> List.of(Kind.ROCKS);
            default -> List.of();
        };
    }

    public static int keep(Kind kind) {
        return switch (kind) {
            case HP, MP -> KEEP_POTS;
            case AMMO -> KEEP_AMMO;
            case ROCKS -> KEEP_ROCKS;
        };
    }

    static int most(Kind kind) {
        return switch (kind) {
            case HP, MP -> GIVE_POTS;
            case AMMO -> GIVE_AMMO;
            case ROCKS -> GIVE_ROCKS;
        };
    }

    static int lowAt(Kind kind) {
        return switch (kind) {
            case HP, MP -> LOW_POTS;
            case AMMO -> LOW_AMMO;
            case ROCKS -> LOW_ROCKS;
        };
    }

    /** The holder's own stacks of a kind (fit for its own weapon). */
    public static List<Item> stacksOf(Character holder, Kind kind) {
        return stock.stacks(holder, kind, holder);
    }

    /** How many of a kind (fit for {@code needy}) the holder carries. */
    public static int count(Character holder, Kind kind, Character needy) {
        int n = 0;
        for (Item item : stock.stacks(holder, kind, needy)) {
            n += Math.max(0, item.getQuantity());
        }
        return n;
    }

    /** How many a donor can spare for {@code needy} (above what it keeps, at most one trade's worth). */
    public static int spare(Character donor, Kind kind, Character needy) {
        return Math.max(0, Math.min(most(kind), count(donor, kind, needy) - keep(kind)));
    }

    /**
     * Whether a companion is low on a kind it uses: potions of either kind, ammo its weapon fires when it fires
     * any, rocks when it carries some already (a skill that eats them).
     */
    public static boolean low(Character c, Kind kind, boolean usesIt) {
        return usesIt && count(c, kind, c) < lowAt(kind);
    }

    /** The donor with the most to spare for {@code needy}, or null when nobody can spare any. */
    public static Character bestDonor(List<Character> donors, Kind kind, Character needy) {
        Character best = null;
        int bestSpare = 0;
        for (Character d : donors) {
            if (d == null || needy == null || d.getId() == needy.getId()) {
                continue;
            }
            int s = spare(d, kind, needy);
            if (s > bestSpare) {
                best = d;
                bestSpare = s;
            }
        }
        return best;
    }

    /** The stacks (and how many of each) the donor hands over: its spare, from its fullest stacks first. */
    public static List<Give> handover(Character donor, Kind kind, Character needy) {
        int left = spare(donor, kind, needy);
        List<Give> out = new ArrayList<>();
        if (left <= 0) {
            return out;
        }
        List<Item> stacks = new ArrayList<>(stock.stacks(donor, kind, needy));
        stacks.sort((a, b) -> Short.compare(b.getQuantity(), a.getQuantity()));
        for (Item item : stacks) {
            if (left <= 0 || out.size() >= BotGiveTrade.WINDOW_SLOTS) {
                break;
            }
            int q = Math.min(left, item.getQuantity());
            if (q > 0) {
                out.add(new Give(item.getInventoryType(), item, (short) q));
                left -= q;
            }
        }
        return out;
    }

    /** How a companion names a kind: "hp pots", "mp pots", "arrows", "rocks". */
    public static String said(Kind kind) {
        return switch (kind) {
            case HP -> "hp pots";
            case MP -> "mp pots";
            case AMMO -> "arrows";
            case ROCKS -> "rocks";
        };
    }

    private static List<Item> rockStacks(Character holder) {
        List<Item> out = new ArrayList<>();
        Inventory etc = holder.getInventory(InventoryType.ETC);
        etc.lockInventory();
        try {
            for (Item item : etc.list()) {
                if (ROCKS.contains(item.getItemId()) && item.getQuantity() > 0) {
                    out.add(item);
                }
            }
        } finally {
            etc.unlockInventory();
        }
        return out;
    }
}
