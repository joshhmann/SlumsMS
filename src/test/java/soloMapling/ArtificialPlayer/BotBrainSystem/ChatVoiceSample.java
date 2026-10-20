package soloMapling.ArtificialPlayer.BotBrainSystem;

import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotPersona;
import soloMapling.server.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Ticket 37's voice comparison: the same player lines through the 1.3.0 prompt and cleaner ("before") and the
 * grounded prompt plus voice check ("after"), against a real OpenAI-compatible endpoint. Prints the raw reply, what
 * reaches chat, and the latency. A test-scope main (needs the network), one call at a time.
 *
 * <pre>
 *   java -cp ... soloMapling.ArtificialPlayer.BotBrainSystem.ChatVoiceSample http://192.168.0.17:8095/v1 qwen9b
 * </pre>
 */
public final class ChatVoiceSample {

    private ChatVoiceSample() {
    }

    /** The 1.3.0 system prompt, verbatim apart from the context plumbing. */
    static String before(BotChatPrompt.Context c, BotPersona.Persona persona, double chattiness) {
        return "You are " + c.botName() + ", a real person playing MapleStory in 2006. You are a level " + c.level() + ' '
                + c.job() + (c.map() == null ? "" : ", standing in " + c.map())
                + (c.activity() == null ? "" : ", currently " + c.activity()) + ". "
                + BotPersona.describe(persona, chattiness) + " You are talking to " + c.playerName() + ", a level "
                + c.playerLevel() + ' ' + c.playerJob() + ". "
                + "World facts (GMS v83, 2006): Victoria Island towns: Henesys (bowmen), Ellinia (magicians), Perion "
                + "(warriors), Kerning City (thieves), Nautilus (pirates), Lith Harbor (port), Sleepywood (dungeon town "
                + "below the island, Ant Tunnel). Any Victoria Island town is a short ride on the regular cab from another "
                + "(no ship needed). The ship to Orbis leaves from Ellinia station. From Orbis: El Nath down Orbis Tower, "
                + "Ludibrium by train, Leafre by ship. Job advances: 1st at lv10 (magician lv8) and 2nd at lv30 from your "
                + "class's instructor in your class's home town (bowmen Henesys, magicians Ellinia, warriors Perion, "
                + "thieves Kerning City, pirates Nautilus); 3rd at lv70 in El Nath; 4th at lv120 in Leafre. Party quests: "
                + "Kerning PQ lv21-30, Ludi PQ lv35-50, Orbis PQ lv51-70. The Free Market is reached from the FM entrance "
                + "in towns. Rules: stay in character as a player; never say you are an AI, a bot or a program, and if "
                + "someone asks, laugh it off like a player would. Talk about the game or casual life only. If you do not "
                + "know something, say so instead of making it up. Answer with one chat line under 60 characters, no "
                + "quotes, no name prefix.";
    }

    record Case(String line, String outcome) {
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://192.168.0.17:8095/v1";
        String model = args.length > 1 ? args[1] : "qwen9b";
        List<Case> cases = List.of(
                new Case("hey", null),
                new Case("nice hat where u get it", null),
                new Case("how do i get to orbis", null),
                new Case("whats ur favorite thing about this game", null),
                new Case("what do u do irl", null),
                new Case("any tips for a new player", null),
                new Case("do u like being an assassin", null),
                new Case("ugh i keep dying to zombie mushrooms", null),
                new Case("wanna party?", "Say yes to the party and tell them to send you the party invite."),
                new Case("can u come with me to sleepywood", "Say no to going with them: you are staying where you are."),
                new Case("where do u train", "Tell them you train at Ant Tunnel I."),
                new Case("trade?", "Say no to trading: you are not selling or giving anything right now."));
        BotPersona.Persona persona = BotPersona.generate(4242);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        int beforeOk = 0, afterOk = 0;
        List<String> rows = new ArrayList<>();
        for (Case c : cases) {
            BotChatPrompt.Context ctx = new BotChatPrompt.Context("Kimchiz", 41, "assassin", "Ant Tunnel I", "grinding",
                    null, "focused on grinding, a bit distracted", "Josh", 30, "page", List.of(),
                    List.of("Jinnie: anyone selling pots", "Kimchiz: no sry"), c.outcome());
            String[] beforeRaw = call(http, base, model, before(ctx, persona, 0.4), c.line());
            String[] afterRaw = call(http, base, model, BotChatPrompt.system(ctx, persona, 0.4), c.line());
            Optional<String> b = ReplyCleaner.clean(beforeRaw[0]);
            Optional<String> a = ReplyCleaner.chat(afterRaw[0]);
            String voiceB = ReplyCleaner.voiceProblem(beforeRaw[0]);
            beforeOk += voiceB == null ? 1 : 0;
            afterOk += a.isPresent() ? 1 : 0;
            rows.add(String.format(Locale.ROOT, "| %s | %s (%s ms)%s | %s (%s ms)%s |", c.line(),
                    show(b), beforeRaw[1], voiceB == null ? "" : " **would fail: " + voiceB + "**",
                    show(a), afterRaw[1], a.isEmpty() ? " -> scripted (" + ReplyCleaner.voiceProblem(afterRaw[0]) + ": `"
                            + oneLine(afterRaw[0]) + "`)" : ""));
        }
        System.out.println("| Player line | Before (1.3.0 prompt) | After (grounded prompt + voice check) |");
        System.out.println("|---|---|---|");
        rows.forEach(System.out::println);
        System.out.printf(Locale.ROOT, "%nbefore: %d/%d pass the new voice check; after: %d/%d reach chat from the model%n",
                beforeOk, cases.size(), afterOk, cases.size());
    }

    private static String show(Optional<String> line) {
        return line.map(l -> "`" + l + "`").orElse("(none)");
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace("\n", " / ").replace("|", "/");
    }

    /** {raw reply, latency ms}. */
    private static String[] call(HttpClient http, String base, String model, String system, String line) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", system), Map.of("role", "user", "content", line)));
        body.put("max_tokens", 40);
        body.put("temperature", 1.0);
        body.put("stream", false);
        body.put("chat_template_kwargs", Map.of("enable_thinking", false));
        long t = System.nanoTime();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        long ms = (System.nanoTime() - t) / 1_000_000;
        return new String[]{OpenAICompatibleBrain.replyText(response.body()), String.valueOf(ms)};
    }
}
