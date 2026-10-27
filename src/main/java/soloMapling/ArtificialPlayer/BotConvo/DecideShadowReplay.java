package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.server.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feeds logged chat through the same path a live player line takes ({@link ConvoSense#read} with the current Decide
 * model, which offers each line it judges to the {@link DecideShadow}), so the shadow run has data on a quiet TEST.
 * Rows are tagged {@code "src":"replay"} and go to the same {@code decide-shadow/<day>.jsonl}.
 *
 * <pre>
 * java -Dslums.decide.shadow=gliclass,onejev -cp target/Cosmic.jar \
 *   soloMapling.ArtificialPlayer.BotConvo.DecideShadowReplay [--current http://192.168.0.17:8900] [--gap-ms 1000]
 *   [--bot-lines] [--keep-dupes] [--limit N] logs/convo/transcript-*.jsonl
 * </pre>
 * Input: the convo transcripts ({@code logs/convo/transcript-<day>.jsonl}: the player's line is {@code asked}, the
 * bot's reply {@code line}), or plain text with one chat line per line. By default only player lines, each distinct
 * line once; {@code --bot-lines} adds the bots' lines as if a player typed them. One judged line at a time, then a
 * pause ({@code --gap-ms}, default 1000): gentle on the shared model hosts. Run from the server folder (rows go to
 * {@code data/autoqa}, or {@code -Dslums.autoqa.dir}).
 */
public final class DecideShadowReplay {

    private DecideShadowReplay() {
    }

    /** One line to replay and who said it. */
    record Line(String text, String speaker) {
    }

    /** The lines in one transcript or text file, in order. */
    static List<Line> parse(List<String> fileLines, boolean botLines) {
        List<Line> out = new ArrayList<>();
        for (String raw : fileLines) {
            String l = raw.trim();
            if (l.isEmpty()) {
                continue;
            }
            if (!l.startsWith("{")) {
                out.add(new Line(l, null));
                continue;
            }
            Object parsed;
            try {
                parsed = Json.parse(l);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!(parsed instanceof Map<?, ?> m)) {
                continue;
            }
            // "(thread: gossip)" and other bracketed markers are not player text
            if (m.get("asked") instanceof String asked && !asked.isBlank() && !asked.startsWith("(")) {
                out.add(new Line(asked.trim(), m.get("player") instanceof String p ? "player:" + p : null));
            }
            if (botLines && m.get("line") instanceof String said && !said.isBlank()) {
                out.add(new Line(said.replaceAll("\\{[a-z]+}", "u").trim(),
                        m.get("bot") instanceof String b ? "bot:" + b : null));
            }
        }
        return out;
    }

    /** Distinct lines (case and spacing ignored), first occurrence kept. */
    static List<Line> distinct(List<Line> lines) {
        Set<String> seen = new LinkedHashSet<>();
        List<Line> out = new ArrayList<>();
        for (Line l : lines) {
            // the form ChatDecide caches on: a repeat would only hit the live cache
            if (seen.add(l.text().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9? ]", " ")
                    .replaceAll("\\s+", " ").trim())) {
                out.add(l);
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String current = System.getProperty(DecideShadow.PROPERTY + ".current.url", "http://192.168.0.17:8900");
        long gapMs = 1000;
        boolean botLines = false, keepDupes = false;
        int limit = Integer.MAX_VALUE;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--current" -> current = args[++i];
                case "--shadow" -> System.setProperty(DecideShadow.PROPERTY, args[++i]);
                case "--gap-ms" -> gapMs = Long.parseLong(args[++i]);
                case "--bot-lines" -> botLines = true;
                case "--keep-dupes" -> keepDupes = true;
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty()) {
            System.err.println("usage: DecideShadowReplay [--shadow gliclass,onejev] [--current URL] [--gap-ms 1000]"
                    + " [--bot-lines] [--keep-dupes] [--limit N] <transcript.jsonl|chat.txt>...");
            System.exit(2);
        }
        List<Map<String, Object>> rows = Collections.synchronizedList(new ArrayList<>());
        DecideShadow shadow = DecideShadow.fromProperties("replay", rows::add);
        if (shadow == null) {
            System.err.println("no shadow models: pass --shadow gliclass,onejev or -D" + DecideShadow.PROPERTY + "=...");
            System.exit(2);
        }
        DecideShadow.install(shadow.start());
        List<Line> lines = new ArrayList<>();
        for (Path f : files) {
            lines.addAll(parse(Files.readAllLines(f, StandardCharsets.UTF_8), botLines));
        }
        if (!keepDupes) {
            lines = distinct(lines);
        }
        // the live client, with its 400 ms deadline; a rate high enough that the replay never trips it
        ChatDecide decide = ChatDecide.forUrl(current, Long.getLong("solomapling.chat.decideTimeoutMs", 400), 8);
        System.out.println("Replaying " + Math.min(limit, lines.size()) + " lines; shadow " + shadow.names()
                + " vs current " + current + " -> " + DecideShadow.dir(soloMapling.autoqa.AutoQa.dataDir()));
        int fed = 0, judged = 0;
        for (Line l : lines) {
            if (fed >= limit) {
                break;
            }
            fed++;
            long before = ((Number) shadow.counters().get("offered")).longValue();
            ConvoSense.read(l.text(), decide, l.speaker());
            if (((Number) shadow.counters().get("offered")).longValue() > before) {
                judged++;
                shadow.drain(30_000);
                Thread.sleep(gapMs);
            }
            if (fed % 50 == 0) {
                System.out.println("  " + fed + " fed, " + judged + " judged by the model");
            }
        }
        shadow.drain(60_000);
        System.out.println(fed + " lines fed, " + judged + " judged by the model (the rest rules alone); "
                + shadow.counters());
        DecideShadowReport.lines(new ArrayList<>(rows), 40).forEach(System.out::println);
        System.exit(0);
    }
}
