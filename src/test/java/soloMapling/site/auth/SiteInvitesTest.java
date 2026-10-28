package soloMapling.site.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105, "Site invite page": /invite/create, /invite/list, /invite/cancel through the auth API. */
class SiteInvitesTest {

    static final String SECRET = "s3cret-for-the-test-only-0123456789";
    static final ObjectMapper JSON = new ObjectMapper();
    static final long DAY = TimeUnit.DAYS.toMillis(1);
    static final int GM = 1, PLAYER = 2, LOW = 3, BANNED = 4, BOT = 5, REVOKED = 6, OTHER = 7, GM2 = 8;

    final MemoryAuthStore store = new MemoryAuthStore();
    final AtomicLong clock = new AtomicLong(1_800_000_000_000L);
    final SiteAccounts accounts = new SiteAccounts(store, clock::get, n -> false, id -> id == REVOKED);
    final SiteAuthApi api = new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8), accounts, null,
            new SiteInvites(store, clock::get, accounts));

    SiteInvitesTest() {
        store.put(new AuthStore.Account(GM, "Boss", "h", false, 0));
        store.putProfile(GM, new AuthStore.Profile(3, List.of("Boss")));
        store.put(new AuthStore.Account(GM2, "Helper", "h", false, 0));
        store.putProfile(GM2, new AuthStore.Profile(2, List.of("Helper")));   // GM level 2: player rules
        store.levels.put(GM2, 50);
        store.put(new AuthStore.Account(PLAYER, "Pal", "h", false, 0));
        store.levels.put(PLAYER, 31);
        store.put(new AuthStore.Account(OTHER, "Other", "h", false, 0));
        store.levels.put(OTHER, 80);
        store.put(new AuthStore.Account(LOW, "Newb", "h", false, 0));
        store.levels.put(LOW, 29);
        store.put(new AuthStore.Account(BANNED, "Cheat", "h", true, 0));
        store.levels.put(BANNED, 90);
        store.put(new AuthStore.Account(BOT, "Botty", "h", false, 0, true));
        store.levels.put(BOT, 90);
        store.put(new AuthStore.Account(REVOKED, "Gone", "h", false, 0));
        store.levels.put(REVOKED, 90);
    }

    Map<?, ?> post(String path, Object... kv) throws Exception {
        Map<String, Object> b = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            b.put((String) kv[i], kv[i + 1]);
        }
        SiteAuthApi.Response r = api.handle("POST", path, SECRET, JSON.writeValueAsBytes(b));
        assertEquals(200, r.status(), path + " " + r.body());
        // Round-trip through JSON, as the site sees it.
        return JSON.readValue(JSON.writeValueAsBytes(r.body()), Map.class);
    }

    static String code(Map<?, ?> m) {
        assertEquals(true, m.get("ok"), m.toString());
        return (String) m.get("code");
    }

    @Test
    void everyInviteRouteNeedsTheSecret() throws Exception {
        byte[] b = JSON.writeValueAsBytes(Map.of("account_id", GM));
        for (String p : List.of("/invite/create", "/invite/list", "/invite/cancel")) {
            assertEquals(401, api.handle("POST", p, null, b).status(), p);
            assertEquals(401, api.handle("POST", p, "wrong", b).status(), p);
            assertEquals(405, api.handle("GET", p, SECRET, b).status(), p);
        }
        assertTrue(store.invites.isEmpty());
        assertEquals(404, api.handle("POST", "/invite/other", SECRET, b).status());
    }

    @Test
    void withoutTheInviteServiceTheRoutesAnswerUnavailable() throws Exception {
        SiteAuthApi bare = new SiteAuthApi(SECRET.getBytes(StandardCharsets.UTF_8), accounts);
        var r = bare.handle("POST", "/invite/list", SECRET, JSON.writeValueAsBytes(Map.of("account_id", GM)));
        assertEquals("UNAVAILABLE", r.body().get("error"));
    }

    @Test
    void gmGetsBangInviteRulesLifeNoteAndNoLimit() throws Exception {
        Map<?, ?> a = post("/invite/create", "account_id", GM, "life", "30d", "note", "for bob\n");
        String c = code(a);
        assertTrue(c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}"), c);
        assertEquals(true, a.get("gm"));
        assertEquals((clock.get() + 30 * DAY) / 1000, ((Number) a.get("expiresAt")).longValue(), "unix seconds");
        InviteCodes.Invite i = store.findInvite(InviteCodes.normalize(c));
        assertEquals("for bob", i.note());
        assertEquals(GM, i.createdByAccount());
        assertEquals("Boss", i.createdByName());
        assertFalse(i.selfServe(), "a GM code never uses a player's week");
        assertNull(post("/invite/create", "account_id", GM, "life", "forever").get("expiresAt"));
        assertEquals((clock.get() + 7 * DAY) / 1000,
                ((Number) post("/invite/create", "account_id", GM).get("expiresAt")).longValue(), "7d by default");
        assertEquals((clock.get() + 90 * DAY) / 1000,
                ((Number) post("/invite/create", "account_id", GM, "life", 90).get("expiresAt")).longValue(), "days as a number");
        assertEquals((clock.get() + 12 * DAY) / 1000,
                ((Number) post("/invite/create", "account_id", GM, "life", "12").get("expiresAt")).longValue());
        for (int n = 0; n < 5; n++) {
            code(post("/invite/create", "account_id", GM));
        }
        assertEquals(10, store.invites.size());
        for (Object life : new Object[]{"2y", 0, 91, -1, 1.5, "91d", "x"}) {
            Map<?, ?> bad = post("/invite/create", "account_id", GM, "life", life);
            assertEquals("BAD_INPUT", bad.get("error"), String.valueOf(life));
            assertEquals("life", bad.get("rule"));
        }
        assertEquals(10, store.invites.size());
    }

    @Test
    void playerGetsAtInviteRulesNeverExpiresTwoAWeekLifeAndNoteIgnored() throws Exception {
        Map<?, ?> a = post("/invite/create", "account_id", PLAYER, "life", "1d", "note", "ignored");
        String c = code(a);
        assertEquals(false, a.get("gm"));
        assertNull(a.get("expiresAt"));
        assertEquals(1, a.get("left"));
        InviteCodes.Invite i = store.findInvite(InviteCodes.normalize(c));
        assertTrue(i.selfServe());
        assertEquals(0, i.expiresAt());
        assertEquals("", i.note());
        assertEquals(PLAYER, i.createdByAccount());
        clock.addAndGet(1000);
        assertEquals(0, post("/invite/create", "account_id", PLAYER).get("left"));
        assertEquals("WEEKLY_LIMIT", post("/invite/create", "account_id", PLAYER).get("error"));
        assertEquals(2, store.invites.size());
        // Another account has its own week; GM level 2 is a player here, as in game.
        code(post("/invite/create", "account_id", OTHER));
        code(post("/invite/create", "account_id", GM2));
        code(post("/invite/create", "account_id", GM2));
        assertEquals("WEEKLY_LIMIT", post("/invite/create", "account_id", GM2, "life", "forever").get("error"));
        // Rolling: 7 days after the first one, one slot is free again.
        clock.addAndGet(7 * DAY - 1000);
        code(post("/invite/create", "account_id", PLAYER));
        assertEquals("WEEKLY_LIMIT", post("/invite/create", "account_id", PLAYER).get("error"));
    }

    @Test
    void playersNeedALevel30Character() throws Exception {
        assertEquals("NOT_ELIGIBLE", post("/invite/create", "account_id", LOW).get("error"));
        Map<?, ?> l = post("/invite/list", "account_id", LOW);
        assertEquals(false, l.get("eligible"));
        store.levels.put(LOW, 30);
        code(post("/invite/create", "account_id", LOW));
        assertEquals(true, post("/invite/list", "account_id", LOW).get("eligible"));
    }

    @Test
    void bannedBotRevokedUnknownAndMissingAccountsAreRefused() throws Exception {
        for (Object id : new Object[]{BANNED, BOT, REVOKED, 999, 1_000_000_001, "2", null}) {
            for (String p : List.of("/invite/create", "/invite/list", "/invite/cancel")) {
                Map<?, ?> m = id == null ? post(p) : post(p, "account_id", id, "code", "ABCDE-FGHJK");
                assertEquals("REFUSED", m.get("error"), p + " " + id);
            }
        }
        store.put(new AuthStore.Account(9, "Temp", "h", false, clock.get() + DAY));
        store.levels.put(9, 90);
        assertEquals("REFUSED", post("/invite/create", "account_id", 9).get("error"), "temp-banned");
        assertTrue(store.invites.isEmpty());
    }

    @Test
    void gmListShowsEveryCodeWithWhoUsedItAndPages() throws Exception {
        String gmCode = code(post("/invite/create", "account_id", GM, "life", "1d", "note", "for bob"));
        clock.addAndGet(1000);
        String mine = code(post("/invite/create", "account_id", PLAYER));
        store.createAccountWithInvite(InviteCodes.normalize(mine), "Friend", "h", "iph", clock.get() + 1);
        clock.addAndGet(1000);
        String open = code(post("/invite/create", "account_id", OTHER));
        post("/invite/cancel", "account_id", GM, "code", open);
        clock.addAndGet(2 * DAY);

        Map<?, ?> l = post("/invite/list", "account_id", GM);
        assertEquals(true, l.get("gm"));
        assertEquals(3, l.get("total"));
        List<?> rows = (List<?>) l.get("codes");
        assertEquals(3, rows.size());
        Map<?, ?> cancelled = (Map<?, ?>) rows.get(0), used = (Map<?, ?>) rows.get(1), expired = (Map<?, ?>) rows.get(2);
        assertEquals(open, cancelled.get("code"));
        assertEquals("cancelled", cancelled.get("status"));
        assertEquals(true, cancelled.get("cancelled"));
        assertEquals("Boss", cancelled.get("cancelledBy"));
        assertEquals(mine, used.get("code"));
        assertEquals("used", used.get("status"));
        assertEquals(false, used.get("cancelled"));
        assertEquals("Friend", used.get("usedBy"));
        assertEquals((clock.get() - 2 * DAY - 1000 + 1) / 1000, ((Number) used.get("usedAt")).longValue());
        assertEquals(true, used.get("playerCode"));
        assertEquals("Pal", used.get("owner"));
        assertEquals(PLAYER, used.get("ownerAccount"));
        assertNull(used.get("expiresAt"));
        assertEquals(gmCode, expired.get("code"));
        assertEquals("expired", expired.get("status"));
        assertEquals("for bob", expired.get("note"));
        assertNull(expired.get("usedBy"));
        assertEquals(Set.of("code", "status", "createdAt", "expiresAt", "usedBy", "usedAt", "cancelled", "cancelledAt",
                "cancelledBy", "owner", "ownerAccount", "playerCode", "note"), expired.keySet());
        assertEquals(1, l.get("page"));
        assertEquals(1, l.get("pages"));
        assertEquals(false, l.get("more"));
        assertNull(l.get("next"));

        Map<?, ?> p1 = post("/invite/list", "account_id", GM, "limit", 2, "page", 1);
        assertEquals(List.of(open, mine), codes(p1));
        assertEquals(2, p1.get("pages"));
        assertEquals(true, p1.get("more"));
        assertEquals(2, p1.get("next"));
        Map<?, ?> p2 = post("/invite/list", "account_id", GM, "limit", 2, "page", 2);
        assertEquals(List.of(gmCode), codes(p2));
        assertEquals(2, p2.get("page"));
        assertEquals(2, p2.get("offset"));
        assertEquals(false, p2.get("more"));
        assertNull(p2.get("next"));
        Map<?, ?> byOffset = post("/invite/list", "account_id", GM, "limit", 2, "offset", 1);
        assertEquals(List.of(mine, gmCode), codes(byOffset));
        assertEquals(3, byOffset.get("total"));
        assertEquals(SiteInvites.LIST_MAX, post("/invite/list", "account_id", GM, "limit", 5000).get("limit"));
    }

    static List<?> codes(Map<?, ?> list) {
        return ((List<?>) list.get("codes")).stream().map(r -> ((Map<?, ?>) r).get("code")).toList();
    }

    @Test
    void playerListShowsOnlyTheirOwnCodesUsedOrNotNeverWho() throws Exception {
        String a = code(post("/invite/create", "account_id", PLAYER));
        clock.addAndGet(1000);
        String b = code(post("/invite/create", "account_id", PLAYER));
        code(post("/invite/create", "account_id", OTHER));
        code(post("/invite/create", "account_id", GM));
        store.createAccountWithInvite(InviteCodes.normalize(a), "Friend", "h", "iph", clock.get() + 1);

        Map<?, ?> l = post("/invite/list", "account_id", PLAYER);
        assertEquals(false, l.get("gm"));
        assertEquals(2, l.get("total"));
        assertEquals(0, l.get("left"));
        assertEquals(true, l.get("eligible"));
        List<?> rows = (List<?>) l.get("codes");
        assertEquals(2, rows.size());
        Map<?, ?> unused = (Map<?, ?>) rows.get(0), used = (Map<?, ?>) rows.get(1);
        assertEquals(b, unused.get("code"));
        assertEquals("unused", unused.get("status"));
        assertEquals(false, unused.get("usedBy"));
        assertNull(unused.get("usedAt"));
        assertEquals(a, used.get("code"));
        assertEquals("used", used.get("status"));
        assertEquals(true, used.get("usedBy"), "a boolean, never the name");
        assertNotNull(used.get("usedAt"));
        assertNull(used.get("expiresAt"));
        assertEquals(Set.of("code", "status", "createdAt", "expiresAt", "usedBy", "usedAt", "cancelled"), used.keySet(),
                "no owner, note or who used it");
        assertFalse(JSON.writeValueAsString(l).contains("Friend"));
    }

    @Test
    void cancelRules() throws Exception {
        String mine = code(post("/invite/create", "account_id", PLAYER));
        String theirs = code(post("/invite/create", "account_id", OTHER));
        String gms = code(post("/invite/create", "account_id", GM));
        // A player can't touch someone else's code, and can't tell it exists.
        assertEquals("NOT_FOUND", post("/invite/cancel", "account_id", PLAYER, "code", theirs).get("error"));
        assertEquals("NOT_FOUND", post("/invite/cancel", "account_id", PLAYER, "code", gms).get("error"));
        assertEquals("NOT_FOUND", post("/invite/cancel", "account_id", PLAYER, "code", "nonsense").get("error"));
        assertEquals("NOT_FOUND", post("/invite/cancel", "account_id", PLAYER, "code", "ZZZZZ-ZZZZZ").get("error"));
        // Own unused code: cancelled (lower case and no dash work too); the player list says "cancelled", not staff.
        Map<?, ?> c = post("/invite/cancel", "account_id", PLAYER, "code", InviteCodes.normalize(mine).toLowerCase());
        assertEquals(true, c.get("ok"));
        assertEquals("CANCELLED", c.get("result"));
        assertEquals(mine, c.get("code"));
        assertEquals(InviteCodes.Status.REVOKED, store.findInvite(InviteCodes.normalize(mine)).status(clock.get()));
        assertEquals("cancelled", ((Map<?, ?>) ((List<?>) post("/invite/list", "account_id", PLAYER).get("codes")).get(0)).get("status"));
        assertEquals("ALREADY_CANCELLED", post("/invite/cancel", "account_id", PLAYER, "code", mine).get("error"));
        // A cancelled code still counts toward the week, as in game.
        assertEquals(1, post("/invite/list", "account_id", PLAYER).get("left"));
        // GM: anyone's unused code; a used one can't be cancelled.
        assertEquals("CANCELLED", post("/invite/cancel", "account_id", GM, "code", theirs).get("result"));
        assertEquals("cancelled_by_staff", ((Map<?, ?>) ((List<?>) post("/invite/list", "account_id", OTHER).get("codes")).get(0)).get("status"));
        store.createAccountWithInvite(InviteCodes.normalize(gms), "Friend", "h", "iph", clock.get());
        assertEquals("ALREADY_USED", post("/invite/cancel", "account_id", GM, "code", gms).get("error"));
        String used = code(post("/invite/create", "account_id", PLAYER));
        store.createAccountWithInvite(InviteCodes.normalize(used), "Friend2", "h", "iph", clock.get());
        assertEquals("ALREADY_USED", post("/invite/cancel", "account_id", PLAYER, "code", used).get("error"));
    }

    @Test
    void theDatabaseFailingAnswersUnavailable() throws Exception {
        MemoryAuthStore broken = new MemoryAuthStore() {
            @Override
            public synchronized Account findAccountById(int accountId) {
                throw new IllegalStateException("db down");
            }
        };
        SiteInvites si = new SiteInvites(broken, clock::get, accounts);
        assertEquals("UNAVAILABLE", si.create(GM, null, null).get("error"));
        assertEquals("UNAVAILABLE", si.list(GM, null, null, null).get("error"));
        assertEquals("UNAVAILABLE", si.cancel(GM, "ABCDE-FGHJK").get("error"));
    }
}
