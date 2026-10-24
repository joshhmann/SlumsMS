package soloMapling.ArtificialPlayer.BotMessagingSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotConvo.ConvoQuality;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 1.5.0 rc6: one record per routing decision of a player's line, for tuning the routing order later
 * ({@code tools/convo/route_labels.py}). It goes to the QaLog CONVO topic as {@code ROUTE CANDIDATES {json}} when that
 * topic is on, and as JSONL to {@code data/convo/routing/<day>.jsonl} only when auto-QA is on or the JVM has
 * {@code -Dsolomapling.convo.routeLog=true} (live writes nothing to disk by default). The player is a hash; their
 * name and other real players' names are replaced in every text.
 */
public final class RouteLog {

    private static final Logger log = LoggerFactory.getLogger(RouteLog.class);
    public static final String PROPERTY = "solomapling.convo.routeLog";

    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "convo-route-log");
        t.setDaemon(true);
        return t;
    });
    private static volatile Boolean diskForTest;

    private RouteLog() {
    }

    /** Whether records go to disk: auto-QA on, or the property. */
    static boolean toDisk() {
        Boolean forced = diskForTest;
        if (forced != null) {
            return forced;
        }
        return Boolean.getBoolean(PROPERTY) || soloMapling.autoqa.AutoQa.isEnabled();
    }

    public static Path file(LocalDate day) {
        return Path.of(ConvoSwitches.str("dataDir", "data/convo"), "routing", day + ".jsonl");
    }

    /** Whether anything would be logged (so the caller can skip building the record). */
    public static boolean wanted() {
        return QaLog.on(QaLog.Topic.CONVO) || toDisk();
    }

    /**
     * Logs one decision. {@code chosen} the candidate key that answers (null none), {@code step} the step label,
     * {@code context} the map's recent chat lines, {@code realNames} every real player's name to hide.
     */
    public static void log(int mapId, int playerId, String playerName, String line, ChatRoute.Decision d,
                           String chosen, String step, List<MapChatLog.Line> context, Collection<String> realNames, long now) {
        boolean qa = QaLog.on(QaLog.Topic.CONVO);
        boolean disk = toDisk();
        if (!qa && !disk) {
            return;
        }
        String row = Json.write(record(mapId, playerId, playerName, line, d, chosen, step, context, realNames, now));
        if (qa) {
            QaLog.map(QaLog.Topic.CONVO, mapId, "ROUTE CANDIDATES " + row);
        }
        if (disk) {
            Path f = file(LocalDate.now());
            writer.execute(() -> append(f, row + "\n"));
        }
    }

    static Map<String, Object> record(int mapId, int playerId, String playerName, String line, ChatRoute.Decision d,
                                      String chosen, String step, List<MapChatLog.Line> context, Collection<String> realNames,
                                      long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", now);
        m.put("map", mapId);
        m.put("player", ConvoQuality.hash(playerId));
        m.put("line", hide(line, playerName, realNames));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("question", d != null && d.question());
        f.put("len", d == null ? 0 : d.length());
        f.put("entities", d == null ? List.of() : d.entities());
        m.put("features", f);
        List<Map<String, Object>> cands = new ArrayList<>();
        if (d != null) {
            for (ChatRoute.Candidate c : d.candidates()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", c.key());
                x.put("named", c.nameScore() >= BotAddressee.SURE);
                x.put("nameScore", c.nameScore());
                x.put("secs", c.secondsAgo());
                x.put("words", c.sharedWords());
                x.put("ents", c.sharedEntities());
                x.put("question", c.question());
                x.put("addressed", c.addressedPlayer());
                x.put("thread", c.inThread());
                x.put("dist", c.distance());
                x.put("dx", c.dx());
                x.put("dy", c.dy());
                x.put("bubble", c.inBubble());
                x.put("facing", c.facing());
                x.put("tier", c.tier() == null ? "-" : c.tier());
                x.put("said", hide(c.line(), playerName, realNames));
                cands.add(x);
            }
        }
        m.put("candidates", cands);
        m.put("chosen", chosen);
        m.put("step", step);
        List<Map<String, Object>> ctx = new ArrayList<>();
        for (MapChatLog.Line l : context) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("who", l.bot() ? l.speaker() : l.speakerId() == playerId ? "{player}" : ConvoQuality.hash(l.speakerId()));
            x.put("bot", l.bot());
            x.put("ago", (now - l.at()) / 1000);
            x.put("text", hide(l.text(), playerName, realNames));
            ctx.add(x);
        }
        m.put("context", ctx);
        return m;
    }

    /** The text with the player's name as {player} and every other real player's as {someone}. */
    static String hide(String text, String playerName, Collection<String> realNames) {
        if (text == null) {
            return null;
        }
        String out = text;
        if (realNames != null) {
            for (String n : realNames) {
                if (n != null && !n.equalsIgnoreCase(playerName)) {
                    out = ConvoQuality.anonymise(out, n).replace("{player}", "{someone}");
                }
            }
        }
        return ConvoQuality.anonymise(out, playerName);
    }

    private static void append(Path f, String row) {
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Route log not written", e);
        }
    }

    /** Test seams. */
    static void diskForTest(Boolean on) {
        diskForTest = on;
    }

    static void flushForTest() throws Exception {
        writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }
}
