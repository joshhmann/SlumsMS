package server.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Durable in-game reports (@bug / @idea): one JSON line per report in an append-only file
 * (default data/reports/reports.jsonl, override with -Dsolomapling.reportsFile). Ids increase and survive restarts
 * (derived from the file's last line). Writes happen on a single background thread, never on a packet thread.
 * Field names match the website form's records (type, title, details, character, where).
 */
public final class ReportStore {
    private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 2000;
    public static final int TITLE_LENGTH = 140;
    public static final int PER_CHAR_HOUR = 5;
    public static final int PER_CHAR_DAY = 20;
    public static final int GLOBAL_HOUR = 60;
    public static final int GLOBAL_DAY = 300;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;

    /** What the command knows about the reporter; the store adds id and time. */
    public record Context(String character, int accountId, int world, int channel, int mapId, String mapName,
                          int x, int y, String clientBuild) {
        public String where() {
            return mapName + " (" + mapId + ") ch" + channel;
        }
    }

    public enum Status { OK, TOO_SHORT, TOO_LONG, RATE_LIMITED, WRITE_FAILED }

    public record Result(Status status, long id) {}

    private static volatile ReportStore instance;

    public static ReportStore getInstance() {
        ReportStore s = instance;
        if (s == null) {
            synchronized (ReportStore.class) {
                if (instance == null) {
                    instance = new ReportStore(Paths.get(System.getProperty("solomapling.reportsFile",
                            "data/reports/reports.jsonl")), Clock.systemUTC());
                }
                s = instance;
            }
        }
        return s;
    }

    private final Path file;
    private final Clock clock;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "report-writer");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private final Map<String, Deque<Long>> perChar = new HashMap<>();
    private final Deque<Long> global = new ArrayDeque<>();
    private long lastId = -1; // -1 = not loaded yet

    public ReportStore(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Validates, rate-limits, assigns an id and queues the write. The future completes once the line is on disk. */
    public CompletableFuture<Result> submit(String type, String text, Context ctx) {
        String details = text == null ? "" : text.strip();
        if (details.codePointCount(0, details.length()) < MIN_LENGTH) {
            return CompletableFuture.completedFuture(new Result(Status.TOO_SHORT, 0));
        }
        if (details.codePointCount(0, details.length()) > MAX_LENGTH) {
            return CompletableFuture.completedFuture(new Result(Status.TOO_LONG, 0));
        }
        long now = clock.millis();
        long id;
        String line;
        synchronized (lock) {
            if (!allow(perChar.computeIfAbsent(ctx.character().toLowerCase(), k -> new ArrayDeque<>()), now, PER_CHAR_HOUR, PER_CHAR_DAY)
                    || !allow(global, now, GLOBAL_HOUR, GLOBAL_DAY)) {
                return CompletableFuture.completedFuture(new Result(Status.RATE_LIMITED, 0));
            }
            if (lastId < 0) {
                lastId = readLastId();
            }
            id = ++lastId;
            perChar.get(ctx.character().toLowerCase()).addLast(now);
            global.addLast(now);
            line = toJson(id, type, details, ctx, Instant.ofEpochMilli(now));
        }
        final long fid = id;
        final String fline = line;
        CompletableFuture<Result> done = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(file, fline + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                done.complete(new Result(Status.OK, fid));
            } catch (IOException | RuntimeException e) {
                log.error("Could not write report #{} to {}", fid, file, e);
                done.complete(new Result(Status.WRITE_FAILED, fid));
            }
        });
        return done;
    }

    /** Sliding windows; prunes old entries. Does not record. */
    private static boolean allow(Deque<Long> times, long now, int perHour, int perDay) {
        while (!times.isEmpty() && now - times.peekFirst() >= DAY_MS) {
            times.pollFirst();
        }
        if (times.size() >= perDay) {
            return false;
        }
        int inHour = 0;
        for (long t : times) {
            if (now - t < HOUR_MS) {
                inHour++;
            }
        }
        return inHour < perHour;
    }

    private long readLastId() {
        try {
            if (!Files.exists(file)) {
                return 0;
            }
            long best = 0;
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int i = l.indexOf("\"id\":");
                if (i < 0) {
                    continue;
                }
                int j = i + 5;
                int k = j;
                while (k < l.length() && Character.isDigit(l.charAt(k))) {
                    k++;
                }
                if (k > j) {
                    best = Math.max(best, Long.parseLong(l.substring(j, k)));
                }
            }
            return best;
        } catch (IOException | RuntimeException e) {
            log.error("Could not read last report id from {}", file, e);
            return 0;
        }
    }

    static String toJson(long id, String type, String details, Context c, Instant time) {
        String title = details.replaceAll("\\s+", " ");
        int cut = title.offsetByCodePoints(0, Math.min(TITLE_LENGTH, title.codePointCount(0, title.length())));
        title = title.substring(0, cut);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"id\":").append(id)
                .append(",\"source\":\"game\"")
                .append(",\"type\":").append(q(type))
                .append(",\"title\":").append(q(title))
                .append(",\"details\":").append(q(details))
                .append(",\"character\":").append(q(c.character()))
                .append(",\"where\":").append(q(c.where()))
                .append(",\"accountId\":").append(c.accountId())
                .append(",\"world\":").append(c.world())
                .append(",\"channel\":").append(c.channel())
                .append(",\"mapId\":").append(c.mapId())
                .append(",\"mapName\":").append(q(c.mapName()))
                .append(",\"x\":").append(c.x())
                .append(",\"y\":").append(c.y())
                .append(",\"time\":").append(q(time.toString()))
                .append(",\"clientBuild\":").append(c.clientBuild() == null ? "null" : q(c.clientBuild()))
                .append('}');
        return sb.toString();
    }

    private static String q(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
