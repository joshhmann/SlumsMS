package client.command.commands.gm3;

import client.Character;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 113: the NX exchange's staff controls, {@code !exchange status|book|halt [reason]|resume}. The name
 * "exchange" already belongs to the players' {@code @exchange} (the item-exchange NPC), so that command hands these
 * sub-commands here when a GM of level 3 or more types one ({@link #handles}); everything else is unchanged.
 * {@code halt} is the emergency stop: no new orders, no fills, no expiry, no payouts; cancels still work.
 */
public final class NxExchangeCommand {

    public static final int GM_LEVEL = 3;
    private static final Set<String> SUBS = Set.of("status", "book", "halt", "resume");

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
                gm.yellowMessage("Exchange HALTED: " + reason + ". No new orders, fills, expiry or payouts.");
            }
            case "resume" -> {
                ex.resume();
                gm.yellowMessage("Exchange running again.");
            }
            default -> ex.status().forEach(gm::yellowMessage);
        }
    }
}
