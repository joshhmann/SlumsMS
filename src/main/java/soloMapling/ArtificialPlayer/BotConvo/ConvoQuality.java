package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 59, owner decision 6: the quality loop.
 * <ul>
 *   <li><b>The last line:</b> every bot line said to a real player (and every thread line on a map) is remembered,
 *   so {@code !rate good|bad [note]} rates it.</li>
 *   <li><b>Ratings:</b> appended to {@code data/convo/ratings.jsonl}. A bank line's weight moves (bad x0.25, a second
 *   bad blacklists it, good x1.5 up to x3; {@code data/convo/bank-weights.json}); a model line goes to the tune file
 *   ({@link ConvoTune}).</li>
 *   <li><b>Transcripts:</b> each line, with the player's line, the bot, voice, tier, source, latency, as JSONL in
 *   {@code logs/convo/transcript-<date>.jsonl}. The player is a short hash, never a name.</li>
 *   <li><b>Daily score:</b> {@link ConvoDailyScore} at {@code solomapling.convo.scoreHour} (4) server time for the day
 *   before, or {@code !rate score}.</li>
 * </ul>
 */
public final class ConvoQuality {

    private static final Logger log = LoggerFactory.getLogger(ConvoQuality.class);

    static final long RATE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    static final long THREAD_WINDOW_MS = TimeUnit.MINUTES.toMillis(2);

    /** A line a bot said: to a player ({@code playerId} > 0) or in a thread on a map. */
    public record Said(long at, int botId, String botName, int playerId, String asked, String line, String source,
                       int bankId, String voice, String tier, long ms, int mapId) {
    }

    private static final Map<Integer, Said> lastToPlayer = new ConcurrentHashMap<>();
    private static final Map<Integer, Said> lastThreadOnMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Double> bankWeights = new ConcurrentHashMap<>();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "convo-quality");
        t.setDaemon(true);
        return t;
    });
    private static volatile boolean booted;
    private static volatile boolean weightsLoaded;
    private static volatile boolean writeFiles = true;

    private ConvoQuality() {
    }

    // ── paths ──

    static Path dataDir() {
        return Path.of(ConvoSwitches.str("dataDir", "data/convo"));
    }

    public static Path ratingsFile() {
        return dataDir().resolve("ratings.jsonl");
    }

    static Path weightsFile() {
        return dataDir().resolve("bank-weights.json");
    }

    public static Path transcriptFile(LocalDate day) {
        return Path.of(ConvoSwitches.str("transcriptDir", "logs/convo"), "transcript-" + day + ".jsonl");
    }

    /** Test seam: no files written (null keeps the default). */
    public static void writeFilesForTest(boolean on) {
        writeFiles = on;
    }

    static void resetForTest() {
        lastToPlayer.clear();
        lastThreadOnMap.clear();
        bankWeights.clear();
        weightsLoaded = true;
    }

    // ── boot: the bank weights and the daily score ──

    /** Loads the bank weights into the picker and schedules the daily score. Safe to call often. */
    public static synchronized void boot() {
        if (booted) {
            return;
        }
        booted = true;
        loadWeights();
        ConvoLines.useWeights(ConvoQuality::weightOf);
        long first = msUntilNext((int) ConvoSwitches.num("scoreHour", 4), 30, System.currentTimeMillis(),
                ZoneId.systemDefault());
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                if (ConvoSwitches.on("quality")) {
                    Path out = ConvoDailyScore.runFor(LocalDate.now().minusDays(1));
                    log.info("Bot chat quality sample written: {}", out);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Bot chat daily score failed", e);
            }
        }, first, TimeUnit.DAYS.toMillis(1), TimeUnit.MILLISECONDS);
    }

    static long msUntilNext(int hour, int minute, long now, ZoneId zone) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
        LocalDateTime next = t.toLocalDate().atTime(hour, minute);
        if (!next.isAfter(t)) {
            next = next.plusDays(1);
        }
        return next.atZone(zone).toInstant().toEpochMilli() - now;
    }

    private static void loadWeights() {
        weightsLoaded = true;
        Path f = weightsFile();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            if (Json.parse(Files.readString(f)) instanceof Map<?, ?> m) {
                m.forEach((k, v) -> {
                    try {
                        bankWeights.put(Integer.parseInt(String.valueOf(k)), ((Number) v).doubleValue());
                    } catch (RuntimeException ignored) {
                        // a bad row
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Bank weights {} unreadable", f, e);
        }
    }

    /** A bank line's weight (1 = normal, 0 = blacklisted). */
    public static double weightOf(int bankId) {
        if (!weightsLoaded) {
            synchronized (ConvoQuality.class) {
                if (!weightsLoaded) {
                    loadWeights();
                }
            }
        }
        return bankWeights.getOrDefault(bankId, 1.0);
    }

    // ── what bots said ──

    /** A bot line went out to a player: remembered for !rate, written to the transcript, logged for !qa. */
    public static void said(Character bot, Character player, String line, ConvoLines.Line source, String tier,
                            ConvoPersona persona, String asked, long ms) {
        String src = source == null ? "scripted" : source.fromBank() ? "bank" : source.source();
        Said s = new Said(System.currentTimeMillis(), bot.getId(), bot.getName(), player.getId(), asked, line, src,
                source == null ? -1 : source.bankId(), persona == null ? null : persona.voice().label(),
                tier.toLowerCase(Locale.ROOT), ms, player.getMapId());
        lastToPlayer.put(player.getId(), s);
        transcript(s, player.getId());
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-4, cv-6, cv-7
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), "SAID " + bot.getName() + " -> " + player.getName() + " ["
                    + s.tier() + ", " + src + (s.bankId() >= 0 ? "#" + s.bankId() : "") + ", " + s.voice() + ", " + ms
                    + " ms] " + line);
        }
    }

    /** A bot-to-bot thread line went out on a map. */
    public static void threadLine(Character bot, int mapId, String line, String source, String topic) {
        Said s = new Said(System.currentTimeMillis(), bot.getId(), bot.getName(), 0, "(thread: " + topic + ")", line,
                "thread-" + source, -1, ConvoPersona.of(bot.getId() - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE)
                .voice().label(), "thread", 0, mapId);
        lastThreadOnMap.put(mapId, s);
        transcript(s, 0);
    }

    /** How a reply was timed (!qa cv-4). */
    public static void timing(Character bot, Character player, HumanTiming.Plan plan) {
        if (QaLog.on(QaLog.Topic.CONVO)) {
            StringBuilder s = new StringBuilder("TIMING " + bot.getName() + ": " + plan.kind());
            for (HumanTiming.Part part : plan.parts()) {
                s.append(" | +").append(part.delayMs()).append(" ms \"").append(part.text()).append('"');
            }
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), s.toString());
        }
    }

    /** A short stable hash for a player in the transcript (never the name). */
    static String hash(int playerId) {
        return playerId <= 0 ? "-" : Integer.toHexString((playerId * 0x9E3779B1) ^ 0x5bd1e995);
    }

    private static void transcript(Said s, int playerId) {
        if (!writeFiles || !ConvoSwitches.on("quality")) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", s.at());
        m.put("bot", s.botName());
        m.put("player", hash(playerId));
        m.put("asked", s.asked());
        m.put("line", s.line());
        m.put("tier", s.tier());
        m.put("source", s.source());
        if (s.bankId() >= 0) {
            m.put("bankId", s.bankId());
        }
        m.put("voice", s.voice());
        m.put("ms", s.ms());
        m.put("map", s.mapId());
        String row = Json.write(m) + "\n";
        Path f = transcriptFile(LocalDate.now());
        writer.execute(() -> append(f, row));
    }

    private static void append(Path f, String row) {
        try {
            Files.createDirectories(f.toAbsolutePath().getParent());
            Files.writeString(f, row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Chat transcript not written", e);
        }
    }

    // ── !rate ──

    /** The line a !rate from this player rates: the last line said to them, else a thread line on their map. */
    static Said rateable(Character player, long now) {
        Said s = lastToPlayer.get(player.getId());
        Said t = lastThreadOnMap.get(player.getMapId());
        boolean sOk = s != null && now - s.at() <= RATE_WINDOW_MS;
        boolean tOk = t != null && now - t.at() <= THREAD_WINDOW_MS;
        if (sOk && tOk) {
            return t.at() > s.at() ? t : s;
        }
        return sOk ? s : tOk ? t : null;
    }

    public static List<String> describeLast(Character player) {
        Said s = rateable(player, System.currentTimeMillis());
        if (s == null) {
            return List.of("No bot line to you in the last 10 minutes.");
        }
        return List.of(s.botName() + ": \"" + s.line() + "\" (" + s.source() + (s.bankId() >= 0 ? " #" + s.bankId() : "")
                + ", " + s.tier() + ", voice " + s.voice() + ")");
    }

    /** {@code !rate good|bad [note]}: what happened, as lines for the GM. */
    public static List<String> rate(Character player, boolean good, String note) {
        long now = System.currentTimeMillis();
        Said s = rateable(player, now);
        if (s == null) {
            return List.of("No bot line to you in the last 10 minutes to rate.");
        }
        List<String> out = new ArrayList<>();
        out.add("Rated " + (good ? "good" : "bad") + ": " + s.botName() + ": \"" + s.line() + "\"");
        if (s.bankId() >= 0) {
            double before = weightOf(s.bankId());
            double after = good ? Math.min(3, before * 1.5) : before < 1 ? 0 : before * 0.25;
            bankWeights.put(s.bankId(), after);
            saveWeights();
            out.add(after == 0 ? "Bank line #" + s.bankId() + " is blacklisted (second bad rating)."
                    : String.format(Locale.ROOT, "Bank line #%d weighs x%.2f now.", s.bankId(), after));
        } else if (s.source().startsWith("llm") || s.source().equals("thread-llm")) {
            ConvoTune.rated(good, s.line(), note, now);
            out.add(good ? "Kept as a good example (data/convo/tune.json)."
                    : "Kept as a bad example" + (note != null && note.contains("\"") ? "; the quoted phrase is on the don't-say list."
                    : ".") + " (data/convo/tune.json)");
        } else {
            out.add("A scripted line: noted in the ratings only.");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("at", now);
        r.put("day", LocalDate.now().toString());
        r.put("rating", good ? "good" : "bad");
        r.put("note", note == null ? "" : note);
        r.put("bot", s.botName());
        r.put("line", s.line());
        r.put("asked", s.asked());
        r.put("source", s.source());
        if (s.bankId() >= 0) {
            r.put("bankId", s.bankId());
        }
        r.put("voice", s.voice());
        r.put("tier", s.tier());
        r.put("by", hash(player.getId()));
        if (writeFiles) {
            String row = Json.write(r) + "\n";
            Path f = ratingsFile();
            writer.execute(() -> append(f, row));
        }
        if (QaLog.on(QaLog.Topic.CONVO)) { // !qa cv-6
            QaLog.map(QaLog.Topic.CONVO, player.getMapId(), "RATE " + (good ? "good" : "bad") + " by " + player.getName()
                    + ": " + s.botName() + " \"" + s.line() + "\" [" + s.source() + (s.bankId() >= 0 ? "#" + s.bankId() : "")
                    + "] " + (note == null ? "" : note) + " -> " + String.join(" ", out.subList(1, out.size())));
        }
        return out;
    }

    private static void saveWeights() {
        if (!writeFiles) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        bankWeights.forEach((k, v) -> m.put(String.valueOf(k), v));
        String json = Json.write(m);
        Path f = weightsFile();
        writer.execute(() -> {
            try {
                Files.createDirectories(f.toAbsolutePath().getParent());
                Files.writeString(f, json);
            } catch (IOException e) {
                log.warn("Bank weights not written", e);
            }
        });
    }

    /** {@code !rate score [yyyy-mm-dd]}: the day's scored sample now (default today). */
    public static List<String> scoreNow(String day) {
        try {
            LocalDate d = day == null ? LocalDate.now() : LocalDate.parse(day);
            return List.of("Wrote " + ConvoDailyScore.runFor(d));
        } catch (IOException | RuntimeException e) {
            return List.of("Score failed: " + e.getMessage());
        }
    }
}
