package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * @weblogin CODE: approves a website sign-in (docs/design/site-login-contract.md). Off unless
 * -Dslums.weblogin.dir is set; only the live server should ever set it to the real login directory.
 */
public class WebLoginCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(WebLoginCommand.class);
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int RATE_LIMIT = 5;
    static final long RATE_WINDOW_MS = 60_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<Integer, ArrayDeque<Long>> recent = new ConcurrentHashMap<>();
    private static volatile boolean groupWarned;
    static LongSupplier clock = System::currentTimeMillis;

    {
        setDescription("Sign in to the Slums website: @weblogin <code from the site>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String code = params.length > 0 ? params[0] : "";
        player.yellowMessage(handle(System.getProperty("slums.weblogin.dir"), player.getId(), c.getAccountName(),
                player.getAccountID(), player.getName(), player.gmLevel(), code));
    }

    static void resetForTest() {
        recent.clear();
        groupWarned = false;
    }

    /** Returns the message to show the player. Never throws. */
    static String handle(String dirProp, int charId, String account, int accountId, String character, int gm, String rawCode) {
        try {
            if (dirProp == null || dirProp.isBlank()) {
                return "Website sign-in isn't available right now.";
            }
            if (charId >= BotStore.BOT_ID_BASE) {
                return "Bots can't use website sign-in.";
            }
            String code = validCode(rawCode);
            if (code == null) {
                return "That code doesn't look right. Use the 6-character code shown on the website.";
            }
            Path dir = Paths.get(dirProp);
            if (isRevoked(dir, accountId)) {
                return "Website sign-in isn't available for this account.";
            }
            if (!allow(accountId)) {
                return "Slow down, try again in a minute.";
            }
            write(dir, code, accountId, account, character, gm);
            return "Website sign-in sent. Go back to your browser.";
        } catch (Throwable t) {
            log.warn("@weblogin failed for account {}", accountId, t);
            return "Couldn't send the website sign-in, please try again.";
        }
    }

    static String validCode(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (code.length() != 6) {
            return null;
        }
        for (int i = 0; i < 6; i++) {
            if (ALPHABET.indexOf(code.charAt(i)) < 0) {
                return null;
            }
        }
        return code;
    }

    private static boolean allow(int accountId) {
        long now = clock.getAsLong();
        ArrayDeque<Long> q = recent.computeIfAbsent(accountId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= RATE_WINDOW_MS) {
                q.pollFirst();
            }
            if (q.size() >= RATE_LIMIT) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }

    private static boolean isRevoked(Path dir, int accountId) {
        Path f = dir.resolve("revoked.txt");
        try {
            if (!Files.isRegularFile(f)) {
                return false;
            }
            String id = Integer.toString(accountId);
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.trim().equals(id)) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("@weblogin could not read revoked.txt", e);
        }
        return false;
    }

    private static void write(Path dir, String code, int accountId, String account, String character, int gm) throws IOException {
        Path approvals = dir.resolve("approvals");
        Files.createDirectories(approvals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("account_id", accountId);
        body.put("account", account);
        body.put("character", character);
        body.put("gm", gm);
        body.put("time", clock.getAsLong() / 1000L);
        Path tmp = approvals.resolve(code + ".json.tmp");
        Path dest = approvals.resolve(code + ".json");
        try {
            Files.write(tmp, MAPPER.writeValueAsBytes(body));
            applyPermissions(tmp);
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private static void applyPermissions(Path f) {
        PosixFileAttributeView view = Files.getFileAttributeView(f, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        try {
            view.setPermissions(PosixFilePermissions.fromString("rw-r-----"));
        } catch (IOException | RuntimeException e) {
            warnGroupOnce("permissions", e);
        }
        try {
            String group = System.getProperty("slums.weblogin.group", "slums-login");
            GroupPrincipal gp = f.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(group);
            view.setGroup(gp);
        } catch (IOException | RuntimeException e) {
            warnGroupOnce("group", e);
        }
    }

    private static void warnGroupOnce(String what, Exception e) {
        if (!groupWarned) {
            groupWarned = true;
            log.warn("@weblogin could not set approval file {} (continuing): {}", what, e.toString());
        }
    }
}
