package constants.net;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * What this server build is: {@link ServerConstants#SLUMSMS_VERSION}, the git commit the jar was built from (written
 * at build time into {@code slums-git.properties} by the git-commit-id plugin; absent when the build had no git), and
 * the running jar's sha256 (computed once, off the caller's thread). Used by @bug reports (ticket 131) and the site's
 * world.json "version".
 */
public final class BuildInfo {
    private BuildInfo() {
    }

    private static volatile Properties props;
    private static volatile CompletableFuture<String> jarHash;

    static Properties props() {
        Properties p = props;
        if (p == null) {
            p = load(BuildInfo.class.getClassLoader().getResourceAsStream("slums-git.properties"));
            props = p;
        }
        return p;
    }

    static Properties load(InputStream in) {
        Properties p = new Properties();
        if (in == null) {
            return p;
        }
        try (in) {
            p.load(in);
        } catch (Exception e) {
            p.clear();
        }
        return p;
    }

    /** The short commit (9 hex digits), or null when the build did not record one. */
    public static String commit() {
        return shortCommit(props());
    }

    static String shortCommit(Properties p) {
        String c = p.getProperty("git.commit.id.abbrev", "").trim();
        if (c.isEmpty()) {
            String full = p.getProperty("git.commit.id", "").trim();
            c = full.length() > 9 ? full.substring(0, 9) : full;
        }
        return c.matches("[0-9a-f]{7,40}") ? c : null;
    }

    /** True when the jar was built from a tree with uncommitted changes; false when clean or unknown. */
    public static boolean dirty() {
        return Boolean.parseBoolean(props().getProperty("git.dirty", "false").trim());
    }

    /** The running jar's sha256 (hex), null while it is still being computed or when the server runs from classes. */
    public static String jarSha256() {
        CompletableFuture<String> f = jarHash;
        if (f == null) {
            synchronized (BuildInfo.class) {
                if (jarHash == null) {
                    jarHash = CompletableFuture.supplyAsync(BuildInfo::hashJar);
                }
                f = jarHash;
            }
        }
        return f.getNow(null);
    }

    private static String hashJar() {
        try {
            var src = BuildInfo.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                return null;
            }
            Path jar = Path.of(src.getLocation().toURI());
            if (!Files.isRegularFile(jar) || !jar.toString().endsWith(".jar")) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jar)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }
}
