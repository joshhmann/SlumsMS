package server.reports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable in-game reports (@bug / @idea): one JSON line per report in the same append-only file the website's report
 * form writes (tools/site/report_service.py; live {@code /var/lib/slums-feedback/reports.jsonl}, TEST
 * {@code /var/lib/slums-report-test/reports.jsonl}; set with {@code -Dsolomapling.reportsFile}, default
 * {@code data/reports/reports.jsonl}). Field names match the form's records (id, time, type, title, details,
 * character, where) plus {@code source:"ingame"}, a {@code ref} ("B-12" for a bug, "I-12" for an idea) and a short
 * {@code context} summary; the full @bug context (ticket 131, {@link BugContext}) goes to {@code context/<ref>.json}
 * next to the reports file so the jsonl stays readable.
 * <p>
 * Ids are shared with the site: every write takes an exclusive POSIX lock on the reports file and re-reads the
 * highest id from its tail, so the two writers never hand out the same id as long as the site does the same
 * (ticket 131 asks the site to lock with fcntl.lockf and re-read). A file or folder this creates gets the report
 * service's permissions (0640 / 0750) with the owner and group of the folder it sits in. Writes run on one
 * background thread, never on a packet thread.
 */
public final class ReportStore {
    private static final Logger log = LoggerFactory.getLogger(ReportStore.class);

    public static final int MIN_LENGTH = 5;
    /** @bug text limit (ticket 131); ideas keep the site form's 2000. */
    public static final int BUG_MAX_LENGTH = 500;
    public static final int MAX_LENGTH = 2000;
    public static final int TITLE_LENGTH = 80;
    /** Per account: 3 reports in any 10 minutes, 20 a day; everyone together: 60 an hour, 300 a day. */
    public static final int PER_ACCOUNT_WINDOW = 3;
    public static final long WINDOW_MS = 10 * 60_000L;
    public static final int PER_ACCOUNT_DAY = 20;
    public static final int GLOBAL_HOUR = 60;
    public static final int GLOBAL_DAY = 300;
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;
    /** How much of the file's end is read to find the last id (lines are a few KB at most). */
    static final int TAIL_BYTES = 256 * 1024;
    public static final String SOURCE = "ingame";
    private static final DateTimeFormatter SITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern REF = Pattern.compile("(?i)^#?([BI])-?(\\d{1,9})$");
    private static final Pattern LINE_ID = Pattern.compile("^\\{\\s*\"id\"\\s*:\\s*(\\d{1,15})");
    private static final Set<PosixFilePermission> FILE_PERMS = PosixFilePermissions.fromString("rw-r-----");
    private static final Set<PosixFilePermission> DIR_PERMS = PosixFilePermissions.fromString("rwxr-x---");

    /**
     * What the command knows about the reporter; the store adds id and time. {@code versions} is the site's
     * "versions" object shape ({@code server}, {@code server_commit}, {@code client}) plus {@code clientBuild}; null
     * or empty is left out.
     */
    public record Context(String character, int accountId, int world, int channel, int mapId, String mapName,
                          int x, int y, String clientBuild, Map<String, Object> versions) {
        public Context(String character, int accountId, int world, int channel, int mapId, String mapName,
                       int x, int y, String clientBuild) {
            this(character, accountId, world, channel, mapId, mapName, x, y, clientBuild, null);
        }

        public String where() {
            return mapName + " (" + mapId + ") ch" + channel;
        }
    }

    public enum Status { OK, TOO_SHORT, TOO_LONG, RATE_LIMITED, WRITE_FAILED }

    public record Result(Status status, long id, String ref) {
        public Result(Status status, long id) {
            this(status, id, null);
        }
    }

    private static volatile ReportStore instance;

    public static ReportStore getInstance() {
        ReportStore s = instance;
        if (s == null) {
            synchronized (ReportStore.class) {
                if (instance == null) {
                    if (System.getProperty("solomapling.reportsFile") == null) {
                        log.warn("-Dsolomapling.reportsFile is not set: @bug/@idea reports go to data/reports/reports.jsonl, "
                                + "not the site's report store (ticket 131)");
                    }
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
    private final Map<String, Deque<Long>> perAccount = new HashMap<>();
    private final Deque<Long> global = new ArrayDeque<>();
    private long lastId = 0; // the highest id this process wrote; the file's tail is re-read at every write

    public ReportStore(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    public Path file() {
        return file;
    }

    public Path contextDir() {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Paths.get("context") : parent.resolve("context");
    }

    /** Short version strings as the site's report service accepts them (its VERSION pattern). */
    private static final Pattern SHORT_VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$");

    /**
     * The line's "versions" object, in the site's shape (tools/site/report_service.py: server, server_commit, and
     * client as a short version string) plus clientBuild (the slums.dll build number). Unknown values are left out.
     */
    public static Map<String, Object> versions(String server, String serverCommit, String clientRelease, int clientBuild) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (server != null && SHORT_VERSION.matcher(server).matches()) {
            v.put("server", server);
        }
        if (serverCommit != null && SHORT_VERSION.matcher(serverCommit).matches()) {
            v.put("server_commit", serverCommit);
        }
        if (clientRelease != null && SHORT_VERSION.matcher(clientRelease).matches()) {
            v.put("client", clientRelease);
        }
        if (clientBuild > 0) {
            v.put("clientBuild", clientBuild);
        }
        return v;
    }

    public static String refFor(String type, long id) {
        return ("bug".equals(type) ? "B-" : "I-") + id;
    }

    /** "B-12", "b12", "#B-12" -> "B-12"; a bare number is a bug ref; anything else null. */
    public static String normalizeRef(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.matches("#?\\d{1,9}")) {
            return "B-" + Long.parseLong(t.replace("#", ""));
        }
        Matcher m = REF.matcher(t);
        return m.matches() ? m.group(1).toUpperCase() + "-" + Long.parseLong(m.group(2)) : null;
    }

    public CompletableFuture<Result> submit(String type, String text, Context ctx) {
        return submit(type, text, ctx, null);
    }

    /**
     * Validates, rate-limits and queues the write; the id and ref are assigned under the file lock on the writer
     * thread. {@code contextSupplier} (may be null) is called on the caller's thread only once the report passed the
     * checks (so a rate-limited spam costs no capture); its map is written to {@code context/<ref>.json}. The future
     * completes once the line is on disk.
     */
    public CompletableFuture<Result> submit(String type, String text, Context ctx,
                                            java.util.function.Supplier<Map<String, Object>> contextSupplier) {
        String details = text == null ? "" : text.strip();
        int len = details.codePointCount(0, details.length());
        if (len < MIN_LENGTH) {
            return CompletableFuture.completedFuture(new Result(Status.TOO_SHORT, 0));
        }
        if (len > maxLength(type)) {
            return CompletableFuture.completedFuture(new Result(Status.TOO_LONG, 0));
        }
        long now = clock.millis();
        String who = ctx.accountId() > 0 ? "a" + ctx.accountId() : "c" + ctx.character().toLowerCase();
        synchronized (lock) {
            Deque<Long> mine = perAccount.computeIfAbsent(who, k -> new ArrayDeque<>());
            if (!allow(mine, now, WINDOW_MS, PER_ACCOUNT_WINDOW, PER_ACCOUNT_DAY)
                    || !allow(global, now, HOUR_MS, GLOBAL_HOUR, GLOBAL_DAY)) {
                return CompletableFuture.completedFuture(new Result(Status.RATE_LIMITED, 0));
            }
            mine.addLast(now);
            global.addLast(now);
        }
        Map<String, Object> fullContext = contextSupplier == null ? null : contextSupplier.get();
        CompletableFuture<Result> done = new CompletableFuture<>();
        writer.execute(() -> {
            long id = 0;
            try {
                ensureDir(file.toAbsolutePath().getParent());
                // POSIX locks belong to the process and drop when ANY descriptor of the file closes, so everything
                // under the lock goes through this one channel
                try (FileChannel ch = openLocked(file); FileLock ignored = ch.lock()) {
                    id = Math.max(lastId, readLastId(ch)) + 1;
                    lastId = id;
                    String ref = refFor(type, id);
                    String contextFile = null;
                    if (fullContext != null) {
                        contextFile = "context/" + ref + ".json";
                        Map<String, Object> full = new LinkedHashMap<>();
                        full.put("ref", ref);
                        full.put("id", id);
                        full.putAll(fullContext);
                        writeContext(ref, full);
                    }
                    String line = toJson(id, type, details, ctx, Instant.ofEpochMilli(now), ref,
                            fullContext == null ? null : BugContext.summary(fullContext, contextFile));
                    ByteBuffer buf = ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8));
                    long pos = ch.size();
                    while (buf.hasRemaining()) {
                        pos += ch.write(buf, pos);
                    }
                    try {
                        ch.force(true);
                    } catch (IOException e) { // the line is written: telling the player "failed" would make them file it twice
                        log.warn("Report {} written but not synced: {}", ref, e.toString());
                    }
                    done.complete(new Result(Status.OK, id, ref));
                }
            } catch (IOException | RuntimeException e) {
                log.error("Could not write report #{} to {}", id, file, e);
                done.complete(new Result(Status.WRITE_FAILED, id));
            }
        });
        return done;
    }

    static int maxLength(String type) {
        return "bug".equals(type) ? BUG_MAX_LENGTH : MAX_LENGTH;
    }

    /** Sliding windows; prunes entries older than a day. Does not record. */
    private static boolean allow(Deque<Long> times, long now, long windowMs, int perWindow, int perDay) {
        while (!times.isEmpty() && now - times.peekFirst() >= DAY_MS) {
            times.pollFirst();
        }
        if (times.size() >= perDay) {
            return false;
        }
        int inWindow = 0;
        for (long t : times) {
            if (now - t < windowMs) {
                inWindow++;
            }
        }
        return inWindow < perWindow;
    }

    /** Auto-QA t-br-1: forgets one account's recent reports so a rerun is not rate-limited. */
    public void resetLimitsForQa(int accountId) {
        synchronized (lock) {
            perAccount.remove("a" + accountId);
        }
    }

    // ── files ────────────────────────────────────────────────────────────────────────────────────

    private static FileChannel openLocked(Path f) throws IOException {
        if (!Files.exists(f)) {
            try {
                FileAttribute<?>[] attrs = posix(f)
                        ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(FILE_PERMS)} : new FileAttribute<?>[0];
                Files.createFile(f, attrs);
                likeParent(f, FILE_PERMS);
            } catch (FileAlreadyExistsException race) {
                // the site made it a moment ago
            }
        }
        return FileChannel.open(f, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    private static void ensureDir(Path dir) throws IOException {
        if (dir == null || Files.isDirectory(dir)) {
            return;
        }
        ensureDir(dir.getParent());
        Files.createDirectories(dir);
        likeParent(dir, DIR_PERMS);
    }

    private static boolean posix(Path f) {
        try {
            Path parent = f.toAbsolutePath().getParent();
            return parent != null && Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Best effort: the report service's permissions, owner and group of the folder (works when the server is root). */
    private static void likeParent(Path p, Set<PosixFilePermission> perms) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(p, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            view.setPermissions(perms);
            Path parent = p.toAbsolutePath().getParent();
            if (parent == null) {
                return;
            }
            PosixFileAttributes pa = Files.readAttributes(parent, PosixFileAttributes.class);
            PosixFileAttributes mine = view.readAttributes();
            if (!pa.owner().equals(mine.owner())) {
                view.setOwner(pa.owner());
            }
            if (!pa.group().equals(mine.group())) {
                view.setGroup(pa.group());
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // not root, or not a POSIX file system: the umask's permissions stay
        }
    }

    private void writeContext(String ref, Map<String, Object> full) throws IOException {
        Path dir = contextDir();
        ensureDir(dir);
        Path target = dir.resolve(ref + ".json");
        Path tmp = dir.resolve("." + ref + ".json.tmp");
        Files.writeString(tmp, Json.write(full), StandardCharsets.UTF_8);
        likeParent(tmp, FILE_PERMS);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The highest {@code "id":N} at the start of a line in the file's last {@link #TAIL_BYTES}; 0 for none. */
    static long readLastId(Path f) {
        if (!Files.exists(f)) {
            return 0;
        }
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            return readLastId(ch);
        } catch (IOException e) {
            log.error("Could not read last report id from {}", f, e);
            return 0;
        }
    }

    static long readLastId(FileChannel ch) {
        try {
            long best = 0;
            for (String l : tail(ch, TAIL_BYTES)) {
                Matcher m = LINE_ID.matcher(l);
                if (m.find()) {
                    best = Math.max(best, Long.parseLong(m.group(1)));
                }
            }
            return best;
        } catch (IOException | RuntimeException e) {
            log.error("Could not read the last report id", e);
            return 0;
        }
    }

    /** The whole lines in the last {@code max} bytes of {@code f}, oldest first. */
    static List<String> tail(Path f, int max) throws IOException {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.READ)) {
            return tail(ch, max);
        }
    }

    static List<String> tail(FileChannel ch, int max) throws IOException {
        long size = ch.size();
        long from = Math.max(0, size - max);
        ByteBuffer buf = ByteBuffer.allocate((int) (size - from));
        long pos = from;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, pos);
            if (n < 0) {
                break;
            }
            pos += n;
        }
        List<String> out = new ArrayList<>(List.of(new String(buf.array(), 0, buf.position(), StandardCharsets.UTF_8).split("\n")));
        if (from > 0 && !out.isEmpty()) {
            out.removeFirst(); // a partial line
        }
        out.removeIf(String::isBlank);
        return out;
    }

    // ── reading back (GM commands, auto-QA) ──────────────────────────────────────────────────────

    /**
     * Runs a read on the writer thread: closing any descriptor of the reports file drops this process's POSIX lock, so
     * a read must never overlap a locked write. Waits at most 3 s (a GM command's answer).
     */
    private <T> T onWriter(java.util.concurrent.Callable<T> read, T fallback) {
        try {
            return writer.submit(read).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return fallback;
        }
    }

    /** The newest {@code n} in-game reports (any type), newest first, parsed. */
    public List<Map<String, Object>> recentInGame(int n) {
        return onWriter(() -> recentInGameNow(n), List.of());
    }

    private List<Map<String, Object>> recentInGameNow(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                return out;
            }
            List<String> lines = tail(file, 4 * TAIL_BYTES);
            for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
                Map<String, Object> r = parse(lines.get(i));
                if (r != null && isInGame(r)) {
                    out.add(r);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read reports from {}: {}", file, e.toString());
        }
        return out;
    }

    /** The in-game report with this ref (see {@link #normalizeRef}), or null. Scans the whole file, newest first. */
    public Map<String, Object> find(String ref) {
        return onWriter(() -> findNow(ref), null);
    }

    private Map<String, Object> findNow(String ref) {
        String want = normalizeRef(ref);
        if (want == null) {
            return null;
        }
        try {
            if (!Files.exists(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                String l = lines.get(i);
                if (!l.contains("\"" + want + "\"")) {
                    continue;
                }
                Map<String, Object> r = parse(l);
                if (r != null && want.equals(r.get("ref")) && isInGame(r)) {
                    return r;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read reports from {}: {}", file, e.toString());
        }
        return null;
    }

    /** The full context of a report, or null. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readContext(String ref) {
        String want = normalizeRef(ref);
        if (want == null) {
            return null;
        }
        Path p = contextDir().resolve(want + ".json");
        try {
            if (!Files.exists(p)) {
                return null;
            }
            return Json.parse(Files.readString(p, StandardCharsets.UTF_8)) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Changes a report's context file on the writer thread (ticket 131: the client's logs are merged in). The
     * function gets the parsed context and returns the new one; null leaves it. Completes with true when written.
     */
    public CompletableFuture<Boolean> updateContext(String ref, UnaryOperator<Map<String, Object>> change) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        writer.execute(() -> {
            try {
                String want = normalizeRef(ref);
                Map<String, Object> cur = want == null ? null : readContext(want);
                Map<String, Object> next = cur == null ? null : change.apply(cur);
                if (next == null) {
                    done.complete(false);
                    return;
                }
                writeContext(want, next);
                done.complete(true);
            } catch (IOException | RuntimeException e) {
                log.warn("Could not update the context of report {}: {}", ref, e.toString());
                done.complete(false);
            }
        });
        return done;
    }

    /**
     * Auto-QA t-br-1 cleanup: removes the in-game line with this ref (and only it) and its context file, under the
     * file lock, rewriting the file in place (same inode, owner and permissions). Completes with how many lines went.
     */
    public CompletableFuture<Integer> removeForQa(String ref) {
        CompletableFuture<Integer> done = new CompletableFuture<>();
        writer.execute(() -> {
            String want = normalizeRef(ref);
            int gone = 0;
            try {
                if (want != null && Files.exists(file)) {
                    try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
                         FileLock ignored = ch.lock()) {
                        List<String> lines = tail(ch, Integer.MAX_VALUE - 16);
                        StringBuilder keep = new StringBuilder();
                        for (String l : lines) {
                            Map<String, Object> r = l.contains("\"" + want + "\"") ? parse(l) : null;
                            if (r != null && want.equals(r.get("ref")) && isInGame(r)) {
                                gone++;
                            } else {
                                keep.append(l).append('\n');
                            }
                        }
                        if (gone > 0) {
                            ch.truncate(0);
                            ByteBuffer buf = ByteBuffer.wrap(keep.toString().getBytes(StandardCharsets.UTF_8));
                            long pos = 0;
                            while (buf.hasRemaining()) {
                                pos += ch.write(buf, pos);
                            }
                            ch.force(true);
                        }
                    }
                }
                if (want != null) {
                    Files.deleteIfExists(contextDir().resolve(want + ".json"));
                }
            } catch (IOException | RuntimeException e) {
                log.warn("QA cleanup of report {} failed: {}", ref, e.toString());
            }
            done.complete(gone);
        });
        return done;
    }

    private static boolean isInGame(Map<String, Object> r) {
        Object s = r.get("source");
        return SOURCE.equals(s) || "game".equals(s); // "game": the first in-game lines (1.6.1 before ticket 131)
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String line) {
        try {
            return Json.parse(line) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String title(String details) {
        String title = details.replaceAll("\\s+", " ");
        int cut = title.offsetByCodePoints(0, Math.min(TITLE_LENGTH, title.codePointCount(0, title.length())));
        return title.substring(0, cut);
    }

    static String toJson(long id, String type, String details, Context c, Instant time) {
        return toJson(id, type, details, c, time, refFor(type, id), null);
    }

    static String toJson(long id, String type, String details, Context c, Instant time, String ref,
                         Map<String, Object> contextSummary) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("time", SITE_TIME.format(time));
        m.put("type", type);
        m.put("title", title(details));
        m.put("details", details);
        m.put("character", c.character());
        m.put("where", c.where());
        m.put("source", SOURCE);
        if (c.versions() != null && !c.versions().isEmpty()) {
            m.put("versions", c.versions());
        }
        m.put("ref", ref);
        m.put("accountId", c.accountId());
        m.put("world", c.world());
        m.put("channel", c.channel());
        m.put("mapId", c.mapId());
        m.put("mapName", c.mapName());
        m.put("x", c.x());
        m.put("y", c.y());
        m.put("clientBuild", c.clientBuild());
        if (contextSummary != null) {
            m.put("context", contextSummary);
        }
        return Json.write(m).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
}
