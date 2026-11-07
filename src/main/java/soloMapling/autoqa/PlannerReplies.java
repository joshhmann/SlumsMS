package soloMapling.autoqa;

import soloMapling.ArtificialPlayer.BotConvo.ConvoQuality;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches;
import soloMapling.ArtificialPlayer.BotConvo.StateOfMind;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code t-pl-1} (m02, owner 2026-10-31): the claims the reply planner made, read back from the chat transcript the
 * server already writes ({@code logs/convo/transcript-<day>.jsonl}). Three things the planner alone is responsible
 * for, each judged from the row's own fields rather than guessed from its words:
 * <ul>
 *   <li>a reply said on a town map (a map with no monsters, {@link StateOfMind#TOWNS}) never claims the bot is
 *       grinding, training or farming right there - the b240 rule, now planned instead of hoped for;</li>
 *   <li>a reply the planner worded (the row has a {@code move}) never reaches a player with a literal
 *       <code>&#123;slot&#125;</code> in it: the planner's own scripts are filled before they leave;</li>
 *   <li>a bot starts at most one chat per player per hour, from any bot ({@link
 *       soloMapling.ArtificialPlayer.BotConvo.SocialGate#botChatGapMs()}), which the planner's greet path enforces.</li>
 * </ul>
 * Threads (a bot talking to the map, {@code player "-"}) are not replies to a player and are left out. The check SKIPs
 * when the planner switch is off or nothing was said in the window, so a run without the switch is a gap, not a pass.
 * <p>
 * Only rows written since this server process started are judged, still inside the 24 h window ({@link
 * ChatQuality#bootMs()}). A deploy replaces the planner, so a line the old build worded says nothing about the build
 * that is running: on TEST (2026-11-01) t-pl-1 failed on one reply from before the 1.6.3 deploy, and a check that
 * cannot tell the two builds apart fails every run for a day after each deploy.
 */
final class PlannerReplies {

    private PlannerReplies() {
    }

    static final long HOUR_MS = TimeUnit.HOURS.toMillis(1);
    static final long WINDOW_MS = TimeUnit.HOURS.toMillis(24);
    /** How many findings go into the verdict's evidence: enough to act on, not a transcript dump. */
    static final int MAX_SHOWN = 6;

    /**
     * One transcript row: who said it to whom, where, and what the planner had decided. {@code slots} is
     * {@code ConvoQuality}'s read of the line ("none" | "filled" | "literal"); {@code open} means the bot spoke first.
     */
    record Row(long at, String bot, String player, String line, int map, String move, String slots, boolean open) {

        /** The row from its JSON, or null when it has no line or time (a torn row is skipped, never a FAIL). */
        static Row of(Map<String, Object> m) {
            if (!(m.get("line") instanceof String line) || !(m.get("at") instanceof Number at)) {
                return null;
            }
            return new Row(at.longValue(), str(m.get("bot")), str(m.get("player")), line,
                    m.get("map") instanceof Number n ? n.intValue() : 0, str(m.get("move")), str(m.get("slots")),
                    Boolean.TRUE.equals(m.get("open")));
        }

        /** A reply to a player: a thread on the map ({@code player "-"}) is not one. */
        boolean toAPlayer() {
            return player != null && !"-".equals(player);
        }

        /** The planner worded this line (rows from before m02 carry no move and are not judged as planned). */
        boolean planned() {
            return move != null;
        }

        private static String str(Object o) {
            return o == null ? null : String.valueOf(o);
        }
    }

    /** Where the transcript lives (the same folder t-cq-1 reads). */
    static Path dir() {
        Path f = ConvoQuality.transcriptFile(LocalDate.now());
        return f == null || f.getParent() == null ? Path.of("logs", "convo") : f.getParent();
    }

    /** Every row between the two times, oldest first. */
    static List<Row> load(Path transcriptDir, long fromMs, long toMs) {
        List<Row> out = new ArrayList<>();
        if (transcriptDir == null) {
            return out;
        }
        LocalDate first = Instant.ofEpochMilli(fromMs).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate last = Instant.ofEpochMilli(toMs).atZone(ZoneId.systemDefault()).toLocalDate();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            for (Map<String, Object> m : ChatQuality.jsonl(transcriptDir.resolve("transcript-" + d + ".jsonl"))) {
                Row r = Row.of(m);
                if (r != null && r.at() >= fromMs && r.at() <= toMs) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparingLong(Row::at));
        return out;
    }

    /**
     * The verdict: SKIP without the planner or without a reply since the server started, FAIL on any broken rule, else
     * PASS with a count. Rows older than {@code bootMs} are dropped and counted first: they were said by whatever build
     * ran before this process ({@code bootMs} of 0 means unknown, so every row counts).
     */
    static Check.Verdict judge(List<Row> rows, boolean plannerOn, long bootMs) {
        if (!plannerOn) {
            return Check.Verdict.skip("the reply planner is off (-Dsolomapling.convo.planner=true)");
        }
        List<Row> said = new ArrayList<>();
        int before = 0;
        for (Row r : rows) {
            if (bootMs > 0 && r.at() < bootMs) {
                before++;
            } else if (r.toAPlayer()) {
                said.add(r);
            }
        }
        String skipped = "  " + before + " transcript row(s) from before this server started were skipped";
        if (said.isEmpty()) {
            if (bootMs <= 0) {
                return Check.Verdict.skip("no bot reply to a player in the last 24 h");
            }
            String why = "no bot reply to a player since this server started (" + Instant.ofEpochMilli(bootMs) + ")";
            return before > 0 ? Check.Verdict.skip(why, skipped) : Check.Verdict.skip(why);
        }
        List<String> bad = new ArrayList<>();
        int planned = 0;
        int town = 0;
        for (Row r : said) {
            if (StateOfMind.TOWNS.contains(r.map())) {
                town++;
                if (StateOfMind.claimsGrindingInTown(r.line())) {
                    bad.add("grinding claimed in a town (map " + r.map() + "): " + r.bot() + " \"" + r.line() + "\"");
                }
            }
            if (r.planned()) {
                planned++;
                if ("literal".equals(r.slots())) {
                    bad.add("a {slot} in a planned line (" + r.move() + "): " + r.bot() + " \"" + r.line() + "\"");
                }
            }
        }
        bad.addAll(tooTalkative(said));
        List<String> ev = new ArrayList<>();
        ev.add(said.size() + " replies to players "
                + (bootMs > 0 ? "since this server started " + Instant.ofEpochMilli(bootMs) : "in the last 24 h")
                + ", " + planned + " planned, " + town + " in a town");
        if (before > 0) {
            ev.add(skipped);
        }
        for (int i = 0; i < Math.min(bad.size(), MAX_SHOWN); i++) {
            ev.add("  " + bad.get(i));
        }
        if (bad.size() > MAX_SHOWN) {
            ev.add("  ... and " + (bad.size() - MAX_SHOWN) + " more");
        }
        return new Check.Verdict(bad.isEmpty() ? Check.Status.PASS : Check.Status.FAIL, ev);
    }

    /**
     * A player may hear from at most one bot an hour (SocialGate's bot-started gap, 1 h by default): more than one
     * bot-started chat inside any hour is the failure the owner asked for. One finding per player, with its bots.
     */
    static List<String> tooTalkative(List<Row> said) {
        Map<String, List<Row>> opens = new LinkedHashMap<>();
        for (Row r : said) {
            if (r.open()) {
                opens.computeIfAbsent(r.player(), k -> new ArrayList<>()).add(r);
            }
        }
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : opens.entrySet()) {
            List<Row> mine = e.getValue();
            mine.sort(Comparator.comparingLong(Row::at));
            for (int i = 0; i < mine.size(); i++) {
                long until = mine.get(i).at() + HOUR_MS;
                List<String> who = new ArrayList<>();
                long last = mine.get(i).at();
                for (int j = i; j < mine.size() && mine.get(j).at() < until; j++) {
                    who.add(mine.get(j).bot());
                    last = mine.get(j).at();
                }
                if (who.size() > 1) {
                    bad.add("player " + e.getKey() + " heard " + who.size() + " bot-started chats in "
                            + Math.max(1, Math.round((last - mine.get(i).at()) / 60_000.0)) + " min from: "
                            + String.join(", ", who));
                    break; // one finding per player is enough to act on
                }
            }
        }
        return bad;
    }

    /** The t-pl-1 expectation: reads the last 24 h of transcript now, judges what this build said, no in-game steps. */
    static Check.Expect expectation() {
        return Expects.of("planned replies: no grinding claimed in a town, no literal {slot}, one bot-started chat "
                + "per player an hour", r -> {
                    long now = System.currentTimeMillis();
                    return judge(load(dir(), now - WINDOW_MS, now), ConvoSwitches.planner(), ChatQuality.bootMs());
                });
    }
}
