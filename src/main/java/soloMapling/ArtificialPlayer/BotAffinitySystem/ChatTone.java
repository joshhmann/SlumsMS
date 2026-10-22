package soloMapling.ArtificialPlayer.BotAffinitySystem;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Ticket 58: was a player's line to a bot kind, plain or rude? Rules first (thanks, gg, "ur trash", the con flag for
 * harassment); a line the rules leave open goes to the cheap decision model (ticket 36's Decide, one small question,
 * no LLM and no thinking), and is plain when the model is off, slow or unsure. Plain lines move nothing.
 */
public final class ChatTone {

    public enum Tone { KIND, PLAIN, RUDE }

    static final ChatDecide.Question TONE = new ChatDecide.Question("tone",
            "How is the player treating you in this line?", labels(
            "kind", "friendly, thankful, complimenting, encouraging or warm",
            "plain", "neutral chat, a question or a request",
            "rude", "insulting, mocking, hostile or telling you off"));

    private static final Pattern KIND = Pattern.compile("(\\b(ty+|tyvm|tysm|thx+|thanks?|thank (u|you)|gj|good job|nice one"
            + "|gg|grats|congrats|gratz|gl|good luck|np|ily|ur (the best|awesome|cool|nice)|you'?re (the best|awesome"
            + "|cool|nice)|appreciate( it)?|love (u|you|ya)|cutie|ur sweet|so nice|helpful|ur a legend|legend)\\b"
            + "|<3|:\\)|\\^\\^|\\^_\\^)");
    private static final Pattern RUDE = Pattern.compile("\\b(noob|nub|newb|scrub|trash|garbage|stfu|shut up|shutup|idiot"
            + "|stupid|dumb|moron|loser|ugly|go away|get lost|f+u+ ?(you|u)?|screw (you|u)|ur bad|you suck|u suck"
            + "|ks+er|leecher|beggar|clown|cringe|lame|annoying|hate (u|you)|nobody asked|kys)\\b");

    private ChatTone() {
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** The tone by rule alone, or empty when the rules cannot tell. Rude wins over kind ("ty noob"). */
    public static Optional<Tone> rule(String line, ChatTriage.Result triage) {
        if (triage != null && triage.con() == ChatTriage.Con.HARASSMENT) {
            return Optional.of(Tone.RUDE);
        }
        String text = line == null ? "" : line.toLowerCase(Locale.ROOT).trim();
        if (text.isEmpty()) {
            return Optional.of(Tone.PLAIN);
        }
        if (RUDE.matcher(text).find()) {
            return Optional.of(Tone.RUDE);
        }
        if (KIND.matcher(text).find() || (triage != null && triage.kind() == ChatTriage.Kind.THANKS)) {
            return Optional.of(Tone.KIND);
        }
        if (triage != null && triage.kind() != ChatTriage.Kind.TALK) {
            return Optional.of(Tone.PLAIN); // hi, bye, lol: nothing to judge
        }
        return Optional.empty();
    }

    /**
     * The tone of a line: by rule when it can, else the decision model's confident answer, else plain. Never blocks:
     * completes when the model answers or times out (bounded by Decide's own deadline and budget).
     */
    public static CompletableFuture<Tone> of(String line, ChatTriage.Result triage, ChatDecide decide) {
        Optional<Tone> byRule = rule(line, triage);
        if (byRule.isPresent()) {
            return CompletableFuture.completedFuture(byRule.get());
        }
        if (decide == null) {
            return CompletableFuture.completedFuture(Tone.PLAIN);
        }
        return decide.ask(line, List.of(TONE)).thenApply(answers -> answers.map(a -> fromModel(a.get(TONE.name()),
                minConfidence())).orElse(Tone.PLAIN));
    }

    static Tone fromModel(ChatDecide.Answer answer, double min) {
        if (answer == null || answer.confidence() < min) {
            return Tone.PLAIN;
        }
        return switch (answer.choice().toLowerCase(Locale.ROOT)) {
            case "kind" -> Tone.KIND;
            case "rude" -> Tone.RUDE;
            default -> Tone.PLAIN;
        };
    }

    static double minConfidence() {
        return AffinityRules.prop("toneMin", 0.6);
    }
}
