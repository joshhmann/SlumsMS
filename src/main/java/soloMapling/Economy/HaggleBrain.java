package soloMapling.Economy;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.5.0 rc3, owner decision 3: what a player's line means inside a haggle with a bot. Rules first (a price, "deal",
 * "how much", "no"), then the fast decision model (GLiNER2.5-Decide through {@link ChatDecide}: a classifier, no
 * reasoning) for what the rules leave open. The answer is only a move; the price itself is always the number the
 * player typed, and what the bot does with it is {@link PriceBand}'s decision in code, so no prompt can talk a bot
 * outside its band.
 */
public final class HaggleBrain {

    public enum Move { OFFER, ACCEPT, ASK_PRICE, REFUSE, OTHER }

    /** The move and, for an offer, the price the player named (mesos). */
    public record Read(Move move, long price, String source) {
    }

    public static final ChatDecide.Question HAGGLE = new ChatDecide.Question("haggle",
            "In a trade, what is the player doing about the price?", labels(
            "accept", "agrees to the price: deal, ok, sure, sounds good",
            "ask_price", "asks how much something costs or what you would pay",
            "refuse", "says no, too expensive, too cheap, or walks away",
            "other", "anything else"));

    // "1.2m", "800k", "2b", "1,200,000", "500000"; a bare small number is not a price
    private static final Pattern PRICE = Pattern.compile("(?<![\\w.])(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)\\s*(k|m|mil|b|bil)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCEPT = Pattern.compile("\\b(deal|ok+|okay|sure|kk|k|fine|sold|done|sounds good|ya|yes|yeah|yep|alright|bet|agreed)\\b");
    private static final Pattern ASK = Pattern.compile("(how much|price\\??$|\\bpc\\b|what (do )?(u|you) (want|pay|give)|whats? (the )?price|\\bcost)");
    private static final Pattern REFUSE = Pattern.compile("\\b(no+|nah|nope|pass|too (much|expensive|high|low|cheap)|rip ?off|scam|nvm|never ?mind|forget it)\\b");

    private HaggleBrain() {
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** The price a line names, or -1: "1.2m", "800k", "1,200,000", or a plain number of 1,000 or more. */
    public static long price(String line) {
        if (line == null) {
            return -1;
        }
        Matcher m = PRICE.matcher(line.toLowerCase(Locale.ROOT));
        long best = -1;
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            String unit = m.group(2);
            double v;
            try {
                v = Double.parseDouble(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            if (unit != null) {
                switch (unit) {
                    case "k" -> v *= 1_000;
                    case "m", "mil" -> v *= 1_000_000;
                    case "b", "bil" -> v *= 1_000_000_000;
                    default -> {
                    }
                }
            } else if (v < 1000) {
                continue;   // "lv 30", "2 of them": not a price
            }
            if (v > 0 && v <= 2_100_000_000d) {
                best = Math.max(best, (long) v);
            }
        }
        return best;
    }

    /** Rules only. */
    public static Read rules(String line) {
        String text = line == null ? "" : line.toLowerCase(Locale.ROOT).trim();
        long p = price(text);
        if (p > 0) {
            return new Read(Move.OFFER, p, "rule");
        }
        if (REFUSE.matcher(text).find()) {
            return new Read(Move.REFUSE, -1, "rule");
        }
        if (ASK.matcher(text).find()) {
            return new Read(Move.ASK_PRICE, -1, "rule");
        }
        if (ACCEPT.matcher(text).find()) {
            return new Read(Move.ACCEPT, -1, "rule");
        }
        return new Read(Move.OTHER, -1, "default");
    }

    /** Rules, then the model when they found nothing (blocks at most the model's deadline: off the game thread). */
    public static Read read(String line, ChatDecide decide) {
        Read r = rules(line);
        if (r.move() != Move.OTHER || decide == null || line == null || line.isBlank()) {
            return r;
        }
        Optional<Map<String, ChatDecide.Answer>> answers = decide.askNow(line, List.of(HAGGLE));
        if (answers.isEmpty()) {
            return r;
        }
        ChatDecide.Answer a = answers.get().get(HAGGLE.name());
        if (a == null || a.confidence() < EconomyConfig.getDouble("bands.decideMin", 0.5)) {
            return r;
        }
        return switch (a.choice()) {
            case "accept" -> new Read(Move.ACCEPT, -1, "decide");
            case "ask_price" -> new Read(Move.ASK_PRICE, -1, "decide");
            case "refuse" -> new Read(Move.REFUSE, -1, "decide");
            default -> r;
        };
    }
}
