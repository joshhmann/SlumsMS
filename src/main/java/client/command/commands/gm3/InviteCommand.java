package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.site.auth.AuthStore;
import soloMapling.site.auth.InviteCodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ticket 105: {@code !invite [1d|7d|30d|forever] [note]} makes a single-use sign-up code for the site/launcher (7d when
 * no duration is given); {@code !invite list} shows the newest codes with their expiry and who used them;
 * {@code !invite revoke <code>} cancels an unused one. The database work runs on a background thread.
 */
public class InviteCommand extends Command {
    private static final Logger log = LoggerFactory.getLogger(InviteCommand.class);
    static final int LIST_LIMIT = 15;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "invite-command");
        t.setDaemon(true);
        return t;
    });
    static AuthStore store = new AuthStore.Jdbc();

    {
        setDescription("Make a site sign-up code: !invite [1d|7d|30d|forever] [note], !invite list, !invite revoke <code>.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int accountId = c.getAccID();
        String by = player.getName();
        WORKER.execute(() -> {
            for (String line : run(params, accountId, by, System.currentTimeMillis())) {
                player.yellowMessage(line);
            }
        });
    }

    /** The reply lines for one use. Never throws. */
    static List<String> run(String[] params, int accountId, String by, long now) {
        List<String> out = new ArrayList<>();
        try {
            String first = params.length > 0 ? params[0] : "";
            if (first.equalsIgnoreCase("list")) {
                List<InviteCodes.Invite> invites = store.recentInvites(LIST_LIMIT);
                if (invites.isEmpty()) {
                    out.add("No invite codes yet.");
                }
                for (InviteCodes.Invite i : invites) {
                    out.add(InviteCodes.describe(i, now));
                }
                return out;
            }
            if (first.equalsIgnoreCase("revoke")) {
                String code = params.length > 1 ? InviteCodes.normalize(params[1]) : null;
                if (code == null) {
                    out.add("Syntax: !invite revoke <code>");
                } else if (store.revokeInvite(code, by, now)) {
                    log.info("Invite {} revoked by {}", InviteCodes.display(code), by);
                    out.add("Revoked " + InviteCodes.display(code) + ".");
                } else {
                    out.add("No unused invite " + InviteCodes.display(code) + ".");
                }
                return out;
            }
            InviteCodes.Validity validity = InviteCodes.Validity.parse(first);
            String[] noteWords = validity == null ? params : Arrays.copyOfRange(params, 1, params.length);
            if (validity == null) {
                validity = InviteCodes.Validity.DEFAULT;
            }
            String note = InviteCodes.cleanNote(String.join(" ", noteWords));
            String code = InviteCodes.newCode();
            store.insertInvite(InviteCodes.Invite.fresh(code, now, accountId, by, note, validity.expiresAt(now)));
            log.info("Invite {} made by {} ({}){}", InviteCodes.display(code), by, validity.word,
                    note.isEmpty() ? "" : ": " + note);
            out.add("Invite code: " + InviteCodes.display(code) + " (one account, "
                    + (validity == InviteCodes.Validity.FOREVER ? "never expires" : "valid " + validity.word) + ").");
            out.add("Sign up on the site or in the launcher with it.");
        } catch (Exception e) {
            log.warn("!invite failed", e);
            out.add("Invite codes aren't available right now (is migration 036 applied?).");
        }
        return out;
    }

    /** Test seam. */
    static void useStoreForTest(AuthStore s) {
        store = s == null ? new AuthStore.Jdbc() : s;
    }
}
