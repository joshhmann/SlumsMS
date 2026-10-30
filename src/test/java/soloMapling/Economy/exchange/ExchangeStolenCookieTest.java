package soloMapling.Economy.exchange;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.Ledger.Asset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 b005 (S2): what a thief holding only a player's site cookie can do. The cookie gives the site a valid account
 * id and a fresh {@code siteAuthAt} (the cookie's own sign-in time), so these requests are exactly what the site sends
 * for a stolen session. They must never sell the account's NX or items, nor spend a large sum of its mesos. Written
 * against the 1.6.1 API only, so they fail on 1.6.1 (where all of these were placed) and pass with the b005 guard.
 */
class ExchangeStolenCookieTest {

    static final long NX0 = 100_000, MESO0 = 2_000_000_000L, H = 3_600_000L;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;
    int req;

    @BeforeEach
    void setUp() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 4; a++) {
            store.addAccount(a, NX0);
            store.addCharacter(10 + a, a, MESO0);
        }
        now = new AtomicLong(1_800_000_000_000L);
        ex = new Exchange(store, ExchangeLiveTest.cfg(100, 0), store::online, store.finder(), now::get);
        assertTrue(ex.recover().isEmpty());
        ex.guard(NxGuard.defaults());
        // the game's own record of password checks, fresh per test (the game uses SiteReauth.GAME)
        ex.signIns(new SiteReauth.SignIns());
        ex.siteReauth(SiteReauth.defaults());
        ex.auditSink(audit::add);
    }

    final List<String> audit = new ArrayList<>();

    /** A site request as the market service writes it for a session whose cookie was issued an hour ago. */
    ExchangeSite.Request site(String body) {
        String created = Instant.ofEpochMilli(now.get()).toString();
        return ExchangeSite.requests("[{\"id\":\"r" + (++req) + "\"," + body + ",\"siteAuthAt\":" + (now.get() - H)
                + ",\"createdAt\":\"" + created + "\"}]").get(0);
    }

    Response preview(long account, String side, long price, long qty, String ticker) {
        now.addAndGet(11_000);
        return ExchangeSite.answer(ex, site("\"account\":" + account + ",\"type\":\"preview\",\"character\":"
                + (10 + account) + ",\"side\":\"" + side + "\",\"price\":" + price + ",\"qty\":" + qty
                + ",\"ticker\":\"" + ticker + "\""), now.get());
    }

    Response confirm(long account, Object token) {
        return ExchangeSite.answer(ex, site("\"account\":" + account + ",\"type\":\"confirm\",\"token\":\"" + token
                + "\""), now.get());
    }

    @Test
    void aStolenCookieAloneCannotSellTheAccountsNx() {
        Response p = preview(3, "SELL", 5_000_000, 50_000, "NX");
        // 1.6.1 answered a preview here; with the game-side sign-in check the game has no password check from this
        // account (the thief never typed it), so nothing is even previewed
        assertEquals("REAUTH", p.code(), p.toString());
        assertEquals(NX0, store.nx(3), "the account's NX never left");
        assertEquals(0, store.balance(Ledger.Bucket.escrow(Asset.NX, 1)));
    }

    @Test
    void aStolenCookieAloneCannotSpendABigSumOfMesos() {
        Response p = preview(4, "BUY", 10_000_000, 50_000, "NX");
        assertEquals("REAUTH", p.code(), p.toString());
        assertEquals(MESO0, store.meso(14));
    }

    @Test
    void aStolenCookieAloneCannotSellItemsWhileThePlayerIsInGame() {
        store.addItems(13, Asset.LEAF, 100);
        store.login(3, 13);
        Response p = preview(3, "SELL", 1, 100, "LEAF");
        if (p.ok()) {
            Response c = confirm(3, p.data().get("token"));
            assertTrue(!c.ok(), "a 1-meso dump of 100 leaves was placed: " + c);
        }
        assertEquals(100, store.finder().find(3).have(Asset.LEAF), "the leaves never left the bags");
    }

    // ---------------------------------------------------------------- 1.6.2 b005: the refusal and the success

    /** The real player signed in with their password {@code ago} ms ago (the auth API recorded it). */
    void signedIn(long account, long ago) {
        ex.signIns().record(account, now.get() - ago);
    }

    @Test
    void theGameSideSignInMustBeFromTheLast12Hours() {
        signedIn(1, 13 * H);
        Response old = ExchangeSite.answer(ex, site("\"account\":1,\"type\":\"mine\""), now.get());
        assertEquals("REAUTH", old.code());
        assertEquals(true, old.data().get("game"));
        signedIn(1, 11 * H);
        assertTrue(ExchangeSite.answer(ex, site("\"account\":1,\"type\":\"mine\""), now.get()).ok());
        // another account's sign-in is not this one's
        assertEquals("REAUTH", ExchangeSite.answer(ex, site("\"account\":2,\"type\":\"mine\""), now.get()).code());
        // the site's own claim is still checked as well
        ExchangeSite.Request noClaim = ExchangeSite.requests("[{\"id\":\"x\",\"account\":1,\"type\":\"mine\","
                + "\"createdAt\":" + now.get() + "}]").get(0);
        assertEquals("REAUTH", ExchangeSite.answer(ex, noClaim, now.get()).code());
        // the public reads need nothing
        assertTrue(ExchangeSite.answer(ex, site("\"account\":2,\"type\":\"book\""), now.get()).ok());
    }

    @Test
    void withARecentRealSignInASiteSellStillNeedsThePasswordForThatOrder() {
        // the thief's cookie is an hour old and the owner signed in an hour ago: both checks of 1.6.1 pass
        signedIn(3, H);
        Response p = preview(3, "SELL", 5_000_000, 50_000, "NX");
        assertTrue(p.ok(), p.toString());
        assertEquals(true, p.data().get("passwordRequired"));
        String token = (String) p.data().get("token");
        Response c = confirm(3, token);
        assertEquals("PASSWORD_REQUIRED", c.code());
        assertEquals(token, c.data().get("token"), "the token stays valid for the password");
        assertEquals(NX0, store.nx(3), "nothing left the account");
        // another account cannot approve it
        assertEquals("TOKEN_EXPIRED", ex.approve(4, token).code());
        assertEquals("PASSWORD_REQUIRED", confirm(3, token).code());
        // the owner types the password (the auth API checked it, then approves the token)
        now.addAndGet(50_000);
        Response a = ex.approve(3, token);
        assertTrue(a.ok(), a.toString());
        now.addAndGet(30_000); // 80 s after the preview: the approval gave the token a fresh 60 s
        Response ok = confirm(3, token);
        assertTrue(ok.ok(), ok.toString());
        assertEquals(NX0 - 50_000, store.nx(3), "the sell is placed: its NX are in escrow");
        assertEquals("place", audit.stream().filter(l -> l.contains("place batch")).findFirst().map(l -> "place")
                .orElse(""), audit.toString());
    }

    @Test
    void anExpiredTokenCannotBeApproved() {
        signedIn(3, H);
        Response p = preview(3, "SELL", 5_000_000, 100, "NX");
        now.addAndGet(61_000);
        assertEquals("TOKEN_EXPIRED", ex.approve(3, (String) p.data().get("token")).code());
        assertEquals("TOKEN_EXPIRED", ex.approve(3, "deadbeefdeadbeef").code());
    }

    @Test
    void siteItemSellsNeedThePasswordToo() {
        signedIn(3, H);
        store.addItems(13, Asset.LEAF, 100);
        store.login(3, 13);
        Response p = preview(3, "SELL", 1, 100, "LEAF");
        assertTrue(p.ok(), p.toString());
        assertEquals("PASSWORD_REQUIRED", confirm(3, p.data().get("token")).code());
        assertEquals(100, store.finder().find(3).have(Asset.LEAF));
    }

    @Test
    void smallSiteBuysGoThroughUntilTenMillionMesosIn12HoursThenThePasswordIsAsked() {
        signedIn(4, H);
        // 1,000 NX at 5,000 per NX: 5,000,000 + 1% fee reserve = 5,050,000 mesos
        Response p1 = preview(4, "BUY", 5_000_000, 1_000, "NX");
        assertEquals(false, p1.data().get("passwordRequired"));
        assertTrue(confirm(4, p1.data().get("token")).ok());
        Response p2 = preview(4, "BUY", 5_000_000, 1_000, "NX");
        assertEquals(true, p2.data().get("passwordRequired"), "10,100,000 would pass the 10,000,000");
        assertEquals("PASSWORD_REQUIRED", confirm(4, p2.data().get("token")).code());
        assertEquals(MESO0 - 5_050_000, store.meso(14));
        assertTrue(ex.approve(4, (String) p2.data().get("token")).ok());
        assertTrue(confirm(4, p2.data().get("token")).ok(), "with the password it goes through: not a cap");
        // one big buy asks at once
        Response big = preview(4, "BUY", 5_000_000, 2_000, "NX");
        assertEquals(true, big.data().get("passwordRequired"));
        // 12 hours later the free amount is back
        now.addAndGet(12 * H);
        signedIn(4, 0);
        Response p3 = preview(4, "BUY", 5_000_000, 1_000, "NX");
        assertEquals(false, p3.data().get("passwordRequired"));
        assertTrue(confirm(4, p3.data().get("token")).ok());
    }

    @Test
    void inGameAtNxNeedsNoSiteSignInOrPassword() throws Exception {
        store.login(3, 13);
        LiveWallet w = store.finder().find(3);
        now.addAndGet(11_000);
        List<String> pv = ExchangeChat.handle(ex, w, new String[]{"sell", "1000", "5000"});
        assertTrue(pv.get(0).startsWith("Preview"), pv.toString());
        List<String> ok = ExchangeChat.handle(ex, w, new String[]{"confirm"});
        assertTrue(ok.get(0).contains("sell 1,000 NX") || ok.get(0).contains("sell 1000 NX"), ok.toString());
        assertEquals(NX0 - 1_000, w.have(Asset.NX));
    }

    @Test
    void switchedOffItBehavesAs161() {
        ex.siteReauth(SiteReauth.OFF);
        Response p = preview(3, "SELL", 5_000_000, 100, "NX");
        assertTrue(p.ok());
        assertEquals(false, p.data().get("passwordRequired"));
        assertTrue(confirm(3, p.data().get("token")).ok());
    }

    @Test
    void theDefaultsAndTheJvmKnobs() {
        SiteReauth d = SiteReauth.defaults();
        assertTrue(d.gameSignIn() && d.stepUp());
        assertEquals(10_000_000L, d.freeBuyMesos());
        System.setProperty("slums.exchange.stepUp", "false");
        System.setProperty("slums.exchange.stepUpFreeMesos", "0");
        try {
            SiteReauth s = SiteReauth.fromSystem();
            assertFalse(s.stepUp());
            assertTrue(s.gameSignIn());
            assertEquals(0, s.freeBuyMesos());
        } finally {
            System.clearProperty("slums.exchange.stepUp");
            System.clearProperty("slums.exchange.stepUpFreeMesos");
        }
    }

    // ---------------------------------------------------------------- halt and audit

    @Test
    void haltStopsAnApprovedSiteOrderAndEveryFillAtOnce() {
        signedIn(3, H);
        signedIn(4, H);
        Response restingBuy = preview(4, "BUY", 5_000_000, 100, "NX");
        assertTrue(confirm(4, restingBuy.data().get("token")).ok());
        Response p = preview(3, "SELL", 5_000_000, 100, "NX");
        String token = (String) p.data().get("token");
        assertTrue(ex.approve(3, token).ok());
        ex.halt("incident");
        Response c = confirm(3, token);
        assertEquals("HALTED", c.code());
        assertTrue(c.message().contains("incident"), c.message());
        assertEquals(NX0, store.nx(3), "no order, no fill");
        assertTrue(store.fills().isEmpty());
        assertEquals("HALTED", preview(3, "SELL", 5_000_000, 100, "NX").code());
        assertEquals("TOKEN_EXPIRED", ex.approve(3, token).code(), "the halt dropped every waiting preview");
        assertEquals(0, ex.tickRegulars(), "no regular quotes while halted");
        store.login(3, 13);
        now.addAndGet(11_000);
        List<String> game = ExchangeChat.handle(ex, store.finder().find(3), new String[]{"sell", "100", "5000"});
        assertTrue(String.join(" ", game).contains("HALTED") || String.join(" ", game).contains("stopped"), game.toString());
        assertTrue(audit.stream().anyMatch(l -> l.contains("HALT incident")), audit.toString());
        ex.resume();
        assertEquals("TOKEN_EXPIRED", confirm(3, token).code(), "a preview from before the halt never places later");
        assertTrue(store.fills().isEmpty());
    }

    @Test
    void everyActionLeavesAnAuditLineWithAccountAndCharacter() throws Exception {
        signedIn(4, H);
        // a site buy rests; an in-game sell fills it; the buyer cancels nothing, the seller's mesos are paid out
        Response b = preview(4, "BUY", 5_000_000, 200, "NX");
        assertTrue(confirm(4, b.data().get("token")).ok());
        store.login(3, 13);
        now.addAndGet(11_000);
        ExchangeChat.handle(ex, store.finder().find(3), new String[]{"sell", "100", "5000"});
        ExchangeChat.handle(ex, store.finder().find(3), new String[]{"confirm"});
        long order = ex.myOrders(4, 1).get(0).id();
        now.addAndGet(11_000);
        Response cancel = ExchangeSite.answer(ex, site("\"account\":4,\"type\":\"cancel\",\"orderId\":" + order),
                now.get());
        assertTrue(cancel.ok(), cancel.toString());
        ex.collectAll();
        String all = String.join("\n", audit);
        assertTrue(all.contains("site preview id=") && all.contains("acct=4 char=14"), all);
        assertTrue(audit.stream().anyMatch(l -> l.contains("place batch") && l.contains("acct=4 char=14")
                && l.contains("BUY NX 200@5000000")), all);
        assertTrue(audit.stream().anyMatch(l -> l.contains("fill #") && l.contains("(acct=4 char=14)")
                && l.contains("(acct=3 char=13)")), all);
        assertTrue(audit.stream().anyMatch(l -> l.startsWith("NX exchange audit: close CANCELLED")
                && l.contains("acct=4 char=14")), all);
        assertTrue(audit.stream().anyMatch(l -> l.contains("payout") && l.contains("acct=")), all);
        assertTrue(audit.stream().anyMatch(l -> l.contains("site cancel") && l.contains("acct=4")
                && l.contains("-> OK")), all);
        // a refusal is audited too
        ExchangeSite.answer(ex, site("\"account\":2,\"type\":\"mine\""), now.get());
        assertTrue(audit.get(audit.size() - 1).contains("site mine") && audit.get(audit.size() - 1).contains("acct=2")
                && audit.get(audit.size() - 1).endsWith("-> REAUTH"), audit.get(audit.size() - 1));
    }
}
