package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.Economy.BotDirectTrade;
import soloMapling.Economy.BotGifts;
import soloMapling.Economy.BotShops;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyLab;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.PriceBook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.5.0 rc3 economy lane: staging and read-outs for bot shops, direct trades, gifts, the price board and the meso
 * sinks.
 * <pre>
 * !economy                         meters (bot buying budget, gifts, tax, godlies), bot shops, open direct trades
 * !economy shop [bot]              opens a shop for that bot (default: the nearest bot on your map); in an FM room
 *                                  it opens where you stand
 * !economy close &lt;bot&gt;            closes that bot's shop (unsold items go back)
 * !economy gift [bot] [potions|scroll|mesos]   the bot gives you a gift now (outside the chance and the caps)
 * !economy quote [bot] &lt;item words&gt; the bot names you a price for something in its bag, as if asked in chat
 * !economy price &lt;item id&gt;         the board's detail: median, range, samples, both bands
 * !economy tax &lt;price&gt;             what the FM shop tax takes from a sale at that price
 * !economy haggle &lt;fair&gt; &lt;line...&gt;  a haggle test: what a bot makes of the line, buying and selling
 * !economy reload                  re-reads economy.yaml (and NPC shop prices)
 * </pre>
 */
public class EconomyCommand extends Command {
    {
        setDescription("Economy lane: !economy [shop|close|gift|quote|price|tax|haggle|reload] ...");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "" : params[0].toLowerCase();
        String[] rest = params.length == 0 ? new String[0] : Arrays.copyOfRange(params, 1, params.length);
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "", "status" -> {
                out.add(EconomyMeters.line());
                out.addAll(BotShops.status());
                out.add("Direct trades open: " + BotDirectTrade.status());
                out.add("Price board: " + PriceBook.itemsTracked() + " items with recent trades");
            }
            case "shop" -> {
                Character bot = EconomyLab.findBot(gm, rest.length > 0 ? rest[0] : null);
                if (bot == null) {
                    out.add("No such bot online (or none on your map).");
                    break;
                }
                boolean inFm = gm.getMapId() >= 910000001 && gm.getMapId() <= 910000022;
                out.addAll(BotShops.open(bot, inFm ? gm.getMapId() : 0, inFm ? gm.getPosition() : null, true));
            }
            case "close" -> out.add(rest.length == 0 ? "!economy close <bot>" : closeShop(gm, rest[0]));
            case "gift" -> {
                Character bot = EconomyLab.findBot(gm, rest.length > 0 && !isKind(rest[0]) ? rest[0] : null);
                String kindArg = rest.length > 0 && isKind(rest[rest.length - 1]) ? rest[rest.length - 1] : null;
                if (bot == null) {
                    out.add("No such bot online (or none on your map).");
                    break;
                }
                BotGifts.Kind kind = kindArg == null ? null : BotGifts.Kind.valueOf(kindArg.toUpperCase());
                String line = BotGifts.give(bot, gm, kind, ThreadLocalRandom.current());
                out.add(line == null ? bot.getName() + " had nothing to give (same map? mesos? a scroll in its bag?)"
                        : line);
            }
            case "quote" -> {
                if (rest.length == 0) {
                    out.add("!economy quote [bot] <item words>");
                    break;
                }
                Character named = EconomyLab.findBot(gm, rest[0]);
                Character bot = named != null ? named : EconomyLab.findBot(gm, null);
                String words = String.join(" ", named != null ? Arrays.copyOfRange(rest, 1, rest.length) : rest);
                if (bot == null) {
                    out.add("No bot on your map.");
                    break;
                }
                BotDirectTrade.Answer a = BotDirectTrade.stageQuote(bot, gm, "how much for " + words);
                out.add(a == null ? bot.getName() + " does not trade in person." : bot.getName() + ": " + a.scripted()
                        + "  [fact: " + a.fact() + "]");
                out.add("Now open a trade with " + bot.getName() + ": it puts the item in the window at that price.");
            }
            case "price" -> {
                if (rest.length == 0 || !rest[0].matches("\\d{7}")) {
                    out.add("!economy price <7-digit item id>   (players: @price <name>)");
                    break;
                }
                int id = Integer.parseInt(rest[0]);
                Integer sv = null;
                try {
                    sv = soloMapling.itemPool.ItemDatabase.getInstance().getItemPrice(id);
                } catch (RuntimeException ignored) {
                    // no pool price
                }
                out.addAll(EconomyLab.price(id, sv == null ? 0 : sv));
            }
            case "tax" -> {
                long price = rest.length > 0 ? soloMapling.Economy.HaggleBrain.price(rest[0]) : -1;
                if (price <= 0 && rest.length > 0 && rest[0].matches("\\d+")) {
                    price = Long.parseLong(rest[0]);
                }
                out.addAll(price > 0 ? EconomyLab.tax(price) : List.of("!economy tax <price, e.g. 2.5m>"));
            }
            case "haggle" -> {
                if (rest.length < 2) {
                    out.add("!economy haggle <fair, e.g. 1m> <the player's line...>");
                    break;
                }
                long fair = soloMapling.Economy.HaggleBrain.price(rest[0]);
                if (fair <= 0 && rest[0].matches("\\d+")) {
                    fair = Long.parseLong(rest[0]);
                }
                String line = String.join(" ", Arrays.copyOfRange(rest, 1, rest.length));
                out.addAll(EconomyLab.haggle(Math.max(1, fair), line, false));
            }
            case "reload" -> {
                int n = EconomyConfig.reload();
                server.ShopFactory.getInstance().reloadShops();
                out.add("economy.yaml re-read: " + n + " keys; NPC shops reload with the new prices.");
            }
            default -> out.add("!economy [shop|close|gift|quote|price|tax|haggle|reload]");
        }
        for (String line : out) {
            gm.yellowMessage(line);
        }
    }

    private static boolean isKind(String s) {
        return s.equalsIgnoreCase("potions") || s.equalsIgnoreCase("scroll") || s.equalsIgnoreCase("mesos");
    }

    private static String closeShop(Character gm, String name) {
        Character bot = EconomyLab.findBot(gm, name);
        return bot == null ? "No such bot online." : BotShops.close(bot.getId(), "closed by a GM");
    }
}
