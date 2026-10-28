package soloMapling.site.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Ticket 105, "Site invite page" (owner 2026-10-28): the site's {@code /invite/create}, {@code /invite/list} and
 * {@code /invite/cancel} routes ({@link SiteAuthApi}). The site sends the signed-in account's id; the server decides
 * everything else from the database:
 * <ul>
 *   <li>GM accounts (highest character GM level {@link InviteRules#GM_LEVEL}+, the {@code gm_level} {@code /verify}
 *       returns): {@code !invite}'s rules: any validity ({@code life}), a note, unlimited; list every code; cancel
 *       any unused code.</li>
 *   <li>Everyone else: {@code @invite}'s rules through the same code path ({@link InviteRules#makePlayerCode}), so the
 *       2-a-week count is shared with the game; list only their own codes, used or not, never who used them; cancel
 *       their own unused codes (a cancelled code still counts toward the week, as in game).</li>
 * </ul>
 * Banned, temp-banned, bot, site-revoked and unknown accounts are {@code REFUSED} (the same check as sign-in). Every
 * create and cancel is logged with the acting account id.
 */
public final class SiteInvites {

    private static final Logger log = LoggerFactory.getLogger(SiteInvites.class);

    public static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
    public static final String WEEKLY_LIMIT = "WEEKLY_LIMIT";
    public static final String REFUSED = "REFUSED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ALREADY_USED = "ALREADY_USED";
    public static final String ALREADY_CANCELLED = "ALREADY_CANCELLED";
    public static final String CANCELLED = "CANCELLED";

    static final int LIST_DEFAULT = 50;
    static final int LIST_MAX = 100;

    private final AuthStore store;
    private final LongSupplier clock;
    private final SiteAccounts accounts;

    public SiteInvites(AuthStore store, LongSupplier clock, SiteAccounts accounts) {
        this.store = store;
        this.clock = clock;
        this.accounts = accounts;
    }

    /** Who is asking: null when the account is unknown or refused. */
    private record Actor(int id, String name, int gmLevel) {
        boolean gm() {
            return gmLevel >= InviteRules.GM_LEVEL;
        }
    }

    private Actor actor(Integer accountId, long now) throws Exception {
        if (accountId == null || accountId <= 0) {
            return null;
        }
        AuthStore.Account a = store.findAccountById(accountId);
        if (a == null || accounts.refused(a, now)) {
            return null;
        }
        return new Actor(a.id(), a.name(), store.profile(a.id()).gmLevel());
    }

    /** GM codes from the site: 1 to this many days, or {@code forever}. */
    static final int MAX_LIFE_DAYS = 90;
    static final int DEFAULT_LIFE_DAYS = 7;

    /**
     * The GM's {@code life}: a whole number of days 1-90 (a JSON number, or text "30" / "30d"), "forever" (0, never
     * expires), or missing/empty for 7 days. -1 when it is none of those.
     */
    static int lifeDays(Object life) {
        if (life == null) {
            return DEFAULT_LIFE_DAYS;
        }
        if (life instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && d >= 1 && d <= MAX_LIFE_DAYS ? (int) d : -1;
        }
        String t = life.toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return DEFAULT_LIFE_DAYS;
        }
        if (t.equals("forever")) {
            return 0;
        }
        if (t.endsWith("d")) {
            t = t.substring(0, t.length() - 1);
        }
        if (!t.matches("[0-9]{1,3}")) {
            return -1;
        }
        int d = Integer.parseInt(t);
        return d >= 1 && d <= MAX_LIFE_DAYS ? d : -1;
    }

    /** {@code /invite/create}. life and note are read for GMs only. */
    public Map<String, Object> create(Integer accountId, Object life, String note) {
        long now = clock.getAsLong();
        try {
            Actor me = actor(accountId, now);
            if (me == null) {
                return fail(REFUSED);
            }
            Map<String, Object> m = ok();
            if (me.gm()) {
                int days = lifeDays(life);
                if (days < 0) {
                    Map<String, Object> bad = fail(SiteAccounts.BAD_INPUT);
                    bad.put("rule", "life");
                    return bad;
                }
                long expiresAt = days == 0 ? 0 : now + TimeUnit.DAYS.toMillis(days);
                InviteCodes.Invite i = InviteRules.makeGmCode(store, me.id(), 0, me.name(), note, expiresAt,
                        days == 0 ? "forever" : days + "d", now, "site (GM account " + me.id() + ")");
                m.put("code", InviteCodes.display(i.code()));
                m.put("expiresAt", seconds(i.expiresAt()));
                m.put("gm", true);
                return m;
            }
            InviteRules.Made made = InviteRules.makePlayerCode(store, me.id(), 0, me.name(), 0, now,
                    "site (account " + me.id() + ")");
            if (made.refusal() == InviteRules.Refusal.NOT_ELIGIBLE) {
                return fail(NOT_ELIGIBLE);
            }
            if (made.refusal() == InviteRules.Refusal.WEEKLY_LIMIT) {
                return fail(WEEKLY_LIMIT);
            }
            m.put("code", InviteCodes.display(made.invite().code()));
            m.put("expiresAt", null);
            m.put("gm", false);
            m.put("left", made.left());
            return m;
        } catch (Exception e) {
            log.warn("Site invite create failed on the server side", e);
            return fail(SiteAccounts.UNAVAILABLE);
        }
    }

    /**
     * {@code /invite/list}: GMs page through every code, players through their own. Paging: {@code limit} (page
     * size, default 50, at most 100) and either {@code page} (1-based) or {@code offset}.
     */
    public Map<String, Object> list(Integer accountId, Integer limit, Integer page, Integer offset) {
        long now = clock.getAsLong();
        try {
            Actor me = actor(accountId, now);
            if (me == null) {
                return fail(REFUSED);
            }
            int lim = limit == null || limit <= 0 ? LIST_DEFAULT : Math.min(limit, LIST_MAX);
            int pg = page != null && page >= 1 ? page : 0;
            int off = pg > 0 ? (int) Math.min(Integer.MAX_VALUE, (long) (pg - 1) * lim)
                    : offset == null || offset < 0 ? 0 : offset;
            Integer scope = me.gm() ? null : me.id();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (InviteCodes.Invite i : store.invitePage(scope, lim, off)) {
                rows.add(me.gm() ? gmRow(i, now) : playerRow(i, now));
            }
            int total = store.countInvites(scope);
            boolean more = off + rows.size() < total;
            Map<String, Object> m = ok();
            m.put("gm", me.gm());
            m.put("codes", rows);
            if (!me.gm()) {
                int made = store.countSelfServeInvitesSince(me.id(), now - InviteCodes.PLAYER_WINDOW_MS);
                m.put("left", Math.max(0, InviteCodes.PLAYER_CODES_PER_WINDOW - made));
                m.put("eligible", store.highestCharacterLevel(me.id()) >= InviteCodes.PLAYER_MIN_LEVEL);
            }
            int current = off / lim + 1;
            m.put("total", total);
            m.put("limit", lim);
            m.put("offset", off);
            m.put("page", current);
            m.put("pages", Math.max(1, (total + lim - 1) / lim));
            m.put("more", more);
            m.put("next", more ? current + 1 : null);
            return m;
        } catch (Exception e) {
            log.warn("Site invite list failed on the server side", e);
            return fail(SiteAccounts.UNAVAILABLE);
        }
    }

    /** {@code /invite/cancel}: GMs any unused code, players their own unused codes. */
    public Map<String, Object> cancel(Integer accountId, String typedCode) {
        long now = clock.getAsLong();
        try {
            Actor me = actor(accountId, now);
            if (me == null) {
                return fail(REFUSED);
            }
            String code = InviteCodes.normalize(typedCode);
            InviteCodes.Invite i = code == null ? null : store.findInvite(code);
            if (i == null || (!me.gm() && i.createdByAccount() != me.id())) {
                return fail(NOT_FOUND);   // someone else's code looks like no code to a player
            }
            String by = me.gm() ? me.name() : me.name() + InviteCodes.OWN_CANCEL_SUFFIX;
            if (i.usedAt() == 0 && i.revokedAt() == 0 && store.revokeInvite(code, by, now)) {
                log.info("Invite {} cancelled on the site by {} account {} ({})", InviteCodes.display(code),
                        me.gm() ? "GM" : "player", me.id(), me.name());
                Map<String, Object> m = ok();
                m.put("result", CANCELLED);
                m.put("code", InviteCodes.display(code));
                return m;
            }
            InviteCodes.Invite now2 = store.findInvite(code);   // changed under us, or already settled
            return fail(now2 != null && now2.usedAt() > 0 ? ALREADY_USED : ALREADY_CANCELLED);
        } catch (Exception e) {
            log.warn("Site invite cancel failed on the server side", e);
            return fail(SiteAccounts.UNAVAILABLE);
        }
    }

    static Map<String, Object> gmRow(InviteCodes.Invite i, long now) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", InviteCodes.display(i.code()));
        r.put("status", gmStatus(i.status(now)));
        r.put("createdAt", seconds(i.createdAt()));
        r.put("expiresAt", seconds(i.expiresAt()));
        r.put("usedBy", i.usedAt() > 0 && i.usedByName() != null && !i.usedByName().isEmpty() ? i.usedByName() : null);
        r.put("usedAt", seconds(i.usedAt()));
        r.put("cancelled", i.revokedAt() > 0);
        r.put("cancelledAt", seconds(i.revokedAt()));
        r.put("cancelledBy", i.revokedAt() > 0 ? i.revokedBy() : null);
        r.put("owner", i.createdByName());
        r.put("ownerAccount", i.createdByAccount());
        r.put("playerCode", i.selfServe());
        r.put("note", i.note() == null ? "" : i.note());
        return r;
    }

    /** A player's own code: used or not and when, never who used it (usedBy is a boolean); no owner or note. */
    static Map<String, Object> playerRow(InviteCodes.Invite i, long now) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("code", InviteCodes.display(i.code()));
        r.put("status", switch (i.status(now)) {
            case OPEN -> "unused";
            case USED -> "used";
            case EXPIRED -> "expired";
            case REVOKED -> InviteCodes.cancelledByMaker(i) ? "cancelled" : "cancelled_by_staff";
        });
        r.put("createdAt", seconds(i.createdAt()));
        r.put("expiresAt", seconds(i.expiresAt()));
        r.put("usedBy", i.usedAt() > 0);
        r.put("usedAt", seconds(i.usedAt()));
        r.put("cancelled", i.revokedAt() > 0);
        return r;
    }

    static String gmStatus(InviteCodes.Status s) {
        return switch (s) {
            case OPEN -> "open";
            case USED -> "used";
            case EXPIRED -> "expired";
            case REVOKED -> "cancelled";
        };
    }

    /** Unix seconds (the site's unit), or null for "not set" (0 in the table). */
    static Long seconds(long ms) {
        return ms > 0 ? ms / 1000 : null;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> fail(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", error);
        return m;
    }
}
