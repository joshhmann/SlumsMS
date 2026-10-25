package soloMapling.ArtificialPlayer.BotBrainSystem;

import soloMapling.ArtificialPlayer.BotConvo.ConvoSense;
import soloMapling.server.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Offline JSONL bridge for tools/convo/benchmark: production request, parser and rules, without a server or DB. */
public final class DecideBenchmarkBridge {
    static final List<ChatDecide.Question> QUESTIONS = List.of(ConvoSense.INTENT, ConvoSense.TONE, ChatTriage.CON);

    private DecideBenchmarkBridge() { }

    static boolean wouldAsk(String line) {
        ChatTriage.Result rule = ChatTriage.rules(line);
        return rule.kind() == ChatTriage.Kind.TALK && (ConvoSense.intentRule(line).isEmpty()
                || ConvoSense.toneRule(line, rule).isEmpty() || rule.conSource() != ChatTriage.Source.RULE);
    }

    static Map<String, Object> describe(ConvoSense.Read read) {
        return Map.of("intent", read.intent().label(), "tone", read.tone().name().toLowerCase(Locale.ROOT),
                "con", read.triage().con().name().toLowerCase(Locale.ROOT),
                "intent_source", read.intentSource().name(), "tone_source", read.toneSource().name(),
                "con_source", read.triage().conSource().name(), "legacy_intent", read.triage().intent().name());
    }

    static Map<String, Object> evaluate(Map<?, ?> row) {
        String line = (String) row.get("line");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("request", ChatDecide.request(line, QUESTIONS));
        out.put("would_ask", wouldAsk(line));
        out.put("rules", describe(ConvoSense.rules(line)));
        Map<String, ChatDecide.Answer> parsed = ChatDecide.parse((String) row.get("response"), QUESTIONS).orElse(Map.of());
        Map<String, Object> raw = new LinkedHashMap<>();
        parsed.forEach((key, value) -> raw.put(key, Map.of("choice", value.choice(), "confidence", value.confidence())));
        out.put("parsed", raw);
        // The Python runner supplies transport/deadline eligibility, not its own interpretation of labels.
        Map<String, ChatDecide.Answer> accepted = Boolean.TRUE.equals(row.get("within_budget")) && wouldAsk(line)
                ? parsed : Map.of();
        out.put("combined", describe(ConvoSense.combine(line, ChatTriage.rules(line), accepted, 0.4, 0.8, 0.4)));
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("input.jsonl output.jsonl required");
        try (var in = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8);
             var out = Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.isBlank()) continue;
                out.write(Json.write(evaluate((Map<?, ?>) Json.parse(line))));
                out.newLine();
            }
        }
    }
}
