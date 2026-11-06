package server.maps;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;

import java.util.function.Consumer;

/**
 * One INFO line per finished or refused hired-merchant / player-shop sale (P0-2, approved by the Coordinator).
 * The line says only the kind, whether buyer and owner are real players or bots, and for a refusal the reason
 * class. It never carries a name, a character id, an item id or a meso amount.
 */
final class MarketLog {
    private static final Logger log = LoggerFactory.getLogger("Market");

    /** Test hook: sees every line that was logged. */
    static volatile Consumer<String> tap;

    private MarketLog() {
    }

    static String who(Character chr) {
        return chr != null && BotHelpers.isBot(chr) ? "bot" : "real";
    }

    static String saleLine(String kind, String buyer, String owner) {
        return "[Market] " + kind + " sale committed: buyer=" + buyer + " owner=" + owner;
    }

    static String refusedLine(String kind, String buyer, String owner, String reason) {
        return "[Market] " + kind + " sale refused: reason=" + reason + " buyer=" + buyer + " owner=" + owner;
    }

    static void sale(String kind, Character buyer, String owner) {
        emit(saleLine(kind, who(buyer), owner));
    }

    static void refused(String kind, Character buyer, String owner, String reason) {
        emit(refusedLine(kind, who(buyer), owner, reason));
    }

    /** A sale where neither side is a real player. */
    static boolean isBotToBot(String line) {
        return line.contains("buyer=bot") && line.contains("owner=bot");
    }

    private static void emit(String line) {
        if (isBotToBot(line)) {
            log.debug(line);   // bot-to-bot sales are too many for INFO
        } else {
            log.info(line);
        }
        Consumer<String> t = tap;
        if (t != null) {
            t.accept(line);
        }
    }
}
