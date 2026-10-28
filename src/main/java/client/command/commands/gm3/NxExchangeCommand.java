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
 * Ticket 113: the NX exchange's staff controls, {@code !exchange status [ticker]|book [ticker]|halt [reason]|resume|
 * fills [ticker] [n]|fund <regular> <nx|meso> <amount>|unfund <regular>|reverse <fillId>}. A ticker (nx, leaf, chaos,
 * ws, elixir; NX when left out) picks the book for status/book/fills; halt/resume/reverse act on the whole exchange,
 * and the regulars are NX-only (fund takes NX or mesos, never items). The name
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
        boolean takesTicker = Set.of("status", "book", "fills").contains(params[0].toLowerCase(Locale.ROOT));
        String tk = takesTicker && params.length > 1 && ex.ticker(params[1]) != null ? ex.ticker(params[1]).id()
                : null;
        if (tk != null) { // the optional ticker: drop it so the rest reads as before
            String[] rest = new String[params.length - 1];
            rest[0] = params[0];
            System.arraycopy(params, 2, rest, 1, params.length - 2);
            params = rest;
        }
        String ticker = tk == null ? "NX" : tk;
        switch (params[0].toLowerCase(Locale.ROOT)) {
            case "book" -> {
                gm.yellowMessage(ticker + " book (" + ex.ticker(ticker).priceUnit() + "):");
                ex.bookLines(ticker, 8).forEach(gm::yellowMessage);
            }
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
                String unit = "NX".equals(ticker) ? "NX" : ex.ticker(ticker).name();
                for (var f : ex.lastTrades(ticker, Math.max(1, Math.min(30, n)))) {
                    gm.yellowMessage(String.format("fill %d: %,d %s at %,d (buy #%d%s, sell #%d%s) %s", f.id(), f.nx(), unit,
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
            default -> {
                if (tk != null && !"NX".equals(tk)) {
                    gm.yellowMessage(ex.tickerLine(ex.ticker(tk)));
                } else {
                    ex.status().forEach(gm::yellowMessage);
                }
            }
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
