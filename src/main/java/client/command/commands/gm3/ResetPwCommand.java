package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.site.auth.PasswordResets;
import soloMapling.site.auth.ResetStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ticket 107: forgotten passwords. {@code !resetpw list} shows the pending reset requests from the site (account, when
 * in PT, the contact the player left, the first characters of the ipHash); {@code !resetpw approve <account>} lets that
 * browser set a new password within 2 hours; {@code !resetpw deny <account>} refuses it. Both are logged with the GM's
 * name. Reach the player at their contact and make sure it is really them before approving. The database work runs on
 * a background thread.
 */
public class ResetPwCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(ResetPwCommand.class);
    static final String SYNTAX = "Syntax: !resetpw list | !resetpw approve <account> | !resetpw deny <account>";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "resetpw-command");
        t.setDaemon(true);
        return t;
    });
    static ResetStore store = new ResetStore.Jdbc();

    {
        setDescription("Password reset requests from the site: !resetpw list, !resetpw approve <account>, !resetpw deny <account>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String by = player.getName();
        WORKER.execute(() -> {
            for (String line : run(params, by, System.currentTimeMillis())) {
                player.yellowMessage(line);
            }
        });
    }

    /** The reply lines for one use. Never throws. */
    static List<String> run(String[] params, String by, long now) {
        List<String> out = new ArrayList<>();
        try {
            String first = params.length > 0 ? params[0] : "";
            String account = params.length > 1 ? params[1] : null;
            if (first.equalsIgnoreCase("list")) {
                out.addAll(PasswordResets.list(store, now));
            } else if (first.equalsIgnoreCase("approve")) {
                out.add(PasswordResets.approve(store, account, by, now));
            } else if (first.equalsIgnoreCase("deny")) {
                out.add(PasswordResets.deny(store, account, by, now));
            } else {
                out.add(SYNTAX);
            }
        } catch (Exception e) {
            log.warn("!resetpw failed", e);
            out.add("Password reset requests aren't available right now (is migration 037 applied?).");
        }
        return out;
    }

    /** Test seam. */
    static void useStoreForTest(ResetStore s) {
        store = s == null ? new ResetStore.Jdbc() : s;
    }
}
