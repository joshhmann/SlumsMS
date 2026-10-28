package client.command.commands.gm3;

import client.Character;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.GameLiveWallet;
import soloMapling.Economy.exchange.Ledger;
import soloMapling.Economy.exchange.LiveWallet;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 113: the NX exchange's staff controls, {@code !exchange status|book|halt [reason]|resume|fills [n]|
 * fund <regular> <nx|meso> <amount>|unfund <regular>|reverse <fillId>}. The name
 * "exchange" already belongs to the players' {@code @exchange} (the item-exchange NPC), so that command hands these
 * sub-commands here when a GM of level 3 or more types one ({@link #handles}); everything else is unchanged.
 * {@code halt} is the emergency stop: no new orders, no fills, no expiry, no payouts; cancels still work.
 * {@code reverse} (GM level 4, only while halted, owner 2026-10-28) undoes one fill with compensating ledger postings
 * and is logged. {@code fund}/{@code unfund} move money between the house account's live wallet (the GM must be
 * logged in on {@code -Dslums.exchange.houseAccount}) and a regular.
 */
public final class NxExchangeCommand {

    public static final int GM_LEVEL = 3;
    public static final int REVERSE_GM_LEVEL = 4;
    private static final Set<String> SUBS = Set.of("status", "book", "halt", "resume", "fills", "fund", "unfund",
            "reverse");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NxExchangeCommand.class);

    private NxExchangeCommand() {
    }

    public static boolean handles(Character who, String[] params) {
        return who != null && who.gmLevel() >= GM_LEVEL && params.length > 0
                && SUBS.contains(params[0].toLowerCase(Locale.ROOT));
    }

    public static void execute(Character gm, String[] params) {
        Exchange ex = ExchangeService.exchange();
        if (ex == null) {
            gm.yellowMessage("The NX exchange is off (-Dslums.exchange=true to run it).");
            return;
        }
        switch (params[0].toLowerCase(Locale.ROOT)) {
            case "book" -> ex.bookLines(8).forEach(gm::yellowMessage);
            case "halt" -> {
                String reason = params.length > 1 ? String.join(" ", Arrays.copyOfRange(params, 1, params.length))
                        : "halted by " + gm.getName();
                ex.halt(reason);
                log.warn("NX exchange halted by {}: {}", gm.getName(), reason);
                gm.yellowMessage("Exchange HALTED: " + reason + ". No new orders, fills, expiry or payouts.");
            }
            case "resume" -> {
                ex.resume();
                log.info("NX exchange resumed by {}", gm.getName());
                gm.yellowMessage("Exchange running again.");
            }
            case "fills" -> {
                int n = params.length > 1 ? parse(params[1], 10) : 10;
                for (var f : ex.lastTrades(Math.max(1, Math.min(30, n)))) {
                    gm.yellowMessage(String.format("fill %d: %,d NX at %,d (buy #%d%s, sell #%d%s) %s", f.id(), f.nx(),
                            f.price(), f.buyOrder(), f.buyerRegular() ? " regular" : "", f.sellOrder(),
                            f.sellerRegular() ? " regular" : "", java.time.Instant.ofEpochMilli(f.at())));
                }
            }
            case "fund" -> {
                int reg = params.length > 1 ? parse(params[1], 0) : 0;
                Ledger.Asset asset = params.length > 2 && params[2].equalsIgnoreCase("nx") ? Ledger.Asset.NX
                        : params.length > 2 && params[2].toLowerCase(Locale.ROOT).startsWith("meso") ? Ledger.Asset.MESO
                        : null;
                long amount = params.length > 3 ? parse(params[3], 0) : 0;
                LiveWallet w = GameLiveWallet.of(gm);
                if (reg <= 0 || asset == null || amount <= 0 || w == null) {
                    gm.yellowMessage("Use: !exchange fund <regular 1..n> <nx|meso> <amount> (from this character)");
                    return;
                }
                Exchange.Response r = ex.fundHouseLive(reg, w, asset, amount, false);
                log.info("NX exchange fund regular {} {} {} by {}: {}", reg, amount, asset, gm.getName(), r.code());
                gm.yellowMessage(r.ok() ? "Regular " + reg + " funded with " + amount + " " + asset + "."
                        : "Not funded: " + r.message() + " (" + r.code() + ")");
            }
            case "unfund" -> {
                int reg = params.length > 1 ? parse(params[1], 0) : 0;
                LiveWallet w = GameLiveWallet.of(gm);
                if (reg <= 0 || w == null) {
                    gm.yellowMessage("Use: !exchange unfund <regular> (its orders are cancelled, its money comes here)");
                    return;
                }
                Exchange.Response r = ex.unfundHouseLive(reg, w, false);
                log.info("NX exchange unfund regular {} by {}: {} {}", reg, gm.getName(), r.code(), r.data());
                gm.yellowMessage(r.ok() ? "Regular " + reg + " emptied: " + r.data() : "Not done: " + r.message()
                        + " (" + r.code() + ")");
            }
            case "reverse" -> {
                if (gm.gmLevel() < REVERSE_GM_LEVEL) {
                    gm.yellowMessage("Reversing a fill needs GM level " + REVERSE_GM_LEVEL + ".");
                    return;
                }
                long fill = params.length > 1 ? parse(params[1], 0) : 0;
                if (fill <= 0) {
                    gm.yellowMessage("Use: !exchange reverse <fillId> (see !exchange fills; only while halted)");
                    return;
                }
                Exchange.Response r = ex.reverse(fill, gm.getName());
                log.warn("NX exchange reverse fill {} by {}: {} {}", fill, gm.getName(), r.code(), r.message());
                gm.yellowMessage(r.ok() ? "Fill " + fill + " reversed (" + r.data().get("postings")
                        + " compensating postings); the money waits in payouts until you resume."
                        : "Not reversed: " + r.message() + " (" + r.code() + ")");
            }
            default -> ex.status().forEach(gm::yellowMessage);
        }
    }

    private static int parse(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
