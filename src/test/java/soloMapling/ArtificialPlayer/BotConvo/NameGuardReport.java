package soloMapling.ArtificialPlayer.BotConvo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 S4 measurement: runs {@link NameGuard} over TEST conversation transcripts ({@code logs/convo/transcript-*.jsonl})
 * and counts the bot-thread lines that named someone who does not exist, as they went out before the guard.
 *
 * <p>Who exists, offline: every name in the bot name pool files (a superset of the names bots hold) plus every bot
 * that spoke in the transcripts. A made-up name that happens to be a pool name counts as known, so the count is a
 * floor. Run: {@code java -cp target/classes:target/test-classes:<deps> soloMapling.ArtificialPlayer.BotConvo.NameGuardReport
 * transcript-2026-10-2*.jsonl}.
 */
public final class NameGuardReport {

    private static final Pattern FIELD = Pattern.compile("\"(bot|asked|line|tier)\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    public record Row(String bot, String asked, String line, String tier) {
    }

    public record Result(int lines, int threadLines, int gossipLines, int flagged, int gossipFlagged, int dropped,
                         int rewritten, Map<String, Integer> strangers, List<String> examples) {
    }

    public static void main(String[] args) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String a : args) {
            rows.addAll(read(Path.of(a)));
        }
        Result r = measure(rows, poolNames());
        System.out.println("lines " + r.lines() + ", thread lines " + r.threadLines() + " (gossip " + r.gossipLines() + ")");
        System.out.println("thread lines naming someone who does not exist: " + r.flagged() + " (gossip "
                + r.gossipFlagged() + "); the guard rewrites " + r.rewritten() + " and drops " + r.dropped());
        System.out.println("strangers: " + r.strangers());
        r.examples().forEach(e -> System.out.println("  " + e));
    }

    public static List<Row> read(Path file) throws IOException {
        List<Row> out = new ArrayList<>();
        for (String json : Files.readAllLines(file)) {
            String bot = null, asked = "", line = null, tier = "";
            Matcher m = FIELD.matcher(json);
            while (m.find()) {
                String v = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
                switch (m.group(1)) {
                    case "bot" -> bot = v;
                    case "asked" -> asked = v;
                    case "line" -> line = v;
                    case "tier" -> tier = v;
                    default -> {
                    }
                }
            }
            if (bot != null && line != null) {
                out.add(new Row(bot, asked, line, tier));
            }
        }
        return out;
    }

    /** The bot name pool files, lower case. */
    public static Set<String> poolNames() throws IOException {
        Set<String> names = new HashSet<>();
        List<Path> files = new ArrayList<>();
        files.add(Path.of("src/main/java/soloMapling/FreeMarket/FMNameDesc/randomRealMaplestoryIGNs.txt"));
        try (var dir = Files.list(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotNames"))) {
            dir.filter(p -> p.toString().endsWith(".txt")).forEach(files::add);
        }
        for (Path f : files) {
            if (Files.isRegularFile(f)) {
                for (String l : Files.readAllLines(f)) {
                    String n = l.trim();
                    if (!n.isEmpty() && !n.startsWith("#")) {
                        names.add(n.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    public static Result measure(List<Row> rows, Set<String> pool) {
        Set<String> known = new HashSet<>(pool);
        for (Row r : rows) {
            known.add(r.bot().toLowerCase(Locale.ROOT));
        }
        int thread = 0, gossip = 0, flagged = 0, gossipFlagged = 0, dropped = 0, rewritten = 0;
        Map<String, Integer> strangers = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (Row r : rows) {
            if (!"thread".equals(r.tier())) {
                continue;
            }
            thread++;
            boolean isGossip = r.asked().contains("gossip");
            gossip += isGossip ? 1 : 0;
            NameGuard.Verdict v = NameGuard.check(r.line(), List.of(r.bot()),
                    name -> known.contains(name.toLowerCase(Locale.ROOT)));
            if (v.clean()) {
                continue;
            }
            flagged++;
            gossipFlagged += isGossip ? 1 : 0;
            dropped += v.dropped() ? 1 : 0;
            rewritten += v.rewritten() ? 1 : 0;
            for (String s : v.unknown()) {
                strangers.merge(s, 1, Integer::sum);
            }
            examples.add(r.asked() + " " + r.line() + "  ->  " + (v.dropped() ? "(dropped)" : v.line()));
        }
        return new Result(rows.size(), thread, gossip, flagged, gossipFlagged, dropped, rewritten, strangers, examples);
    }
}
