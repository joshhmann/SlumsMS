package soloMapling.site.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 1.6.5 P0-7: banned.txt for the site's login service. It sits next to revoked.txt (same folder resolution as
 * {@link SiteAccounts#revokedFile}), holds one banned account id per line and is rewritten whole, atomically, whenever
 * the set changes. Missing file = nobody banned. Nothing else in that folder is ever read, written or deleted.
 * Bans call {@link #requestSweep()}; a 5-minute sweep catches temp-ban expiry and bans written straight into the DB.
 */
public final class BannedFile {
    private static final Logger log = LoggerFactory.getLogger(BannedFile.class);
    static final String FILE_NAME = "banned.txt";
    private static final long WARN_EVERY_MS = 60L * 60 * 1000;

    /** Banned = 1 (unban writes -1) or a temp ban still in the future (the default tempban is 2005-05-11). */
    static final String BANNED_IDS_SQL = "SELECT id FROM accounts WHERE banned = 1 OR tempban > ?";

    private static final AtomicBoolean pending = new AtomicBoolean();
    private static volatile ExecutorService worker;
    private static volatile long lastWarn;
    private static volatile boolean loggedOff;

    private BannedFile() {
    }

    /** The folder banned.txt goes in: the parent of the site's revoked.txt; null when no site folder is configured. Pure. */
    static Path directory(String explicitRevoked, String webloginDir) {
        Path f = SiteAccounts.revokedFile(explicitRevoked, webloginDir);
        return f == null ? null : f.toAbsolutePath().getParent();
    }

    /** Ask for a sweep soon. Never blocks the caller; a burst of requests runs one sweep. */
    public static void requestSweep() {
        if (!pending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor().execute(() -> {
                pending.set(false); // a request that arrives mid-sweep schedules another
                try {
                    sweep();
                } catch (RuntimeException e) {
                    warn("sweep failed: " + e);
                }
            });
        } catch (RuntimeException e) {
            pending.set(false);
        }
    }

    private static ExecutorService executor() {
        ExecutorService w = worker;
        if (w == null) {
            synchronized (BannedFile.class) {
                if ((w = worker) == null) {
                    w = worker = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "BannedFile-Sweep");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return w;
    }

    /** Query the DB and write the folder's banned.txt. A DB failure leaves the file as it was. */
    static void sweep() {
        Path dir = directory(System.getProperty("slums.auth.revoked"), System.getProperty("slums.weblogin.dir"));
        if (dir == null) {
            if (!loggedOff) {
                loggedOff = true;
                log.info("banned.txt: no -Dslums.auth.revoked or -Dslums.weblogin.dir set, not writing it");
            }
            return;
        }
        Set<Integer> ids;
        try (Connection con = DatabaseConnection.getConnection()) {
            ids = queryBannedIds(con, System.currentTimeMillis());
        } catch (SQLException e) {
            warn("could not read banned accounts: " + e.getMessage());
            return;
        }
        try {
            write(ids, dir);
        } catch (IOException | RuntimeException e) {
            warn("could not write " + dir.resolve(FILE_NAME) + ": " + e);
        }
    }

    static Set<Integer> queryBannedIds(Connection con, long nowMs) throws SQLException {
        Set<Integer> ids = new TreeSet<>();
        try (PreparedStatement ps = con.prepareStatement(BANNED_IDS_SQL)) {
            ps.setTimestamp(1, new Timestamp(nowMs));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt(1));
                }
            }
        }
        return ids;
    }

    /**
     * Write the whole set, sorted, one id per line, via a temp file in the same folder renamed over banned.txt.
     * Skips the write when the file already holds exactly this. Returns whether it replaced the file.
     */
    static boolean write(Collection<Integer> ids, Path dir) throws IOException {
        String text = ids.stream().sorted().distinct().map(String::valueOf).collect(Collectors.joining("\n"));
        byte[] bytes = (text.isEmpty() ? "" : text + "\n").getBytes(StandardCharsets.US_ASCII);
        Path target = dir.resolve(FILE_NAME);
        try {
            if (Files.isRegularFile(target) && java.util.Arrays.equals(bytes, Files.readAllBytes(target))) {
                return false;
            }
        } catch (IOException ignored) {
            // unreadable: just rewrite it
        }
        Path tmp = Files.createTempFile(dir, ".banned.", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic rename not supported in " + dir, e);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    /** At most one warning an hour, so an unwritable folder doesn't fill the log every sweep. */
    private static void warn(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarn >= WARN_EVERY_MS) {
            lastWarn = now;
            log.warn("banned.txt: {} (further problems are logged at most hourly)", msg);
        }
    }
}
