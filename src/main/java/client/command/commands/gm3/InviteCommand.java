package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.site.auth.AuthStore;
import soloMapling.site.auth.InviteCodes;
import soloMapling.site.auth.InviteRules;

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

    static final int GM_LEVEL = InviteRules.GM_LEVEL;

    {
        setDescription("Make a sign-up code for a friend (level 30+, 2 a week): @invite, @invite list.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int accountId = c.getAccID();
        String by = player.getName();
        int charId = player.getId();
        int level = player.getLevel();
        boolean gm = player.gmLevel() >= GM_LEVEL;
        WORKER.execute(() -> {
            long now = System.currentTimeMillis();
            List<String> lines = gm ? run(params, accountId, charId, by, now) : runPlayer(params, accountId, charId, by, level, now);
            for (String line : lines) {
                player.yellowMessage(line);
            }
        });
    }

    /**
     * {@code @invite}: a one-use code that never expires, for an account with a character of level 30+, at most
     * {@link InviteCodes#PLAYER_CODES_PER_WINDOW} per rolling 7 days (counted from when each was made, cancelled ones
     * included). {@code @invite list}: your codes, used or unused, nothing about who used them. Never throws.
     * onlineLevel: the level of the character typing (the saved row can lag).
     */
    static List<String> runPlayer(String[] params, int accountId, int charId, String by, int onlineLevel, long now) {
        List<String> out = new ArrayList<>();
        try {
            if (params.length > 0 && params[0].equalsIgnoreCase("list")) {
                List<InviteCodes.Invite> mine = store.invitesMadeBy(accountId, LIST_LIMIT);
                if (mine.isEmpty()) {
                    out.add("You haven't made any invite codes. @invite makes one.");
                }
                for (InviteCodes.Invite i : mine) {
                    out.add(InviteCodes.describeForMaker(i, now));
                }
                return out;
            }
            if (params.length > 0) {
                out.add("Syntax: @invite (make a code), @invite list.");
                return out;
            }
            // The shared rule (the site's invite page uses the same one, so the week is counted across both).
            InviteRules.Made made = InviteRules.makePlayerCode(store, accountId, charId, by, onlineLevel, now, "@invite");
            if (made.refusal() == InviteRules.Refusal.NOT_ELIGIBLE) {
                out.add("You need a level " + InviteCodes.PLAYER_MIN_LEVEL + " character to invite a friend.");
                return out;
            }
            if (made.refusal() == InviteRules.Refusal.WEEKLY_LIMIT) {
                out.add("You've made " + InviteCodes.PLAYER_CODES_PER_WINDOW + " invite codes in the last 7 days. Try again later.");
                return out;
            }
            out.add("Invite code: " + InviteCodes.display(made.invite().code()) + " (one friend, never expires).");
            out.add("They sign up on the site or in the launcher with it. You have " + made.left() + " left this week.");
        } catch (Exception e) {
            log.warn("@invite failed", e);
            out.add("Invite codes aren't available right now.");
        }
        return out;
    }

    /** The reply lines for one use. Never throws. */
    static List<String> run(String[] params, int accountId, String by, long now) {
        return run(params, accountId, 0, by, now);
    }

    static List<String> run(String[] params, int accountId, int charId, String by, long now) {
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
                    log.info("Invite {} revoked by {} (account {}) via !invite", InviteCodes.display(code), by, accountId);
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
            InviteCodes.Invite invite = InviteRules.makeGmCode(store, accountId, charId, by,
                    String.join(" ", noteWords), validity, now, "!invite");
            String code = invite.code();
            out.add("Invite code: " + InviteCodes.display(code) + " (one account, "
                    + (validity == InviteCodes.Validity.FOREVER ? "never expires" : "valid " + validity.word) + ").");
            out.add("Sign up on the site or in the launcher with it.");
        } catch (Exception e) {
            log.warn("!invite failed", e);
            out.add("Invite codes aren't available right now (is migration 036 applied?).");
        }
        return out;
    }

    /** Auto-QA t-iv-1: the player path ({@code @invite}) for a throwaway account, whatever the caller's own GM level. */
    public static List<String> runPlayerForQa(String[] params, int accountId, int charId, String by, int level, long now) {
        return runPlayer(params, accountId, charId, by, level, now);
    }

    /** Test seam. */
    static void useStoreForTest(AuthStore s) {
        store = s == null ? new AuthStore.Jdbc() : s;
    }
}
