package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.server.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * m02 section 6: the replay check over real TEST traffic. Four days of replies to players (2026-10-28..31, 768 lines,
 * checked in as {@code chat/replies-to-players-2026-10-28-31.jsonl}) are re-read with the rule triage and the planner:
 * how many said the bot was grinding where it stood in a town, which of the lines the planner would have moved
 * instead, what its scripts say in their place, and which lines three or more bots said word for word. {@code main}
 * prints the tables for REPORT.md; {@link ReplyReplayTest} asserts them.
 */
final class ReplyReplay {

    static final String FIXTURE = "/chat/replies-to-players-2026-10-28-31.jsonl";

    /** What players call the towns in the transcript, for the planner's place fact (offline: no game data). */
    private static final Map<Integer, String> TOWN_NAMES = Map.ofEntries(
            Map.entry(100000000, "henesys"), Map.entry(101000000, "ellinia"), Map.entry(102000000, "perion"),
            Map.entry(103000000, "kerning"), Map.entry(104000000, "lith"), Map.entry(105040300, "sleepywood"),
            Map.entry(120000000, "nautilus"), Map.entry(910000000, "fm"), Map.entry(200000000, "orbis"),
            Map.entry(211000000, "el nath"), Map.entry(220000000, "ludi"), Map.entry(230000000, "aqua"),
            Map.entry(240000000, "leafre"), Map.entry(250000000, "mu lung"), Map.entry(251000000, "herb town"),
            Map.entry(600000000, "nlc"), Map.entry(680000000, "amoria"), Map.entry(221000000, "omega"),
            Map.entry(260000000, "ariant"), Map.entry(261000000, "magatia"));

    record Row(long at, String bot, String asked, String line, String source, int map) {
    }

    record Result(int replies, int town, int townAsked, int plannedAsked, int claimsBefore, int claimsAfter, int planned, int scripts,
                  Map<String, Integer> byMove, Map<String, Integer> sources, List<String> claimSamples,
                  List<String> repeatsBefore, List<String> repeatsAfter, String topBefore, String topAfter) {
    }

    private ReplyReplay() {
    }

    static List<Row> rows() throws IOException {
        List<Row> out = new ArrayList<>();
        for (String raw : read().split("\n")) {
            if (raw.isBlank()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) Json.parse(raw);
            out.add(new Row(num(r.get("at")), str(r.get("bot")), str(r.get("asked")), str(r.get("line")),
                    str(r.get("source")), (int) num(r.get("map"))));
        }
        return out;
    }

    /** One said line: who said it and when. */
    private record Said(long at, String bot) {
    }

    static Result run(List<Row> rows) {
        ReplyPlanner.clearForTest();
        List<Row> ordered = new ArrayList<>(rows);
        ordered.sort(java.util.Comparator.comparingLong(Row::at));
        int town = 0;
        int townAsked = 0;
        int plannedAsked = 0;
        int claimsBefore = 0;
        int claimsAfter = 0;
        int planned = 0;
        int scripts = 0;
        Map<String, Integer> byMove = new TreeMap<>();
        Map<String, Integer> sources = new TreeMap<>();
        List<String> claimSamples = new ArrayList<>();
        Map<String, List<Said>> saidBy = new LinkedHashMap<>();
        Map<String, List<Said>> planSaidBy = new LinkedHashMap<>();
        java.util.Random rng = new java.util.Random(7);
        for (Row row : ordered) {
            sources.merge(row.source() == null ? "?" : row.source(), 1, Integer::sum);
            saidBy.computeIfAbsent(StateOfMind.words(row.line()), k -> new ArrayList<>())
                    .add(new Said(row.at(), row.bot()));
            if (isTown(row.map())) {
                town++;
            }
            if (StateOfMind.claimsGrindingInTown(row.line())) {
                claimsBefore++;
                if (claimSamples.size() < 8) {
                    claimSamples.add(row.bot() + " @ " + TOWN_NAMES.getOrDefault(row.map(), "" + row.map()) + ": "
                            + row.line());
                }
            }
            if (!isTown(row.map())) {
                continue; // outside a town a plan needs the mob names, which this fixture does not carry
            }
            boolean askedLine = !row.asked().isBlank();
            if (askedLine) {
                townAsked++;
            }
            ReplyPlanner.View view = view(row);
            ReplyPlanner.Plan plan = ReplyPlanner.plan(view);
            if (plan == null) {
                continue;
            }
            planned++;
            if (askedLine) {
                plannedAsked++;
            }
            byMove.merge(plan.move().name(), 1, Integer::sum);
            String script = ReplyPlanner.line(row.bot().hashCode(), view, plan, rng, row.at());
            if (script == null) {
                continue;
            }
            scripts++;
            if (StateOfMind.claimsGrindingInTown(script)) {
                claimsAfter++;
            }
            planSaidBy.computeIfAbsent(StateOfMind.words(script), k -> new ArrayList<>())
                    .add(new Said(row.at(), row.bot()));
        }
        return new Result(rows.size(), town, townAsked, plannedAsked, claimsBefore, claimsAfter, planned, scripts, byMove, sources, claimSamples,
                repeats(saidBy), repeats(planSaidBy), top(saidBy), top(planSaidBy));
    }

    /** The line the most bots said word for word at any time in the four days: the worst of the collapse. */
    private static String top(Map<String, List<Said>> saidBy) {
        String best = "none";
        int most = 0;
        for (Map.Entry<String, List<Said>> e : saidBy.entrySet()) {
            Set<String> bots = new LinkedHashSet<>();
            for (Said said : e.getValue()) {
                bots.add(said.bot());
            }
            if (bots.size() > most) {
                most = bots.size();
                best = most + " bots: \"" + e.getKey() + "\"";
            }
        }
        return best;
    }

    private static final long WINDOW_MS = 10 * 60_000L;

    /**
     * Lines three or more bots said word for word inside ten minutes of each other, worst first: the count is the most
     * distinct bots that said that line in any ten-minute window, which is what "repeated" means to a player.
     */
    private static List<String> repeats(Map<String, List<Said>> saidBy) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Said>> e : saidBy.entrySet()) {
            List<Said> said = new ArrayList<>(e.getValue());
            said.sort(java.util.Comparator.comparingLong(Said::at));
            int worst = 0;
            for (int i = 0; i < said.size(); i++) {
                Set<String> bots = new LinkedHashSet<>();
                for (int j = i; j < said.size() && said.get(j).at() - said.get(i).at() <= WINDOW_MS; j++) {
                    bots.add(said.get(j).bot());
                }
                worst = Math.max(worst, bots.size());
            }
            if (worst >= 3) {
                out.add(worst + " bots: \"" + e.getKey() + "\"");
            }
        }
        out.sort((a, b) -> Integer.parseInt(b.substring(0, b.indexOf(' ')))
                - Integer.parseInt(a.substring(0, a.indexOf(' '))));
        return out;
    }

    /** The planner's view of a recorded reply: what the reads would have given it, without game data. */
    static ReplyPlanner.View view(Row row) {
        String place = TOWN_NAMES.get(row.map());
        ChatTriage.Result triage = ChatTriage.rules(row.asked());
        List<String> spots = new ArrayList<>();
        try {
            for (TrainingTips.Spot s : TrainingTips.spotsFor(30, 100, 1)) {
                spots.add(s.name());
            }
        } catch (RuntimeException | LinkageError e) {
            // no leveling path offline: fall through to a named spot so coverage can still be counted
        }
        if (spots.isEmpty()) {
            spots.add("ant tunnel");
        }
        return new ReplyPlanner.View(row.bot(), 30, "warrior", place, place != null, List.of(), "hanging out in town",
                false, false, "player", 30, "warrior", false, ReplyPlanner.Bond.STRANGER, null, null, null, spots,
                "ant tunnel", false, row.asked(), triage.kind(), triage.intent(), null);
    }

    static boolean isTown(int map) {
        return StateOfMind.TOWNS.contains(map) || map == 910000000;
    }

    public static void main(String[] args) throws IOException {
        Result r = run(rows());
        System.out.println("replies to players: " + r.replies() + "  " + r.sources());
        System.out.println("on a town map:      " + r.town() + "  (" + r.townAsked()
                + " with the player's line recorded, " + r.plannedAsked() + " of those planned)");
        System.out.println("grinding-in-town before: " + r.claimsBefore() + "  after (planner scripts): "
                + r.claimsAfter());
        System.out.println("planner covers:     " + r.planned() + " of " + r.town() + "  " + r.byMove());
        System.out.println("scripts tried:      " + r.scripts());
        System.out.println("worst line of the four days, before: " + r.topBefore());
        System.out.println("word-for-word by 3+ bots inside 10 min, before: " + r.repeatsBefore().size());
        for (String s : r.repeatsBefore().subList(0, Math.min(10, r.repeatsBefore().size()))) {
            System.out.println("  " + s);
        }
        System.out.println("worst line of the four days, after (planner scripts): " + r.topAfter());
        System.out.println("word-for-word by 3+ bots inside 10 min, after: " + r.repeatsAfter().size());
        for (String s : r.repeatsAfter()) {
            System.out.println("  " + s);
        }
        System.out.println("samples of the before set:");
        for (String s : r.claimSamples()) {
            System.out.println("  " + s);
        }
    }

    private static String read() throws IOException {
        try (InputStream in = ReplyReplay.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IOException("missing fixture " + FIXTURE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** For a run over fresh logs: {@code java ... ReplyReplay <files>}. */
    static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
