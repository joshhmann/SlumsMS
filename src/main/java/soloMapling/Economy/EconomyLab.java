package soloMapling.Economy;

import client.Character;
import server.Trade;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.5.0 rc3: the economy lane's GM staging read-outs, shared by {@code !economy} and the {@code !qa tr-*} scenarios:
 * the tax on a sale, a haggle test against the band, the board's detail for an item, and finding a bot by name.
 */
public final class EconomyLab {

    private EconomyLab() {
    }

    /** What a shop sale at {@code price} costs the seller: the v83 fee, the tax, which one applies, what they keep. */
    public static List<String> tax(long price) {
        List<String> out = new ArrayList<>();
        int v83 = Trade.getFee(price);
        int fee = MesoSinks.shopFee(price);
        out.add(String.format("A shop sale at %,d: v83 fee %,d (%.1f%%), FM tax %.1f%% = %,d; the seller pays %,d and keeps %,d",
                price, v83, price > 0 ? 100.0 * v83 / price : 0, EconomyConfig.getDouble("sinks.shopTaxPercent", 3.0),
                Math.round(price * EconomyConfig.getDouble("sinks.shopTaxPercent", 3.0) / 100.0), fee, price - fee));
        out.add(String.format("Taxed today so far: %,d mesos", EconomyMeters.get(EconomyMeters.TAX)));
        return out;
    }

    /**
     * A haggle test: the player's line against a bot holding an item worth {@code fair}, both ways (the bot buying,
     * the bot selling). Shows the move read from the line and the price the bot answers, always inside the band.
     */
    public static List<String> haggle(long fair, String line, boolean friend) {
        List<String> out = new ArrayList<>();
        HaggleBrain.Read read = HaggleBrain.read(line, ChatDecide.get());
        out.add("Line: \"" + line + "\" -> " + read.move() + (read.price() > 0 ? " " + read.price() : "") + " ("
                + read.source() + ")");
        for (PriceBand.Side side : PriceBand.Side.values()) {
            PriceBand.Band band = PriceBand.of(side, fair, friend);
            long open = PriceBand.opening(band);
            String answer;
            if (read.move() == HaggleBrain.Move.OFFER) {
                PriceBand.Decision d = PriceBand.respond(band, open, read.price(), 0);
                answer = d.kind() + " at " + String.format("%,d", d.price()) + (band.contains(d.price())
                        || side == PriceBand.Side.BOT_BUYS && d.price() <= band.high() ? " (inside the band)"
                        : " (OUTSIDE THE BAND - a bug)");
            } else {
                answer = "no number: the bot keeps its price " + String.format("%,d", open);
            }
            out.add(String.format("  bot %s, fair %,d%s: band %,d..%,d, opens at %,d -> %s",
                    side == PriceBand.Side.BOT_BUYS ? "buys" : "sells", band.fair(), friend ? " (friend)" : "",
                    band.low(), band.high(), open, answer));
        }
        return out;
    }

    /** The board's detail for an item id: median, range, samples, fair value, both bands. */
    public static List<String> price(int itemId, long staticValue) {
        List<String> out = new ArrayList<>();
        PriceBook.Quote q = PriceBook.quote(itemId, staticValue);
        out.add(q.describe(BotShops.nameOf(itemId) + " (" + itemId + ")"));
        long fair = q.fromSamples() ? q.median() : staticValue;
        if (fair > 0) {
            PriceBand.Band buy = PriceBand.of(PriceBand.Side.BOT_BUYS, fair, false);
            PriceBand.Band sell = PriceBand.of(PriceBand.Side.BOT_SELLS, fair, false);
            out.add(String.format("  fair %,d: bots buy at %,d..%,d, sell at %,d..%,d; a bot shop asks %,d", fair,
                    buy.low(), buy.high(), sell.low(), sell.high(), PriceBand.nice(Math.round(fair
                            * EconomyConfig.getDouble("botShops.lootMarkup", 1.0)), false)));
        }
        out.add("  samples: " + q.samples() + " in the window, " + q.playerSamples() + " with a player; board from "
                + (q.playerOnly() ? "players only" : q.fromSamples() ? "players and bot seeds" : "the static value"));
        if (ScrollMarketPrice.isScroll(itemId)) {
            // 162: the scroll's learned market price (what bots scroll, value gear and sell it by)
            out.add("  " + (ScrollMarketPrice.enabled() ? "" : "[slums.scroll.marketPrice off] ")
                    + ScrollMarketPrice.view(itemId, staticValue).line("market price"));
        }
        return out;
    }

    /** An online bot by name (case-insensitive), or the nearest bot on the GM's map when {@code name} is null. */
    public static Character findBot(Character gm, String name) {
        Character best = null;
        double bestD = Double.MAX_VALUE;
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr == null) {
                continue;
            }
            if (name != null) {
                if (chr.getName().equalsIgnoreCase(name)) {
                    return chr;
                }
                continue;
            }
            if (chr.getMapId() == gm.getMapId()) {
                double d = chr.getPosition().distance(gm.getPosition());
                if (d < bestD) {
                    bestD = d;
                    best = chr;
                }
            }
        }
        if (best == null && name == null && gm.getMap() != null) {
            for (Character c : gm.getMap().getAllPlayers()) {
                if (BotHelpers.isBot(c)) {
                    double d = c.getPosition().distance(gm.getPosition());
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
