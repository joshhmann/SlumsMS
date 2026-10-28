package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.volatility.MarketEvents;
import soloMapling.Economy.exchange.volatility.MarketRumours;
import soloMapling.Economy.exchange.volatility.MarketTraders;
import soloMapling.Economy.exchange.volatility.MarketVolatility;

import java.util.List;
import java.util.Locale;

/**
 * 1.6.1 volatility, staff: {@code !market} (status), {@code !market events} (running and recent events, the kinds),
 * {@code !market event <rush|blight|rooty> [ticker] [minutes] [secret]} (start one; LEAF is the only ticker with
 * events), {@code !market end <name>}, {@code !market traders}, {@code !market act [botId]} (a trader's turn now),
 * {@code !market rumour [ticker]} (one rumour now). Each part answers that it is off when its flag is.
 */
public class MarketCommand extends Command {
    {
        setDescription("Market volatility: !market [events|event <rush|blight|rooty> [ticker] [minutes] [secret]|end <name>|traders|act [botId]|rumour [ticker]]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        try {
            for (String line : run(params, System.currentTimeMillis())) {
                gm.yellowMessage(line);
            }
        } catch (RuntimeException e) {
            gm.yellowMessage("!market failed: " + e.getMessage());
        }
    }

    static List<String> run(String[] params, long now) {
        String sub = params.length == 0 ? "status" : params[0].toLowerCase(Locale.ROOT);
        MarketVolatility mv = MarketVolatility.get();
        MarketEvents ev = MarketEvents.get();
        Exchange ex = ExchangeService.exchange();
        switch (sub) {
            case "status" -> {
                var cfg = ev.config();
                MarketRumours.Rumour r = mv == null ? null : mv.lastRumour();
                return List.of("Market: traders " + onOff(cfg.traders()) + " (" + cfg.traderCount() + "), events "
                                + onOff(cfg.events()) + " (" + cfg.eventsPerWeek() + "/week), rumours " + onOff(cfg.rumours())
                                + (ex == null ? "; the exchange is off (-Dslums.exchange=true)" : ""),
                        "Running events: " + describe(ev.active(now), now),
                        "Last rumour: " + (r == null ? "none" : r.claim() + (r.fake() ? " (made up) " : " ")
                                + r.ticker() + ": " + r.line()));
            }
            case "events" -> {
                StringBuilder kinds = new StringBuilder();
                for (MarketEvents.Kind k : MarketEvents.Kind.values()) {
                    kinds.append(kinds.length() == 0 ? "" : ", ").append(k.name().toLowerCase(Locale.ROOT)).append(" (")
                            .append(k.ticker).append(", ").append(k.defaultMinutes).append(" min)");
                }
                List<MarketEvents.Active> h = ev.history();
                return List.of("Events " + onOff(ev.enabled()) + ". Kinds: " + kinds,
                        "Running: " + describe(ev.active(now), now),
                        "Recent: " + (h.isEmpty() ? "none" : describe(h.subList(Math.max(0, h.size() - 5), h.size()),
                                now)));
            }
            case "event" -> {
                if (!ev.enabled()) {
                    return List.of("Market events are off (-Dslums.market.events=true).");
                }
                MarketEvents.Kind k = params.length > 1 ? MarketEvents.Kind.parse(params[1]) : null;
                if (k == null) {
                    return List.of("Usage: !market event <rush|blight|rooty> [ticker] [minutes] [secret]");
                }
                int minutes = 0;
                boolean secret = false;
                for (int i = 2; i < params.length; i++) {
                    String p = params[i].toLowerCase(Locale.ROOT);
                    if (p.equals("secret")) {
                        secret = true;
                    } else if (p.matches("\\d+")) {
                        minutes = Integer.parseInt(p);
                    } else if (!p.equalsIgnoreCase(k.ticker)) {
                        return List.of("No " + k.label + " lever for " + p.toUpperCase(Locale.ROOT)
                                + ": market events are LEAF-only for now.");
                    }
                }
                MarketEvents.Active a = ev.start(k, minutes, !secret, now);
                return List.of(k.label + " on for " + a.minutesLeft(now) + " min (" + (a.announced() ? "announced"
                        : "secret") + ")" + (k == MarketEvents.Kind.ROOTY_BONUS ? "; Rooty takes "
                        + ev.config().rootyBonusLeaves() + " leaves for a Chaos Scroll 60%" : "; Maple Leaf chance 500 -> "
                        + ev.leafChance(500, now) + " per million"));
            }
            case "end" -> {
                MarketEvents.Kind k = params.length > 1 ? MarketEvents.Kind.parse(params[1]) : null;
                return List.of(k != null && ev.end(k, now) ? k.label + " ended." : "No such event running.");
            }
            case "traders", "act", "rumour", "rumor" -> {
                if (mv == null || ex == null) {
                    return List.of("Off: needs -Dslums.exchange=true and the part's flag (-Dslums.exchange.traders, "
                            + "-Dslums.market.rumours).");
                }
                if (sub.startsWith("rumo")) {
                    if (!mv.config().rumours()) {
                        return List.of("Rumours are off (-Dslums.market.rumours=true).");
                    }
                    MarketRumours.Rumour r = mv.maybeRumour(ex, now, true, params.length > 1 ? params[1] : null);
                    return List.of(r == null ? "No rumour: no bot stands on a map with a player, or no line fit."
                            : "Said (" + r.claim() + (r.fake() ? ", made up" : ", true") + "): " + r.line());
                }
                if (!mv.config().traders()) {
                    return List.of("Trader bots are off (-Dslums.exchange.traders=true).");
                }
                MarketTraders.Roster roster = mv.gameRoster(now);
                if (sub.equals("traders")) {
                    return mv.traders().status(roster, ex);
                }
                long want = params.length > 1 && params[1].matches("\\d+") ? Long.parseLong(params[1]) : 0;
                for (MarketTraders.Trader t : roster.traders()) {
                    var w = roster.walletOf(t);
                    if (w != null && (want == 0 || t.id() == want)) {
                        ex.collectBot(w);
                        int n = mv.traders().act(ex, t, w, now, mv.config(), mv.events(), mv.rumours());
                        return List.of("Trader " + t.id() + " (" + t.archetype() + ") acted: " + n + " action(s). "
                                + "!market traders shows them.");
                    }
                }
                return List.of("No trader " + (want == 0 ? "" : want + " ") + "is online.");
            }
            default -> {
                return List.of("Usage: !market [events|event <rush|blight|rooty> [ticker] [minutes] [secret]|end <name>|"
                        + "traders|act [botId]|rumour [ticker]]");
            }
        }
    }

    private static String onOff(boolean b) {
        return b ? "ON" : "off";
    }

    private static String describe(List<MarketEvents.Active> as, long now) {
        if (as.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (MarketEvents.Active a : as) {
            sb.append(sb.length() == 0 ? "" : "; ").append(a.kind().label).append(a.announced() ? "" : " (secret)")
                    .append(a.endsAt() > now ? ", " + a.minutesLeft(now) + " min left" : ", ended");
        }
        return sb.toString();
    }
}
