package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.InventoryType;
import client.inventory.Item;
import config.YamlConfig;
import constants.inventory.ItemConstants;
import net.server.Server;
import server.ItemInformationProvider;
import server.cashshop.CashShopCatalog;
import server.cashshop.CashShopServices;
import server.cashshop.CouponSchedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * 1.5.1 {@code @coupons}: when your EXP and drop coupons work, on the server's clock and on Pacific time.
 *
 * <p>Coupon hours are checked on the SERVER's clock (config TIMEZONE, GMT-6 on live), which is not the players'
 * Pacific clock and moves against it with daylight saving. The Cash Shop window can only show an item's name, so the
 * shop names carry the server hours ("2x EXP M-F 7-11am 30d") and this command says which zone that is, translates it,
 * and tells you whether each coupon you hold is working right now. Read-only.
 */
public class CouponsCommand extends Command {
    {
        setDescription("When your EXP/drop coupons work (server time and Pacific time).");
    }

    /** One rate coupon a player holds. {@code expiration} is epoch millis, -1 for none. */
    public record Held(int itemId, String name, long expiration) {
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        List<Held> held = new ArrayList<>();
        Collection<Item> cash = player.getInventory(InventoryType.CASH).list();
        for (Item it : cash) {
            int id = it.getItemId();
            if (!CashShopServices.isRateCoupon(id)) {   // EXP and drop only, not the Maple Life cards
                continue;
            }
            CashShopCatalog.Row row = CashShopCatalog.byItemId(id);
            String name = row != null ? row.name() : ii.getName(id);
            held.add(new Held(id, name != null ? name : String.valueOf(id), it.getExpiration()));
        }
        List<Integer> active = Server.getInstance().getActiveCoupons();
        List<String> lines;
        synchronized (active) {
            List<Integer> snapshot = new ArrayList<>(active);
            lines = report(held, ZonedDateTime.now(ZoneId.systemDefault()),
                    Server.getInstance()::getCouponSchedule, snapshot::contains,
                    YamlConfig.config.server.USE_STACK_COUPON_RATES);
        }
        for (String line : lines) {
            player.dropMessage(5, line);
        }
    }

    /** The command's text. {@code now} is in the server's zone (the zone the coupon task checks hours in). */
    public static List<String> report(List<Held> held, ZonedDateTime now, IntFunction<CouponSchedule> schedules,
                                      IntPredicate activeNow, boolean stackRates) {
        List<String> out = new ArrayList<>();
        Integer shift = CouponSchedule.playerShiftHours(now);
        ZonedDateTime pt = now.withZoneSameInstant(CouponSchedule.PLAYER_ZONE);
        String head = "Coupon hours are server time (" + CouponSchedule.offsetLabel(now) + "). Now: server "
                + CouponSchedule.clock(now);
        if (shift != null && shift != 0) {
            head += ", Pacific " + CouponSchedule.clock(pt) + " (server is " + hoursWord(-shift)
                    + (shift < 0 ? " ahead)." : " behind).");
        } else {
            head += ".";
        }
        out.add(head);

        int activeExp = 0, activeDrop = 0;
        for (Held h : held) {
            CouponSchedule s = schedules.apply(h.itemId());
            StringBuilder sb = new StringBuilder(h.name()).append(": ");
            if (s == null) {
                sb.append("no schedule on this server.");
            } else {
                String when = s.describe();
                sb.append(when).append(when.equals("any time") ? "" : " server");
                if (shift != null && shift != 0 && !s.allDay()) {
                    sb.append(" = ").append(s.describeShifted(shift)).append(" Pacific");
                }
                sb.append(". ");
                boolean on = activeNow.test(h.itemId());
                sb.append(on ? "Working now." : "Not working now.");
                if (on) {
                    if (ItemConstants.isExpCoupon(h.itemId())) {
                        activeExp++;
                    } else {
                        activeDrop++;
                    }
                }
            }
            if (h.expiration() > 0) {
                sb.append(" Expires ").append(untilWord(now.toInstant(), Instant.ofEpochMilli(h.expiration()))).append('.');
            }
            out.add(sb.toString());
        }
        if (held.isEmpty()) {
            out.add("You have no EXP or drop coupons.");
        }
        if (!stackRates && (activeExp > 1 || activeDrop > 1)) {
            out.add("Coupons do not stack: only your best working EXP coupon (and best drop coupon) counts.");
        }
        out.add("Cash Shop coupon names use server hours: \"2x EXP M-F 7-11am 30d\" = 2x EXP, Mon-Fri, 7-11 AM server"
                + " time, lasts 30 days. Sa-Su = weekend, no day = every day.");
        return out;
    }

    private static String hoursWord(int h) {
        return h == 1 ? "1 hour" : h + " hours";
    }

    /** "in 29 days", "in 3 hours", "in 40 minutes", "now". */
    static String untilWord(Instant now, Instant when) {
        long mins = (when.toEpochMilli() - now.toEpochMilli()) / 60_000;
        if (mins <= 0) {
            return "now";
        }
        if (mins >= 2 * 24 * 60) {
            return "in " + mins / (24 * 60) + " days";
        }
        if (mins >= 120) {
            return "in " + mins / 60 + " hours";
        }
        return "in " + mins + " minutes";
    }
}
