package soloMapling.site.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Ticket 105: the one place the invite-code rules live, shared by the game commands ({@code @invite} / {@code !invite})
 * and the site's invite page ({@link SiteInvites}), so a code made in one place counts in the other.
 * <ul>
 *   <li>GM (level {@link #GM_LEVEL}+): any validity, a note, unlimited; never counts toward a player's week.</li>
 *   <li>Player: an account with a character of level {@link InviteCodes#PLAYER_MIN_LEVEL}+ makes a one-use code that
 *       never expires, at most {@link InviteCodes#PLAYER_CODES_PER_WINDOW} per rolling
 *       {@link InviteCodes#PLAYER_WINDOW_MS} (cancelled ones included).</li>
 * </ul>
 * The weekly count and the insert run under one lock, so the game and the site (or two site requests) can't both take
 * the last slot of a week.
 */
public final class InviteRules {

    private static final Logger log = LoggerFactory.getLogger(InviteRules.class);

    /** The GM level that gets {@code !invite}'s rules (as {@code !ban}). */
    public static final int GM_LEVEL = 3;

    private static final Object MAKE_LOCK = new Object();

    public enum Refusal {
        /** No character of level 30+ on the account. */
        NOT_ELIGIBLE,
        /** Already 2 player codes in the last 7 days. */
        WEEKLY_LIMIT
    }

    /** A player code, or why there is none. madeThisWindow: the account's player codes in the window, this one included. */
    public record Made(InviteCodes.Invite invite, int madeThisWindow, Refusal refusal) {
        public boolean ok() {
            return refusal == null;
        }

        /** Player codes the account may still make this window. */
        public int left() {
            return Math.max(0, InviteCodes.PLAYER_CODES_PER_WINDOW - madeThisWindow);
        }
    }

    private InviteRules() {
    }

    /**
     * The {@code @invite} rule. onlineLevel: the level of the character asking when one is online (the saved row can
     * lag), 0 from the site. charId: the maker character, 0 from the site. via: where it was asked, for the log.
     */
    public static Made makePlayerCode(AuthStore store, int accountId, int charId, String byName, int onlineLevel,
                                      long now, String via) throws SQLException {
        int level = Math.max(onlineLevel, store.highestCharacterLevel(accountId));
        if (level < InviteCodes.PLAYER_MIN_LEVEL) {
            return new Made(null, 0, Refusal.NOT_ELIGIBLE);
        }
        synchronized (MAKE_LOCK) {
            int made = store.countSelfServeInvitesSince(accountId, now - InviteCodes.PLAYER_WINDOW_MS);
            if (made >= InviteCodes.PLAYER_CODES_PER_WINDOW) {
                return new Made(null, made, Refusal.WEEKLY_LIMIT);
            }
            InviteCodes.Invite invite = InviteCodes.Invite.selfServe(InviteCodes.newCode(), now, accountId, charId, byName);
            store.insertInvite(invite);
            log.info("Invite {} made by player {} (account {}, char {}) via {}, {} of {} this week",
                    InviteCodes.display(invite.code()), byName, accountId, charId, via, made + 1,
                    InviteCodes.PLAYER_CODES_PER_WINDOW);
            return new Made(invite, made + 1, null);
        }
    }

    /** The {@code !invite} rule: any validity and note, no limit. The caller has checked the GM level. */
    public static InviteCodes.Invite makeGmCode(AuthStore store, int accountId, int charId, String byName, String note,
                                                InviteCodes.Validity validity, long now, String via) throws SQLException {
        return makeGmCode(store, accountId, charId, byName, note, validity.expiresAt(now), validity.word, now, via);
    }

    /** As above with an explicit expiry (epoch ms, 0 = never; the site's 1-90 days); life: its label for the log. */
    public static InviteCodes.Invite makeGmCode(AuthStore store, int accountId, int charId, String byName, String note,
                                                long expiresAt, String life, long now, String via) throws SQLException {
        String clean = InviteCodes.cleanNote(note);
        InviteCodes.Invite invite = InviteCodes.Invite.fresh(InviteCodes.newCode(), now, accountId, byName, clean,
                expiresAt).withCreatedBy(charId);
        store.insertInvite(invite);
        log.info("Invite {} made by GM {} (account {}, char {}) via {} ({}){}", InviteCodes.display(invite.code()),
                byName, accountId, charId, via, life, clean.isEmpty() ? "" : ": " + clean);
        return invite;
    }
}
